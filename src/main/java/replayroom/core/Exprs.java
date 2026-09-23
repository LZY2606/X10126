package replayroom.core;

import java.util.Map;
import java.util.function.LongSupplier;

/** 条件与赋值表达式求值。支持: 比较(==,!=,<,<=,>,>=)、变量、数字/字符串/布尔字面量、
 *  加减运算、字符串拼接、rand(n) 确定性伪随机。 */
public final class Exprs {
    private Exprs() {
    }

    public static Object operand(String token, Map<String, Object> vars) {
        String t = token.trim();
        if (t.isEmpty()) {
            throw new ActionFailure("空操作数");
        }
        switch (t) {
            case "null":
                return null;
            case "true":
                return Boolean.TRUE;
            case "false":
                return Boolean.FALSE;
            default:
                break;
        }
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1);
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException ignored) {
            // not a long
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException ignored) {
            // not a double
        }
        return vars.get(t);
    }

    public static boolean condition(String cond, Map<String, Object> vars) {
        String c = cond == null ? "" : cond.trim();
        if (c.isEmpty()) {
            return true;
        }
        String[] ops = {"==", "!=", "<=", ">=", "<", ">"};
        for (String op : ops) {
            int idx = c.indexOf(op);
            if (idx > 0) {
                Object left = operand(c.substring(0, idx), vars);
                Object right = operand(c.substring(idx + op.length()), vars);
                return compare(left, right, op);
            }
        }
        return Boolean.TRUE.equals(operand(c, vars));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean compare(Object l, Object r, String op) {
        if (l == null || r == null) {
            boolean eq = l == null && r == null;
            if (op.equals("==")) {
                return eq;
            }
            if (op.equals("!=")) {
                return !eq;
            }
            return false;
        }
        if (l instanceof Number && r instanceof Number) {
            double a = ((Number) l).doubleValue();
            double b = ((Number) r).doubleValue();
            switch (op) {
                case "==": return a == b;
                case "!=": return a != b;
                case "<": return a < b;
                case "<=": return a <= b;
                case ">": return a > b;
                case ">=": return a >= b;
                default: return false;
            }
        }
        if (l instanceof Comparable && l.getClass().isInstance(r)) {
            int cmp = ((Comparable) l).compareTo(r);
            switch (op) {
                case "==": return cmp == 0;
                case "!=": return cmp != 0;
                case "<": return cmp < 0;
                case "<=": return cmp <= 0;
                case ">": return cmp > 0;
                case ">=": return cmp >= 0;
                default: return false;
            }
        }
        boolean eq = l.equals(r);
        if (op.equals("==")) {
            return eq;
        }
        return op.equals("!=") && !eq;
    }

    public static Object expr(String expr, Map<String, Object> vars, LongSupplier rng) {
        String e = expr == null ? "" : expr.trim();
        if (e.isEmpty()) {
            throw new ActionFailure("空表达式");
        }
        if (e.startsWith("rand(") && e.endsWith(")")) {
            long n = Long.parseLong(e.substring(5, e.length() - 1).trim());
            if (n <= 0) {
                throw new ActionFailure("rand(n) 需要 n > 0");
            }
            return Math.floorMod(rng.getAsLong(), n);
        }
        for (int i = 1; i < e.length(); i++) {
            char ch = e.charAt(i);
            if (ch == '+' || ch == '-') {
                Object l = expr(e.substring(0, i), vars, rng);
                Object r = expr(e.substring(i + 1), vars, rng);
                if (ch == '+' && (l instanceof String || r instanceof String)) {
                    return str(l) + str(r);
                }
                if (l instanceof Number && r instanceof Number) {
                    if (l instanceof Double || r instanceof Double) {
                        double a = ((Number) l).doubleValue();
                        double b = ((Number) r).doubleValue();
                        return ch == '+' ? a + b : a - b;
                    }
                    long a = ((Number) l).longValue();
                    long b = ((Number) r).longValue();
                    return ch == '+' ? a + b : a - b;
                }
                throw new ActionFailure("表达式操作数类型不支持: " + e);
            }
        }
        return operand(e, vars);
    }

    private static String str(Object o) {
        return o == null ? "null" : o.toString();
    }
}
