package dev.replay.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed read-only view over a state machine definition map, with validation. */
public final class DefinitionView {
    private final Map<String, Object> raw;

    public DefinitionView(Map<String, Object> raw) {
        validate(raw);
        this.raw = raw;
    }

    public Map<String, Object> raw() {
        return raw;
    }

    public String name() {
        return strOr(raw.get("name"), "状态机");
    }

    public String initialState() {
        return (String) raw.get("initialState");
    }

    public long seed() {
        Object seed = raw.get("seed");
        return seed instanceof Number ? ((Number) seed).longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> initialData() {
        Object data = raw.get("initialData");
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> transitions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<Object>) raw.getOrDefault("transitions", List.of())) {
            result.add((Map<String, Object>) item);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public int priorityOf(String source) {
        Object sources = raw.get("sources");
        if (sources instanceof Map) {
            Object value = ((Map<String, Object>) sources).get(source);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- validation

    public static void validate(Map<String, Object> def) {
        require(def != null, "定义不能为空");
        Object statesRaw = def.get("states");
        require(statesRaw instanceof List && !((List<?>) statesRaw).isEmpty(), "states 必须是非空数组");
        List<String> states = new ArrayList<>();
        for (Object state : (List<?>) statesRaw) {
            require(state instanceof String && !((String) state).isBlank(), "状态名必须是非空字符串");
            String name = (String) state;
            require(!states.contains(name), "状态重复: " + name);
            states.add(name);
        }
        Object initial = def.get("initialState");
        require(initial instanceof String && states.contains(initial),
                "initialState 必须是 states 中的一个状态");
        Object seed = def.get("seed");
        require(seed == null || seed instanceof Long || seed instanceof Integer,
                "seed 必须是整数");
        Object initialData = def.get("initialData");
        require(initialData == null || initialData instanceof Map, "initialData 必须是对象");
        Object sources = def.get("sources");
        if (sources instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) sources).entrySet()) {
                require(entry.getKey() instanceof String, "sources 的键必须是字符串");
                require(entry.getValue() instanceof Number, "sources 的优先级必须是数字: " + entry.getKey());
            }
        }

        Object transitions = def.get("transitions");
        require(transitions instanceof List, "transitions 必须是数组");
        Set<String> names = new HashSet<>();
        int index = 0;
        for (Object item : (List<?>) transitions) {
            require(item instanceof Map, "transitions[" + index + "] 必须是对象");
            Map<?, ?> transition = (Map<?, ?>) item;
            String where = "transitions[" + index + "]";
            Object name = transition.get("name");
            require(name instanceof String && !((String) name).isBlank(), where + ".name 必须是非空字符串");
            require(names.add((String) name), "迁移名称重复: " + name);
            Object from = transition.get("from");
            require(from instanceof String, where + ".from 必须是字符串");
            require(states.contains(from) || "*".equals(from),
                    where + ".from 不在 states 中: " + from);
            Object event = transition.get("event");
            require(event instanceof String && !((String) event).isBlank(),
                    where + ".event 必须是非空字符串");
            Object when = transition.get("when");
            if (when != null) {
                require(when instanceof List, where + ".when 必须是条件数组");
                int conditionIndex = 0;
                for (Object condition : (List<?>) when) {
                    Expr.validate(condition, where + ".when[" + conditionIndex++ + "]");
                }
            }
            Object to = transition.get("to");
            if (to != null) {
                require(to instanceof String && states.contains(to),
                        where + ".to 不在 states 中: " + to);
            }
            Object actions = transition.get("actions");
            require(actions == null || actions instanceof List, where + ".actions 必须是数组");
            if (actions instanceof List) {
                int actionIndex = 0;
                for (Object action : (List<?>) actions) {
                    validateAction(action, where + ".actions[" + actionIndex++ + "]");
                }
            }
            index++;
        }
    }

    private static void validateAction(Object action, String where) {
        require(action instanceof Map, where + " 必须是对象");
        Map<?, ?> map = (Map<?, ?>) action;
        Object opValue = map.get("op");
        require(opValue instanceof String, where + ".op 必须是字符串");
        String op = (String) opValue;
        switch (op) {
            case "set", "inc" -> {
                require(map.get("path") instanceof String, where + ".path 必须是字符串");
                Object value = map.get(op.equals("set") ? "value" : "by");
                if (value != null) {
                    Expr.validate(value, where + "." + (op.equals("set") ? "value" : "by"));
                }
            }
            case "emit" -> {
                require(map.get("type") instanceof String, where + ".type 必须是字符串");
                Object data = map.get("data");
                if (data instanceof Map) {
                    validateActionData((Map<?, ?>) data, where + ".data");
                } else {
                    require(data == null, where + ".data 必须是对象");
                }
                Object delay = map.get("delay");
                require(delay == null || delay instanceof Number, where + ".delay 必须是数字");
                Object source = map.get("source");
                require(source == null || source instanceof String, where + ".source 必须是字符串");
            }
            case "fail" -> require(map.get("message") instanceof String, where + ".message 必须是字符串");
            case "assert" -> {
                Expr.validate(map.get("that"), where + ".that");
                require(map.get("message") instanceof String, where + ".message 必须是字符串");
            }
            case "random" -> {
                require(map.get("path") instanceof String, where + ".path 必须是字符串");
                Expr.validate(map.get("bound"), where + ".bound");
            }
            case "output" -> {
                require(map.get("name") instanceof String, where + ".name 必须是字符串");
                Expr.validate(map.get("value"), where + ".value");
            }
            default -> throw new IllegalArgumentException(where + " 的未知动作: " + op);
        }
    }

    private static void validateActionData(Map<?, ?> data, String where) {
        for (Map.Entry<?, ?> entry : data.entrySet()) {
            require(entry.getKey() instanceof String, where + " 的键必须是字符串");
            Object value = entry.getValue();
            if (value instanceof Map && ((Map<?, ?>) value).containsKey("expr")) {
                Expr.validate(((Map<?, ?>) value).get("expr"), where + "." + entry.getKey());
            } else if (value instanceof Map) {
                validateActionData((Map<?, ?>) value, where + "." + entry.getKey());
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String strOr(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }
}
