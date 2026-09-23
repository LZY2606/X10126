package replayroom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class Engine {
    static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

    private static final class LineageNode {
        private final String branchId;
        private final int eventPos;
        private final String checkpointId;

        private LineageNode(String branchId, int eventPos, String checkpointId) {
            this.branchId = branchId;
            this.eventPos = eventPos;
            this.checkpointId = checkpointId;
        }

        private String key() {
            return branchId + "#" + eventPos;
        }
    }

    private final Map<String, Object> workspace;
    private final Map<String, Object> branches;
    private final Map<String, Object> checkpoints;
    private long nextId;

    @SuppressWarnings("unchecked")
    Engine(Map<String, Object> workspace) {
        this.workspace = Objects.requireNonNull(workspace);
        this.branches = (Map<String, Object>) workspace.computeIfAbsent("branches", key -> new LinkedHashMap<>());
        this.checkpoints = (Map<String, Object>) workspace.computeIfAbsent("checkpoints", key -> new LinkedHashMap<>());
        this.nextId = ((Number) workspace.getOrDefault("nextId", 1L)).longValue();
    }

    static Engine create(Map<String, Object> definition, long seed) {
        validateDefinition(definition);
        String fingerprint = Json.sha256(definition);
        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("schemaVersion", 1);
        workspace.put("definition", definition);
        workspace.put("definitionFingerprint", fingerprint);
        workspace.put("seed", seed);
        workspace.put("branches", new LinkedHashMap<>());
        workspace.put("checkpoints", new LinkedHashMap<>());
        workspace.put("nextId", 1L);
        Engine engine = new Engine(workspace);
        engine.resetMain("main", false);
        engine.workspace.put("sessionFingerprint", engine.sessionFingerprint());
        return engine;
    }

    Map<String, Object> workspace() {
        workspace.put("nextId", nextId);
        workspace.put("sessionFingerprint", sessionFingerprint());
        return workspace;
    }

    Map<String, Object> summary() {
        workspace.put("nextId", nextId);
        workspace.put("sessionFingerprint", sessionFingerprint());
        return Json.copy(workspace);
    }

    String definitionFingerprint() {
        return (String) workspace.get("definitionFingerprint");
    }

    String sessionFingerprint() {
        Map<String, Object> locked = new LinkedHashMap<>();
        locked.put("definitionFingerprint", workspace.get("definitionFingerprint"));
        locked.put("seed", workspace.get("seed"));
        locked.put("events", lockedEvents());
        return Json.sha256(locked);
    }

    private List<Object> lockedEvents() {
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : branches.values()) {
            Map<String, Object> branch = Json.object(item);
            for (Object event : Json.list(branch.get("events"))) {
                Map<String, Object> eventMap = Json.object(event);
                ids.add(Json.string(eventMap, "id"));
            }
        }
        List<Object> result = new ArrayList<>();
        for (String id : ids) {
            for (Object item : branches.values()) {
                Map<String, Object> branch = Json.object(item);
                for (Object event : Json.list(branch.get("events"))) {
                    Map<String, Object> eventMap = Json.object(event);
                    if (id.equals(eventMap.get("id"))) {
                        result.add(Json.copy(eventMap));
                        break;
                    }
                }
            }
        }
        result.sort(Engine::compareExternal);
        return result;
    }

    void replaceDefinition(Map<String, Object> definition, Long seed) {
        validateDefinition(definition);
        String newFingerprint = Json.sha256(definition);
        String oldFingerprint = definitionFingerprint();
        if (!newFingerprint.equals(oldFingerprint)) {
            for (Object value : checkpoints.values()) {
                Json.object(value).put("archived", true);
            }
            for (Object value : branches.values()) {
                Json.object(value).put("archived", true);
            }
        }
        workspace.put("definition", definition);
        workspace.put("definitionFingerprint", newFingerprint);
        if (seed != null) {
            workspace.put("seed", seed);
        }
        String branchId = uniqueId("branch");
        resetMain(branchId, true);
        workspace.put("sessionFingerprint", sessionFingerprint());
    }

    @SuppressWarnings("unchecked")
    private void resetMain(String branchId, boolean preserveArchived) {
        Map<String, Object> newBranches = new LinkedHashMap<>();
        Map<String, Object> newCheckpoints = new LinkedHashMap<>();
        if (preserveArchived) {
            for (Map.Entry<String, Object> entry : branches.entrySet()) {
                if (Boolean.TRUE.equals(Json.object(entry.getValue()).get("archived"))) {
                    newBranches.put(entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, Object> entry : checkpoints.entrySet()) {
                if (Boolean.TRUE.equals(Json.object(entry.getValue()).get("archived"))) {
                    newCheckpoints.put(entry.getKey(), entry.getValue());
                }
            }
        }
        branches.clear();
        checkpoints.clear();
        branches.putAll(newBranches);
        checkpoints.putAll(newCheckpoints);

        Map<String, Object> definition = Json.object(workspace.get("definition"));
        Map<String, Object> state = Json.copy(Json.object(definition.get("initialState")));
        long seed = Json.integer(workspace.get("seed"), "seed");
        Map<String, Object> genesis = new LinkedHashMap<>();
        genesis.put("definitionFingerprint", definitionFingerprint());
        genesis.put("seed", seed);
        String traceHash = Json.sha256(genesis);

        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("id", branchId);
        branch.put("name", "主线");
        branch.put("events", new ArrayList<>());
        branch.put("eventPos", 0);
        branch.put("state", state);
        branch.put("rngState", seed);
        branch.put("trace", new ArrayList<>());
        branch.put("traceHash", traceHash);
        branch.put("parentCheckpointId", "cp-initial-" + branchId);
        branch.put("archived", false);
        branches.put(branchId, branch);

        Map<String, Object> checkpoint = checkpointFromBranch(branch, "初始检查点", branch.get("traceHash"));
        checkpoint.put("id", "cp-initial-" + branchId);
        checkpoints.put((String) checkpoint.get("id"), checkpoint);
    }

    private Map<String, Object> checkpointFromBranch(Map<String, Object> branch, String name, Object traceHash) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("id", uniqueId("cp"));
        checkpoint.put("branchId", branch.get("id"));
        checkpoint.put("eventPos", branch.get("eventPos"));
        checkpoint.put("state", Json.copy(branch.get("state")));
        checkpoint.put("rngState", branch.get("rngState"));
        checkpoint.put("trace", Json.copy(branch.get("trace")));
        checkpoint.put("traceHash", traceHash);
        checkpoint.put("parentCheckpointId", branch.get("parentCheckpointId"));
        checkpoint.put("definitionFingerprint", definitionFingerprint());
        checkpoint.put("name", name);
        checkpoint.put("archived", false);
        return checkpoint;
    }

    static void validateDefinition(Map<String, Object> definition) {
        Json.string(definition, "name");
        Object states = definition.get("states");
        if (!(states instanceof List<?> list) || list.isEmpty()) {
            throw new Failure("states 必须是非空数组");
        }
        Set<String> stateIds = new LinkedHashSet<>();
        for (Object item : list) {
            stateIds.add(Json.string(Json.object(item), "id"));
        }
        Object initial = definition.get("initialState");
        if (!(initial instanceof Map<?, ?>)) {
            throw new Failure("initialState 必须是对象");
        }
        Map<String, Object> initialState = Json.object(initial);
        String initialNode = Json.string(initialState, "node");
        if (!stateIds.contains(initialNode)) {
            throw new Failure("初始状态不在 states 中: " + initialNode);
        }
        if (!(initialState.get("data") instanceof Map<?, ?>)) {
            throw new Failure("initialState.data 必须是对象");
        }
        for (Object item : Json.list(definition.getOrDefault("transitions", List.of()))) {
            Map<String, Object> transition = Json.object(item);
            Json.string(transition, "event");
            String from = transition.containsKey("from") ? Json.string(transition, "from") : "*";
            if (!"*".equals(from) && !stateIds.contains(from)) {
                throw new Failure("转换 from 不存在: " + from);
            }
            for (Object action : Json.list(transition.getOrDefault("actions", List.of()))) {
                Map<String, Object> actionMap = Json.object(action);
                String type = Json.string(actionMap, "type");
                if (Set.of("setNode", "set", "merge", "increment", "append", "output", "emit", "randomInt", "fail").contains(type)
                        && !"output".equals(type)
                        && !"emit".equals(type)
                        && !"randomInt".equals(type)
                        && !"fail".equals(type)
                        && !"merge".equals(type)
                        && actionMap.get("path") == null
                        && actionMap.get("node") == null) {
                    throw new Failure("动作缺少 path 或 node: " + type);
                }
            }
        }
    }

    private String uniqueId(String prefix) {
        return prefix + "-" + nextId++;
    }

    @SuppressWarnings("unchecked")
    int importEvents(String branchId, List<Object> incoming) {
        Map<String, Object> branch = activeBranch(branchId);
        List<Object> events = (List<Object>) branch.get("events");
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : events) {
            ids.add(Json.string(Json.object(item), "id"));
        }
        for (Object item : incoming) {
            Map<String, Object> event = normalizeExternal(Json.object(item));
            String id = Json.string(event, "id");
            if (!ids.add(id)) {
                throw new Failure("事件 ID 重复: " + id);
            }
            events.add(event);
        }
        events.sort(Engine::compareExternal);
        ensureOrdering(events);
        return incoming.size();
    }

    private Map<String, Object> normalizeExternal(Map<String, Object> raw) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", Json.string(raw, "id"));
        event.put("event", Json.string(raw, "event"));
        event.put("time", Json.integer(raw.get("time"), "time"));
        event.put("sourcePriority", Json.integer(raw.getOrDefault("sourcePriority", 0), "sourcePriority"));
        event.put("seq", Json.integer(raw.get("seq"), "seq"));
        if (raw.containsKey("payload")) {
            event.put("payload", Json.copy(raw.get("payload")));
        }
        event.put("internal", false);
        return event;
    }

    private void ensureOrdering(List<Object> events) {
        for (int i = 1; i < events.size(); i++) {
            if (compareExternal(events.get(i - 1), events.get(i)) == 0) {
                throw new Failure("同一逻辑时刻、来源优先级和原始序号不能出现两个事件");
            }
        }
    }

    private static int compareExternal(Object leftValue, Object rightValue) {
        Map<String, Object> left = Json.object(leftValue);
        Map<String, Object> right = Json.object(rightValue);
        int result = Long.compare(Json.integer(left.get("time"), "time"), Json.integer(right.get("time"), "time"));
        if (result != 0) {
            return result;
        }
        result = Long.compare(
                Json.integer(right.get("sourcePriority"), "sourcePriority"),
                Json.integer(left.get("sourcePriority"), "sourcePriority"));
        if (result != 0) {
            return result;
        }
        result = Long.compare(Json.integer(left.get("seq"), "seq"), Json.integer(right.get("seq"), "seq"));
        if (result != 0) {
            return result;
        }
        return Json.string(left, "id").compareTo(Json.string(right, "id"));
    }

    private Map<String, Object> activeBranch(String branchId) {
        Map<String, Object> branch = Json.object(branches.get(branchId));
        if (Boolean.TRUE.equals(branch.get("archived"))) {
            throw new Failure("该分支属于旧定义，只能查看: " + branchId);
        }
        return branch;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> step(String branchId, int count) {
        Map<String, Object> branch = activeBranch(branchId);
        List<Object> trace = (List<Object>) branch.get("trace");
        for (int i = 0; i < count; i++) {
            Map<String, Object> event = nextEvent(branch);
            if (event == null) {
                break;
            }
            trace.add(processOne(branch, event, Json.copy(branch.get("state"))));
        }
        return Json.copy(branch);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nextEvent(Map<String, Object> branch) {
        List<Object> events = (List<Object>) branch.get("events");
        int pos = ((Number) branch.get("eventPos")).intValue();
        List<Object> pending = (List<Object>) branch.computeIfAbsent("pending", key -> new ArrayList<>());
        Map<String, Object> external = pos < events.size() ? Json.object(events.get(pos)) : null;
        int pendingIndex = -1;
        for (int i = 0; i < pending.size(); i++) {
            if (pendingIndex < 0 || compareExternal(pending.get(i), pending.get(pendingIndex)) < 0) {
                pendingIndex = i;
            }
        }
        if (external == null && pendingIndex < 0) {
            return null;
        }
        if (pendingIndex >= 0 && (external == null || compareExternal(pending.get(pendingIndex), external) <= 0)) {
            return Json.object(pending.remove(pendingIndex));
        }
        branch.put("eventPos", pos + 1);
        return Json.copy(external);
    }

    private Map<String, Object> processOne(Map<String, Object> branch, Map<String, Object> event, Map<String, Object> before) {
        long rngBefore = Json.integer(branch.get("rngState"), "rngState");
        List<Object> outputs = new ArrayList<>();
        Map<String, Object> workingState = Json.copy(before);
        long workingRng = rngBefore;
        List<Object> stagedInternal = new ArrayList<>();
        Map<String, Object> failure = null;

        Map<String, Object> transition = selectTransition(before, event);
        if (transition != null) {
            try {
                for (Object actionItem : Json.list(transition.getOrDefault("actions", List.of()))) {
                    Object[] actionResult = runAction(
                            Json.object(actionItem), workingState, event, workingRng, stagedInternal.size());
                    workingRng = (Long) actionResult[0];
                    List<Object> actionOutputs = Json.list(actionResult[1]);
                    outputs.addAll(actionOutputs);
                    List<Object> emitted = Json.list(actionResult[2]);
                    stagedInternal.addAll(emitted);
                }
            } catch (Failure e) {
                failure = new LinkedHashMap<>();
                failure.put("message", e.getMessage());
                branch.put("state", Json.copy(before));
                branch.put("rngState", rngBefore);
                workingState = Json.copy(before);
                workingRng = rngBefore;
                stagedInternal.clear();
            }
        }

        if (failure == null) {
            branch.put("state", workingState);
            branch.put("rngState", workingRng);
            addPending(branch, stagedInternal);
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event", Json.copy(event));
        entry.put("before", before);
        entry.put("after", Json.copy(branch.get("state")));
        entry.put("outputs", outputs);
        entry.put("transition", transition == null ? null : transition.get("name"));
        if (failure != null) {
            entry.put("failure", failure);
        }
        String hashInput = branch.get("traceHash") + "\n" + Json.canonical(entry);
        entry.put("hash", Json.sha256(hashInput));
        branch.put("traceHash", entry.get("hash"));
        return entry;
    }

    @SuppressWarnings("unchecked")
    private void addPending(Map<String, Object> branch, List<Object> events) {
        List<Object> pending = (List<Object>) branch.computeIfAbsent("pending", key -> new ArrayList<>());
        pending.addAll(events);
    }

    private Map<String, Object> selectTransition(Map<String, Object> state, Map<String, Object> event) {
        Map<String, Object> definition = Json.object(workspace.get("definition"));
        String eventName = Json.string(event, "event");
        String node = Json.string(state, "node");
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Object item : Json.list(definition.getOrDefault("transitions", List.of()))) {
            Map<String, Object> transition = Json.object(item);
            if (!eventName.equals(Json.string(transition, "event"))) {
                continue;
            }
            if (transition.containsKey("from") && !"*".equals(Json.string(transition, "from"))
                    && !node.equals(transition.get("from"))) {
                continue;
            }
            if (!condition(transition.get("condition"), state, event)) {
                continue;
            }
            matches.add(transition);
        }
        if (matches.isEmpty()) {
            return null;
        }
        matches.sort((left, right) -> Long.compare(
                Json.integer(right.getOrDefault("priority", 0L), "priority"),
                Json.integer(left.getOrDefault("priority", 0L), "priority")));
        return matches.get(0);
    }

    private boolean condition(Object expression, Map<String, Object> state, Map<String, Object> event) {
        if (expression == null) {
            return true;
        }
        return evaluate(Json.object(expression), state, event);
    }

    private boolean evaluate(Map<String, Object> expression, Map<String, Object> state, Map<String, Object> event) {
        if (expression.containsKey("and")) {
            for (Object item : Json.list(expression.get("and"))) {
                if (!evaluate(Json.object(item), state, event)) {
                    return false;
                }
            }
            return true;
        }
        if (expression.containsKey("or")) {
            for (Object item : Json.list(expression.get("or"))) {
                if (evaluate(Json.object(item), state, event)) {
                    return true;
                }
            }
            return false;
        }
        if (expression.containsKey("not")) {
            return !evaluate(Json.object(expression.get("not")), state, event);
        }
        Object left = resolveValue(Json.string(expression, "path"), state, event);
        Object right = expression.get("equals");
        String operator = expression.containsKey("equals") ? "equals"
                : Json.optionalString(expression, "op", "equals");
        if (!expression.containsKey("equals") && expression.containsKey("value")) {
            right = expression.get("value");
        }
        return compare(left, operator, right);
    }

    private boolean compare(Object left, String operator, Object right) {
        return switch (operator) {
            case "equals", "=" -> Objects.equals(left, right);
            case "notEquals", "!=" -> !Objects.equals(left, right);
            case "exists" -> left != null;
            case "missing" -> left == null;
            case "in" -> Json.list(right).contains(left);
            case "contains" -> left instanceof List<?> list ? list.contains(right)
                    : left instanceof String text && right instanceof String part && text.contains(part);
            case "gt", ">" -> number(left, "left") > number(right, "right");
            case "gte", ">=" -> number(left, "left") >= number(right, "right");
            case "lt", "<" -> number(left, "left") < number(right, "right");
            case "lte", "<=" -> number(left, "left") <= number(right, "right");
            default -> throw new Failure("不支持的条件运算符: " + operator);
        };
    }

    private double number(Object value, String name) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new Failure(name + " 不是数字");
    }

    private Object resolveValue(String path, Map<String, Object> state, Map<String, Object> event) {
        if (path.equals("$event")) {
            return Json.copy(event);
        }
        if (path.startsWith("$event.")) {
            return getPath(event, path.substring("$event.".length()));
        }
        return getPath(state, path);
    }

    @SuppressWarnings("unchecked")
    private Object getPath(Object root, String path) {
        Object current = root;
        if (path.isEmpty()) {
            return current;
        }
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = ((Map<String, Object>) map).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private Object[] runAction(Map<String, Object> action, Map<String, Object> state, Map<String, Object> event,
                               long rngState, int internalCount) {
        String type = Json.string(action, "type");
        List<Object> outputs = new ArrayList<>();
        List<Object> emitted = new ArrayList<>();
        long nextRng = rngState;
        switch (type) {
            case "setNode" -> state.put("node", Json.string(action, "node"));
            case "set" -> setPath(state, Json.string(action, "path"), actionValue(action, state, event));
            case "merge" -> mergePath(state, Json.string(action, "path"), Json.object(actionValue(action, state, event)));
            case "increment" -> {
                String path = Json.string(action, "path");
                Object current = getPath(state, path);
                long delta = (long) number(action.getOrDefault("delta", 1L), "delta");
                long value = (current == null ? 0L : (long) number(current, path)) + delta;
                setPath(state, path, value);
            }
            case "append" -> appendPath(state, Json.string(action, "path"), actionValue(action, state, event));
            case "output" -> {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("name", Json.string(action, "name"));
                output.put("value", actionValue(action, state, event));
                outputs.add(output);
            }
            case "emit" -> emitted.add(internalEvent(action, event, internalCount));
            case "randomInt" -> {
                long min = (long) number(action.getOrDefault("min", 0L), "min");
                long max = (long) number(action.get("max"), "max");
                if (max < min) {
                    throw new Failure("randomInt 的 max 小于 min");
                }
                long span = max - min + 1;
                long nextSeed = (rngState * 6364136223846793005L + 1442695040888963407L) & Long.MAX_VALUE;
                long value = min + Long.remainderUnsigned(nextSeed, span);
                nextRng = nextSeed;
                setPath(state, Json.string(action, "path"), value);
            }
            case "fail" -> throw new Failure(Json.optionalString(action, "message", "动作显式失败"));
            default -> throw new Failure("不支持的动作类型: " + type);
        }
        return new Object[] {nextRng, outputs, emitted};
    }

    private Object actionValue(Map<String, Object> action, Map<String, Object> state, Map<String, Object> event) {
        if (action.containsKey("valueFrom")) {
            return resolveValue(Json.string(action, "valueFrom"), state, event);
        }
        return Json.copy(action.get("value"));
    }

    private Map<String, Object> internalEvent(Map<String, Object> action, Map<String, Object> parent, int internalCount) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", Json.optionalString(action, "id", parent.get("id") + "-internal-" + internalCount));
        event.put("event", Json.string(action, "event"));
        event.put("time", parent.get("time"));
        event.put("sourcePriority", Json.integer(action.getOrDefault("sourcePriority", -1), "sourcePriority"));
        event.put("seq", Json.integer(action.getOrDefault("seq", Json.integer(parent.get("seq"), "seq") * 1000L + internalCount), "seq"));
        if (action.containsKey("payload")) {
            event.put("payload", Json.copy(action.get("payload")));
        }
        event.put("internal", true);
        event.put("parentId", parent.get("id"));
        return event;
    }

    @SuppressWarnings("unchecked")
    private void setPath(Object root, String path, Object value) {
        String[] parts = path.split("\\.");
        Object current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!(current instanceof Map<?, ?>)) {
                throw new Failure("路径不是对象: " + path);
            }
            Map<String, Object> map = (Map<String, Object>) current;
            current = map.computeIfAbsent(parts[i], key -> new LinkedHashMap<>());
        }
        if (!(current instanceof Map<?, ?>)) {
            throw new Failure("路径不是对象: " + path);
        }
        ((Map<String, Object>) current).put(parts[parts.length - 1], Json.copy(value));
    }

    @SuppressWarnings("unchecked")
    private void mergePath(Object root, String path, Map<String, Object> value) {
        String[] parts = path.split("\\.");
        Object current = root;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?>)) {
                throw new Failure("路径不是对象: " + path);
            }
            current = ((Map<String, Object>) current).computeIfAbsent(part, key -> new LinkedHashMap<>());
        }
        if (!(current instanceof Map<?, ?>)) {
            throw new Failure("merge 目标必须是对象: " + path);
        }
        ((Map<String, Object>) current).putAll(Json.copy(value));
    }

    @SuppressWarnings("unchecked")
    private void appendPath(Object root, String path, Object value) {
        String[] parts = path.split("\\.");
        Object current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!(current instanceof Map<?, ?>)) {
                throw new Failure("路径不是对象: " + path);
            }
            current = ((Map<String, Object>) current).computeIfAbsent(parts[i], key -> new LinkedHashMap<>());
        }
        String last = parts[parts.length - 1];
        if (!(current instanceof Map<?, ?> map)) {
            throw new Failure("路径不是对象: " + path);
        }
        Object target = ((Map<String, Object>) map).computeIfAbsent(last, key -> new ArrayList<>());
        if (!(target instanceof List<?>)) {
            throw new Failure("append 目标必须是数组: " + path);
        }
        ((List<Object>) target).add(Json.copy(value));
    }
