package replay;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structured conditions, always evaluated against the pre-event snapshot.
 * Forms:
 *   {"var":"count","op":">=","value":3}
 *   {"var":"state","op":"==","value":"IDLE"}   (reserved var "state" = current state)
 *   {"and":[c1,c2]}, {"or":[c1,c2]}, {"not":c}
 *   {"payload":"key","op":"==","value":1}      (reads the current event payload)
 */
public final class Condition {
    private Condition() {}

    public static boolean eval(Object cond, SnapshotView view) {
        if (cond == null) return true;
        Map<String, Object> c = Json.obj(cond);
        if (c.containsKey("and")) {
            for (Object sub : Json.arr(c.get("and"))) if (!eval(sub, view)) return false;
            return true;
        }
        if (c.containsKey("or")) {
            for (Object sub : Json.arr(c.get("or"))) if (eval(sub, view)) return true;
            return false;
        }
        if (c.containsKey("not")) {
            return !eval(c.get("not"), view);
        }
        Object actual;
        if (c.containsKey("var")) {
            actual = view.variable(Json.str(c.get("var")));
        } else if (c.containsKey("payload")) {
            actual = view.payload(Json.str(c.get("payload")));
        } else {
            throw new IllegalArgumentException("condition needs 'var' or 'payload': " + Json.canonical(c));
        }
        String op = Json.str(c.getOrDefault("op", "=="));
        Object expected = c.get("value");
        return compare(actual, op, expected);
    }

    private static boolean compare(Object actual, String op, Object expected) {
        switch (op) {
            case "exists": return actual != null;
            case "missing": return actual == null;
            case "==": return Objects.equals(normalize(actual), normalize(expected));
            case "!=": return !Objects.equals(normalize(actual), normalize(expected));
            case "<": case "<=": case ">": case ">=": {
                double a = asDouble(actual);
                double b = asDouble(expected);
                switch (op) {
                    case "<": return a < b;
                    case "<=": return a <= b;
                    case ">": return a > b;
                    default: return a >= b;
                }
            }
            default:
                throw new IllegalArgumentException("unknown op: " + op);
        }
    }

    private static Object normalize(Object o) {
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) return (long) d;
            return d;
        }
        return o;
    }

    private static double asDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        throw new IllegalArgumentException("not comparable as number: " + o);
    }

    /** Read-only view of the pre-event snapshot plus the event payload. */
    public interface SnapshotView {
        Object variable(String name);
        Object payload(String key);
    }

    public static SnapshotView view(String state, Map<String, Object> vars, Map<String, Object> payload) {
        return new SnapshotView() {
            public Object variable(String name) {
                if ("state".equals(name)) return state;
                return vars.get(name);
            }
            public Object payload(String key) {
                return payload == null ? null : payload.get(key);
            }
        };
    }

    static {
        // keep List import used for clarity of supported forms
        List.of();
    }
}
