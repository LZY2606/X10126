package dev.replay.engine;

import java.util.List;
import java.util.Map;

/**
 * Tiny deterministic condition / expression language.
 *
 * Node forms:
 *   null | true/false | number | "string"            literals
 *   {"state":"a.b"}                                   read pre-event state data
 *   {"event":"a.b"} / {"eventType": true}             read event payload / type
 *   {"op":"and|or", "args":[...]}                     logical
 *   {"op":"not", "arg":X}
 *   {"op":"eq|ne|lt|le|gt|ge", "left":X, "right":Y}   comparison
 *   {"op":"add|sub|mul|div|mod", "left":X, "right":Y} arithmetic
 */
public final class Expr {
    private Expr() {
    }

    public static Object eval(Object node, Map<String, Object> data, Map<String, Object> event) {
        if (node == null || node instanceof Boolean || node instanceof Number || node instanceof String) {
            return node;
        }
        if (!(node instanceof Map)) {
            throw new IllegalArgumentException("表达式必须是标量或对象");
        }
        Map<?, ?> map = (Map<?, ?>) node;
        if (map.containsKey("state")) {
            return readPath(data, String.valueOf(map.get("state")));
        }
        if (map.containsKey("event")) {
            return readPath(event, String.valueOf(map.get("event")));
        }
        if (map.containsKey("eventType")) {
            return event.get("type");
        }
        Object opValue = map.get("op");
        if (!(opValue instanceof String)) {
            throw new IllegalArgumentException("表达式对象必须包含 op/state/event");
        }
        String op = (String) opValue;
        return switch (op) {
            case "and" -> {
                boolean result = true;
                for (Object arg : argsOf(map)) {
                    result = truthy(eval(arg, data, event));
                    if (!result) {
                        break;
                    }
                }
                yield result;
            }
            case "or" -> {
                boolean result = false;
                for (Object arg : argsOf(map)) {
                    result = truthy(eval(arg, data, event));
                    if (result) {
                        break;
                    }
                }
                yield result;
            }
            case "not" -> !truthy(eval(requiredArg(map, "arg"), data, event));
            case "eq" -> compare(map, data, event) == 0;
            case "ne" -> compare(map, data, event) != 0;
            case "lt" -> compare(map, data, event) < 0;
            case "le" -> compare(map, data, event) <= 0;
            case "gt" -> compare(map, data, event) > 0;
            case "ge" -> compare(map, data, event) >= 0;
            case "add", "sub", "mul", "div", "mod" -> arithmetic(op, map, data, event);
            default -> throw new IllegalArgumentException("未知表达式操作: " + op);
        };
    }

    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0.0;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        return true;
    }

    public static long asLong(Object value, String what) {
        if (value instanceof Number) {
            double doubleValue = ((Number) value).doubleValue();
            if (Math.rint(doubleValue) != doubleValue) {
                throw new IllegalStateException(what + " 必须是整数");
            }
            return ((Number) value).longValue();
        }
        throw new IllegalStateException(what + " 必须是数字");
    }

    // -------------------------------------------------------------- internals

    static void validate(Object node, String where) {
        if (node == null || node instanceof Boolean || node instanceof Number
                || node instanceof String) {
            return;
        }
        if (!(node instanceof Map)) {
            throw new IllegalArgumentException(where + ": 表达式必须是标量或对象");
        }
        Map<?, ?> map = (Map<?, ?>) node;
        if (map.containsKey("state") || map.containsKey("event")) {
            Object path = map.containsKey("state") ? map.get("state") : map.get("event");
            if (!(path instanceof String)) {
                throw new IllegalArgumentException(where + ": state/event 路径必须是字符串");
            }
            return;
        }
        if (map.containsKey("eventType")) {
            return;
        }
        Object opValue = map.get("op");
        if (!(opValue instanceof String)) {
            throw new IllegalArgumentException(where + ": 表达式对象必须包含 op");
        }
        String op = (String) opValue;
        switch (op) {
            case "and", "or" -> {
                Object args = map.get("args");
                if (!(args instanceof List) || ((List<?>) args).isEmpty()) {
                    throw new IllegalArgumentException(where + ": " + op + " 需要非空 args");
                }
                int i = 0;
                for (Object arg : (List<?>) args) {
                    validate(arg, where + ".args[" + i++ + "]");
                }
            }
            case "not" -> validate(requiredArg(map, "arg"), where + ".arg");
            case "eq", "ne", "lt", "le", "gt", "ge", "add", "sub", "mul", "div", "mod" -> {
                validate(map.get("left"), where + ".left");
                validate(map.get("right"), where + ".right");
            }
            default -> throw new IllegalArgumentException(where + ": 未知表达式操作 " + op);
        }
    }

    private static List<?> argsOf(Map<?, ?> map) {
        Object args = map.get("args");
        if (!(args instanceof List)) {
            throw new IllegalStateException("逻辑操作缺少 args");
        }
        return (List<?>) args;
    }

    private static Object requiredArg(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null && !map.containsKey(key)) {
            throw new IllegalStateException("缺少参数 " + key);
        }
        return value;
    }

    private static int compare(Map<?, ?> map, Map<String, Object> data, Map<String, Object> event) {
        Object left = eval(map.get("left"), data, event);
        Object right = eval(map.get("right"), data, event);
        return compareValues(left, right);
    }

    static int compareValues(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static Object arithmetic(String op, Map<?, ?> map, Map<String, Object> data,
                                     Map<String, Object> event) {
        Object leftValue = eval(map.get("left"), data, event);
        Object rightValue = eval(map.get("right"), data, event);
        if (!(leftValue instanceof Number) || !(rightValue instanceof Number)) {
            throw new IllegalStateException("算术操作的操作数必须是数字");
        }
        double left = ((Number) leftValue).doubleValue();
        double right = ((Number) rightValue).doubleValue();
        double result = switch (op) {
            case "add" -> left + right;
            case "sub" -> left - right;
            case "mul" -> left * right;
            case "div" -> {
                if (right == 0.0) {
                    throw new ArithmeticException("除数为零");
                }
                yield left / right;
            }
            case "mod" -> {
                if (right == 0.0) {
                    throw new ArithmeticException("模零");
                }
                yield left % right;
            }
            default -> throw new IllegalStateException("未知算术操作 " + op);
        };
        if (!Double.isFinite(result)) {
            throw new ArithmeticException("算术结果不是有限数");
        }
        if (result == Math.rint(result) && result >= Long.MIN_VALUE && result <= Long.MAX_VALUE) {
            return (long) result;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object readPath(Map<String, Object> root, String path) {
        if (path == null || path.isEmpty()) {
            return root;
        }
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
