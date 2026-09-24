package replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Engine {
    public static final String VERSION = "state-machine-replay-room/v1";

    public static String definitionFingerprint(Map<String, Object> definition) {
        validateDefinition(definition);
        return Hashes.sha256(Json.canonical(Map.of("format", VERSION, "definition", definition)));
    }

    public static Map<String, Object> initialSnapshot(Map<String, Object> definition, long seed, String branchId) {
        validateDefinition(definition);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("state", Json.string(definition, "initialState"));
        snapshot.put("variables", Json.clone(definition.getOrDefault("variables", Map.of())));
        snapshot.put("rngState", seed & ((1L << 48) - 1));
        snapshot.put("seed", seed);
        snapshot.put("branchId", branchId);
        return snapshot;
    }

    public static void validateDefinition(Map<String, Object> definition) {
        if (definition == null) throw new EngineException("definition is required");
        List<Object> states = Json.list(definition.get("states"));
        if (states.isEmpty()) throw new EngineException("states must not be empty");
        List<String> stateNames = new ArrayList<>();
        for (Object state : states) {
            if (!(state instanceof String name) || name.isBlank()) throw new EngineException("state names must be non-empty strings");
            stateNames.add(name);
        }
        String initial = Json.string(definition, "initialState");
        if (!stateNames.contains(initial)) throw new EngineException("initialState is not declared in states");
        if (!(definition.getOrDefault("variables", Map.of()) instanceof Map)) throw new EngineException("variables must be an object");
        for (Map.Entry<String, Object> variable : Json.at(definition, "variables").entrySet()) {
            validateConstant(variable.getValue(), "variable " + variable.getKey());
        }
        List<Object> transitions = Json.list(definition.get("transitions"));
        for (int i = 0; i < transitions.size(); i++) {
            Map<String, Object> transition = Json.object(transitions.get(i));
            String from = Json.string(transition, "from");
            String to = Json.optionalString(transition, "to", from);
            String on = Json.string(transition, "on");
            if (!stateNames.contains(from)) throw new EngineException("transition " + i + " has unknown from state");
            if (!stateNames.contains(to)) throw new EngineException("transition " + i + " has unknown to state");
            if (on.isBlank()) throw new EngineException("transition " + i + " event type is blank");
            Object condition = transition.get("condition");
            if (condition != null) validateCondition(Json.object(condition));
            Object actions = transition.getOrDefault("actions", List.of());
            for (Object action : Json.list(actions)) validateAction(Json.object(action), stateNames, i);
        }
    }

    public static StepResult applyEvent(Map<String, Object> definition, Map<String, Object> before, Map<String, Object> event,
                                        long stepIndex, String previousHash) {
        validateDefinition(definition);
        Map<String, Object> snapshotBefore = Json.clone(before);
        Map<String, Object> candidate = Json.clone(snapshotBefore);
        DeterministicRandom random = new DeterministicRandom(longNumber(snapshotBefore.get("rngState"), "rngState"));
        List<Map<String, Object>> chosenTransitions = matchingTransitions(definition, snapshotBefore, event);
        List<Map<String, Object>> actionRecords = new ArrayList<>();
        List<Map<String, Object>> outputs = new ArrayList<>();
        List<Map<String, Object>> internalEvents = new ArrayList<>();
        Map<String, Object> failure = null;
        String outcome;
        if (chosenTransitions.isEmpty()) {
            outcome = "no_transition";
        } else {
            Map<String, Object> transition = chosenTransitions.get(0);
            candidate.put("state", Json.optionalString(transition, "to", Json.string(transition, "from")));
            outcome = "transitioned";
            int emitOrdinal = 0;
            List<Object> actions = Json.list(transition.getOrDefault("actions", List.of()));
            for (int actionIndex = 0; actionIndex < actions.size(); actionIndex++) {
                Map<String, Object> action = Json.object(actions.get(actionIndex));
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("index", actionIndex);
                record.put("type", Json.string(action, "type"));
                try {
                    executeAction(action, candidate, random, event, stepIndex, Json.string(event, "id"), emitOrdinal, internalEvents, outputs, record);
                    if ("emit".equals(action.get("type"))) emitOrdinal++;
                    record.put("status", "ok");
                    actionRecords.add(record);
                } catch (ActionFailure e) {
                    failure = new LinkedHashMap<>(e.details);
                    failure.put("actionIndex", actionIndex);
                    failure.putIfAbsent("message", e.getMessage());
                    failure.put("transition", transitionIdentity(transition));
                    record.put("status", "failed");
                    record.put("message", e.getMessage());
                    actionRecords.add(record);
                    candidate = Json.clone(snapshotBefore);
                    internalEvents.clear();
                    outputs.clear();
                    outcome = "action_failed";
                    break;
                }
            }
        }
        candidate.put("rngState", random.stateValue());
        if ("action_failed".equals(outcome)) {
            candidate.put("rngState", snapshotBefore.get("rngState"));
        }
        Map<String, Object> eventContent = eventContent(event);
        Map<String, Object> hashMaterial = new LinkedHashMap<>();
        hashMaterial.put("version", VERSION);
        hashMaterial.put("previousHash", previousHash);
        hashMaterial.put("index", stepIndex);
        hashMaterial.put("event", eventContent);
        hashMaterial.put("before", compactSnapshot(snapshotBefore));
        hashMaterial.put("after", "action_failed".equals(outcome) ? compactSnapshot(snapshotBefore) : compactSnapshot(candidate));
        hashMaterial.put("outcome", outcome);
        hashMaterial.put("failure", failure);
        hashMaterial.put("outputs", outputs);
        hashMaterial.put("internalEvents", internalEventContent(internalEvents));
        String hash = Hashes.sha256(Json.canonical(hashMaterial));
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("index", stepIndex);
        step.put("eventId", event.get("id"));
        step.put("event", eventContent);
        step.put("outcome", outcome);
        step.put("stateBefore", snapshotBefore.get("state"));
        step.put("stateAfter", ("action_failed".equals(outcome) ? snapshotBefore : candidate).get("state"));
        step.put("before", compactSnapshot(snapshotBefore));
        step.put("after", compactSnapshot("action_failed".equals(outcome) ? snapshotBefore : candidate));
        step.put("actions", actionRecords);
        step.put("outputs", outputs);
        step.put("internalEvents", internalEvents);
        step.put("failure", failure);
        step.put("hash", hash);
        StepResult result = new StepResult();
        result.step = step;
        result.snapshot = "action_failed".equals(outcome) ? Json.clone(snapshotBefore) : candidate;
        result.internalEvents = internalEvents;
        return result;
    }

    public static Comparator<Map<String, Object>> eventComparator() {
        return Comparator
                .comparingLong((Map<String, Object> event) -> longNumber(event.get("logicalTime"), "logicalTime"))
                .thenComparing(event -> Boolean.TRUE.equals(event.get("internal")))
                .thenComparingLong(event -> Boolean.TRUE.equals(event.get("internal"))
                        ? longNumber(event.getOrDefault("parentStep", 0L), "parentStep") : Long.MIN_VALUE)
                .thenComparingLong(event -> longNumber(event.get("sourcePriority"), "sourcePriority"))
                .thenComparingLong(event -> longNumber(event.get("originalSeq"), "originalSeq"))
                .thenComparing(event -> Json.optionalString(event, "source", ""))
                .thenComparing(event -> Json.optionalString(event, "id", ""));
    }

    public static Map<String, Object> eventContent(Map<String, Object> event) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("logicalTime", longNumber(event.get("logicalTime"), "logicalTime"));
        content.put("type", Json.string(event, "type"));
        content.put("payload", event.getOrDefault("payload", Map.of()));
        content.put("source", Json.optionalString(event, "source", "external"));
        content.put("sourcePriority", longNumber(event.get("sourcePriority"), "sourcePriority"));
        content.put("originalSeq", longNumber(event.get("originalSeq"), "originalSeq"));
        content.put("internal", Boolean.TRUE.equals(event.get("internal")));
        if (event.containsKey("parentStep")) content.put("parentStep", longNumber(event.get("parentStep"), "parentStep"));
        return content;
    }

    public static String eventKey(Map<String, Object> event) {
        return Hashes.sha256(Json.canonical(eventContent(event)));
    }

    public static Map<String, Object> snapshotDiff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", Map.of("before", before.get("state"), "after", after.get("state")));
        Map<String, Object> variables = new LinkedHashMap<>();
        Map<String, Object> left = Json.object(before.getOrDefault("variables", Map.of()));
        Map<String, Object> right = Json.object(after.getOrDefault("variables", Map.of()));
        for (String key : unionKeys(left, right)) {
            if (!Objects.equals(left.get(key), right.get(key))) {
                Map<String, Object> variableDiff = new LinkedHashMap<>();
                variableDiff.put("before", left.getOrDefault(key, null));
                variableDiff.put("after", right.getOrDefault(key, null));
                variables.put(key, variableDiff);
            }
        }
        result.put("variables", variables);
        Map<String, Object> rngDiff = new LinkedHashMap<>();
        rngDiff.put("before", before.get("rngState"));
        rngDiff.put("after", after.get("rngState"));
        result.put("rngState", rngDiff);
        return result;
    }

    private static void executeAction(Map<String, Object> action, Map<String, Object> snapshot, DeterministicRandom random,
                                      Map<String, Object> event, long stepIndex, String parentId, int emitOrdinal,
                                      List<Map<String, Object>> internalEvents, List<Map<String, Object>> outputs,
                                      Map<String, Object> record) {
        String type = Json.string(action, "type");
        switch (type) {
            case "set" -> {
                String name = Json.string(action, "var");
                Object value = evaluateValue(action.get("value"), snapshot, event);
                variables(snapshot).put(name, value);
                record.put("var", name);
                record.put("value", value);
            }
            case "random" -> {
                String name = Json.string(action, "var");
                long min = Json.longValue(action, "min", 0);
                long max = Json.longValue(action, "max", Long.MAX_VALUE);
                long value = random.nextLong(min, max);
                variables(snapshot).put(name, value);
                record.put("var", name);
                record.put("value", value);
            }
            case "output" -> {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("channel", Json.optionalString(action, "channel", "default"));
                output.put("payload", evaluateValue(action.getOrDefault("payload", Map.of()), snapshot, event));
                outputs.add(output);
                record.put("output", output);
            }
            case "emit" -> {
                Map<String, Object> emitted = new LinkedHashMap<>();
                emitted.put("id", parentId + ":internal:" + emitOrdinal);
                emitted.put("logicalTime", longNumber(event.get("logicalTime"), "logicalTime"));
                emitted.put("type", Json.string(action, "eventType"));
                emitted.put("source", "internal");
                emitted.put("sourcePriority", 0L);
                emitted.put("originalSeq", (long) emitOrdinal);
                emitted.put("internal", true);
                emitted.put("parentStep", stepIndex);
                emitted.put("payload", evaluateValue(action.getOrDefault("payload", Map.of()), snapshot, event));
                internalEvents.add(emitted);
                record.put("event", eventContent(emitted));
            }
            case "fail" -> {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("type", "action_failure");
                details.put("code", Json.optionalString(action, "code", "ACTION_FAILED"));
                details.put("message", Json.optionalString(action, "message", "action failed"));
                throw new ActionFailure(details);
            }
            default -> throw new EngineException("unknown action type " + type);
        }
    }

    private static List<Map<String, Object>> matchingTransitions(Map<String, Object> definition,
                                                                  Map<String, Object> snapshot,
                                                                  Map<String, Object> event) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : Json.list(definition.get("transitions"))) {
            Map<String, Object> transition = Json.object(item);
            if (!Json.string(transition, "from").equals(snapshot.get("state"))) continue;
            if (!Json.string(transition, "on").equals(event.get("type"))) continue;
            Object condition = transition.get("condition");
            if (condition == null || evaluateCondition(Json.object(condition), snapshot, event)) {
                result.add(transition);
            }
        }
        return result;
    }

    private static boolean evaluateCondition(Map<String, Object> condition, Map<String, Object> snapshot,
                                             Map<String, Object> event) {
        String op = Json.string(condition, "op");
        return switch (op) {
            case "always" -> true;
            case "never" -> false;
            case "not" -> !evaluateCondition(Json.object(condition.get("condition")), snapshot, event);
            case "and" -> {
                boolean value = true;
                for (Object child : Json.list(condition.get("conditions"))) {
                    value = value && evaluateCondition(Json.object(child), snapshot, event);
                }
                yield value;
            }
            case "or" -> {
                boolean value = false;
                for (Object child : Json.list(condition.get("conditions"))) {
                    value = value || evaluateCondition(Json.object(child), snapshot, event);
                }
                yield value;
            }
            case "exists" -> resolvePath(Json.string(condition, "path"), snapshot, event) != null;
            case "eq", "ne", "lt", "le", "gt", "ge" -> compare(op,
                    resolvePath(Json.string(condition, "path"), snapshot, event),
                    evaluateValue(condition.get("value"), snapshot, event));
            default -> throw new EngineException("unknown condition operator " + op);
        };
    }

    private static boolean compare(String op, Object left, Object right) {
        int comparison;
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            comparison = Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        } else {
            comparison = String.valueOf(left).compareTo(String.valueOf(right));
        }
        return switch (op) {
            case "eq" -> Objects.equals(left, right) || comparison == 0;
            case "ne" -> !Objects.equals(left, right) && comparison != 0;
            case "lt" -> comparison < 0;
            case "le" -> comparison <= 0;
            case "gt" -> comparison > 0;
            case "ge" -> comparison >= 0;
            default -> false;
        };
    }

    private static Object evaluateValue(Object expression, Map<String, Object> snapshot, Map<String, Object> event) {
        if (!(expression instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Json.clone(expression);
        }
        Map<String, Object> value = Json.object(expression);
        if (value.containsKey("literal")) return Json.clone(value.get("literal"));
        if (value.containsKey("const")) return Json.clone(value.get("const"));
        if (value.containsKey("var")) return resolvePath("variables." + Json.string(value, "var"), snapshot, event);
        if (value.containsKey("path")) return resolvePath(Json.string(value, "path"), snapshot, event);
        if (value.containsKey("event")) return resolvePath("event." + Json.string(value, "event"), snapshot, event);
        return Json.clone(value);
    }

    private static Object resolvePath(String path, Map<String, Object> snapshot, Map<String, Object> event) {
        String[] parts = path.split("\\.");
        Object current = switch (parts[0]) {
            case "state" -> snapshot.get("state");
            case "variables" -> snapshot.get("variables");
            case "event" -> event;
            default -> throw new EngineException("unsupported condition path " + path);
        };
        for (int i = 1; i < parts.length; i++) {
            if (current == null) return null;
            if (!(current instanceof Map<?, ?> map)) {
                throw new EngineException("path does not resolve to object: " + path);
            }
            current = map.get(parts[i]);
        }
        return current;
    }

    private static Map<String, Object> variables(Map<String, Object> snapshot) {
        return Json.object(snapshot.get("variables"));
    }

    private static Map<String, Object> compactSnapshot(Map<String, Object> snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", snapshot.get("state"));
        result.put("variables", snapshot.get("variables"));
        result.put("rngState", snapshot.get("rngState"));
        return result;
    }

    private static List<Object> internalEventContent(List<Map<String, Object>> events) {
        List<Object> result = new ArrayList<>();
        for (Map<String, Object> event : events) result.add(eventContent(event));
        return result;
    }

    private static Map<String, Object> transitionIdentity(Map<String, Object> transition) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", transition.get("from"));
        result.put("to", transition.get("to"));
        result.put("on", transition.get("on"));
        return result;
    }

    private static List<String> unionKeys(Map<String, Object> left, Map<String, Object> right) {
        List<String> result = new ArrayList<>();
        for (String key : left.keySet()) if (!result.contains(key)) result.add(key);
        for (String key : right.keySet()) if (!result.contains(key)) result.add(key);
        return result;
    }

    private static long longNumber(Object value, String name) {
        if (!(value instanceof Number number)) throw new EngineException(name + " must be an integer");
        return number.longValue();
    }

    private static void validateCondition(Map<String, Object> condition) {
        String op = Json.string(condition, "op");
        switch (op) {
            case "always", "never" -> { }
            case "not" -> validateCondition(Json.object(condition.get("condition")));
            case "and", "or" -> {
                for (Object child : Json.list(condition.get("conditions"))) validateCondition(Json.object(child));
            }
            case "exists", "eq", "ne", "lt", "le", "gt", "ge" -> {
                if (!(Json.string(condition, "path")).startsWith("state")
                        && !Json.string(condition, "path").startsWith("variables.")
                        && !Json.string(condition, "path").startsWith("event.")) {
                    throw new EngineException("unsupported condition path in " + op);
                }
            }
            default -> throw new EngineException("unknown condition operator " + op);
        }
    }

    private static void validateAction(Map<String, Object> action, List<String> states, int transitionIndex) {
        String type = Json.string(action, "type");
        switch (type) {
            case "set" -> {
                requireNonBlank(action, "var", transitionIndex);
                validateConstant(action.get("value"), "set value");
            }
            case "random" -> {
                requireNonBlank(action, "var", transitionIndex);
                Json.longValue(action, "min", 0);
                Json.longValue(action, "max", Long.MAX_VALUE);
            }
            case "output" -> validateConstant(action.getOrDefault("payload", Map.of()), "output payload");
            case "emit" -> {
                requireNonBlank(action, "eventType", transitionIndex);
                validateConstant(action.getOrDefault("payload", Map.of()), "emit payload");
            }
            case "fail" -> { }
            default -> throw new EngineException("unknown action type " + type);
        }
    }

    private static void validateConstant(Object value, String name) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) return;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new EngineException(name + " has non-string key");
                validateConstant(entry.getValue(), name);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) validateConstant(item, name);
        } else {
            throw new EngineException(name + " contains unsupported value");
        }
    }

    private static void requireNonBlank(Map<String, Object> action, String key, int transitionIndex) {
        if (Json.optionalString(action, key, "").isBlank()) {
            throw new EngineException("transition " + transitionIndex + " action missing " + key);
        }
    }

    public static final class StepResult {
        public Map<String, Object> step;
        public Map<String, Object> snapshot;
        public List<Map<String, Object>> internalEvents;
    }

    private static final class ActionFailure extends RuntimeException {
        private final Map<String, Object> details;

        private ActionFailure(Map<String, Object> details) {
            super(Json.optionalString(details, "message", "action failed"), null, false, false);
            this.details = details;
        }
    }
}
