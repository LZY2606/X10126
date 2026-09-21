package replay.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import replay.Json;

/**
 * Core deterministic replay engine for one branch.
 *
 * Ordering contract:
 *  - external events are consumed in stable (time, priority, seq) order;
 *  - internal events produced by an action are enqueued AFTER the current
 *    event and processed before any later external event;
 *  - conditions see the snapshot before the event is handled.
 *
 * Failure contract:
 *  - an action failure (or action expression error) rolls back state change,
 *    variable writes, output entries and internal events of that event,
 *    while a failure record still enters the trajectory.
 */
public final class ReplayEngine {

    private final Definition definition;
    private String state;
    private Map<String, Object> vars = new LinkedHashMap<>();
    private DetRandom rng;
    private long stepIndex;
    private String traceHash;

    /** Pending internal events, FIFO by emission order. */
    private final Deque<InternalEvent> internals = new ArrayDeque<>();
    private int nextExternalIndex;
    private final List<Map<String, Object>> externalEvents;

    private ReplayEngine(Definition definition, List<Map<String, Object>> externalEvents) {
        this.definition = definition;
        this.externalEvents = externalEvents;
    }

    public static ReplayEngine fresh(Definition definition, EventLog log) {
        ReplayEngine engine = new ReplayEngine(definition, log.events);
        engine.state = definition.initialState;
        engine.vars = new LinkedHashMap<>(definition.initialVars);
        engine.rng = new DetRandom(definition.seed);
        engine.stepIndex = 0;
        engine.traceHash = initialHash(definition, log);
        engine.nextExternalIndex = 0;
        return engine;
    }

    public static String initialHash(Definition definition, EventLog log) {
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("definitionFingerprint", definition.fingerprint());
        lock.put("initialState", definition.initialState);
        lock.put("initialVars", definition.initialVars);
        lock.put("seed", definition.seed);
        lock.put("eventsFingerprint", log.fingerprint());
        return Hashes.fingerprint(lock);
    }

    public boolean hasNext() {
        return !internals.isEmpty() || nextExternalIndex < externalEvents.size();
    }

    /** Execute one step. Never throws for action failures: recorded in step. */
    public Map<String, Object> stepOnce() {
        if (!hasNext()) {
            throw new IllegalStateException("no more events");
        }
        boolean internal = !internals.isEmpty();
        Map<String, Object> event;
        InternalEvent internalMeta = null;
        if (internal) {
            internalMeta = internals.pollFirst();
            event = internalMeta.event;
        } else {
            event = externalEvents.get(nextExternalIndex++);
        }
        return processEvent(event, internal, internalMeta == null ? 0 : internalMeta.emissionStep);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> processEvent(Map<String, Object> incoming, boolean internal, long emissionStep) {
        String beforeState = state;
        Map<String, Object> beforeVars = deepCopy(vars);
        DetRandom rngBefore = DetRandom.restore(rng.getSeed(), rng.getCallCount());
        long rngBeforeCount = rng.getCallCount();
        long now = Json.lng(incoming, "time", 0L);

        List<Map<String, Object>> outputs = new ArrayList<>();
        List<Map<String, Object>> spawned = new ArrayList<>();
        Map<String, Object> eventCopy = new LinkedHashMap<>(incoming);

        // Snapshot the condition context BEFORE the event changes anything.
        Expr.Ctx conditionCtx = new Expr.Ctx(state, vars, eventCopy, now, rng);

        Definition.Transition selected = null;
        boolean conditionError = false;
        String conditionErrorMsg = null;
        for (Definition.Transition candidate : definition.matching(state, Json.str(incoming, "event"))) {
            try {
                if (Expr.isTrue(candidate.condition, conditionCtx)) {
                    selected = candidate;
                    break;
                }
            } catch (RuntimeException e) {
                conditionError = true;
                conditionErrorMsg = e.getMessage();
                break;
            }
        }

        String failure = null;
        String targetState = state;
        if (conditionError) {
            failure = "condition error: " + conditionErrorMsg;
        } else if (selected != null) {
            // Transactional execution: apply tentatively, roll back on any failure.
            try {
                for (Definition.Action action : selected.actions) {
                    executeAction(action, conditionCtx, now, outputs, spawned);
                }
                targetState = selected.to;
            } catch (ActionFailure f) {
                failure = f.getMessage();
            } catch (RuntimeException e) {
                failure = "action error: " + e.getMessage();
            }
        }

        if (failure != null) {
            // Roll back everything: state, vars, RNG position, outputs, spawned internals.
            state = beforeState;
            vars = beforeVars;
            rng = rngBefore;
            outputs.clear();
            spawned.clear();
            targetState = beforeState;
        } else {
            for (Map<String, Object> spawn : spawned) {
                internals.addLast(new InternalEvent(spawn, stepIndex));
            }
        }

        Map<String, Object> afterVars = deepCopy(vars);
        List<Map<String, Object>> stateChange = diffState(beforeState, beforeVars, targetState, afterVars);

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("index", stepIndex);
        step.put("time", now);
        step.put("kind", internal ? "internal" : "external");
        step.put("eventId", Json.str(incoming, "id"));
        step.put("event", Json.str(incoming, "event"));
        step.put("source", Json.str(incoming, "source", internal ? "internal" : "log"));
        step.put("seq", Json.lng(incoming, "seq", 0L));
        if (internal) {
            step.put("emissionStep", emissionStep);
        }
        step.put("eventPayload", incoming.get("payload"));
        step.put("transition", selected == null ? null
                : selected.from + " --" + selected.on + "--> " + selected.to);
        step.put("beforeState", beforeState);
        step.put("afterState", targetState);
        step.put("beforeVars", beforeVars);
        step.put("afterVars", afterVars);
        step.put("changes", stateChange);
        step.put("outputs", outputs);
        step.put("spawnedInternals", spawned);
        step.put("pendingInternalCount", internals.size());
        step.put("success", failure == null);
        if (failure != null) {
            step.put("failure", failure);
        }
        step.put("rngCalls", rng.getCallCount());

        traceHash = Hashes.chain(traceHash, hashContent(step, rngBeforeCount));
        step.put("stepHash", Hashes.shortFp(hashContent(step, rngBeforeCount)));
        step.put("traceHash", traceHash);
        stepIndex++;
        return step;
    }

    private List<Map<String, Object>> hashContent(Map<String, Object> step, long rngBefore) {
        // Include rng position before handling so identical runs stay identical.
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> copy = new LinkedHashMap<>(step);
        copy.remove("stepHash");
        copy.remove("traceHash");
        content.add(copy);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rngCallsBefore", rngBefore);
        meta.put("nextExternalIndex", nextExternalIndex);
        content.add(meta);
        return content;
    }

    private void executeAction(Definition.Action action, Expr.Ctx ctx, long now,
                               List<Map<String, Object>> outputs,
                               List<Map<String, Object>> spawned) {
        switch (action.type) {
            case "emit" -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("channel", action.channel);
                out.put("payload", Expr.resolve(action.payload, ctx));
                out.put("time", now);
                outputs.add(out);
            }
            case "emitInternal" -> {
                Map<String, Object> internal = new LinkedHashMap<>();
                internal.put("id", "i" + stepIndex + "-" + spawned.size());
                internal.put("event", action.event);
                internal.put("time", now);
                internal.put("source", "internal");
                internal.put("priority", action.priority);
                internal.put("seq", spawned.size());
                Object payload = Expr.resolve(action.payload, ctx);
                if (payload != null) {
                    internal.put("payload", payload);
                }
                spawned.add(internal);
            }
            case "set" -> {
                vars.put(action.name, Expr.resolve(action.value, ctx));
            }
            case "fail" -> {
                Object resolved = Expr.resolve(action.message, ctx);
                throw new ActionFailure(String.valueOf(resolved));
            }
            default -> throw new IllegalStateException("unknown action: " + action.type);
        }
    }

