package gsb.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极小表达式 / 动作语言。
 *
 * 值表达式（数组形式，第一个元素是操作符）：
 *   ["lit", 任意常量]
 *   ["get", "路径"]                 读状态变量，如 count / user.name；不存在 -> null
 *   ["event"]                       当前事件 {id,type,time,priority,seq,source,payload}
 *   ["payload", "路径"]             当前事件 payload 字段
 *   ["add"|"sub"|"mul"|"div"|"mod", a, b]
 *   ["neg", a]
 *   ["eq"|"ne"|"lt"|"le"|"gt"|"ge", a, b]
 *   ["and"|"or", ...]
 *   ["not", a]
 *   ["if", cond, then, else]
 *   ["concat", ...]
 *   ["random", bound]               消耗一次确定性随机数，返回 [0,bound) 的 long
 * 裸字符串以 $. 开头视为 get 路径（如 "$.count"），其他按字符串常量处理。
 *
 * 条件表达式值为布尔；缺失条件恒真。
 *
 * 动作：
 *   {"op":"set", "path":"x", "value": <expr>}
 *   {"op":"output", "channel":"...", "value": <expr>}
 *   {"op":"emit", "type":"...", "payload":{k:<expr>}, "delay":n}
 *   {"op":"fail", "when":<cond>, "message":"..."}
 */
public final class Language {
    private Language() {
    }

    public static final class EvalException extends RuntimeException {
        public EvalException(String m) {
            super(m);
        }
    }

    public static final class ActionResult {
        public final List<Map<String, Object>> outputs = new ArrayList<>();
        public final List<Map<String, Object>> internals = new ArrayList<>();
    }

    /** 计算布尔条件；expr == null 视为恒真。 */
    public static boolean condition(Object expr, Map<String, Object> state, Map<String, Object> event, Rng rng) {
        if (expr == null) {
            return true;
        }
        Object v = value(expr, state, event, rng);
        return truthy(v);
    }

