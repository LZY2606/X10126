package replay.core;

import replay.core.Model.Definition;
import replay.core.Model.EventDef;
import replay.core.Model.EventRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic replay engine. Processes one external event per step, draining any
 * internally emitted events (FIFO) immediately after it. Conditions are evaluated
 * against the pre-event snapshot; a failed action rolls back the state changes and
 * the internal events derived from this event, while the failure itself is traced.
 */
public final class Machine {
    public static final int MAX_INTERNAL_PER_STEP = 1000;

    private final Definition def;
    private final long seed;

    private String state;
    private Map<String, Object> vars;
    private long rngCursor;
    private String traceHash;
    private final List<Map<String, Object>> trace = new ArrayList<>();
    private final Deque<EventRecord> internalQueue = new ArrayDeque<>();
    private long internalSeq = 0;

    public Machine(Definition def, long seed) {
        this.def = def;
        this.seed = seed;
        reset();
    }

    private Machine(Definition def, long seed, Map<String, Object> snapshot) {
        this.def = def;
        this.seed = seed;
        restore(snapshot);
    }

    public static Machine fromSnapshot(Definition def, long seed, Map<String, Object> snapshot) {
        return new Machine(def, seed, snapshot);
    }

    public final void reset() {
        this.state = def.initialState;
        this.vars = def.initialVars();
        this.rngCursor = 0;
        this.traceHash = Hash.sha256("genesis:" + def.fingerprint() + ":" + seed);
        this.trace.clear();
        this.internalQueue.clear();
        this.internalSeq = 0;
    }

    @SuppressWarnings("unchecked")
    public void restore(Map<String, Object> snapshot) {
        this.state = Model.str(snapshot.get("state"), def.initialState);
        this.vars = Model.deepCopyMap((Map<String, Object>) snapshot.get("vars"));
        this.rngCursor = snapshot.get("rngCursor") instanceof Number n ? n.longValue() : 0L;
        this.traceHash = Model.str(snapshot.get("traceHash"), Hash.sha256("genesis"));
        this.internalQueue.clear();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("vars", Model.deepCopyMap(vars));
        m.put("rngCursor", rngCursor);
        m.put("traceHash", traceHash);
        return m;
    }

    public String state() { return state; }
    public Map<String, Object> vars() { return java.util.Collections.unmodifiableMap(vars); }
    public String traceHash() { return traceHash; }
    public List<Map<String, Object>> trace() { return trace; }
    public int nextIndex() { return trace.size(); }

    /** Processes one external event plus the internal cascade it triggers. Returns new trace entries. */
    public List<Map<String, Object>> stepExternal(EventRecord event) {
        List<Map<String, Object>> produced = new ArrayList<>();
        produced.add(processOne(event));
        int guard = 0;
        while (!internalQueue.isEmpty()) {
            if (++guard > MAX_INTERNAL_PER_STEP) {
                internalQueue.clear();
                throw new IllegalStateException("internal event limit exceeded (" + MAX_INTERNAL_PER_STEP + ")");
            }
            produced.add(processOne(internalQueue.poll()));
        }
        return produced;
    }

    private Map<String, Object> processOne(EventRecord event) {
        Map<String, Object> before = stateSnapshot();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("index", trace.size());
        entry.put("event", event.toJson());
        entry.put("internal", event.internal);
        entry.put("before", before);

        List<String> outputs = new ArrayList<>();
        List<EventRecord> emitted = new ArrayList<>();
        String failure = null;
        boolean applied = false;
        Boolean conditionResult = null;

        EventDef eventDef = def.events.get(event.name);
        // Working copies: only committed on success.
        String workState = state;
        Map<String, Object> workVars = Model.deepCopyMap(vars);

        if (eventDef == null) {
            failure = "no definition for event '" + event.name + "'";
        } else {
            try {
                if (eventDef.condition != null) {
                    conditionResult = Expr.evalBool(eventDef.condition, scope(event, workState, workVars));
                }
                if (conditionResult != null && !conditionResult) {
                    // condition false: no effect, still traced
                } else {
                    int actionIndex = 0;
                    for (Map<String, Object> action : eventDef.actions) {
                        workState = applyAction(action, actionIndex, event, workState, workVars, outputs, emitted);
                        actionIndex++;
                    }
                    applied = true;
                }
            } catch (Exception ex) {
                failure = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            }
        }

        if (applied) {
            state = workState;
            vars = workVars;
            internalQueue.addAll(emitted); // internal events only ever queue behind the current event
        }
        // on failure or false condition: working copies and emitted events are discarded (rollback)

        entry.put("condition", eventDef == null ? null : eventDef.condition);
        entry.put("conditionResult", conditionResult);
        entry.put("applied", applied);
        entry.put("outputs", outputs);
        if (failure != null) entry.put("failure", failure);
        entry.put("after", stateSnapshot());
        traceHash = Hash.sha256(traceHash + ":" + Json.canonical(entry));
        entry.put("traceHash", traceHash);
        trace.add(entry);
        return entry;
    }