    private List<Map<String, Object>> diffState(String beforeState, Map<String, Object> beforeVars,
                                                String afterState, Map<String, Object> afterVars) {
        List<Map<String, Object>> changes = new ArrayList<>();
        if (!beforeState.equals(afterState)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("path", "$state");
            c.put("before", beforeState);
            c.put("after", afterState);
            changes.add(c);
        }
        java.util.Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(beforeVars.keySet());
        keys.addAll(afterVars.keySet());
        for (String key : keys) {
            Object b = beforeVars.get(key);
            Object a = afterVars.get(key);
            if (!Hashes.canonical(Hashes.canonicalize(b))
                    .equals(Hashes.canonical(Hashes.canonicalize(a)))) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("path", "vars." + key);
                c.put("before", b);
                c.put("after", a);
                changes.add(c);
            }
        }
        return changes;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            copy.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object v) {
        if (v instanceof Map) {
            return deepCopy((Map<String, Object>) v);
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) v) {
                out.add(deepCopyValue(item));
            }
            return out;
        }
        return v;
    }

    // ---------- snapshot / restore for checkpoints ----------

    public Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.state = state;
        s.vars = deepCopy(vars);
        s.rngCalls = rng.getCallCount();
        s.stepIndex = stepIndex;
        s.traceHash = traceHash;
        s.nextExternalIndex = nextExternalIndex;
        for (InternalEvent ie : internals) {
            s.internals.add(new InternalEvent(deepCopy(ie.event), ie.emissionStep));
        }
        return s;
    }

    public void restore(Snapshot s) {
        this.state = s.state;
        this.vars = deepCopy(s.vars);
        this.rng = DetRandom.restore(definition.seed, s.rngCalls);
        this.stepIndex = s.stepIndex;
        this.traceHash = s.traceHash;
        this.nextExternalIndex = s.nextExternalIndex;
        this.internals.clear();
        for (InternalEvent ie : s.internals) {
            this.internals.addLast(new InternalEvent(deepCopy(ie.event), ie.emissionStep));
        }
    }

    public String getTraceHash() {
        return traceHash;
    }

    public long getStepIndex() {
        return stepIndex;
    }

    public String getState() {
        return state;
    }

    public Map<String, Object> getVars() {
        return vars;
    }

    public int getNextExternalIndex() {
        return nextExternalIndex;
    }

    public List<Map<String, Object>> snapshotPendingInternals() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InternalEvent ie : internals) {
            out.add(deepCopy(ie.event));
        }
        return out;
    }

    public static final class Snapshot {
        public String state;
        public Map<String, Object> vars;
        public long rngCalls;
        public long stepIndex;
        public String traceHash;
        public int nextExternalIndex;
        public final List<InternalEvent> internals = new ArrayList<>();
    }

    private static final class InternalEvent {
        final Map<String, Object> event;
        final long emissionStep;

        InternalEvent(Map<String, Object> event, long emissionStep) {
            this.event = event;
            this.emissionStep = emissionStep;
        }
    }

    private static final class ActionFailure extends RuntimeException {
        ActionFailure(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