    public static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() != 0.0;
        }
        if (v instanceof String) {
            return !((String) v).isEmpty();
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static Object value(Object expr, Map<String, Object> state, Map<String, Object> event, Rng rng) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof String) {
            String s = (String) expr;
            if (s.startsWith("$.")) {
                return getPath(state, s.substring(2));
            }
            return s;
        }
        if (expr instanceof Boolean || expr instanceof Number) {
            return expr;
        }
        if (!(expr instanceof List)) {
            throw new EvalException("expression must be an array or literal");
        }
        List<Object> e = (List<Object>) expr;
        if (e.isEmpty()) {
            throw new EvalException("empty expression");
        }
        String op = (String) e.get(0);
        switch (op) {
            case "lit":
                return e.size() > 1 ? e.get(1) : null;
            case "get":
                return getPath(state, asString(arg(e, 1)));
            case "event":
                return event;
            case "payload": {
                Object p = event.get("payload");
                Map<String, Object> pm = p instanceof Map ? (Map<String, Object>) p : new LinkedHashMap<>();
                return getPath(pm, asString(arg(e, 1)));
            }
            case "add":
                return num(arg(e, 1), state, event, rng) + num(arg(e, 2), state, event, rng);
            case "sub":
                return num(arg(e, 1), state, event, rng) - num(arg(e, 2), state, event, rng);
            case "mul":
                return num(arg(e, 1), state, event, rng) * num(arg(e, 2), state, event, rng);
            case "div": {
                double b = num(arg(e, 2), state, event, rng);
                if (b == 0.0) {
                    throw new EvalException("division by zero");
                }
                return num(arg(e, 1), state, event, rng) / b;
            }
            case "mod": {
                long b = (long) num(arg(e, 2), state, event, rng);
                if (b == 0) {
                    throw new EvalException("modulo by zero");
                }
                return (long) num(arg(e, 1), state, event, rng) % b;
            }
            case "neg":
                return -num(arg(e, 1), state, event, rng);
            case "eq":
                return equalsVal(value(arg(e, 1), state, event, rng), value(arg(e, 2), state, event, rng));
            case "ne":
                return !equalsVal(value(arg(e, 1), state, event, rng), value(arg(e, 2), state, event, rng));
            case "lt":
                return num(arg(e, 1), state, event, rng) < num(arg(e, 2), state, event, rng);
            case "le":
                return num(arg(e, 1), state, event, rng) <= num(arg(e, 2), state, event, rng);
            case "gt":
                return num(arg(e, 1), state, event, rng) > num(arg(e, 2), state, event, rng);
            case "ge":
                return num(arg(e, 1), state, event, rng) >= num(arg(e, 2), state, event, rng);
            case "and": {
                for (int i = 1; i < e.size(); i++) {
                    if (!truthy(value(e.get(i), state, event, rng))) {
                        return false;
                    }
                }
                return true;
            }
            case "or": {
                for (int i = 1; i < e.size(); i++) {
                    if (truthy(value(e.get(i), state, event, rng))) {
                        return true;
                    }
                }
                return false;
            }
            case "not":
                return !truthy(value(arg(e, 1), state, event, rng));
            case "if":
                return truthy(value(arg(e, 1), state, event, rng))
                        ? value(arg(e, 2), state, event, rng)
                        : value(arg(e, 3), state, event, rng);
            case "concat": {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < e.size(); i++) {
                    sb.append(stringVal(value(e.get(i), state, event, rng)));
                }
                return sb.toString();
            }
            case "random": {
                long bound = (long) num(arg(e, 1), state, event, rng);
                return rng.nextInt(bound);
            }
            default:
                throw new EvalException("unknown operator: " + op);
        }
    }

    private static Object arg(List<Object> e, int i) {
        if (i >= e.size()) {
            throw new EvalException("missing argument " + i + " for '" + e.get(0) + "'");
        }
        return e.get(i);
    }

    private static double num(Object expr, Map<String, Object> state, Map<String, Object> event, Rng rng) {
        Object v = value(expr, state, event, rng);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble((String) v);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new EvalException("expected number, got: " + typeName(v));
    }

    private static String stringVal(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d)) {
                return String.valueOf((long) d);
            }
        }
        return String.valueOf(v);
    }

    private static String asString(Object v) {
        if (v instanceof String) {
            String s = (String) v;
            return s.startsWith("$.") ? s.substring(2) : s;
        }
        return stringVal(v);
    }

    private static String typeName(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    static boolean equalsVal(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a == null ? b == null : a.equals(b);
    }

    @SuppressWarnings("unchecked")
    static Object getPath(Map<String, Object> root, String path) {
        Object cur = root;
        if (path.isEmpty()) {
            return cur;
        }
        for (String part : path.split("\\.")) {
            if (cur instanceof Map) {
                cur = ((Map<String, Object>) cur).get(part);
            } else {
                return null;
            }
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    static void setPath(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                cur.put(parts[i], next);
            }
            cur = (Map<String, Object>) next;
        }
        cur.put(parts[parts.length - 1], value);
    }

    /** 执行动作；任何动作抛错都由调用方整体回滚。 */
    @SuppressWarnings("unchecked")
    public static void execute(Map<String, Object> action, Map<String, Object> state,
                               Map<String, Object> event, Rng rng, ActionResult out, long eventTime) {
        String op = (String) action.get("op");
        if (op == null) {
            throw new EvalException("action requires op");
        }
        switch (op) {
            case "set": {
                String path = (String) action.get("path");
                if (path == null) {
                    throw new EvalException("set requires path");
                }
                Object v = value(action.get("value"), state, event, rng);
                setPath(state, path, v);
                return;
            }
            case "output": {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("channel", action.getOrDefault("channel", "default"));
                rec.put("value", value(action.get("value"), state, event, rng));
                out.outputs.add(rec);
                return;
            }
            case "emit": {
                Object when = action.get("when");
                if (when != null && !condition(when, state, event, rng)) {
                    return;
                }
                Object type = action.get("type");
                if (!(type instanceof String)) {
                    throw new EvalException("emit requires type");
                }
                long delay = Json.asLong(action.get("delay"), 0L);
                Map<String, Object> payload = new LinkedHashMap<>();
                Object ps = action.get("payload");
                if (ps instanceof Map) {
                    for (Map.Entry<String, Object> e : ((Map<String, Object>) ps).entrySet()) {
                        payload.put(e.getKey(), value(e.getValue(), state, event, rng));
                    }
                }
                Map<String, Object> ie = new LinkedHashMap<>();
                ie.put("type", type);
                ie.put("payload", payload);
                ie.put("time", eventTime + delay);
                out.internals.add(ie);
                return;
            }
            case "fail": {
                Object when = action.get("when");
                boolean bad = when == null || condition(when, state, event, rng);
                if (bad) {
                    throw new EvalException(String.valueOf(action.getOrDefault("message", "action failed")));
                }
                return;
            }
            default:
                throw new EvalException("unknown action op: " + op);
        }
    }
}
