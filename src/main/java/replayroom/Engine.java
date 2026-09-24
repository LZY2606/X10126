package replayroom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Engine {
    private Engine() {}

    public static Map<String, Object> defaultMachine() {
        Map<String, Object> machine = new LinkedHashMap<>();
        machine.put("states", List.of("idle", "active", "failed"));
        machine.put("sources", Map.of("sensor", 10, "operator", 5));
        List<Object> transitions = new ArrayList<>();
        transitions.add(Map.of(
                "id", "start",
                "from", "idle",
                "event", "start",
                "actions", List.of(Map.of("type", "set", "path", "started", "value", true))
        ));
        transitions.add(Map.of(
                "id", "sensor-tick",
                "from", "active",
                "event", "tick",
                "condition", "payload.value >= data.threshold",
                "actions", List.of(
                        Map.of("type", "set", "path", "data.score", "value", "data.score + payload.value"),
                        Map.of("type", "emit", "event", "scored", "payload", Map.of("amount", "payload.value")),
                        Map.of("type", "output", "name", "score-changed", "payload", Map.of("score", "data.score"))
                )
        ));
        transitions.add(Map.of(
                "id", "fail-on-danger",
                "from", "active",
                "event", "danger",
                "actions", List.of(
                        Map.of("type", "set", "path", "data.attempted", "value", true),
                        Map.of("type", "emit", "event", "should-not-commit"),
                        Map.of("type", "fail", "reason", "danger-rejected")
                )
        ));
        transitions.add(Map.of(
                "id", "go-active",
                "from", "idle",
                "event", "activate",
                "to", "active"
        ));
        machine.put("transitions", transitions);
        return machine;
    }

    public static Map<String, Object> defaultInitialData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("score", 0);
        data.put("threshold", 5);
        data.put("started", false);
        return data;
    }

    public static Map<String, Object> step(Map<String, Object> machine, String initialState,
                                           Map<String, Object> initialData, long seed,
                                           String currentState, Map<String, Object> currentData,
                                           List<Map<String, Object>> queue, long internalSeq,
                                           Long currentRng, String previousHash, int stepIndex) {
        List<Map<String, Object>> workingQueue = Json.cloneList(queue);
        int selected = selectIndex(workingQueue);
        Map<String, Object> event = Json.cloneObject(workingQueue.remove(selected));

        Map<String, Object> beforeData = Json.cloneObject(currentData);
        String beforeState = currentState;
        String transitionId = null;
        List<Map<String, Object>> emitted = new ArrayList<>();
        List<Map<String, Object>> outputs = new ArrayList<>();
        Map<String, Object> failure = null;

        long[] rngState = {currentRng == null ? seed : currentRng};
        Map<String, Object> snapshot = buildContext(beforeState, Json.cloneObject(beforeData), event, rngState);
        Map<String, Object> transition = firstMatchingTransition(machine, beforeState, event, snapshot);
        String afterState = beforeState;
        Map<String, Object> afterData = Json.cloneObject(beforeData);
        long nextInternalSeq = internalSeq;

        if (transition != null) {
            transitionId = Json.optionalString(transition, "id", null);
            long[] tentativeRng = {currentRng == null ? seed : currentRng};
            Map<String, Object> tentativeData = Json.cloneObject(beforeData);
            String tentativeState = beforeState;
            List<Map<String, Object>> tentativeEmitted = new ArrayList<>();
            List<Map<String, Object>> tentativeOutputs = new ArrayList<>();
            try {
                String target = transition.get("to") == null ? beforeState
                        : Json.string(transition.get("to"), "transition.to");
                if (!Json.list(machine.get("states"), "machine.states").contains(target)) {
                    throw new IllegalArgumentException("Transition target is unknown: " + target);
                }
                tentativeState = target;
                List<Object> actions = Json.list(transition.getOrDefault("actions", List.of()), "transition.actions");
                for (Object actionValue : actions) {
                    Map<String, Object> action = Json.object(actionValue, "transition.action");
                    int emittedBefore = tentativeEmitted.size();
                    executeAction(action, tentativeState, tentativeData, event, tentativeRng,
                            tentativeEmitted, tentativeOutputs, nextInternalSeq);
                    nextInternalSeq += tentativeEmitted.size() - emittedBefore;
                }
                afterState = tentativeState;
                afterData = tentativeData;
                rngState = tentativeRng;
                emitted = tentativeEmitted;
                outputs = tentativeOutputs;
            } catch (RuntimeException e) {
                failure = new LinkedHashMap<>();
                failure.put("reason", e.getClass().getSimpleName());
                failure.put("message", e.getMessage());
                afterState = beforeState;
                afterData = Json.cloneObject(beforeData);
                rngState = new long[]{currentRng == null ? seed : currentRng};
                emitted = new ArrayList<>();
                outputs = new ArrayList<>();
                nextInternalSeq = internalSeq;
            }
        }

        workingQueue.addAll(0, emitted);
        Map<String, Object> stepRecord = new LinkedHashMap<>();
        stepRecord.put("index", stepIndex);
        stepRecord.put("event", event);
        stepRecord.put("stateBefore", beforeState);
        stepRecord.put("stateAfter", afterState);
        stepRecord.put("dataBefore", beforeData);
        stepRecord.put("dataAfter", Json.cloneObject(afterData));
        stepRecord.put("dataDiff", Diff.maps(beforeData, afterData));
        stepRecord.put("outputs", outputs);
        stepRecord.put("emitted", emitted);
        stepRecord.put("failure", failure);
        stepRecord.put("transitionId", transitionId);
        stepRecord.put("queueAfter", Json.cloneList(workingQueue));
        stepRecord.put("nextInternalSeq", nextInternalSeq);
        stepRecord.put("rngAfter", rngState[0]);

        Map<String, Object> hashInput = new LinkedHashMap<>();
        hashInput.put("previousHash", previousHash);
        hashInput.put("index", stepIndex);
        hashInput.put("initialState", initialState);
        hashInput.put("initialData", initialData);
        hashInput.put("seed", seed);
        hashInput.put("step", stepRecord);
        String hash = Hashing.sha256(hashInput);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record", stepRecord);
        result.put("hash", hash);
        result.put("state", afterState);
        result.put("data", afterData);
        result.put("queue", workingQueue);
        result.put("internalSeq", nextInternalSeq);
        result.put("rngState", rngState[0]);
        return result;
    }

    private static void executeAction(Map<String, Object> action, String state,
                                      Map<String, Object> data, Map<String, Object> event,
                                      long[] rngState, List<Map<String, Object>> emitted,
                                      List<Map<String, Object>> outputs, long nextInternalSeq) {
        String type = Json.string(action.get("type"), "action.type");
        switch (type) {
            case "set" -> {
                String path = Json.string(action.get("path"), "action.path");
                Object rawValue = action.get("value");
                Object value = rawValue instanceof String expression
                        ? Expression.evaluate(expression, buildContext(state, data, event, rngState))
                        : Json.cloneValue(rawValue == null ? null : rawValue);
                setPath(data, path, value);
            }
            case "emit" -> {
                String name = Json.string(action.get("event"), "action.event");
                Object payloadTemplate = action.get("payload");
                Object payload = payloadTemplate instanceof Map<?, ?>
                        ? evaluateValueMap(Json.object(payloadTemplate, "action.payload"), state, data, event, rngState)
                        : payloadTemplate instanceof String expression
                        ? Expression.evaluate(expression, buildContext(state, data, event, rngState))
                        : Json.cloneValue(payloadTemplate);
                Map<String, Object> internalEvent = new LinkedHashMap<>(event);
                internalEvent.put("id", name + "-" + (nextInternalSeq + emitted.size() + 1));
                internalEvent.put("event", name);
                internalEvent.put("source", "__internal__");
                internalEvent.put("priority", 0);
                internalEvent.put("seq", nextInternalSeq + emitted.size() + 1);
                internalEvent.put("internal", true);
                internalEvent.put("payload", payload);
                emitted.add(internalEvent);
            }
            case "output" -> {
                String name = Json.string(action.get("name"), "action.name");
                Object payloadTemplate = action.get("payload");
                Object payload = payloadTemplate instanceof Map<?, ?>
                        ? evaluateValueMap(Json.object(payloadTemplate, "action.payload"), state, data, event, rngState)
                        : payloadTemplate instanceof String expression
                        ? Expression.evaluate(expression, buildContext(state, data, event, rngState))
                        : Json.cloneValue(payloadTemplate);
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("name", name);
                output.put("payload", payload);
                outputs.add(output);
            }
            case "fail" -> throw new ActionFailureException(Json.optionalString(action, "reason", "action-failure"));
            default -> throw new IllegalArgumentException("Unknown action type: " + type);
        }
    }

    private static Map<String, Object> evaluateValueMap(Map<String, Object> template, String state,
                                                        Map<String, Object> data, Map<String, Object> event,
                                                        long[] rngState) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String expression && expressionNeedsEvaluation(expression)) {
                result.put(entry.getKey(), Expression.evaluate(expression, buildContext(state, data, event, rngState)));
            } else {
                result.put(entry.getKey(), Json.cloneValue(value));
            }
        }
        return result;
    }

    private static boolean expressionNeedsEvaluation(String value) {
        return value.contains(" ") || value.contains("payload.") || value.contains("data.")
                || value.contains("state") || value.contains("randInt") || value.contains("time")
                || value.contains("$") || value.contains("+") || value.contains("-");
    }

    private static Map<String, Object> buildContext(String state, Map<String, Object> data,
                                                    Map<String, Object> event, long[] rngState) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("state", state);
        context.put("data", data);
        context.put("payload", Json.cloneObject(event.get("payload")));
        context.put("event", Json.cloneObject(event));
        context.put("time", event.get("time"));
        Map<String, Object> rngHolder = new LinkedHashMap<>();
        rngHolder.put("state", rngState);
        context.put("$rng", rngHolder);
        return context;
    }

    @SuppressWarnings("unchecked")
    private static void setPath(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(parts[parts.length - 1], value);
    }

    private static Map<String, Object> firstMatchingTransition(Map<String, Object> machine, String state,
                                                               Map<String, Object> event,
                                                               Map<String, Object> context) {
        Map<String, Object> wildcard = null;
        for (Object value : Json.list(machine.getOrDefault("transitions", List.of()), "machine.transitions")) {
            Map<String, Object> transition = Json.object(value, "machine.transitions[]");
            String eventName = Json.optionalString(transition, "event", "");
            if (!eventName.isBlank() && !eventName.equals(event.get("event"))) continue;
            Object from = transition.getOrDefault("from", "*");
            boolean exact = fromMatches(from, state, false);
            boolean wildcardMatch = fromMatches(from, state, true);
            if (!exact && !wildcardMatch) continue;
            String condition = transition.get("condition") == null ? null
                    : Json.string(transition.get("condition"), "transition.condition");
            if (condition == null || Expression.isTrue(condition, context)) {
                if (exact) return transition;
                if (wildcard == null) wildcard = transition;
            }
        }
        return wildcard;
    }

    private static boolean fromMatches(Object from, String state, boolean wildcardOnly) {
        if (from instanceof List<?> list) {
            for (Object value : list) {
                String candidate = Json.string(value, "transition.from[]");
                if (candidate.equals("*") && wildcardOnly) return true;
                if (!wildcardOnly && candidate.equals(state)) return true;
            }
            return false;
        }
        String candidate = from == null ? "*" : Json.string(from, "transition.from");
        if (wildcardOnly) return candidate.equals("*");
        return candidate.equals("*") || candidate.equals(state);
    }

    private static int selectIndex(List<Map<String, Object>> queue) {
        int selected = -1;
        EventOrder best = null;
        for (int i = 0; i < queue.size(); i++) {
            EventOrder order = new EventOrder(queue.get(i), i);
            if (best == null || order.compareTo(best) < 0) {
                best = order;
                selected = i;
            }
        }
        return selected;
    }

    private record EventOrder(long time, boolean internal, long priority, long seq, String source, String id, int index)
            implements Comparable<EventOrder> {
        EventOrder(Map<String, Object> event, int index) {
            this(
                    Json.integer(event.get("time"), "event.time"),
                    Boolean.TRUE.equals(event.get("internal")),
                    Json.integer(event.getOrDefault("priority", 0L), "event.priority"),
                    Json.integer(event.getOrDefault("seq", 0L), "event.seq"),
                    Json.optionalString(event, "source", ""),
                    Json.optionalString(event, "id", ""),
                    index
            );
        }

        @Override
        public int compareTo(EventOrder other) {
            int result = Long.compare(time, other.time);
            if (result != 0) return result;
            result = Boolean.compare(other.internal, internal);
            if (result != 0) return result;
            result = Long.compare(other.priority, priority);
            if (result != 0) return result;
            result = Long.compare(seq, other.seq);
            if (result != 0) return result;
            result = source.compareTo(other.source);
            if (result != 0) return result;
            result = id.compareTo(other.id);
            if (result != 0) return result;
            return Integer.compare(index, other.index);
        }
    }
}
