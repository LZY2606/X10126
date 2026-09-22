package replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless deterministic replay engine. Every operation takes the branch's
 * stored state map and returns a new state / node, so identical inputs always
 * produce identical fingerprints.
 *
 * Ordering of events sharing one logical time is fixed by
 * (time, priority, seq, id); internal events spawned by an action always run
 * immediately after the spawning event before the next external event.
 */
public final class DeterministicEngine {

    private DeterministicEngine() {
    }

    public static final Comparator<Models.Event> EVENT_ORDER = Comparator
            .comparingLong((Models.Event e) -> e.time)
            .thenComparingLong(e -> e.priority)
            .thenComparingLong(e -> e.seq)
            .thenComparing(e -> e.id);

    public static Map<String, Object> initialState(Models.Definition definition) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("state", definition.initialState());
        state.put("vars", definition.initialVars());
        state.put("rng", definition.seed);
        return state;
    }

    /** External events in deterministic processing order. */
    public static List<Models.Event> sortExternal(List<Models.Event> events) {
        List<Models.Event> sorted = new ArrayList<>();
        for (Models.Event event : events) {
            if (!event.internal) {
                sorted.add(event);
            }
        }
        sorted.sort(EVENT_ORDER);
        return sorted;
    }

    /**
     * Choose the next event to process for a branch: queued internal events
     * always precede the next unconsumed external event.
     */
    public static Models.Event nextEvent(Map<String, Object> branchState,
                                        List<Models.Event> external,
                                        java.util.Set<String> dropped) {
        List<Object> queued = Json.arr(branchState, "internalQueue");
        if (!queued.isEmpty()) {
            return Models.Event.fromMap(Json.asObj(queued.get(0), "queued event"));
        }
        for (Models.Event event : external) {
            if (!dropped.contains(event.id)
                    && !isConsumed(branchState, event.id)) {
                return event;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static boolean isConsumed(Map<String, Object> state, String eventId) {
        return ((List<Object>) state.getOrDefault("consumed", new ArrayList<>()))
                .contains(eventId);
    }

    public static final class StepResult {
        public final Map<String, Object> node;
        public final Map<String, Object> stateAfter;

        public StepResult(Map<String, Object> node, Map<String, Object> stateAfter) {
            this.node = node;
            this.stateAfter = stateAfter;
        }
    }

    /** Execute exactly one event (the queue head), returning trace node + new state. */
    public static StepResult step(Models.Definition definition,
                                  Map<String, Object> stateBefore,
                                  Models.Event event,
                                  long stepIndex,
                                  String prevHash) {
        // Conditions and actions observe an immutable snapshot taken before the event.
        Map<String, Object> snapshot = Json.copyObj(stateBefore);
        Map<String, Object> working = Json.copyObj(stateBefore);

        List<Object> queueAtStart = Json.arr(working, "internalQueue");
        boolean wasQueued = !queueAtStart.isEmpty()
                && event.id.equals(Json.asObj(queueAtStart.get(0), "queued event").get("id"));

        Rng rng = new Rng(((Number) working.getOrDefault("rng", definition.seed)).longValue());
        EvalContext context = new EvalContext(working, event, rng);

        String stateName = String.valueOf(working.get("state"));
        Map<String, Object> transition = findTransition(definition, stateName, event, context);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("kind", "step");
        node.put("step", stepIndex);
        node.put("definition", definition.fingerprint);
        node.put("event", event.toMap());
        node.put("stateBefore", stateName);
        node.put("stateAfter", stateName);
        node.put("matched", transition != null);
        node.put("internal", wasQueued);

        List<Object> outputs = new ArrayList<>();
        List<Object> spawned = new ArrayList<>();
        String failure = null;
        if (transition != null) {
            String target = Json.str(transition, "to");
            if (target == null) {
                target = stateName;
            }
            try {
                for (Object actionObject : Json.arr(transition, "actions")) {
                    Map<String, Object> action = Json.asObj(actionObject, "action");
                    executeAction(action, context, outputs, spawned);
                }
                working.put("state", target);
                node.put("stateAfter", target);
                node.put("transition", transitionName(transition));
            } catch (RuntimeException e) {
                // Roll back every state change and every internal event this event produced;
                // the failure itself is still recorded in the trace.
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                node.put("stateAfter", stateName);
                node.put("rolledBack", true);
                outputs.clear();
                spawned.clear();
            }
        } else {
            node.put("stateAfter", stateName);
        }

        if (failure != null) {
            // Roll back variables, state name, rng and the events spawned by the
            // failed action, but keep the fact that this event has happened:
            // the internal queue head is dequeued / the external event consumed.
            working = Json.copyObj(snapshot);
        }
        List<Object> newQueue = new ArrayList<>(Json.arr(working, "internalQueue"));
        if (wasQueued) {
            if (!newQueue.isEmpty()) {
                newQueue.remove(0);
            }
        } else {
            List<Object> consumed = new ArrayList<>(Json.arr(working, "consumed"));
            consumed.add(event.id);
            working.put("consumed", consumed);
        }
        if (failure == null) {
            newQueue.addAll(spawned);
            working.put("rng", rng.stateValue());
        }
        working.put("internalQueue", newQueue);

        node.put("outputs", outputs);
        node.put("spawned", spawned);
        node.put("failure", failure);
        node.put("diff", diffStates(snapshot, working, failure != null));
        node.put("hash", hashNode(node, prevHash));
        return new StepResult(node, working);
    }

    private static String transitionName(Map<String, Object> transition) {
        String name = Json.str(transition, "name");
        return name != null ? name
                : String.valueOf(transition.getOrDefault("on", "?")) + "->"
                        + transition.getOrDefault("to", "?");
    }

    private static Map<String, Object> findTransition(Models.Definition definition,
                                                      String stateName,
                                                      Models.Event event,
                                                      EvalContext context) {
        Map<String, Object> fallback = null;
        for (Map<String, Object> transition : definition.transitions()) {
            String from = Json.str(transition, "from");
            if (from != null && !"*".equals(from) && !from.equals(stateName)) {
                continue;
            }
            String on = Json.str(transition, "on");
            if (on != null && !"*".equals(on) && !on.equals(event.type)) {
                continue;
            }
            String condition = Json.str(transition, "when");
            if (condition == null || condition.trim().isEmpty()) {
                if (fallback == null) {
                    fallback = transition;
                }
                continue;
            }
            try {
                if (Expr.isTruthy(Expr.eval(condition, context))) {
                    return transition;
                }
            } catch (RuntimeException e) {
                throw new ReplayException("condition error in transition "
                        + transitionName(transition) + ": " + e.getMessage());
            }
        }
        return fallback;
    }

    private static void executeAction(Map<String, Object> action,
                                      EvalContext context,
                                      List<Object> outputs,
                                      List<Object> spawned) {
        String kind = Json.requireStr(action, "type");
        switch (kind) {
            case "set": {
                String name = Json.requireStr(action, "name");
                Object value = evalArg(action.get("value"), context);
                assign(context.state, name, value);
                break;
            }
            case "inc": {
                String name = Json.requireStr(action, "name");
                double delta = action.containsKey("by")
                        ? ((Number) evalArg(action.get("by"), context)).doubleValue() : 1.0;
                @SuppressWarnings("unchecked")
                Map<String, Object> vars = (Map<String, Object>) context.state.get("vars");
                Object current = vars.get(name);
                double base = current instanceof Number ? ((Number) current).doubleValue() : 0.0;
                double updated = base + delta;
                vars.put(name, updated == Math.rint(updated) ? (long) updated : updated);
                break;
            }
            case "emit": {
                String type = Json.requireStr(action, "event");
                Object payloadValue = action.get("payload");
                Map<String, Object> payload = payloadValue == null
                        ? new LinkedHashMap<>()
                        : Json.asObj(evalArg(payloadValue, context), "emit payload");
                payload = evaluateExpressions(payload, context);
                Map<String, Object> internal = new LinkedHashMap<>();
                int ordinal = spawned.size();
                internal.put("id", "in:" + context.event.id + ":" + ordinal);
                internal.put("type", type);
                internal.put("time", context.event.time);
                internal.put("priority", context.event.priority);
                internal.put("seq", ordinal);
                internal.put("payload", payload);
                internal.put("internal", true);
                internal.put("parentId", context.event.id);
                spawned.add(internal);
                break;
            }
            case "log": {
                Object message = evalArg(action.getOrDefault("message", ""), context);
                outputs.add(message);
                break;
            }
            case "fail": {
                Object message = evalArg(action.getOrDefault("message", "action failed"), context);
                throw new ActionFailureException(String.valueOf(message));
            }
            default:
                throw new ReplayException("unknown action type: " + kind);
        }
    }

    private static Object evalArg(Object spec, EvalContext context) {
        if (spec instanceof String && ((String) spec).startsWith("=")) {
            return Expr.eval(((String) spec).substring(1), context);
        }
        return spec;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evaluateExpressions(Map<String, Object> payload,
                                                           EvalContext context) {
        Map<String, Object> evaluated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && ((String) value).startsWith("=")) {
                value = Expr.eval(((String) value).substring(1), context);
            }
            evaluated.put(entry.getKey(), value);
        }
        return evaluated;
    }

    @SuppressWarnings("unchecked")
    private static void assign(Map<String, Object> state, String path, Object value) {
        if ("state".equals(path)) {
            state.put("state", String.valueOf(value));
            return;
        }
        Map<String, Object> vars = (Map<String, Object>) state.get("vars");
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = vars;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                cursor.put(parts[i], next);
            }
            cursor = (Map<String, Object>) next;
        }
        cursor.put(parts[parts.length - 1], value);
    }

    /** Flat before/after diff of state name and variables. */
    @SuppressWarnings("unchecked")
    public static List<Object> diffStates(Map<String, Object> before,
                                          Map<String, Object> after,
                                          boolean rolledBack) {
        if (rolledBack) {
            return new ArrayList<>();
        }
        List<Object> changes = new ArrayList<>();
        if (!java.util.Objects.equals(before.get("state"), after.get("state"))) {
            changes.add(change("$state", before.get("state"), after.get("state")));
        }
        Map<String, Object> beforeVars = (Map<String, Object>) before.get("vars");
        Map<String, Object> afterVars = (Map<String, Object>) after.get("vars");
        collectDiff("", beforeVars, afterVars, changes);
        return changes;
    }

    private static Map<String, Object> change(String path, Object from, Object to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("path", path);
        change.put("before", from);
        change.put("after", to);
        return change;
    }

    @SuppressWarnings("unchecked")
    private static void collectDiff(String prefix, Map<String, Object> before,
                                    Map<String, Object> after, List<Object> changes) {
        java.util.Set<String> paths = new java.util.TreeSet<>();
        if (before != null) {
            paths.addAll(before.keySet());
        }
        if (after != null) {
            paths.addAll(after.keySet());
        }
        for (String key : paths) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object b = before == null ? null : before.get(key);
            Object a = after == null ? null : after.get(key);
            if (b instanceof Map || a instanceof Map) {
                collectDiff(path,
                        b instanceof Map ? (Map<String, Object>) b : null,
                        a instanceof Map ? (Map<String, Object>) a : null,
                        changes);
            } else if (!java.util.Objects.equals(b, a)) {
                changes.add(change(path, b, a));
            }
        }
    }

    /** Chained hash binding each step to the previous one and to the definition. */
    public static String hashNode(Map<String, Object> node, String prevHash) {
        Map<String, Object> hashPayload = new LinkedHashMap<>();
        hashPayload.put("prev", prevHash);
        hashPayload.put("step", node.get("step"));
        hashPayload.put("event", node.get("event"));
        hashPayload.put("stateBefore", node.get("stateBefore"));
        hashPayload.put("stateAfter", node.get("stateAfter"));
        hashPayload.put("matched", node.get("matched"));
        hashPayload.put("internal", node.get("internal"));
        hashPayload.put("outputs", node.get("outputs"));
        hashPayload.put("spawned", node.get("spawned"));
        hashPayload.put("failure", node.get("failure"));
        hashPayload.put("rolledBack", node.get("rolledBack"));
        return Hashes.sha256(Json.writeCanonical(hashPayload));
    }

    /** Fingerprint of a full trace: definition hash chained with every step hash. */
    public static String traceHash(String definitionFingerprint, List<String> stepHashes) {
        String acc = definitionFingerprint;
        for (String stepHash : stepHashes) {
            acc = Hashes.sha256(acc + ":" + stepHash);
        }
        return acc;
    }

    /** Stable fingerprint of a live state (state name, variables, queue, rng). */
    public static String stateHash(Map<String, Object> state) {
        return Hashes.sha256(Json.writeCanonical(state));
    }

    private static final class EvalContext implements Expr.Context {
        private final Map<String, Object> state;
        private final Models.Event event;
        private final Rng rng;

        EvalContext(Map<String, Object> state, Models.Event event, Rng rng) {
            this.state = state;
            this.event = event;
            this.rng = rng;
        }

        @Override
        public String currentState() {
            return String.valueOf(state.get("state"));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object variable(String name) {
            return ((Map<String, Object>) state.get("vars")).get(name);
        }

        @Override
        public Models.Event event() {
            return event;
        }

        @Override
        public Rng rng() {
            return rng;
        }
    }

    public static class ReplayException extends RuntimeException {
        public ReplayException(String message) {
            super(message);
        }
    }

    public static class ActionFailureException extends ReplayException {
        public ActionFailureException(String message) {
            super(message);
        }
    }
}
