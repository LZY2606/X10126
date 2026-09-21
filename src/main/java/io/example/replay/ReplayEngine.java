package io.example.replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReplayEngine {
    private final MachineDefinition definition;
    private final long seed;
    private ReplayState state;
    private List<StoredEvent> externalEvents = new ArrayList<>();

    public ReplayEngine(MachineDefinition definition, long seed) {
        this.definition = definition;
        this.seed = seed;
        reset(List.of());
    }

    public final void reset(List<StoredEvent> events) {
        state = new ReplayState(definition.initialState(), definition.initialData(), seed);
        state.variables.put("seed", seed);
        externalEvents = new ArrayList<>();
        for (StoredEvent event : events) {
            externalEvents.add(event.copy());
        }
        externalEvents.sort(externalComparator());
    }

    public void restore(ReplayState state, List<StoredEvent> remainingExternal) {
        this.state = state.copy();
        externalEvents = new ArrayList<>();
        for (StoredEvent event : remainingExternal) {
            externalEvents.add(event.copy());
        }
        externalEvents.sort(externalComparator());
    }

    public boolean canStep() {
        return !state.internalQueue.isEmpty() || !externalEvents.isEmpty();
    }

    public StoredEvent nextEvent() {
        StoredEvent internal = state.internalQueue.isEmpty() ? null : state.internalQueue.get(0);
        StoredEvent external = externalEvents.isEmpty() ? null : externalEvents.get(0);
        if (internal == null) {
            return external;
        }
        if (external == null) {
            return internal;
        }
        if (external.time() < internal.time() || internal.enqueueSeq() == 0L) {
            return external;
        }
        return internal;
    }

    public StepResult step() {
        if (!canStep()) {
            return null;
        }
        StoredEvent event = nextEvent();
        boolean external = !event.internal();
        if (external) {
            externalEvents.remove(0);
        } else {
            state.internalQueue.remove(0);
        }

        Map<String, Object> before = state.snapshotJson();
        ReplayState candidate = state.copy();
        Map<String, Object> transitionJson;
        String status = null;
        String error = null;
        try {
            transitionJson = findTransition(event, candidate);
        } catch (RuntimeException e) {
            transitionJson = null;
            status = "failed";
            error = e.getMessage();
        }
        List<Object> outputs = new ArrayList<>();
        String transitionId = null;

        if ("failed".equals(status)) {
            // Failed condition evaluation is recorded against the unchanged state.
        } else if (transitionJson == null) {
            status = "ignored";
        } else {
            transitionId = Json.string(transitionJson, "id");
            try {
                executeTransition(transitionJson, event, candidate, outputs);
                state = candidate;
                status = "ok";
            } catch (RuntimeException e) {
                error = e.getMessage();
                status = "failed";
            }
        }

        Map<String, Object> after = state.snapshotJson();
        TraceStep traceStep = appendTrace(event, before, after, status, transitionId, error, outputs);
        return new StepResult(event.copy(), traceStep, state.copy(),
                Json.object(Json.deepCopy(before)), Json.object(Json.deepCopy(after)),
                Diff.compute(before, after), remainingExternalJson());
    }

    public ReplayState replayState() {
        return state.copy();
    }

    public List<StoredEvent> remainingExternalEvents() {
        return externalEvents.stream().map(StoredEvent::copy).toList();
    }

    public String currentTraceHash() {
        return state.trace.isEmpty() ? null : state.trace.get(state.trace.size() - 1).traceHash();
    }

    private Map<String, Object> findTransition(StoredEvent event, ReplayState candidate) {
        for (Map<String, Object> transition : definition.transitions()) {
            String from = Json.string(transition, "from");
            if (!"*".equals(from) && !from.equals(candidate.currentState)) {
                continue;
            }
            if (!Json.string(transition, "event").equals(event.type())) {
                continue;
            }
            String condition = Json.string(transition, "condition");
            try {
                DeterministicRandom conditionRandom = candidate.random();
                Expression.EvalContext context = contextFor(event, candidate, conditionRandom);
                if (Expression.isTrue(condition, context)) {
                    return transition;
                }
            } catch (RuntimeException e) {
                throw new ConditionException(e.getMessage());
            }
        }
        return null;
    }

    private void executeTransition(Map<String, Object> transition, StoredEvent event,
                                   ReplayState candidate, List<Object> outputs) {
        candidate.currentState = Json.string(transition, "to");
        DeterministicRandom random = candidate.random();
        for (Object actionValue : Json.list(transition.get("actions"))) {
            executeAction(Json.object(actionValue), event, candidate, random, outputs);
        }
        candidate.saveRandom(random);
    }

    private void executeAction(Map<String, Object> action, StoredEvent event,
                               ReplayState candidate, DeterministicRandom random, List<Object> outputs) {
        String type = Json.string(action, "type");
        if (type == null) {
            throw new IllegalArgumentException("Action requires type");
        }
        Expression.EvalContext context = contextFor(event, candidate, random);
        switch (type) {
            case "set" -> {
                String path = Json.string(action, "path");
                if (path == null || path.isBlank()) {
                    throw new IllegalArgumentException("set action requires path");
                }
                String valueExpression = Json.string(action, "expression");
                Object value = valueExpression == null
                        ? Json.deepCopy(action.get("value"))
                        : Expression.normalizeNumber(Expression.evaluate(valueExpression, context));
                Paths.set(pathRoot(path, candidate), pathTail(path), value);
            }
            case "remove" -> Paths.remove(pathRoot(requiredPath(action), candidate),
                    pathTail(requiredPath(action)));
            case "output" -> outputs.add(outputJson(action, context));
            case "emit" -> {
                String eventType = requiredString(action, "eventType");
                String payloadExpression = Json.string(action, "payloadExpression");
                Object payloadValue = payloadExpression == null
                        ? action.get("payload")
                        : Expression.evaluate(payloadExpression, context);
                Map<String, Object> payload = payloadValue == null
                        ? Map.of()
                        : Json.object(Json.deepCopy(payloadValue));
                long delay = (long) Math.floor(numberOrDefault(action.get("delay"), 0d));
                StoredEvent internalEvent = StoredEvent.internal(eventType,
                        event.time() + Math.max(0L, delay), candidate.nextInternalSeq++, payload);
                candidate.internalQueue.add(internalEvent);
                candidate.internalQueue.sort(internalComparator());
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("kind", "emit");
                output.put("event", internalEvent.toJsonMutable());
                outputs.add(output);
            }
            case "failIf" -> {
                if (Expression.isTrue(Json.string(action, "condition"), context)) {
                    candidate.saveRandom(random);
                    throw new ActionFailure(resolveMessage(action, context));
                }
            }
            case "fail" -> {
                candidate.saveRandom(random);
                throw new ActionFailure(resolveMessage(action, context));
            }
            default -> throw new IllegalArgumentException("Unsupported action type: " + type);
        }
    }

    private String resolveMessage(Map<String, Object> action, Expression.EvalContext context) {
        String expression = Json.string(action, "messageExpression");
        if (expression != null) {
            return String.valueOf(Expression.evaluate(expression, context));
        }
        Object message = action.get("message");
        return message == null ? "action failed" : String.valueOf(message);
    }

    private Map<String, Object> outputJson(Map<String, Object> action, Expression.EvalContext context) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "output");
        result.put("name", action.getOrDefault("name", "message"));
        Object value = action.get("value");
        if (value != null) {
            result.put("value", Json.deepCopy(value));
        }
        String expression = Json.string(action, "expression");
        if (expression != null) {
            result.put("value", Expression.evaluate(expression, context));
        }
        return result;
    }

    private Expression.EvalContext contextFor(StoredEvent event, ReplayState target, DeterministicRandom random) {
        Map<String, Object> eventMap = event.toJsonMutable();
        return new Expression.EvalContext(target.data, target.variables, eventMap, event.payload(), seed, random);
    }

    private TraceStep appendTrace(StoredEvent event, Map<String, Object> before, Map<String, Object> after,
                                  String status, String transitionId, String error, List<Object> outputs) {
        int index = state.trace.size();
        Map<String, Object> hashPayload = new LinkedHashMap<>();
        hashPayload.put("index", index);
        hashPayload.put("event", event.toJsonMutable());
        hashPayload.put("status", status);
        hashPayload.put("transitionId", transitionId);
        hashPayload.put("error", error);
        hashPayload.put("outputs", Json.deepCopy(outputs));
        hashPayload.put("after", after);
        String stepHash = Hashing.sha256(Json.canonical(hashPayload));
        String previousHash = currentTraceHash();
        Map<String, Object> chainPayload = new LinkedHashMap<>();
        chainPayload.put("previous", previousHash);
        chainPayload.put("stepHash", stepHash);
        String traceHash = Hashing.chain(previousHash, Json.canonical(chainPayload));
        TraceStep step = new TraceStep(index, event.id(), event.type(), event.time(), event.source(),
                event.internal(), status, transitionId, error, Json.deepCopy(outputs), before, after,
                stepHash, traceHash);
        state.trace.add(step);
        return step;
    }

    private List<Object> remainingExternalJson() {
        return externalEvents.stream().map(event -> (Object) event.toJsonMutable()).toList();
    }

    private static Map<String, Object> pathRoot(String path, ReplayState state) {
        String normalized = path.startsWith("$") ? path.substring(1) : path;
        String root = normalized.startsWith(".") ? normalized.substring(1) : normalized;
        int dot = root.indexOf('.');
        String name = dot < 0 ? root : root.substring(0, dot);
        return switch (name) {
            case "data" -> state.data;
            case "vars" -> state.variables;
            default -> throw new IllegalArgumentException("Path must start with state, data or vars");
        };
    }

    private static String pathTail(String path) {
        String normalized = path.startsWith("$") ? path.substring(1) : path;
        normalized = normalized.startsWith(".") ? normalized.substring(1) : normalized;
        int dot = normalized.indexOf('.');
        return dot < 0 ? null : normalized.substring(dot + 1);
    }

    private static String requiredPath(Map<String, Object> action) {
        String path = Json.string(action, "path");
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("action requires path");
        }
        return path;
    }

    private static String requiredString(Map<String, Object> action, String key) {
        String value = Json.string(action, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("action requires " + key);
        }
        return value;
    }

    private static double numberOrDefault(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static Comparator<StoredEvent> externalComparator() {
        return Comparator.comparingLong(StoredEvent::time)
                .thenComparingInt(StoredEvent::priority)
                .thenComparing(StoredEvent::source, Comparator.nullsFirst(String::compareTo))
                .thenComparingLong(StoredEvent::originalSeq)
                .thenComparing(StoredEvent::id, Comparator.nullsFirst(String::compareTo));
    }

    private static Comparator<StoredEvent> internalComparator() {
        return Comparator.comparingLong(StoredEvent::time)
                .thenComparingLong(StoredEvent::enqueueSeq);
    }

    public record StepResult(StoredEvent event, TraceStep step, ReplayState state,
                             Map<String, Object> before, Map<String, Object> after,
                             List<Diff.Change> diff, List<Object> remainingExternal) {
    }

    public static class ConditionException extends RuntimeException {
        public ConditionException(String message) {
            super(message);
        }
    }

    public static class ActionFailure extends RuntimeException {
        public ActionFailure(String message) {
            super(message);
        }
    }
}