    private String applyAction(Map<String, Object> action, int index, EventRecord event,
                               String workState, Map<String, Object> workVars,
                               List<String> outputs, List<EventRecord> emitted) {
        String type = Model.str(action.get("type"), "");
        Expr.Scope scope = scope(event, workState, workVars);
        switch (type) {
            case "transition" -> {
                String to = Model.str(action.get("to"), null);
                if (to == null || !def.states.contains(to))
                    throw new IllegalArgumentException("action " + index + ": unknown target state '" + to + "'");
                return to;
            }
            case "set" -> {
                String var = Model.str(action.get("var"), null);
                if (var == null) throw new IllegalArgumentException("action " + index + ": set requires 'var'");
                Object raw = action.get("value");
                Object value = raw instanceof String s ? Expr.eval(s, scope) : raw;
                workVars.put(var, value);
                return workState;
            }
            case "output" -> {
                String message = Model.str(action.get("message"), "");
                outputs.add(Expr.template(message, scope));
                return workState;
            }
            case "emit" -> {
                String name = Model.str(action.get("event"), null);
                if (name == null || !def.events.containsKey(name))
                    throw new IllegalArgumentException("action " + index + ": cannot emit undefined event '" + name + "'");
                EventRecord inner = new EventRecord();
                inner.id = event.id + "/i" + (++internalSeq);
                inner.time = event.time;
                inner.source = "$internal";
                inner.seq = internalSeq;
                inner.name = name;
                Object p = action.get("payload");
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = p instanceof Map ? (Map<String, Object>) p : new LinkedHashMap<String, Object>();
                inner.payload = Model.deepCopyMap(payload);
                inner.internal = true;
                emitted.add(inner);
                return workState;
            }
            case "randomAssign" -> {
                String var = Model.str(action.get("var"), null);
                long min = action.get("min") instanceof Number n ? n.longValue() : 0L;
                long max = action.get("max") instanceof Number n ? n.longValue() : 0L;
                if (var == null) throw new IllegalArgumentException("action " + index + ": randomAssign requires 'var'");
                if (min > max) throw new IllegalArgumentException("action " + index + ": min > max");
                long value = min + Math.floorMod(deterministicLong(seed, rngCursor), max - min + 1);
                rngCursor++;
                workVars.put(var, value);
                return workState;
            }
            case "fail" -> throw new IllegalArgumentException(
                    "action " + index + " failed: " + Model.str(action.get("message"), "explicit failure"));
            default -> throw new IllegalArgumentException("action " + index + ": unknown type '" + type + "'");
        }
    }

    /** SplitMix64-based deterministic stream keyed by (seed, cursor). */
    private static long deterministicLong(long seed, long cursor) {
        long z = seed + cursor * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private Expr.Scope scope(EventRecord event, String currentState, Map<String, Object> currentVars) {
        return path -> {
            String head = path.get(0);
            Object value;
            if (head.equals("state")) {
                value = currentState;
            } else if (head.equals("payload")) {
                value = event.payload;
            } else {
                value = currentVars.get(head);
            }
            for (int i = head.equals("payload") ? 1 : 1; i < path.size(); i++) {
                if (value instanceof Map<?, ?> m) value = m.get(path.get(i));
                else return null;
            }
            return value;
        };
    }

    private Map<String, Object> stateSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("vars", Model.deepCopyMap(vars));
        return m;
    }
}
