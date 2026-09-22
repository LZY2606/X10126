package replay.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import replay.json.JsonUtil;

public final class ReplayEngine {
    private final Map<String, Object> definition;
    private final String definitionFingerprint;
    private Map<String, Object> data;
    private String currentState;
    private long randomState;
    private long nextInternalSequence;
    private final List<Map<String, Object>> pending;
    private final List<Map<String, Object>> trajectory;
    private String trajectoryHash;
    private long baseStepCount;

    public ReplayEngine(Map<String, Object> definition) {
        Definition.validate(definition);
        this.definition = definition;
        this.definitionFingerprint = JsonUtil.fingerprint(definition);
        this.currentState = JsonUtil.string(definition, "initialState");
        this.data = JsonUtil.object(JsonUtil.deepCopy(definition.getOrDefault("initialData", Map.of())), "initialData");
        this.randomState = JsonUtil.integer(definition.getOrDefault("randomSeed", 0L), "randomSeed");
        this.nextInternalSequence = 1L;
        this.pending = new ArrayList<>();
        this.trajectory = new ArrayList<>();
        this.trajectoryHash = baseHash();
    }

    public static ReplayEngine fromCheckpoint(Map<String, Object> definition, Map<String, Object> checkpoint) {
        Definition.validate(definition);
        String expected = JsonUtil.fingerprint(definition);
        String actual = String.valueOf(checkpoint.get("definitionFingerprint"));
        if (!expected.equals(actual)) {
            throw new DefinitionMismatchException(actual, expected);
        }
        ReplayEngine engine = new ReplayEngine(definition);
        engine.currentState = JsonUtil.string(checkpoint, "currentState");
        engine.data = JsonUtil.object(JsonUtil.deepCopy(checkpoint.getOrDefault("data", Map.of())), "data");
        engine.randomState = JsonUtil.integer(checkpoint.get("randomState"), "randomState");
        engine.nextInternalSequence = JsonUtil.integer(checkpoint.get("nextInternalSequence"), "nextInternalSequence");
        engine.trajectoryHash = String.valueOf(checkpoint.get("trajectoryHash"));
        engine.baseStepCount = JsonUtil.integer(checkpoint.get("stepIndex"), "stepIndex");
        engine.pending.clear();
        for (Object item : JsonUtil.list(checkpoint.get("pending"), "pending")) {
            engine.pending.add(JsonUtil.object(JsonUtil.deepCopy(item), "pending event"));
        }
        engine.trajectory.clear();
        return engine;
    }

