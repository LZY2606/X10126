package replay.engine;

import java.util.List;
import java.util.Map;

import replay.json.JsonUtil;

public final class Definition {
    private Definition() {
    }

    public static void validate(Map<String, Object> definition) {
        JsonUtil.string(definition, "definitionVersion");
        JsonUtil.string(definition, "initialState");
        long seed = JsonUtil.integer(definition.getOrDefault("randomSeed", 0L), "randomSeed");
        if (seed < 0L) {
            throw new IllegalArgumentException("randomSeed must be non-negative");
        }
        List<Object> states = JsonUtil.list(definition.get("states"), "states");
        if (states.isEmpty()) {
            throw new IllegalArgumentException("states must contain at least one state");
        }
        String initialState = JsonUtil.string(definition, "initialState");
        if (states.stream().noneMatch(state -> initialState.equals(String.valueOf(state)))) {
            throw new IllegalArgumentException("initialState must be declared in states");
        }
        Object initialData = definition.get("initialData");
        if (initialData != null) {
            JsonUtil.object(initialData, "initialData");
        }
        if (definition.containsKey("sourcePriorities")) {
            JsonUtil.object(definition.get("sourcePriorities"), "sourcePriorities");
        }
        List<Object> transitions = JsonUtil.list(definition.get("transitions"), "transitions");
        for (int i = 0; i < transitions.size(); i++) {
            Map<String, Object> transition = JsonUtil.object(transitions.get(i), "transitions[" + i + "]");
            JsonUtil.optionalString(transition, "from", "*");
            JsonUtil.string(transition, "event");
            JsonUtil.optionalString(transition, "to", JsonUtil.optionalString(transition, "from", "*"));
            validateConditions(JsonUtil.list(transition.getOrDefault("conditions", List.of()), "conditions"), i);
            validateActions(JsonUtil.list(transition.getOrDefault("actions", List.of()), "actions"), i);
        }
    }

    private static void validateConditions(List<Object> conditions, int transitionIndex) {
        for (int i = 0; i < conditions.size(); i++) {
            Map<String, Object> condition = JsonUtil.object(conditions.get(i), "conditions[" + i + "]");
            String where = "transitions[" + transitionIndex + "].conditions[" + i + "]";
            String type = JsonUtil.optionalString(condition, "type", "equals");
            String path = JsonUtil.string(condition, "path");
            requireRootPath(path, where, "state", "data", "event");
            switch (type) {
                case "equals", "notEquals", "greaterThan", "greaterThanOrEqual", "lessThan", "lessThanOrEqual" -> {
                    if (!condition.containsKey("value")) {
                        throw new IllegalArgumentException(where + " requires value");
                    }
                }
                case "exists" -> {
                }
                default -> throw new IllegalArgumentException(where + " has unsupported type " + type);
            }
        }
    }

    private static void validateActions(List<Object> actions, int transitionIndex) {
        for (int i = 0; i < actions.size(); i++) {
            Map<String, Object> action = JsonUtil.object(actions.get(i), "actions[" + i + "]");
            String where = "transitions[" + transitionIndex + "].actions[" + i + "]";
            switch (JsonUtil.string(action, "type")) {
                case "set" -> {
                    requireNestedPath(JsonUtil.string(action, "path"), where, "data");
                    requireKey(action, "value", where);
                }
                case "increment" -> {
                    requireNestedPath(JsonUtil.string(action, "path"), where, "data");
                    if (action.containsKey("amount")) {
                        JsonUtil.integer(action.get("amount"), where + ".amount");
                    }
                }
                case "random" -> {
                    requireNestedPath(JsonUtil.string(action, "path"), where, "data");
                    long min = JsonUtil.integer(action.getOrDefault("min", 0L), where + ".min");
                    long max = JsonUtil.integer(action.getOrDefault("max", Long.MAX_VALUE), where + ".max");
                    if (min >= max) {
                        throw new IllegalArgumentException(where + " requires min < max");
                    }
                }
                case "emit" -> JsonUtil.string(action, "event");
                case "output" -> JsonUtil.string(action, "name");
                case "fail" -> {
                }
                default -> throw new IllegalArgumentException(where + " has unsupported type");
            }
        }
    }

    private static void requireRootPath(String path, String where, String... roots) {
        String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
        for (String allowed : roots) {
            if (allowed.equals(root)) {
                return;
            }
        }
        throw new IllegalArgumentException(where + " path must start with one of " + String.join(", ", roots));
    }

    private static void requireNestedPath(String path, String where, String... roots) {
        requireRootPath(path, where, roots);
        if (!path.contains(".")) {
            throw new IllegalArgumentException(where + " path must identify a field below " + String.join(" or ", roots));
        }
    }

    private static void requireKey(Map<String, Object> map, String key, String where) {
        if (!map.containsKey(key)) {
            throw new IllegalArgumentException(where + " requires " + key);
        }
    }

    public static Map<String, Object> sample() {
        return replay.json.JsonUtil.object(replay.json.Json.parse("""
                {
                  "definitionVersion": "demo-v1",
                  "initialState": "idle",
                  "initialData": {"retries": 0, "score": 0},
                  "randomSeed": 20260923,
                  "sourcePriorities": {"operator": 10, "sensor": 5, "timer": 1},
                  "states": ["idle", "running", "blocked"],
                  "transitions": [
                    {
                      "from": "idle",
                      "event": "start",
                      "to": "running",
                      "actions": [
                        {"type": "output", "name": "started", "payload": {"by": "event"}}
                      ]
                    },
                    {
                      "from": "running",
                      "event": "tick",
                      "to": "running",
                      "actions": [
                        {"type": "increment", "path": "data.score", "amount": 1},
                        {"type": "emit", "event": "score-check", "source": "internal", "data": {"reason": "tick"}}
                      ]
                    },
                    {
                      "from": "running",
                      "event": "score-check",
                      "to": "blocked",
                      "conditions": [
                        {"type": "greaterThanOrEqual", "path": "data.score", "value": 2}
                      ],
                      "actions": [
                        {"type": "random", "path": "data.token", "min": 1000, "max": 9999},
                        {"type": "output", "name": "blocked", "payload": {"reason": "score"}}
                      ]
                    },
                    {
                      "from": "running",
                      "event": "reset",
                      "to": "idle",
                      "actions": [
                        {"type": "set", "path": "data.score", "value": 0}
                      ]
                    },
                    {
                      "from": "*",
                      "event": "panic",
                      "to": "blocked",
                      "conditions": [
                        {"type": "equals", "path": "event.data.hard", "value": true}
                      ],
                      "actions": [
                        {"type": "fail", "message": "panic path is intentionally transactional"}
                      ]
                    }
                  ]
                }
                """), "sample definition");
    }
}
