package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tiny deterministic condition language: clauses joined by "&&", each of the form
 * "operand op operand" where op is one of == != > < >= <= and an operand is a
 * reference (payload.x, vars.x, state) or a literal (number, 'string', true/false/null).
 */
public final class Condition {
    private Condition() {}

    public static boolean eval(String expr, Map<String, Object> event, Snapshot snap) {
        if (expr == null || expr.trim().isEmpty()) return true;
        for (String clause : expr.split("&&")) {
            if (!evalClause(clause.trim(), event, snap)) return false;
        }
        return true;
    }

    private static boolean evalClause(String clause, Map<String, Object> event, Snapshot snap) {
        String[] ops = {"==", "!=", ">=", "<=", ">", "<"};
        for (String op : ops) {
            int idx = indexOfOp(clause, op);
            if (idx < 0) continue;
            Object left = operand(clause.substring(0, idx).trim(), event, snap);
            Object right = operand(clause.substring(idx + op.length()).trim(), event, snap);
            return compare(left, right, op);
        }
        // bare operand: truthiness
        Object v = operand(clause, event, snap);
        if (v instanceof Boolean) return (Boolean) v;
        return v != null;
    }

    private static int indexOfOp(String s, String op) {
        // avoid matching '>' inside '>=' handled by ordering of ops array
        int idx = s.indexOf(op);
        if (idx < 0) return -1;
        if ((">".equals(op) || "<".equals(op)) && idx + 1 < s.length() && s.charAt(idx + 1) == '=') return -1;
        return idx;
    }

    @SuppressWarnings("unchecked")
    static Object operand(String token, Map<String, Object> event, Snapshot snap) {
        if (token.isEmpty()) throw new IllegalArgumentException("Empty operand in condition");
        if (token.equals("state")) return snap.state;
        if (token.startsWith("payload.")) {
            Object payload = event.get("payload");
            return payload instanceof Map ? ((Map<String, Object>) payload).get(token.substring(8)) : null;
        }
        if (token.startsWith("vars.")) return snap.vars.get(token.substring(5));
        if (token.equals("true")) return Boolean.TRUE;
        if (token.equals("false")) return Boolean.FALSE;
        if (token.equals("null")) return null;
        if (token.startsWith("'") && token.endsWith("'") && token.length() >= 2)
            return token.substring(1, token.length() - 1);
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException ignored) {}
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException ignored) {}
        return token; // treat as bare string
    }

    static boolean compare(Object a, Object b, String op) {
        switch (op) {
            case "==": return valuesEqual(a, b);
            case "!=": return !valuesEqual(a, b);
        }
        if (a instanceof Number && b instanceof Number) {
            double x = ((Number) a).doubleValue(), y = ((Number) b).doubleValue();
            switch (op) {
                case ">": return x > y;
                case "<": return x < y;
                case ">=": return x >= y;
                case "<=": return x <= y;
            }
        }
        if (a instanceof Comparable && b instanceof Comparable) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            int c = ((Comparable) a).compareTo(b);
            switch (op) {
                case ">": return c > 0;
                case "<": return c < 0;
                case ">=": return c >= 0;
                case "<=": return c <= 0;
            }
        }
        return false;
    }

    static boolean valuesEqual(Object a, Object b) {
        if (a instanceof Number && b instanceof Number)
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        return java.util.Objects.equals(a, b);
    }

    /** Resolve an action value: strings like "payload.x"/"vars.x" are references, anything else literal. */
    static Object resolve(Object value, Map<String, Object> event, Snapshot snap) {
        if (value instanceof String) {
            String s = (String) value;
            if (s.startsWith("payload.") || s.startsWith("vars.") || s.equals("state"))
                return operand(s, event, snap);
            return s;
        }
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) value;
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) out.put(e.getKey(), resolve(e.getValue(), event, snap));
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<?>) value) out.add(resolve(o, event, snap));
            return out;
        }
        return value;
    }
}