    public void enqueueExternal(List<Map<String, Object>> events) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> event : events) {
            normalized.add(normalizeExternal(definition, event));
        }
        pending.addAll(normalized);
        sortPending();
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    public StepResult step() {
        if (pending.isEmpty()) {
            throw new IllegalStateException("No pending event");
        }
        Map<String, Object> event = pending.remove(0);
        Map<String, Object> before = snapshot();
        Map<String, Object> transition = findTransition(event, before);
        List<Map<String, Object>> outputs = new ArrayList<>();
        List<Map<String, Object>> spawned = new ArrayList<>();
        String failure = null;
        String nextState = before.get("state").toString();
        long savedRandomState = randomState;
        long savedInternalSequence = nextInternalSequence;
        Map<String, Object> savedData = JsonUtil.object(JsonUtil.deepCopy(data), "data backup");

        if (transition != null) {
            nextState = JsonUtil.optionalString(transition, "to", currentState);
            try {
                executeActions(JsonUtil.list(transition.get("actions"), "actions"), event, before, outputs, spawned);
            } catch (ActionFailureException e) {
                failure = e.getMessage();
                randomState = savedRandomState;
                nextInternalSequence = savedInternalSequence;
                data = savedData;
                spawned.clear();
                outputs.clear();
                nextState = currentState;
            }
        }

        Map<String, Object> after = snapshot(nextState);
        currentState = nextState;
        pending.addAll(0, spawned);
        sortPending();

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("stepIndex", baseStepCount + trajectory.size() + 1L);
        record.put("before", before);
        record.put("event", JsonUtil.deepCopy(canonicalEvent(event)));
        record.put("transition", transitionName(transition));
        record.put("outputs", outputs);
        record.put("derivedInternalEvents", spawned.stream().map(this::canonicalEvent).toList());
        record.put("failure", failure);
        record.put("after", after);
        record.put("fingerprint", stepFingerprint(record));
        trajectory.add(record);
        trajectoryHash = record.get("fingerprint").toString();
        return new StepResult(record, failure);
    }

    public void loadRuntime(Map<String, Object> runtime) {
        currentState = JsonUtil.string(runtime, "state");
        data = JsonUtil.object(JsonUtil.deepCopy(runtime.get("data")), "runtime data");
        randomState = JsonUtil.integer(runtime.get("randomState"), "randomState");
        nextInternalSequence = JsonUtil.integer(runtime.get("nextInternalSequence"), "nextInternalSequence");
        trajectoryHash = String.valueOf(runtime.get("trajectoryHash"));
        baseStepCount = JsonUtil.integer(runtime.get("stepIndex"), "runtime stepIndex");
        pending.clear();
        for (Object event : JsonUtil.list(runtime.get("pending"), "pending")) {
            pending.add(JsonUtil.object(JsonUtil.deepCopy(event), "pending event"));
        }
        trajectory.clear();
        for (Object step : JsonUtil.list(runtime.get("steps"), "steps")) {
            trajectory.add(JsonUtil.object(JsonUtil.deepCopy(step), "step"));
        }
    }

    public Map<String, Object> checkpoint(String name, String branchId) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("checkpointId", JsonUtil.sha256(branchId + ":" + name + ":" + trajectoryHash + ":" + trajectory.size()));
        checkpoint.put("name", name);
        checkpoint.put("branchId", branchId);
        checkpoint.put("stepIndex", (long) trajectory.size());
        checkpoint.put("currentState", currentState);
        checkpoint.put("data", JsonUtil.deepCopy(data));
        checkpoint.put("randomState", randomState);
        checkpoint.put("nextInternalSequence", nextInternalSequence);
        checkpoint.put("pending", JsonUtil.deepCopy(pending));
        checkpoint.put("externalIds", externalIds());
        checkpoint.put("processedExternalIds", processedExternalIds());
        checkpoint.put("definitionVersion", definition.get("definitionVersion"));
        checkpoint.put("definitionFingerprint", definitionFingerprint);
        checkpoint.put("trajectoryHash", trajectoryHash);
        checkpoint.put("fingerprint", JsonUtil.fingerprint(checkpoint));
        return checkpoint;
    }

    public Map<String, Object> stateView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("definitionFingerprint", definitionFingerprint);
        view.put("state", currentState);
        view.put("data", JsonUtil.deepCopy(data));
        view.put("randomState", randomState);
        view.put("nextInternalSequence", nextInternalSequence);
        view.put("pendingCount", (long) pending.size());
        view.put("nextEvent", pending.isEmpty() ? null : canonicalEvent(pending.get(0)));
        view.put("trajectoryLength", (long) trajectory.size());
        view.put("trajectoryHash", trajectoryHash);
        return view;
    }

    public String definitionFingerprint() {
        return definitionFingerprint;
    }

    public String trajectoryHash() {
        return trajectoryHash;
    }

    public long stepCount() {
        return trajectory.size();
    }

    public List<Map<String, Object>> trajectoryRecords() {
        return trajectory.stream().map(JsonUtil::deepCopy).map(event -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = (Map<String, Object>) event;
            return copy;
        }).toList();
    }

    public List<Map<String, Object>> pendingEvents() {
        return pending.stream().map(this::canonicalEvent).map(event -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = (Map<String, Object>) event;
            return copy;
        }).toList();
    }

    public List<String> processedExternalIds() {
        java.util.List<String> ids = new ArrayList<>();
        for (Map<String, Object> step : trajectory) {
            Map<String, Object> event = JsonUtil.object(step.get("event"), "step event");
            if ("external".equals(event.get("kind"))) {
                ids.add(String.valueOf(event.get("id")));
            }
        }
        return List.copyOf(ids);
    }

    public List<String> externalIds() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (Map<String, Object> event : pending) {
            if ("external".equals(event.get("kind"))) {
                ids.add(String.valueOf(event.get("id")));
            }
        }
        for (Map<String, Object> step : trajectory) {
            Map<String, Object> event = JsonUtil.object(step.get("event"), "step event");
            if ("external".equals(event.get("kind"))) {
                ids.add(String.valueOf(event.get("id")));
            }
        }
        return List.copyOf(ids);
    }

    private Map<String, Object> snapshot() {
        return snapshot(currentState);
    }

    private Map<String, Object> snapshot(String state) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("state", state);
        snapshot.put("data", JsonUtil.deepCopy(data));
        snapshot.put("randomState", randomState);
        return snapshot;
    }

    private Map<String, Object> findTransition(Map<String, Object> event, Map<String, Object> snapshot) {
        List<Object> transitions = JsonUtil.list(definition.get("transitions"), "transitions");
        for (Object candidateObject : transitions) {
            Map<String, Object> candidate = JsonUtil.object(candidateObject, "transition");
            String from = JsonUtil.optionalString(candidate, "from", "*");
            boolean stateMatches = "*".equals(from) || from.equals(currentState);
            boolean eventMatches = JsonUtil.string(candidate, "event").equals(event.get("event"));
            if (stateMatches && eventMatches && conditionsMatch(
                    JsonUtil.list(candidate.getOrDefault("conditions", List.of()), "conditions"), event, snapshot)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean conditionsMatch(List<Object> conditions, Map<String, Object> event, Map<String, Object> snapshot) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("state", currentState);
        scope.put("data", snapshot.get("data"));
        scope.put("event", canonicalEvent(event));
        for (Object conditionObject : conditions) {
            Map<String, Object> condition = JsonUtil.object(conditionObject, "condition");
            String type = JsonUtil.optionalString(condition, "type", "equals");
            Object actual = readPath(scope, JsonUtil.string(condition, "path"));
            if (!evaluate(type, actual, condition.get("value"))) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluate(String type, Object actual, Object expected) {
        return switch (type) {
            case "equals" -> valuesEqual(actual, expected);
            case "notEquals" -> !valuesEqual(actual, expected);
            case "exists" -> actual != null;
            case "greaterThan", "greaterThanOrEqual", "lessThan", "lessThanOrEqual" -> compareNumbers(actual, expected, type);
            default -> throw new IllegalArgumentException("Unsupported condition type " + type);
        };
    }

    private boolean compareNumbers(Object actual, Object expected, String type) {
        if (!(actual instanceof Number left) || !(expected instanceof Number right)) {
            return false;
        }
        int result = Double.compare(left.doubleValue(), right.doubleValue());
        return switch (type) {
            case "greaterThan" -> result > 0;
            case "greaterThanOrEqual" -> result >= 0;
            case "lessThan" -> result < 0;
            case "lessThanOrEqual" -> result <= 0;
            default -> false;
        };
    }

    private void executeActions(List<Object> actions, Map<String, Object> event, Map<String, Object> before,
                                List<Map<String, Object>> outputs, List<Map<String, Object>> spawned) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("state", before.get("state"));
        scope.put("data", before.get("data"));
        scope.put("event", canonicalEvent(event));
        for (Object actionObject : actions) {
            Map<String, Object> action = JsonUtil.object(actionObject, "action");
            switch (JsonUtil.string(action, "type")) {
                case "set" -> {
                    Object value = JsonUtil.deepCopy(action.get("value"));
                    writePath(data, JsonUtil.string(action, "path"), value, true);
                }
                case "increment" -> {
                    String path = JsonUtil.string(action, "path");
                    Object current = readPath(Map.of("data", data), path);
                    long amount = JsonUtil.integer(action.getOrDefault("amount", 1L), "amount");
                    if (current == null) {
                        current = 0L;
                    }
                    if (!(current instanceof Number number)) {
                        throw new ActionFailureException("increment target " + path + " is not numeric");
                    }
                    writePath(data, path, number.longValue() + amount, true);
                }
                case "random" -> {
                    long min = JsonUtil.integer(action.getOrDefault("min", 0L), "min");
                    long max = JsonUtil.integer(action.getOrDefault("max", Long.MAX_VALUE), "max");
                    if (min >= max) {
                        throw new ActionFailureException("random range is empty");
                    }
                    long span = max - min;
                    long value = min + Math.floorMod(nextRandom(), span);
                    writePath(data, JsonUtil.string(action, "path"), value, true);
                }
                case "emit" -> spawned.add(internalEvent(action, event));
                case "output" -> {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("name", action.get("name"));
                    output.put("payload", JsonUtil.deepCopy(action.getOrDefault("payload", Map.of())));
                    outputs.add(output);
                }
                case "fail" -> throw new ActionFailureException(
                        JsonUtil.optionalString(action, "message", "Action failed"));
                default -> throw new ActionFailureException("Unsupported action type");
            }
        }
    }

    private long nextRandom() {
        long state = randomState;
        state ^= state << 13;
        state ^= state >>> 7;
        state ^= state << 17;
        if (state == Long.MIN_VALUE) {
            state = 1L;
        }
        randomState = state;
        return Math.abs(state);
    }

    private Map<String, Object> internalEvent(Map<String, Object> action, Map<String, Object> parent) {
        List<Long> route = new ArrayList<>(readLongList(parent, "route"));
        long ordinal = nextInternalSequence++;
        route.add(ordinal);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", parent.get("id") + ".i" + ordinal);
        event.put("event", action.get("event"));
        event.put("logicalTime", parent.get("logicalTime"));
        event.put("source", action.getOrDefault("source", "internal"));
        event.put("kind", "internal");
        event.put("priority", parent.get("priority"));
        event.put("rootSeq", parent.get("rootSeq"));
        event.put("rootId", parent.get("rootId"));
        event.put("parentId", parent.get("id"));
        event.put("route", route);
        event.put("originalSeq", ordinal);
        event.put("data", JsonUtil.deepCopy(action.getOrDefault("data", Map.of())));
        return event;
    }

    private List<Long> readLongList(Map<String, Object> map, String key) {
        List<Object> values = JsonUtil.list(map.getOrDefault(key, List.of()), key);
        List<Long> result = new ArrayList<>();
        for (Object value : values) {
            result.add(JsonUtil.integer(value, key + " item"));
        }
        return result;
    }

    public static Map<String, Object> normalizeExternal(Map<String, Object> definition, Map<String, Object> input) {
        String event = JsonUtil.string(input, "event");
        long logicalTime = JsonUtil.integer(input.getOrDefault("logicalTime", 0L), "logicalTime");
        String source = JsonUtil.optionalString(input, "source", "default");
        long originalSeq = JsonUtil.integer(input.get("originalSeq"), "originalSeq");
        long rootSeq = originalSeq;
        String id = JsonUtil.optionalString(input, "id",
                source + "-" + logicalTime + "-" + originalSeq + "-" + rootSeq);
        Map<String, Object> eventMap = new LinkedHashMap<>();
        eventMap.put("id", id);
        eventMap.put("event", event);
        eventMap.put("logicalTime", logicalTime);
        eventMap.put("source", source);
        eventMap.put("kind", "external");
        eventMap.put("priority", sourcePriority(definition, source));
        eventMap.put("rootSeq", rootSeq);
        eventMap.put("rootId", id);
        eventMap.put("route", List.of());
        eventMap.put("originalSeq", originalSeq);
        eventMap.put("data", JsonUtil.deepCopy(input.getOrDefault("data", Map.of())));
        return eventMap;
    }


    public static long sourcePriority(Map<String, Object> definition, String source) {
        Object priorities = definition.get("sourcePriorities");
        if (priorities instanceof Map<?, ?> map && map.get(source) instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private void sortPending() {
        pending.sort(Comparator
                .comparingLong((Map<String, Object> event) -> JsonUtil.integer(event.get("logicalTime"), "logicalTime"))
                .thenComparingLong(event -> -JsonUtil.integer(event.get("priority"), "priority"))
                .thenComparingLong(event -> JsonUtil.integer(event.get("rootSeq"), "rootSeq"))
                .thenComparing(ReplayEngine::routeKey)
                .thenComparing(event -> String.valueOf(event.get("source")))
                .thenComparing(event -> String.valueOf(event.get("id"))));
    }

    private static String routeKey(Map<String, Object> event) {
        List<Long> route = new ArrayList<>();
        for (Object item : JsonUtil.list(event.getOrDefault("route", List.of()), "route")) {
            route.add(JsonUtil.integer(item, "route item"));
        }
        StringBuilder builder = new StringBuilder();
        for (long value : route) {
            builder.append(String.format("%020d", value)).append('/');
        }
        return builder.toString();
    }

    private Map<String, Object> canonicalEvent(Map<String, Object> event) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("id", event.get("id"));
        canonical.put("event", event.get("event"));
        canonical.put("logicalTime", event.get("logicalTime"));
        canonical.put("source", event.get("source"));
        canonical.put("kind", event.get("kind"));
        canonical.put("priority", event.get("priority"));
        canonical.put("rootSeq", event.get("rootSeq"));
        canonical.put("rootId", event.get("rootId"));
        canonical.put("parentId", event.get("parentId"));
        canonical.put("route", event.get("route"));
        canonical.put("originalSeq", event.get("originalSeq"));
        canonical.put("data", event.get("data"));
        return canonical;
    }

    private Object readPath(Map<String, Object> scope, String path) {
        String[] parts = path.split("\\.");
        Object current = scope.get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[i]);
            } else {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private void writePath(Object root, String path, Object value, boolean stripRoot) {
        String[] parts = path.split("\\.");
        int start = stripRoot ? 1 : 0;
        Object current = root;
        for (int i = start; i < parts.length - 1; i++) {
            Map<String, Object> map = JsonUtil.object(current, "path parent");
            current = map.computeIfAbsent(parts[i], ignored -> new LinkedHashMap<String, Object>());
        }
        ((Map<String, Object>) current).put(parts[parts.length - 1], value);
    }

    private boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        return left == null ? right == null : left.equals(right);
    }

    private String transitionName(Map<String, Object> transition) {
        if (transition == null) {
            return null;
        }
        return JsonUtil.optionalString(transition, "name",
                JsonUtil.optionalString(transition, "from", "*") + ":" + transition.get("event"));
    }

    private String baseHash() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("definitionFingerprint", definitionFingerprint);
        base.put("initialState", currentState);
        base.put("initialData", data);
        base.put("randomSeed", definition.getOrDefault("randomSeed", 0L));
        return JsonUtil.fingerprint(base);
    }

    private String stepFingerprint(Map<String, Object> record) {
        Map<String, Object> hashInput = new LinkedHashMap<>();
        hashInput.put("parentHash", trajectoryHash);
        hashInput.put("stepIndex", record.get("stepIndex"));
        hashInput.put("before", record.get("before"));
        hashInput.put("event", record.get("event"));
        hashInput.put("transition", record.get("transition"));
        hashInput.put("outputs", record.get("outputs"));
        hashInput.put("derivedInternalEvents", record.get("derivedInternalEvents"));
        hashInput.put("failure", record.get("failure"));
        hashInput.put("after", record.get("after"));
        return JsonUtil.fingerprint(hashInput);
    }

    public record StepResult(Map<String, Object> record, String failure) {
    }

    public static class ActionFailureException extends RuntimeException {
        public ActionFailureException(String message) {
            super(message);
        }
    }

    public static class DefinitionMismatchException extends RuntimeException {
        private final String actual;
        private final String expected;

        public DefinitionMismatchException(String actual, String expected) {
            super("checkpoint definition fingerprint " + actual + " does not match current definition " + expected);
            this.actual = actual;
            this.expected = expected;
        }

        public String actual() {
            return actual;
        }

        public String expected() {
            return expected;
        }
    }
}
