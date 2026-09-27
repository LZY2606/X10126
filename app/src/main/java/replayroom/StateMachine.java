package replayroom;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

public final class StateMachine {
    public static final String INITIAL_CHECKPOINT = "cp-0";

    public static Map<String, Object> validateDefinition(Map<String, Object> source) {
        Map<String, Object> definition = Models.object(source, "definition");
        String name = Models.requireString(definition, "name");
        long version = Models.integer(definition, "version", 1);
        String initialState = Models.requireString(definition, "initialState");
        Object data = definition.containsKey("initialData") ? Models.deepCopy(definition.get("initialData")) : Models.map();
        if (!(data instanceof Map)) throw new IllegalArgumentException("initialData must be an object");
        List<Object> states = Models.array(definition.getOrDefault("states", Models.list()), "states");
        List<String> stateNames = new ArrayList<>();
        for (Object state : states) {
            if (!(state instanceof String s) || s.isBlank()) throw new IllegalArgumentException("states must contain non-empty strings");
            stateNames.add(s);
        }
        if (!stateNames.contains(initialState)) throw new IllegalArgumentException("initialState must be one of states");
        List<Object> rules = Models.array(definition.getOrDefault("rules", Models.list()), "rules");
        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> rule = Models.object(rules.get(i), "rules[" + i + "]");
            Models.requireString(rule, "event");
            Models.requireString(rule, "name");
            if (rule.containsKey("condition")) Models.object(rule.get("condition"), "rule condition");
            List<Object> actions = Models.array(rule.getOrDefault("actions", Models.list()), "rule actions");
            for (Object action : actions) Models.object(action, "action");
        }
        Map<String, Object> clean = Models.map();
        clean.put("name", name);
        clean.put("version", version);
        clean.put("states", stateNames);
        clean.put("initialState", initialState);
        clean.put("initialData", data);
        List<Object> cleanRules = Models.list();
        for (Object rule : rules) cleanRules.add(Models.deepCopy(rule));
        clean.put("rules", cleanRules);
        return clean;
    }

    public static String definitionFingerprint(Map<String, Object> definition) {
        return Hashes.fingerprint(validateDefinition(definition));
    }

    public static Map<String, Object> initialSnapshot(Map<String, Object> definition) {
        Map<String, Object> validated = validateDefinition(definition);
        Map<String, Object> state = Models.map();
        state.put("state", validated.get("initialState"));
        state.put("data", Models.deepCopy(validated.get("initialData")));
        return state;
    }

    public static Map<String, Object> makeSession(String id, Map<String, Object> definitionInput, long seed, List<Object> eventsInput, String fingerprint) {
        Map<String, Object> definition = validateDefinition(definitionInput);
        String definitionHash = definitionFingerprint(definition);
        List<Map<String, Object>> events = normalizeImportedEvents(eventsInput, new ArrayList<>());
        Map<String, Object> state = initialSnapshot(definition);
        String lock = sessionLock(definitionHash, state, seed, events);
        Map<String, Object> session = Models.map();
        session.put("id", id);
        session.put("name", "会话 " + id);
        session.put("definition", definition);
        session.put("definitionFingerprint", definitionHash);
        session.put("seed", seed);
        session.put("events", eventListToObjects(events));
        session.put("nextExternal", events.size());
        session.put("pendingInternal", Models.list());
        session.put("internalSequence", 0L);
        session.put("state", state);
        session.put("steps", Models.list());
        session.put("outputs", Models.list());
        session.put("checkpoints", Models.list());
        String initialTrace = fingerprint == null ? Hashes.fingerprint(lock) : fingerprint;
        session.put("traceHash", initialTrace);
        session.put("initialLockHash", initialTrace);
        session.put("createdFromLock", fingerprint == null ? lock : null);
        addInitialCheckpoint(session);
        return session;
    }

    public static Map<String, Object> step(Map<String, Object> session) {
        List<Object> pending = Models.array(session.get("pendingInternal"), "pendingInternal");
        List<Object> events = Models.array(session.get("events"), "events");
        long nextExternal = Models.integer(session, "nextExternal", 0);
        Map<String, Object> event;
        String origin;
        if (!pending.isEmpty()) {
            event = Models.object(pending.remove(0), "internal event");
            origin = "internal";
        } else if (nextExternal < events.size()) {
            event = Models.object(events.get((int) nextExternal), "event");
            nextExternal++;
            session.put("nextExternal", nextExternal);
            origin = "external";
        } else {
            throw new IllegalStateException("No events remain");
        }

        Map<String, Object> before = Models.object(Models.deepCopy(session.get("state")), "state");
        Map<String, Object> working = Models.object(Models.deepCopy(before), "state");
        long stepIndex = Models.array(session.get("steps"), "steps").size() + 1L;
        Map<String, Object> definition = Models.object(session.get("definition"), "definition");
        long seed = Models.integer(session, "seed", 0);
        List<Map<String, Object>> spawned = new ArrayList<>();
        List<Map<String, Object>> outputs = new ArrayList<>();
        String failure = null;
        String matchedRule = null;
        long startedInternal = Models.integer(session, "internalSequence", 0);

        Map<String, Object> matched = findRule(definition, event, before);
        if (matched == null) {
            session.put("state", working);
        } else {
            matchedRule = Models.requireString(matched, "name");
            try {
                List<Object> actions = Models.array(matched.get("actions"), "actions");
                for (Object actionInput : actions) {
                    Map<String, Object> action = Models.object(actionInput, "action");
                    executeAction(action, event, before, working, session, outputs, spawned, seed, stepIndex);
                }
                session.put("state", working);
                pending.addAll(spawned);
            } catch (RuntimeException e) {
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                spawned.clear();
                session.put("state", before);
            }
        }

        session.put("internalSequence", startedInternal + spawned.size());
        Map<String, Object> after = Models.object(Models.deepCopy(session.get("state")), "state");
        Map<String, Object> stepRecord = Models.map();
        stepRecord.put("index", stepIndex);
        stepRecord.put("event", Models.deepCopy(event));
        stepRecord.put("eventOrigin", origin);
        stepRecord.put("rule", matchedRule);
        stepRecord.put("before", before);
        stepRecord.put("after", after);
        stepRecord.put("diff", diff(before, after));
        stepRecord.put("outputs", outputs);
        stepRecord.put("failure", failure);
        stepRecord.put("fingerprint", stepFingerprint(stepRecord, priorHash(session)));
        Models.array(session.get("steps"), "steps").add(stepRecord);
        for (Map<String, Object> output : outputs) {
            Models.array(session.get("outputs"), "outputs").add(Models.deepCopy(output));
        }
        session.put("traceHash", stepRecord.get("fingerprint"));
        return stepRecord;
    }

    public static Map<String, Object> checkpoint(Map<String, Object> session, String name) {
        List<Object> checkpoints = Models.array(session.get("checkpoints"), "checkpoints");
        String id = "cp-" + checkpoints.size();
        long stepIndex = Models.array(session.get("steps"), "steps").size();
        long nextExternal = Models.integer(session, "nextExternal", 0);
        List<Object> pending = Models.array(session.get("pendingInternal"), "pendingInternal");
        long internalSequence = Models.integer(session, "internalSequence", 0);
        String anchorId = newAnchorId(session, id);
        Map<String, Object> created = checkpointBase(session, id, anchorId, name == null || name.isBlank() ? "检查点 " + id : name, stepIndex, nextExternal, pending, internalSequence);
        created.put("lineage", lineage(session, id));
        checkpoints.add(created);
        addAnchor(session, created);
        return created;
    }

    public static void restore(Map<String, Object> session, String checkpointId) {
        Map<String, Object> checkpoint = findCheckpoint(session, checkpointId);
        verifyCheckpoint(session, checkpoint);
        truncateTo(session, Models.integer(checkpoint, "stepIndex", 0));
        session.put("state", Models.deepCopy(checkpoint.get("state")));
        session.put("nextExternal", Models.integer(checkpoint, "nextExternal", 0));
        session.put("pendingInternal", Models.deepCopy(checkpoint.get("pendingInternal")));
        session.put("internalSequence", Models.integer(checkpoint, "internalSequence", 0));
        session.put("traceHash", checkpoint.get("traceHash"));
    }

    public static Map<String, Object> fork(Map<String, Object> session, String branchId, String checkpointId, String expectedDefinitionFingerprint) {
        Map<String, Object> cp = findCheckpoint(session, checkpointId == null ? INITIAL_CHECKPOINT : checkpointId);
        verifyCheckpoint(session, cp);
        if (expectedDefinitionFingerprint != null && !expectedDefinitionFingerprint.equals(cp.get("definitionFingerprint"))) {
            throw new IllegalStateException("检查点定义指纹不匹配，拒绝把旧定义检查点接到新定义");
        }
        long stepIndex = Models.integer(cp, "stepIndex", 0);
        List<Object> sourceEvents = Models.array(session.get("events"), "events");
        List<Object> events = Models.list();
        long nextExternal = Models.integer(cp, "nextExternal", 0);
        for (int i = 0; i < nextExternal && i < sourceEvents.size(); i++) events.add(Models.deepCopy(sourceEvents.get(i)));
        List<Object> keptSteps = Models.list();
        List<Object> keptOutputs = Models.list();
        List<Object> sourceSteps = Models.array(session.get("steps"), "steps");
        for (int i = 0; i < stepIndex && i < sourceSteps.size(); i++) {
            Object stepCopy = Models.deepCopy(sourceSteps.get(i));
            keptSteps.add(stepCopy);
            for (Object output : Models.array(Models.object(stepCopy, "step").get("outputs"), "outputs")) keptOutputs.add(output);
        }
        Map<String, Object> copy = Models.map();
        copy.put("id", branchId);
        copy.put("name", "分支 " + branchId);
        copy.put("definition", Models.deepCopy(session.get("definition")));
        copy.put("definitionFingerprint", session.get("definitionFingerprint"));
        copy.put("seed", session.get("seed"));
        copy.put("events", events);
        copy.put("nextExternal", nextExternal);
        copy.put("pendingInternal", Models.deepCopy(cp.get("pendingInternal")));
        copy.put("internalSequence", Models.integer(cp, "internalSequence", 0));
        copy.put("state", Models.deepCopy(cp.get("state")));
        copy.put("steps", keptSteps);
        copy.put("outputs", keptOutputs);
        copy.put("checkpoints", Models.list());
        copy.put("anchors", Models.list());
        copy.put("traceHash", cp.get("traceHash"));
        copy.put("initialLockHash", cp.get("traceHash"));
        copy.put("forkedFrom", new LinkedHashMap<>(Map.of("sessionId", session.get("id"), "checkpointId", cp.get("id"), "lineage", Models.deepCopy(cp.get("lineage")))));
        addInitialCheckpoint(copy);
        return copy;
    }

    public static void appendExternalEvents(Map<String, Object> session, List<Object> incoming) {
        List<Map<String, Object>> existing = eventObjects(Models.array(session.get("events"), "events"));
        List<Map<String, Object>> normalized = normalizeImportedEvents(incoming, existing);
        List<Object> events = Models.list();
        for (Map<String, Object> event : normalized) events.add(event);
        session.put("events", events);
    }

    public static Map<String, Object> compareSessions(Map<String, Object> left, Map<String, Object> right) {
        String baseId = commonAncestor(left, right);
        Map<String, Object> result = Models.map();
        result.put("commonAncestor", baseId);
        result.put("leftState", left.get("state"));
        result.put("rightState", right.get("state"));
        result.put("stateDiff", diff(left.get("state"), right.get("state")));
        result.put("leftOnlyOutputs", suffixOutputs(left, baseId));
        result.put("rightOnlyOutputs", suffixOutputs(right, baseId));
        result.put("leftExternalEvents", suffixExternal(left, baseId));
        result.put("rightExternalEvents", suffixExternal(right, baseId));
        return result;
    }

    public static Map<String, Object> merge(String id, Map<String, Object> left, Map<String, Object> right) {
        if (!Objects.equals(left.get("definitionFingerprint"), right.get("definitionFingerprint"))) {
            throw new MergeException(List.of(conflict("definition", Map.of("left", left.get("definitionFingerprint"), "right", right.get("definitionFingerprint")), "定义指纹不同")));
        }
        if (!Objects.equals(left.get("seed"), right.get("seed"))) {
            throw new MergeException(List.of(conflict("seed", Map.of("left", left.get("seed"), "right", right.get("seed")), "随机种子不同")));
        }
        String ancestorId = commonAncestor(left, right);
        Map<String, Object> ancestorCheckpoint = findCheckpoint(left, ancestorId);
        long baseExternal = Models.integer(ancestorCheckpoint, "nextExternal", 0);
        List<Map<String, Object>> leftSuffix = externalSuffix(left, baseExternal);
        List<Map<String, Object>> rightSuffix = externalSuffix(right, baseExternal);
        List<Map<String, Object>> mergedEvents = mergeExternal(leftSuffix, rightSuffix);
        Map<String, Object> merged = Models.map();
        merged.put("id", id);
        merged.put("name", "合并 " + id);
        merged.put("definition", Models.deepCopy(left.get("definition")));
        merged.put("definitionFingerprint", left.get("definitionFingerprint"));
        merged.put("seed", left.get("seed"));
        List<Object> prefix = Models.list();
        List<Object> leftEvents = Models.array(left.get("events"), "events");
        for (int i = 0; i < baseExternal && i < leftEvents.size(); i++) prefix.add(Models.deepCopy(leftEvents.get(i)));
        for (Map<String, Object> event : mergedEvents) prefix.add(Models.deepCopy(event));
        merged.put("events", prefix);
        merged.put("nextExternal", baseExternal);
        merged.put("pendingInternal", Models.list());
        merged.put("internalSequence", 0L);
        merged.put("state", Models.deepCopy(ancestorCheckpoint.get("state")));
        merged.put("steps", Models.list());
        merged.put("outputs", Models.list());
        merged.put("checkpoints", Models.list());
        merged.put("anchors", Models.list());
        merged.put("traceHash", ancestorCheckpoint.get("traceHash"));
        merged.put("initialLockHash", ancestorCheckpoint.get("traceHash"));
        merged.put("mergedFrom", Map.of("left", left.get("id"), "right", right.get("id"), "commonAncestor", ancestorId));
        addInitialCheckpoint(merged);
        while (true) {
            boolean pending = !Models.array(merged.get("pendingInternal"), "pendingInternal").isEmpty();
            boolean external = Models.integer(merged, "nextExternal", 0) < Models.array(merged.get("events"), "events").size();
            if (!pending && !external) break;
            step(merged);
        }
        return merged;
    }

    private static Map<String, Object> findRule(Map<String, Object> definition, Map<String, Object> event, Map<String, Object> before) {
        for (Object ruleInput : Models.array(definition.get("rules"), "rules")) {
            Map<String, Object> rule = Models.object(ruleInput, "rule");
            if (Models.requireString(rule, "event").equals(event.get("type")) && conditionMatches(rule.get("condition"), event, before)) {
                return rule;
            }
        }
        return null;
    }

    private static boolean conditionMatches(Object condition, Map<String, Object> event, Map<String, Object> state) {
        if (condition == null) return true;
        Map<String, Object> conditionMap = Models.object(condition, "condition");
        String op = Models.str(conditionMap, "op", "and");
        Object value = conditionMap.get("value");
        return switch (op) {
            case "and" -> {
                boolean all = true;
                for (Object child : Models.array(conditionMap.getOrDefault("children", Models.list()), "and children")) {
                    all &= conditionMatches(child, event, state);
                }
                yield all;
            }
            case "or" -> {
                boolean any = false;
                for (Object child : Models.array(conditionMap.getOrDefault("children", Models.list()), "or children")) {
                    any |= conditionMatches(child, event, state);
                }
                yield any;
            }
            case "not" -> !conditionMatches(conditionMap.get("child"), event, state);
            case "exists" -> resolveValue(Models.requireString(conditionMap, "path"), event, state, null) != null;
            case "eq", "ne", "lt", "lte", "gt", "gte" -> compare(op, resolveValue(Models.requireString(conditionMap, "path"), event, state, null), templated(value, event, state, null, 0));
            case "in" -> {
                Object actual = resolveValue(Models.requireString(conditionMap, "path"), event, state, null);
                boolean found = false;
                for (Object candidate : Models.array(value, "in value")) {
                    found |= Objects.equals(actual, templated(candidate, event, state, null, 0));
                }
                yield found;
            }
            case "matches" -> String.valueOf(resolveValue(Models.requireString(conditionMap, "path"), event, state, null)).matches(String.valueOf(templated(value, event, state, null, 0)));
            default -> throw new IllegalArgumentException("Unsupported condition op: " + op);
        };
    }

    private static boolean compare(String op, Object left, Object right) {
        int comparison;
        if (left instanceof Number ln && right instanceof Number rn) {
            comparison = Double.compare(ln.doubleValue(), rn.doubleValue());
        } else {
            comparison = String.valueOf(left).compareTo(String.valueOf(right));
        }
        return switch (op) {
            case "eq" -> Objects.equals(left, right) || (left instanceof Number && right instanceof Number && comparison == 0);
            case "ne" -> !(Objects.equals(left, right) || (left instanceof Number && right instanceof Number && comparison == 0));
            case "lt" -> comparison < 0;
            case "lte" -> comparison <= 0;
            case "gt" -> comparison > 0;
            case "gte" -> comparison >= 0;
            default -> false;
        };
    }

    private static void executeAction(Map<String, Object> action, Map<String, Object> event, Map<String, Object> before, Map<String, Object> working, Map<String, Object> session, List<Map<String, Object>> outputs, List<Map<String, Object>> spawned, long seed, long stepIndex) {
        String type = Models.requireString(action, "type");
        Object randomContext = Map.of("step", stepIndex, "event", event, "state", before);
        switch (type) {
            case "transition" -> {
                String state = String.valueOf(templated(action.get("state"), event, before, session, 0));
                List<String> states = new ArrayList<>();
                for (Object item : Models.array(Models.object(session.get("definition"), "definition").get("states"), "states")) states.add(String.valueOf(item));
                if (!states.contains(state)) throw new IllegalArgumentException("Unknown target state: " + state);
                working.put("state", state);
            }
            case "set" -> Models.setPath(working.get("data"), pathInData(Models.requireString(action, "path")), templated(action.get("value"), event, before, session, stepIndex));
            case "increment" -> {
                String path = pathInData(Models.requireString(action, "path"));
                Object current = Models.getPath(working.get("data"), path);
                double amount = action.containsKey("amount") ? ((Number) templated(action.get("amount"), event, before, session, stepIndex)).doubleValue() : 1D;
                double base = current instanceof Number number ? number.doubleValue() : 0D;
                Models.setPath(working.get("data"), path, base + amount);
            }
            case "emit" -> {
                Map<String, Object> output = Models.map();
                output.put("channel", Models.str(action, "channel", "default"));
                output.put("payload", templated(action.getOrDefault("payload", Models.map()), event, before, session, stepIndex));
                output.put("step", stepIndex);
                output.put("eventId", event.get("id"));
                outputs.add(output);
            }
            case "raise" -> throw new ActionFailureException(Models.str(action, "message", "动作显式失败"));
            case "random" -> {
                long bound = ((Number) templated(action.getOrDefault("bound", 1_000_000), event, before, session, stepIndex)).longValue();
                if (bound <= 0) throw new IllegalArgumentException("random bound must be positive");
                long value = deterministicRandom(seed, stepIndex, event, spawned.size(), outputs.size()) % bound;
                Models.setPath(working.get("data"), pathInData(Models.requireString(action, "path")), value);
            }
            case "enqueue" -> {
                long internalSequence = Models.integer(session, "internalSequence", 0) + spawned.size();
                Map<String, Object> internal = Models.map();
                internal.put("id", "int-" + stepIndex + "-" + internalSequence);
                internal.put("type", Models.requireString(action, "event"));
                internal.put("time", event.get("time"));
                internal.put("sourcePriority", 0L);
                internal.put("seq", internalSequence);
                internal.put("internal", Boolean.TRUE);
                internal.put("payload", templated(action.getOrDefault("payload", Models.map()), event, before, session, stepIndex));
                internal.put("parentEventId", event.get("id"));
                spawned.add(internal);
            }
            default -> throw new IllegalArgumentException("Unsupported action: " + type);
        }
    }

    private static long deterministicRandom(long seed, long stepIndex, Map<String, Object> event, int internalCount, int outputCount) {
        String material = Json.canonical(Map.of("seed", seed, "step", stepIndex, "eventId", event.get("id"), "internal", internalCount, "output", outputCount));
        byte[] hash = material.getBytes(StandardCharsets.UTF_8);
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            hash = digest.digest(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long value = 0;
        for (int i = 0; i < 8; i++) value = (value << 8) | (hash[i] & 0xffL);
        return Math.abs(value);
    }

    private static Object templated(Object value, Map<String, Object> event, Map<String, Object> state, Map<String, Object> session, long stepIndex) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = Models.map();
            for (Map.Entry<?, ?> entry : map.entrySet()) copy.put(String.valueOf(entry.getKey()), templated(entry.getValue(), event, state, session, stepIndex));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = Models.list();
            for (Object item : list) copy.add(templated(item, event, state, session, stepIndex));
            return copy;
        }
        if (value instanceof String s) return resolveTemplate(s, event, state, session, stepIndex);
        return value;
    }

    private static Object resolveTemplate(String template, Map<String, Object> event, Map<String, Object> state, Map<String, Object> session, long stepIndex) {
        if (!template.contains("${")) return template;
        String result = template;
        while (result.contains("${")) {
            int start = result.indexOf("${");
            int end = result.indexOf('}', start + 2);
            if (end < 0) throw new IllegalArgumentException("Bad template: " + template);
            String expression = result.substring(start + 2, end).trim();
            Object replacement;
            if (expression.startsWith("event.")) replacement = Models.getPath(event, "$." + expression.substring(6));
            else if (expression.startsWith("state.")) replacement = Models.getPath(state, "$." + expression.substring(6));
            else if ("step".equals(expression)) replacement = stepIndex;
            else replacement = null;
            result = result.substring(0, start) + replacement + result.substring(end + 1);
        }
        if (result.matches("-?\\d+")) return Long.parseLong(result);
        if (result.matches("-?\\d+\\.\\d+")) return Double.parseDouble(result);
        if ("true".equals(result) || "false".equals(result)) return Boolean.parseBoolean(result);
        return result;
    }

    private static Object resolveValue(String path, Map<String, Object> event, Map<String, Object> state, Map<String, Object> session) {
        if (path.startsWith("event.")) return Models.getPath(event, "$." + path.substring(6));
        if (path.startsWith("state.")) return Models.getPath(state, "$." + path.substring(6));
        return Models.getPath(state, path);
    }

    private static String pathInData(String path) {
        if (path.startsWith("data.")) return path.substring(5);
        if (path.startsWith("$.data.")) return path.substring(7);
        return path;
    }

    private static List<Map<String, Object>> normalizeImportedEvents(List<Object> input, List<Map<String, Object>> existing) {
        List<Map<String, Object>> result = new ArrayList<>(existing);
        for (int i = 0; i < input.size(); i++) {
            Map<String, Object> raw = Models.object(input.get(i), "event");
            Map<String, Object> event = Models.map();
            long time = ((Number) raw.getOrDefault("time", 0L)).longValue();
            long priority = ((Number) raw.getOrDefault("sourcePriority", 100L)).longValue();
            long seq = ((Number) raw.getOrDefault("seq", i + 1L)).longValue();
            event.put("id", Models.str(raw, "id", ""));
            event.put("type", Models.requireString(raw, "type"));
            event.put("time", time);
            event.put("sourcePriority", priority);
            event.put("seq", seq);
            event.put("payload", raw.containsKey("payload") ? Models.deepCopy(raw.get("payload")) : Models.map());
            event.put("source", Models.str(raw, "source", "import"));
            if (raw.containsKey("internal")) event.put("internal", raw.get("internal"));
            if (raw.containsKey("parentEventId")) event.put("parentEventId", raw.get("parentEventId"));
            String id = String.valueOf(event.get("id"));
            if (id.isBlank()) {
                Map<String, Object> identity = Models.map();
                identity.put("time", time);
                identity.put("sourcePriority", priority);
                identity.put("seq", seq);
                identity.put("type", event.get("type"));
                identity.put("payload", event.get("payload"));
                event.put("id", "ext-" + Hashes.sha256(Json.canonical(identity)).substring(0, 16));
            }
            result.add(event);
        }
        validateEventKeys(result);
        result.sort(Comparator.comparingLong((Map<String, Object> e) -> ((Number) e.get("time")).longValue())
                .thenComparingLong(e -> ((Number) e.get("sourcePriority")).longValue())
                .thenComparingLong(e -> ((Number) e.get("seq")).longValue())
                .thenComparing(e -> String.valueOf(e.get("id"))));
        return result;
    }

    private static void validateEventKeys(List<Map<String, Object>> events) {
        Map<String, Map<String, Object>> ids = new LinkedHashMap<>();
        Map<String, Map<String, Object>> keys = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String id = String.valueOf(event.get("id"));
            Map<String, Object> previousId = ids.put(id, event);
            if (previousId != null && !Json.canonical(previousId).equals(Json.canonical(event))) {
                throw new IllegalArgumentException("Duplicate event id with different content: " + id);
            }
            String key = eventKey(event);
            Map<String, Object> previousKey = keys.put(key, event);
            if (previousKey != null && !String.valueOf(previousKey.get("id")).equals(id)) {
                throw new IllegalArgumentException("Ambiguous same-time event key: " + key);
            }
        }
    }

    private static String eventKey(Map<String, Object> event) {
        return event.get("time") + ":" + event.get("sourcePriority") + ":" + event.get("seq");
    }

    private static List<Map<String, Object>> eventObjects(List<Object> events) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object event : events) result.add(Models.object(event, "event"));
        return result;
    }

    private static List<Object> eventListToObjects(List<Map<String, Object>> events) {
        List<Object> result = Models.list();
        result.addAll(events);
        return result;
    }

    private static String sessionLock(String definitionFingerprint, Map<String, Object> initialState, long seed, List<Map<String, Object>> events) {
        Map<String, Object> lock = Models.map();
        lock.put("definitionFingerprint", definitionFingerprint);
        lock.put("initialState", initialState);
        lock.put("seed", seed);
        lock.put("events", events);
        return Json.canonical(lock);
    }

    private static String priorHash(Map<String, Object> session) {
        return String.valueOf(session.get("traceHash"));
    }

    private static String stepFingerprint(Map<String, Object> step, String previous) {
        Map<String, Object> material = Models.map();
        material.put("previous", previous);
        material.put("event", step.get("event"));
        material.put("eventOrigin", step.get("eventOrigin"));
        material.put("rule", step.get("rule"));
        material.put("before", step.get("before"));
        material.put("after", step.get("after"));
        material.put("outputs", step.get("outputs"));
        material.put("failure", step.get("failure"));
        return Hashes.fingerprint(material);
    }

    private static void addInitialCheckpoint(Map<String, Object> session) {
        String checkpointId = INITIAL_CHECKPOINT;
        String anchorId = newAnchorId(session, checkpointId);
        List<Object> lineage;
        if (session.containsKey("forkedFrom")) {
            Map<String, Object> forkedFrom = Models.object(session.get("forkedFrom"), "forkedFrom");
            lineage = Models.list();
            for (Object item : Models.array(forkedFrom.get("lineage"), "fork lineage")) lineage.add(Models.deepCopy(item));
            lineage.add(Map.of("checkpointId", checkpointId, "anchorId", anchorId, "traceHash", session.get("traceHash"), "sessionId", session.get("id"), "kind", "fork"));
        } else {
            lineage = Models.list(Map.of("checkpointId", checkpointId, "anchorId", anchorId, "traceHash", session.get("traceHash"), "sessionId", session.get("id"), "kind", "initial"));
        }
        Map<String, Object> checkpoint = checkpointBase(session, checkpointId, anchorId, "初始检查点", 0L, 0L, Models.list(), 0L);
        checkpoint.put("lineage", lineage);
        Models.array(session.get("checkpoints"), "checkpoints").add(checkpoint);
        addAnchor(session, checkpoint);
    }

    private static Map<String, Object> checkpointBase(Map<String, Object> session, String id, String anchorId, String name, long stepIndex, long nextExternal, Object pendingInternal, long internalSequence) {
        Map<String, Object> checkpoint = Models.map();
        checkpoint.put("id", id);
        checkpoint.put("anchorId", anchorId);
        checkpoint.put("name", name);
        checkpoint.put("stepIndex", stepIndex);
        checkpoint.put("definitionFingerprint", session.get("definitionFingerprint"));
        checkpoint.put("stateFingerprint", Hashes.fingerprint(session.get("state")));
        checkpoint.put("traceHash", session.get("traceHash"));
        checkpoint.put("state", Models.deepCopy(session.get("state")));
        checkpoint.put("nextExternal", nextExternal);
        checkpoint.put("pendingInternal", Models.deepCopy(pendingInternal));
        checkpoint.put("internalSequence", internalSequence);
        checkpoint.put("checkpointFingerprint", checkpointFingerprint(session, anchorId, stepIndex, nextExternal, pendingInternal, internalSequence));
        return checkpoint;
    }

    private static String newAnchorId(Map<String, Object> session, String checkpointId) {
        Map<String, Object> material = Models.map();
        material.put("sessionId", session.get("id"));
        material.put("checkpointId", checkpointId);
        material.put("traceHash", session.get("traceHash"));
        material.put("state", session.get("state"));
        material.put("nextExternal", session.getOrDefault("nextExternal", 0L));
        material.put("pendingInternal", session.getOrDefault("pendingInternal", Models.list()));
        return "anc-" + Hashes.sha256(Json.canonical(material)).substring(0, 16);
    }

    private static String checkpointFingerprint(Map<String, Object> session, String anchorId, long stepIndex, long nextExternal, Object pendingInternal, long internalSequence) {
        Map<String, Object> material = Models.map();
        material.put("anchorId", anchorId);
        material.put("definitionFingerprint", session.get("definitionFingerprint"));
        material.put("state", session.get("state"));
        material.put("traceHash", session.get("traceHash"));
        material.put("stepIndex", stepIndex);
        material.put("nextExternal", nextExternal);
        material.put("pendingInternal", pendingInternal);
        material.put("internalSequence", internalSequence);
        return Hashes.fingerprint(material);
    }

    private static void addAnchor(Map<String, Object> session, Map<String, Object> checkpoint) {
        if (!session.containsKey("anchors")) session.put("anchors", Models.list());
        Map<String, Object> anchor = Models.map();
        anchor.put("anchorId", checkpoint.get("anchorId"));
        anchor.put("checkpointId", checkpoint.get("id"));
        anchor.put("traceHash", checkpoint.get("traceHash"));
        anchor.put("state", Models.deepCopy(checkpoint.get("state")));
        anchor.put("nextExternal", checkpoint.get("nextExternal"));
        anchor.put("pendingInternal", Models.deepCopy(checkpoint.get("pendingInternal")));
        anchor.put("internalSequence", checkpoint.get("internalSequence"));
        Models.array(session.get("anchors"), "anchors").add(anchor);
    }

    private static List<Object> lineage(Map<String, Object> session, String checkpointId) {
        Map<String, Object> latest = findCheckpoint(session, currentCheckpointId(session));
        List<Object> lineage = Models.list();
        for (Object item : Models.array(latest.get("lineage"), "lineage")) lineage.add(Models.deepCopy(item));
        String anchorId = newAnchorId(session, checkpointId);
        lineage.add(Map.of("checkpointId", checkpointId, "anchorId", anchorId, "traceHash", session.get("traceHash"), "sessionId", session.get("id"), "kind", "checkpoint"));
        return lineage;
    }

    private static String currentCheckpointId(Map<String, Object> session) {
        List<Object> checkpoints = Models.array(session.get("checkpoints"), "checkpoints");
        return String.valueOf(Models.object(checkpoints.get(checkpoints.size() - 1), "checkpoint").get("id"));
    }

    private static Map<String, Object> findCheckpoint(Map<String, Object> session, String id) {
        for (Object checkpoint : Models.array(session.get("checkpoints"), "checkpoints")) {
            Map<String, Object> map = Models.object(checkpoint, "checkpoint");
            if (String.valueOf(map.get("id")).equals(id)) return map;
        }
        return null;
    }

    private static void verifyCheckpoint(Map<String, Object> session, Map<String, Object> checkpoint) {
        if (checkpoint == null) throw new IllegalArgumentException("检查点不存在");
        if (!Objects.equals(checkpoint.get("definitionFingerprint"), session.get("definitionFingerprint"))) {
            throw new IllegalStateException("检查点定义指纹不匹配，拒绝使用旧定义检查点");
        }
        if (!Objects.equals(checkpoint.get("stateFingerprint"), Hashes.fingerprint(checkpoint.get("state")))) {
            throw new IllegalStateException("检查点状态指纹损坏");
        }
        if (!Objects.equals(checkpoint.get("traceHash"), traceAt(session, Models.integer(checkpoint, "stepIndex", 0)))) {
            throw new IllegalStateException("检查点轨迹哈希不匹配");
        }
    }

    private static Object traceAt(Map<String, Object> session, long stepIndex) {
        if (stepIndex == 0) return session.get("initialLockHash");
        List<Object> steps = Models.array(session.get("steps"), "steps");
        return Models.object(steps.get((int) stepIndex - 1), "step").get("fingerprint");
    }

    private static void truncateTo(Map<String, Object> session, long stepIndex) {
        List<Object> steps = Models.array(session.get("steps"), "steps");
        while (steps.size() > stepIndex) steps.remove(steps.size() - 1);
        int outputCount = 0;
        for (Object step : steps) {
            outputCount += Models.array(Models.object(step, "step").get("outputs"), "outputs").size();
        }
        List<Object> outputs = Models.array(session.get("outputs"), "outputs");
        while (outputs.size() > outputCount) outputs.remove(outputs.size() - 1);
    }

    private static Map<String, Object> diff(Object before, Object after) {
        Map<String, Object> result = Models.map();
        result.put("added", Models.list());
        result.put("removed", Models.list());
        result.put("changed", Models.list());
        diffAt("$", before, after, result);
        return result;
    }

    private static void diffAt(String path, Object before, Object after, Map<String, Object> diff) {
        if (Objects.equals(before, after)) return;
        if (before == null) {
            Models.array(diff.get("added"), "added").add(Map.of("path", path, "value", after));
            return;
        }
        if (after == null) {
            Models.array(diff.get("removed"), "removed").add(Map.of("path", path, "old", before));
            return;
        }
        if (before instanceof Map<?, ?> oldMap && after instanceof Map<?, ?> newMap) {
            for (Object key : oldMap.keySet()) {
                if (!newMap.containsKey(key)) diffAt(path + "." + key, oldMap.get(key), null, diff);
            }
            for (Object key : newMap.keySet()) {
                if (!oldMap.containsKey(key)) diffAt(path + "." + key, null, newMap.get(key), diff);
                else diffAt(path + "." + key, oldMap.get(key), newMap.get(key), diff);
            }
        } else {
            Models.array(diff.get("changed"), "changed").add(Map.of("path", path, "old", before, "value", after));
        }
    }

    private static String commonAncestor(Map<String, Object> left, Map<String, Object> right) {
        Map<String, String> leftAncestors = new LinkedHashMap<>();
        for (Object checkpoint : Models.array(left.get("checkpoints"), "checkpoints")) {
            Map<String, Object> cp = Models.object(checkpoint, "checkpoint");
            collectLineageIds(cp, leftAncestors);
        }
        List<Object> rightCheckpoints = Models.array(right.get("checkpoints"), "checkpoints");
        for (int i = rightCheckpoints.size() - 1; i >= 0; i--) {
            Map<String, Object> cp = Models.object(rightCheckpoints.get(i), "checkpoint");
            for (Object item : Models.array(cp.get("lineage"), "lineage")) {
                Map<String, Object> lineage = Models.object(item, "lineage");
                String checkpointId = String.valueOf(lineage.get("checkpointId"));
                if (leftAncestors.containsKey(checkpointId) && Objects.equals(leftAncestors.get(checkpointId), String.valueOf(lineage.get("traceHash")))) {
                    return checkpointId;
                }
            }
        }
        throw new MergeException(List.of(conflict("ancestor", Map.of(), "没有共同祖先检查点")));
    }

    private static void collectLineageIds(Map<String, Object> checkpoint, Map<String, String> map) {
        for (Object item : Models.array(checkpoint.get("lineage"), "lineage")) {
            Map<String, Object> lineage = Models.object(item, "lineage");
            map.put(String.valueOf(lineage.get("checkpointId")), String.valueOf(lineage.get("traceHash")));
        }
    }

    private static List<Map<String, Object>> externalSuffix(Map<String, Object> session, long baseExternal) {
        List<Object> steps = Models.array(session.get("steps"), "steps");
        long processed = processedExternalAt(session, steps.size());
        List<Map<String, Object>> result = new ArrayList<>();
        List<Object> events = Models.array(session.get("events"), "events");
        for (long i = baseExternal; i < processed && i < events.size(); i++) {
            result.add(Models.object(events.get((int) i), "event"));
        }
        return result;
    }

    private static long processedExternalAt(Map<String, Object> session, int stepCount) {
        long externalCount = 0;
        List<Object> steps = Models.array(session.get("steps"), "steps");
        for (int i = 0; i < stepCount && i < steps.size(); i++) {
            if ("external".equals(Models.object(steps.get(i), "step").get("eventOrigin"))) externalCount++;
        }
        return externalCount;
    }

    private static List<Map<String, Object>> mergeExternal(List<Map<String, Object>> left, List<Map<String, Object>> right) {
        List<Map<String, Object>> merged = new ArrayList<>();
        int li = 0;
        int ri = 0;
        while (li < left.size() || ri < right.size()) {
            if (li == left.size()) merged.add(right.get(ri++));
            else if (ri == right.size()) merged.add(left.get(li++));
            else {
                Map<String, Object> le = left.get(li);
                Map<String, Object> re = right.get(ri);
                int identity = compareIdentity(le, re);
                if (identity == 0) {
                    if (!Objects.equals(le.get("type"), re.get("type")) || !Objects.equals(Json.canonical(le.get("payload")), Json.canonical(re.get("payload")))) {
                        throw new MergeException(List.of(conflict("event-content", Map.of("left", le, "right", re), "共同事件内容不同")));
                    }
                    merged.add(le);
                    li++;
                    ri++;
                } else if (identity < 0) {
                    if (compareIdentity(le, re) == 0 || sameKey(le, re)) throw new MergeException(List.of(conflict("event-order", Map.of("left", le, "right", re), "同一逻辑时刻的不同外部事件顺序有歧义")));
                    merged.add(le);
                    li++;
                } else {
                    if (sameKey(le, re)) throw new MergeException(List.of(conflict("event-order", Map.of("left", le, "right", re), "同一逻辑时刻的不同外部事件顺序有歧义")));
                    merged.add(re);
                    ri++;
                }
            }
        }
        validateEventKeys(merged);
        return merged;
    }

    private static int compareIdentity(Map<String, Object> left, Map<String, Object> right) {
        int byTime = Long.compare(((Number) left.get("time")).longValue(), ((Number) right.get("time")).longValue());
        if (byTime != 0) return byTime;
        int byPriority = Long.compare(((Number) left.get("sourcePriority")).longValue(), ((Number) right.get("sourcePriority")).longValue());
        if (byPriority != 0) return byPriority;
        int bySeq = Long.compare(((Number) left.get("seq")).longValue(), ((Number) right.get("seq")).longValue());
        if (bySeq != 0) return bySeq;
        return String.valueOf(left.get("id")).compareTo(String.valueOf(right.get("id")));
    }

    private static boolean sameKey(Map<String, Object> left, Map<String, Object> right) {
        return Objects.equals(left.get("time"), right.get("time")) && Objects.equals(left.get("sourcePriority"), right.get("sourcePriority")) && Objects.equals(left.get("seq"), right.get("seq"));
    }

    private static Map<String, Object> conflict(String type, Map<String, Object> events, String message) {
        Map<String, Object> conflict = Models.map();
        conflict.put("type", type);
        conflict.put("events", events);
        conflict.put("message", message);
        return conflict;
    }

    private static List<Object> suffixOutputs(Map<String, Object> session, String ancestorId) {
        long baseStep = baseStep(session, ancestorId);
        List<Object> result = Models.list();
        List<Object> steps = Models.array(session.get("steps"), "steps");
        for (int i = (int) baseStep; i < steps.size(); i++) {
            result.addAll(Models.array(Models.object(steps.get(i), "step").get("outputs"), "outputs"));
        }
        return result;
    }

    private static List<Object> suffixExternal(Map<String, Object> session, String ancestorId) {
        long baseStep = baseStep(session, ancestorId);
        List<Object> result = Models.list();
        List<Object> steps = Models.array(session.get("steps"), "steps");
        for (int i = (int) baseStep; i < steps.size(); i++) {
            Map<String, Object> step = Models.object(steps.get(i), "step");
            if ("external".equals(step.get("eventOrigin"))) result.add(step.get("event"));
        }
        return result;
    }

    private static long baseStep(Map<String, Object> session, String ancestorId) {
        Map<String, Object> checkpoint = findCheckpoint(session, ancestorId);
        if (checkpoint != null) return Models.integer(checkpoint, "stepIndex", 0);
        return 0;
    }

    public static final class MergeException extends RuntimeException {
        private final List<Map<String, Object>> conflicts;

        public MergeException(List<Map<String, Object>> conflicts) {
            super(String.valueOf(conflicts.get(0).get("message")));
            this.conflicts = conflicts;
        }

        public List<Map<String, Object>> conflicts() {
            return conflicts;
        }
    }

    public static final class ActionFailureException extends RuntimeException {
        public ActionFailureException(String message) {
            super(message);
        }
    }
}
