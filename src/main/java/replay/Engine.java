package replay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import replay.Model.Definition;
import replay.Model.Event;
import replay.Model.TraceEntry;

/**
 * Deterministic replay engine. Pure function of
 * (definition, seed, initial state/vars, external event log, step count).
 * Uses a virtual logical clock only; never touches wall time.
 */
public final class Engine {

    /** Virtual logical clock: advances only to processed event times. */
    public static final class LogicalClock {
        private long now;
        public long now() { return now; }
        public void advanceTo(long t) { if (t > now) now = t; }
    }

    /** Deterministic xorshift PRNG so random actions replay identically. */
    public static final class Rng {
        private long state;
        public Rng(long seed) { this.state = seed == 0 ? 0x9E3779B97F4A7C15L : seed; }
        public long next() {
            long x = state;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            state = x;
            return x >>> 1;
        }
        public long nextLong(long bound) {
            if (bound <= 0) return 0;
            return next() % bound;
        }
    }

    public static final class Outcome {
        public final List<TraceEntry> trace = new ArrayList<>();
        public String state;
        public Map<String, Object> vars;
        public long clock;
        public long rngProbe; // exposed for tests / debugging
        public List<Object> allOutputs = new ArrayList<>();
    }

    public static Comparator<Event> ordering(Map<String, Integer> priorities) {
        return Comparator
                .comparingLong((Event e) -> e.time)
                .thenComparingInt(e -> priorities.getOrDefault(e.source, 0))
                .thenComparingLong(e -> e.seq)
                .thenComparing(e -> e.id);
    }

    /** Replay up to {@code steps} events (or all if steps < 0). */
    public static Outcome replay(Definition def, long seed, List<Event> externalEvents,
                                 int steps, Map<String, Integer> priorities) {
        List<Event> sorted = new ArrayList<>(externalEvents);
        sorted.sort(ordering(priorities));

        Outcome out = new Outcome();
        out.state = def.initialState;
        out.vars = new LinkedHashMap<>(def.variables);
        LogicalClock clock = new LogicalClock();
        Rng rng = new Rng(seed);
        ArrayDeque<Event> internalQueue = new ArrayDeque<>();
        int externalIdx = 0;
        long internalSeq = 0;
        int processed = 0;

        while (steps < 0 || processed < steps) {
            Event ev;
            boolean isInternal;
            if (!internalQueue.isEmpty()) {
                ev = internalQueue.poll();
                isInternal = true;
            } else if (externalIdx < sorted.size()) {
                ev = sorted.get(externalIdx++);
                isInternal = false;
            } else break;

            clock.advanceTo(ev.time);
            TraceEntry entry = new TraceEntry();
            entry.index = processed;
            entry.clock = clock.now();
            entry.event = ev;
            entry.stateBefore = out.state;
            entry.varsBefore = new LinkedHashMap<>(out.vars);

            Map<String, Object> transition = def.findTransition(out.state, ev.name);
            if (transition == null) {
                entry.status = "no-transition";
                entry.stateAfter = out.state;
                entry.varsAfter = new LinkedHashMap<>(out.vars);
                out.trace.add(entry);
                processed++;
                continue;
            }

            Object condition = transition.get("condition");
            if (condition != null && !evalCondition(condition, out.state, out.vars)) {
                entry.status = "condition-false";
                entry.stateAfter = out.state;
                entry.varsAfter = new LinkedHashMap<>(out.vars);
                out.trace.add(entry);
                processed++;
                continue;
            }

            // Stage effects so a failing action rolls back state and derived internal events.
            Map<String, Object> stagedVars = new LinkedHashMap<>(out.vars);
            String stagedState = transition.containsKey("to")
                    ? String.valueOf(transition.get("to")) : out.state;
            List<Event> stagedEmits = new ArrayList<>();
            List<Object> stagedOutputs = new ArrayList<>();
            String failure = null;

            Object actionsObj = transition.get("actions");
            if (actionsObj != null) {
                for (Object ao : Json.asList(actionsObj)) {
                    Map<String, Object> action = Json.asMap(ao);
                    String type = String.valueOf(action.get("type"));
                    try {
                        switch (type) {
                            case "set" -> stagedVars.put(Json.asString(action.get("var")), action.get("value"));
                            case "increment" -> {
                                String var = Json.asString(action.get("var"));
                                long by = action.containsKey("by") ? Json.asLong(action.get("by")) : 1L;
                                Object cur = stagedVars.get(var);
                                long base = cur instanceof Number n ? n.longValue() : 0L;
                                stagedVars.put(var, base + by);
                            }
                            case "emit" -> {
                                long delay = action.containsKey("delay") ? Json.asLong(action.get("delay")) : 0L;
                                Object pl = action.get("payload");
                                Event ie = new Event(
                                        "i" + internalSeq,
                                        clock.now() + delay,
                                        "internal",
                                        internalSeq,
                                        Json.asString(action.get("event")),
                                        pl == null ? new LinkedHashMap<>() : Json.asMap(pl),
                                        true);
                                internalSeq++;
                                stagedEmits.add(ie);
                            }
                            case "output" -> {
                                Map<String, Object> o = Json.map();
                                o.put("channel", String.valueOf(action.getOrDefault("channel", "default")));
                                o.put("value", action.get("value"));
                                stagedOutputs.add(o);
                            }
                            case "random" -> {
                                long bound = action.containsKey("bound") ? Json.asLong(action.get("bound")) : 100L;
                                stagedVars.put(Json.asString(action.get("var")), rng.nextLong(bound));
                            }
                            case "fail" -> {
                                Object when = action.get("when");
                                if (when == null || evalCondition(when, stagedState, stagedVars)) {
                                    throw new ActionFailure(
                                            String.valueOf(action.getOrDefault("message", "action failed")));
                                }
                            }
                            default -> throw new ActionFailure("unknown action type: " + type);
                        }
                    } catch (ActionFailure af) {
                        failure = af.getMessage();
                        break;
                    }
                }
            }

            if (failure != null) {
                // Roll back: keep prior state/vars, drop derived internal events and outputs.
                entry.status = "failed";
                entry.error = failure;
                entry.stateAfter = out.state;
                entry.varsAfter = new LinkedHashMap<>(out.vars);
            } else {
                out.state = stagedState;
                out.vars = stagedVars;
                internalQueue.addAll(stagedEmits);
                entry.status = "applied";
                entry.stateAfter = out.state;
                entry.varsAfter = new LinkedHashMap<>(out.vars);
                entry.outputs = new ArrayList<>(stagedOutputs);
                for (Event ie : stagedEmits) entry.emitted.add(ie.id);
                out.allOutputs.addAll(stagedOutputs);
            }
            out.trace.add(entry);
            processed++;
        }

        out.clock = clock.now();
        out.rngProbe = rng.state;
        return out;
    }

