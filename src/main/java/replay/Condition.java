package replay;

import java.util.Map;

/**
 * Tiny expression evaluator for transition conditions.
 * Reads the pre-event snapshot: state, var.*, payload.*, event.type/source/time.
 * Supports: == != < <= > >= && || ! parentheses, numbers, 'strings', true/false/null.
 */
public final class Condition {
    private Condition() {}

    public interface Ctx {
        Object resolve(String path);
    }

    public static boolean evaluate(String expr, Ctx ctx) {
        Object v = new P(expr, ctx).parseOr();
        return truthy(v);
    }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        return true;
    }

    private static final class P {
        private final String s;
        private final Ctx ctx;
        private int pos;

        P(String s, Ctx ctx) { this.s = s; this.ctx = ctx; }

        Object parseOr() {
            Object left = parseAnd();
            while (true) {
                ws();
                if (match("||")) left = truthy(left) || truthy(parseAnd());
                else return left;
            }
        }

        Object parseAnd() {
            Object left = parseNot();
            while (true) {
                ws();
                if (match("&&")) left = truthy(left) && truthy(parseNot());
                else return left;
            }
        }

        Object parseNot() {
            ws();
            if (match("!")) return !truthy(parseNot());
            return parseComparison();
        }

        Object parseComparison() {
            Object left = parsePrimary();
            ws();
            for (String op : new String[]{"==", "!=", "<=", ">=", "<", ">"}) {
                if (match(op)) {
                    Object right = parsePrimary();
                    return compare(left, right, op);
                }
            }
            return left;
        }

        Object parsePrimary() {
            ws();
            if (pos >= s.length()) throw new IllegalArgumentException("unexpected end of condition");
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Object v = parseOr();
                ws();
                if (pos >= s.length() || s.charAt(pos) != ')')
                    throw new IllegalArgumentException("missing ')' in condition");
                pos++;
                return v;
            }
            if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                pos++;
                while (pos < s.length() && s.charAt(pos) != '\'') sb.append(s.charAt(pos++));
                pos++;
                return sb.toString();
            }
            if (Character.isDigit(c) || c == '-') {
                int start = pos;
                pos++;
                while (pos < s.length() && "0123456789.".indexOf(s.charAt(pos)) >= 0) pos++;
                String num = s.substring(start, pos);
                return num.contains(".") ? (Object) Double.parseDouble(num) : Long.parseLong(num);
            }
            if (Character.isLetter(c) || c == '_') {
                int start = pos;
                while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '.' || s.charAt(pos) == '_')) pos++;
                String word = s.substring(start, pos);
                return switch (word) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    case "null" -> null;
                    default -> ctx.resolve(word);
                };
            }
            throw new IllegalArgumentException("unexpected character '" + c + "' in condition");
        }

        private boolean match(String token) {
            if (s.startsWith(token, pos)) { pos += token.length(); return true; }
            return false;
        }

        private void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }

    @SuppressWarnings("unchecked")
    static Object resolvePath(String path, Map<String, Object> root) {
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String part : parts) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(part);
        }
        return cur;
    }

    private static boolean compare(Object a, Object b, String op) {
        if (a instanceof Number na && b instanceof Number nb) {
            double x = na.doubleValue(), y = nb.doubleValue();
            return switch (op) {
                case "==" -> x == y;
                case "!=" -> x != y;
                case "<" -> x < y;
                case "<=" -> x <= y;
                case ">" -> x > y;
                case ">=" -> x >= y;
                default -> false;
            };
        }
        boolean eq = a == null ? b == null : a.equals(b);
        return switch (op) {
            case "==" -> eq;
            case "!=" -> !eq;
            default -> throw new IllegalArgumentException("operator " + op + " needs numbers");
        };
    }
}
