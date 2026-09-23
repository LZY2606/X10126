package replay.core;

import java.util.List;
import java.util.Map;
import replay.json.Json;

/**
 * Conditions are plain JSON objects evaluated against the pre-event snapshot:
 *   {"var": "balance", "op": "ge", "value": 100}
 *   {"event": "amount", "op": "gt", "value": 0}
 *   {"and": [c1, c2]} / {"or": [...]} / {"not": c}
 * Ops: eq ne gt ge lt le contains exists.
 */
public final class Condition {
    private Condition() {
    }

    public static boolean eval(Object node, Map<String, Object> vars, Map<String, Object> payload) {
        if (node == null) {
            return true;
        }
        Map<String, Object> cond = Json.obj(node, "condition");
        if (cond.containsKey("and")) {
            for (Object child : Json.arr(cond.get("and"), "and")) {
                if (!eval(child, vars, payload)) {
                    return false;
                }
            }
            return true;
        }
        if (cond.containsKey("or")) {
            for (Object child : Json.arr(cond.get("or"), "or")) {
                if (eval(child, vars, payload)) {
                    return true;
                }
            }
            return false;
        }
        if (cond.containsKey("not")) {
            return !eval(cond.get("not"), vars, payload);
        }
        String op = cond.get("op") instanceof String s ? s : "eq";
        boolean hasVar = cond.containsKey("var");
        boolean hasEvent = cond.containsKey("event");
        if (!hasVar && !hasEvent) {
            throw new IllegalArgumentException("condition needs 'var' or 'event': " + Json.write(cond));
        }
        String key = hasVar ? Json.str(cond, "var") : Json.str(cond, "event");
        Map<String, Object> scope = hasVar ? vars : payload;
        Object actual = scope.get(key);
        if ("exists".equals(op)) {
            return actual != null;
        }
        Object expected = cond.get("value");
        return compare(actual, expected, op);
    }

    @SuppressWarnings("unchecked")
    private static boolean compare(Object actual, Object expected, String op) {
        switch (op) {
            case "eq":
                return looseEquals(actual, expected);
            case "ne":
                return !looseEquals(actual, expected);
            case "contains":
                if (actual instanceof String s && expected instanceof String sub) {
                    return s.contains(sub);
                }
                if (actual instanceof List<?> list) {
                    for (Object item : list) {
                        if (looseEquals(item, expected)) {
                            return true;
                        }
                    }
                }
                return false;
            case "gt":
                return ordering(actual, expected) > 0;
            case "ge":
                return ordering(actual, expected) >= 0;
            case "lt":
                return ordering(actual, expected) < 0;
            case "le":
                return ordering(actual, expected) <= 0;
            default:
                throw new IllegalArgumentException("unknown condition op: " + op);
        }
    }

    private static boolean looseEquals(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }
        return a == null ? b == null : a.equals(b);
    }

    private static int ordering(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.compareTo(sb);
        }
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return ba.compareTo(bb);
        }
        throw new IllegalArgumentException(
                "cannot order " + a + " vs " + b);
    }
}
