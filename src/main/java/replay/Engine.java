package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Deterministic replay engine. Pure logical time: the queue is ordered by
 * (time, sourcePriority, seq, insertionId); no wall clock is ever consulted.
 */
public final class Engine {
    private final MachineDefinition definition;
    private String state;
    private Map<String, Object> vars;
    private final TreeSet<Event> queue;
    private final List<Map<String, Object>> trace = new ArrayList<>();
    private final Rng rng;
    private long insertionCounter = 0;
    private long internalSeq = 1;
    private long stepCount = 0;
    private String lastHash;
    /** Every applied external (non-internal) event with the step that applied it. */
    private final List<Map<String, Object>> appliedExternal = new ArrayList<>();

    public Engine(MachineDefinition definition, long seed) {
        this.definition = definition;
        this.state = definition.initialState;
        this.vars = Json.obj(Json.deepCopy(definition.initialVariables));
        this.rng = new Rng(seed);
        this.queue = new TreeSet<>((a, b) -> a.compareTo(b, definition.priorityFn()));
        this.lastHash = Hashing.sha256("genesis:" + definition.fingerprint + ":" + seed);
    }

    public MachineDefinition definition() { return definition; }
    public void setState(String newState) { this.state = newState; }
    public String state() { return state; }
    public Map<String, Object> vars() { return vars; }
    public long stepCount() { return stepCount; }
    public String trajectoryHash() { return lastHash; }
    public List<Map<String, Object>> trace() { return trace; }
    public List<Map<String, Object>> appliedExternal() { return appliedExternal; }

    /** External events applied after the given step number. */
    public List<Event> externalEventsSince(long step) {
        List<Event> out = new ArrayList<>();
        for (Map<String, Object> rec : appliedExternal) {
            if (Json.num(rec.get("step")) > step) {
                out.add(eventFromEngineJson(Json.obj(rec.get("event"))));
            }
        }
        return out;
    }
    public int pendingCount() { return queue.size(); }

    public List<Event> pending() {
        return new ArrayList<>(queue);
    }

    public void enqueueExternal(List<Event> events) {
        for (Event e : events) enqueue(e);
    }

    private void enqueue(Event e) {
        queue.add(e);
        insertionCounter = Math.max(insertionCounter, e.insertionId + 1);
    }

    /** Process the head event. Returns the trace entry, or null when the queue is empty. */
    public Map<String, Object> step() {
        Event event = queue.pollFirst();
        if (event == null) return null;

        String stateBefore = state;
        Map<String, Object> varsBefore = Json.obj(Json.deepCopy(vars));

        Map<String, Object> transition = definition.matchTransition(event, stateBefore, varsBefore);

        // transactional working copies
        String workState = stateBefore;
        Map<String, Object> workVars = varsBefore;
        List<Map<String, Object>> outputs = new ArrayList<>();
        List<Event> raised = new ArrayList<>();
        String failure = null;
        List<String> applied = new ArrayList<>();

        if (transition != null) {
            workVars = Json.obj(Json.deepCopy(varsBefore));
            Object actionsObj = transition.get("actions");
            List<Object> actions = actionsObj instanceof List ? Json.arr(actionsObj) : List.of();
            Condition.SnapshotView view = Condition.view(stateBefore, varsBefore, event.payload);
            try {
                for (Object a : actions) {
                    Map<String, Object> action = Json.obj(a);
                    String type = Json.str(action.get("type"));
                    switch (type) {
                        case "setState":
                            workState = Json.str(action.get("state"));
                            applied.add("setState->" + workState);
                            break;
                        case "setVar": {
                            String var = Json.str(action.get("var"));
                            Object value;
                            if (action.containsKey("random")) {
                                value = rng.nextLong(Json.num(action.get("random")));
                            } else {
                                value = Json.deepCopy(action.get("value"));
                            }
                            workVars.put(var, value);
                            applied.add("setVar " + var + "=" + Json.canonical(value));
                            break;
                        }
                        case "increment": {
                            String var = Json.str(action.get("var"));
                            long by = Json.num(action.getOrDefault("by", 1L));
                            Object cur = workVars.get(var);
                            long base = cur instanceof Number ? ((Number) cur).longValue() : 0;
                            workVars.put(var, base + by);
                            applied.add("increment " + var + " by " + by);
                            break;
                        }
                        case "emit": {
                            Map<String, Object> out = Json.newObj();
                            out.put("name", Json.str(action.get("name")));
                            if (action.containsKey("data")) out.put("data", Json.deepCopy(action.get("data")));
                            outputs.add(out);
                            applied.add("emit " + out.get("name"));
                            break;
                        }
                        case "raise": {
                            long delay = Json.num(action.getOrDefault("delay", 0L));
                            Object p = action.get("payload");
                            Event internal = new Event(
                                    "int-" + internalSeq,
                                    event.time + delay,
                                    "internal",
                                    internalSeq,
                                    Json.str(action.get("event")),
                                    p == null ? Json.newObj() : Json.obj(Json.deepCopy(p)),
                                    true,
                                    insertionCounter + raised.size());
                            internalSeq++;
                            raised.add(internal);
                            applied.add("raise " + internal.name + "@" + internal.time);
                            break;
                        }
                        case "fail": {
                            Object when = action.get("when");
                            if (when == null || Condition.eval(when, view)) {
                                throw new ActionFailure(
                                        Json.str(action.getOrDefault("message", "action failed")));
                            }
                            applied.add("fail(skipped)");
                            break;
                        }
                        default:
                            throw new ActionFailure("unknown action type: " + type);
                    }
                }
            } catch (ActionFailure af) {
                failure = af.getMessage();
                // rollback: discard working state, outputs and raised internal events
                workState = stateBefore;
                workVars = varsBefore;
                outputs = new ArrayList<>();
                raised = new ArrayList<>();
            }
        }

        // commit
        state = workState;
        vars = workVars;
        for (Event r : raised) enqueue(r);
        stepCount++;
        if (!event.internal) {
            Map<String, Object> rec = Json.newObj();
            rec.put("step", stepCount);
            rec.put("event", eventToEngineJson(event));
            appliedExternal.add(rec);
        }

        Map<String, Object> entry = Json.newObj();
        entry.put("step", stepCount);
        entry.put("eventId", event.id);
        entry.put("eventName", event.name);
        entry.put("time", event.time);
        entry.put("source", event.source);
        if (event.internal) entry.put("internal", true);
        entry.put("matched", transition != null);
        entry.put("actions", applied);
        entry.put("outputs", outputs);
        if (failure != null) entry.put("failure", failure);
        entry.put("stateBefore", stateBefore);
        entry.put("stateAfter", state);
        entry.put("varsBefore", varsBefore);
        entry.put("varsAfter", Json.deepCopy(vars));
        entry.put("prevHash", lastHash);
        String hash = Hashing.sha256(lastHash + ":" + Json.canonical(entry));
        entry.put("hash", hash);
        lastHash = hash;
        trace.add(entry);
        return entry;
    }