    /** Conditions read the pre-event snapshot. Supports all/any/not and comparisons. */
    @SuppressWarnings("unchecked")
    public static boolean evalCondition(Object cond, String state, Map<String, Object> vars) {
        Map<String, Object> c = Json.asMap(cond);
        if (c.containsKey("all")) {
            for (Object sub : Json.asList(c.get("all"))) {
                if (!evalCondition(sub, state, vars)) return false;
            }
            return true;
        }
        if (c.containsKey("any")) {
            for (Object sub : Json.asList(c.get("any"))) {
                if (evalCondition(sub, state, vars)) return true;
            }
            return false;
        }
        if (c.containsKey("not")) {
            return !evalCondition(c.get("not"), state, vars);
        }
        String var = Json.asString(c.get("var"));
        Object actual = "state".equals(var) ? state : vars.get(var);
        String op = String.valueOf(c.getOrDefault("op", "eq"));
        Object expected = c.get("value");
        return switch (op) {
            case "eq" -> compareEq(actual, expected);
            case "ne" -> !compareEq(actual, expected);
            case "lt" -> compareNum(actual, expected) < 0;
            case "le" -> compareNum(actual, expected) <= 0;
            case "gt" -> compareNum(actual, expected) > 0;
            case "ge" -> compareNum(actual, expected) >= 0;
            default -> throw new IllegalArgumentException("unknown op: " + op);
        };
    }

    private static boolean compareEq(Object a, Object b) {
        if (a instanceof Number an && b instanceof Number bn) {
            return Double.compare(an.doubleValue(), bn.doubleValue()) == 0;
        }
        return Objects.equals(a, b);
    }

    private static int compareNum(Object a, Object b) {
        double av = a instanceof Number n ? n.doubleValue() : Double.NaN;
        double bv = b instanceof Number n ? n.doubleValue() : Double.NaN;
        return Double.compare(av, bv);
    }

    public static final class ActionFailure extends RuntimeException {
        public ActionFailure(String message) { super(message); }
    }
}