    /** Drain the queue (bounded to avoid infinite loops from self-raising machines). */
    public int run(long maxSteps) {
        int n = 0;
        while (!queue.isEmpty() && n < maxSteps) {
            step();
            n++;
        }
        return n;
    }

    // ---------- snapshot / restore (checkpoints & persistence) ----------

    public Map<String, Object> snapshot() {
        Map<String, Object> s = Json.newObj();
        s.put("definitionFingerprint", definition.fingerprint);
        s.put("state", state);
        s.put("vars", Json.deepCopy(vars));
        s.put("rngState", rng.state());
        s.put("insertionCounter", insertionCounter);
        s.put("internalSeq", internalSeq);
        s.put("stepCount", stepCount);
        s.put("lastHash", lastHash);
        List<Object> q = Json.newArr();
        for (Event e : queue) q.add(eventToEngineJson(e));
        s.put("queue", q);
        List<Object> ext = Json.newArr();
        ext.addAll(appliedExternal);
        s.put("appliedExternal", ext);
        List<Object> tr = Json.newArr();
        tr.addAll(trace);
        s.put("trace", tr);
        return s;
    }

    public static Engine restore(MachineDefinition definition, Map<String, Object> s) {
        String fp = Json.str(s.get("definitionFingerprint"));
        if (!definition.fingerprint.equals(fp)) {
            throw new DefinitionMismatchException(definition.fingerprint, fp);
        }
        Engine engine = new Engine(definition, 0);
        engine.state = Json.str(s.get("state"));
        engine.vars = Json.obj(Json.deepCopy(s.get("vars")));
        engine.rng.state(Json.num(s.get("rngState")));
        engine.insertionCounter = Json.num(s.get("insertionCounter"));
        engine.internalSeq = Json.num(s.get("internalSeq"));
        engine.stepCount = Json.num(s.get("stepCount"));
        engine.lastHash = Json.str(s.get("lastHash"));
        for (Object e : Json.arr(s.get("queue"))) {
            engine.queue.add(eventFromEngineJson(Json.obj(e)));
        }
        for (Object rec : Json.arr(s.getOrDefault("appliedExternal", Json.newArr()))) {
            engine.appliedExternal.add(Json.obj(Json.deepCopy(rec)));
        }
        for (Object t : Json.arr(s.getOrDefault("trace", Json.newArr()))) {
            engine.trace.add(Json.obj(Json.deepCopy(t)));
        }
        return engine;
    }

    private static Map<String, Object> eventToEngineJson(Event e) {
        Map<String, Object> m = e.toJson();
        m.put("insertionId", e.insertionId);
        m.put("internal", e.internal);
        return m;
    }

    private static Event eventFromEngineJson(Map<String, Object> m) {
        return new Event(
                Json.str(m.get("id")),
                Json.num(m.get("time")),
                Json.str(m.get("source")),
                Json.num(m.get("seq")),
                Json.str(m.get("name")),
                Json.obj(Json.deepCopy(m.getOrDefault("payload", Json.newObj()))),
                Json.bool(m.get("internal"), false),
                Json.num(m.getOrDefault("insertionId", 0L)));
    }

    public static final class ActionFailure extends RuntimeException {
        public ActionFailure(String message) { super(message); }
    }

    public static final class DefinitionMismatchException extends RuntimeException {
        public final String expected;
        public final String actual;
        public DefinitionMismatchException(String expected, String actual) {
            super("checkpoint definition fingerprint mismatch: expected " + expected + " but checkpoint has " + actual);
            this.expected = expected;
            this.actual = actual;
        }
    }
}
