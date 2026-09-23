package replay;

import java.util.Map;

/**
 * Tiny deterministic expression language for conditions and action values.
 * Supports numbers, strings, booleans, + - * / %, comparisons, == !=, && || !,
 * identifiers (state variables), {@code state} (current state name) and
 * {@code payload.key} / {@code payload['key']} for the event payload.
 */
public final class Expr {
    private Expr() {}

    public static Object eval(String expression, Map<String, Object> vars,
                              String currentState, Map<String, Object> payload) {
        Parser p = new Parser(expression);
        Object v = p.evalOr(new Context(vars, currentState, payload));
        p.end();
        return v;
    }

    public static boolean isTrue(String condition, Map<String, Object> vars,
                                 String currentState, Map<String, Object> payload) {
        Object v = eval(condition, vars, currentState, payload);
        return truth(v);
    }

    static boolean truth(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) return !s.isEmpty();
        if (v == null) return false;
        return true;
    }

    record Context(Map<String, Object> vars, String state, Map<String, Object> payload) {}

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        void ws() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }

        void end() {
            ws();
            if (pos != s.length()) throw new IllegalArgumentException("Unexpected character at " + pos);
        }

        Object evalOr(Context ctx) {
            Object left = evalAnd(ctx);
            while (true) {
                ws();
                if (match("||")) left = truth(evalAnd(ctx)) || truth(left);
                else return left;
            }
        }

        Object evalAnd(Context ctx) {
            Object left = evalNot(ctx);
            while (true) {
                ws();
                if (match("&&")) left = truth(evalNot(ctx)) && truth(left);
                else return left;
            }
        }

        Object evalNot(Context ctx) {
            ws();
            if (match("!")) return !truth(evalNot(ctx));
            return evalEq(ctx);
        }

        Object evalEq(Context ctx) {
            Object left = evalRel(ctx);
            while (true) {
                ws();
                if (match("==")) left = equals(left, evalRel(ctx));
                else if (match("!=")) left = !equals(left, evalRel(ctx));
                else return left;
            }
        }

        Object evalRel(Context ctx) {
            Object left = evalAdd(ctx);
            while (true) {
                ws();
                if (match("<=")) left = compare(left, evalAdd(ctx)) <= 0;
                else if (match(">=")) left = compare(left, evalAdd(ctx)) >= 0;
                else if (match("<")) left = compare(left, evalAdd(ctx)) < 0;
                else if (match(">")) left = compare(left, evalAdd(ctx)) > 0;
                else return left;
            }
        }

        Object evalAdd(Context ctx) {
            Object left = evalMul(ctx);
            while (true) {
                ws();
                if (match("+")) {
                    Object right = evalMul(ctx);
                    if (left instanceof String || right instanceof String) {
                        left = String.valueOf(left) + String.valueOf(right);
                    } else {
                        left = num(left) + num(right);
                    }
                } else if (match("-")) {
                    left = num(left) - num(evalMul(ctx));
                } else return left;
            }
        }

        Object evalMul(Context ctx) {
            Object left = evalUnary(ctx);
            while (true) {
                ws();
                if (match("*")) left = num(left) * num(evalUnary(ctx));
                else if (match("/")) {
                    double d = num(evalUnary(ctx));
                    if (d == 0) throw new IllegalStateException("Division by zero");
                    left = num(left) / d;
                } else if (match("%")) {
                    double d = num(evalUnary(ctx));
                    if (d == 0) throw new IllegalStateException("Modulo by zero");
                    left = num(left) % d;
                } else return left;
            }
        }

        Object evalUnary(Context ctx) {
            ws();
            if (match("-")) return -num(evalUnary(ctx));
            return evalPrimary(ctx);
        }

        Object evalPrimary(Context ctx) {
            ws();
            if (match("(")) {
                Object v = evalOr(ctx);
                ws();
                if (!match(")")) throw new IllegalArgumentException("Expected ')' at " + pos);
                return v;
            }
            char c = s.charAt(pos);
            if (c == '"' || c == '\'') return readString(c);
            if (Character.isDigit(c)) return readNumber();
            if (Character.isLetter(c) || c == '_') {
                String name = readIdent();
                Object value;
                if ("true".equals(name)) return Boolean.TRUE;
                if ("false".equals(name)) return Boolean.FALSE;
                if ("null".equals(name)) return null;
                if ("state".equals(name)) value = ctx.state;
                else if ("payload".equals(name)) value = ctx.payload;
                else value = ctx.vars.get(name);
                while (true) {
                    ws();
                    if (pos < s.length() && s.charAt(pos) == '.') {
                        pos++;
                        ws();
                        String part = readIdent();
                        value = lookup(value, part);
                    } else if (pos < s.length() && s.charAt(pos) == '[') {
                        pos++;
                        ws();
                        Object key = evalOr(ctx);
                        ws();
                        if (!match("]")) throw new IllegalArgumentException("Expected ']' at " + pos);
                        value = lookup(value, String.valueOf(key));
                    } else return value;
                }
            }
            throw new IllegalArgumentException("Unexpected character '" + c + "' at " + pos);
        }

        @SuppressWarnings("unchecked")
        private static Object lookup(Object container, String key) {
            if (container instanceof Map<?, ?> m) return ((Map<String, Object>) m).get(key);
            throw new IllegalStateException("Cannot read field '" + key + "' of " + container);
        }

        private static boolean equals(Object a, Object b) {
            if (a instanceof Number && b instanceof Number) return Double.compare(num(a), num(b)) == 0;
            return String.valueOf(a).equals(String.valueOf(b));
        }

        private static int compare(Object a, Object b) {
            if (!(a instanceof Number) || !(b instanceof Number)) {
                throw new IllegalStateException("Cannot compare " + typeName(a) + " with " + typeName(b));
            }
            return Double.compare(num(a), num(b));
        }

        private static String typeName(Object v) { return v == null ? "null" : v.getClass().getSimpleName(); }

        static double num(Object v) {
            if (v instanceof Number n) return n.doubleValue();
            throw new IllegalStateException("Expected number, got " + (v == null ? "null" : v));
        }

        boolean match(String token) {
            if (s.startsWith(token, pos)) { pos += token.length(); return true; }
            return false;
        }

        double readNumber() {
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.'
                    || s.charAt(pos) == 'e' || s.charAt(pos) == 'E' || s.charAt(pos) == '+'
                    || s.charAt(pos) == '-')) {
                pos++;
            }
            return Double.parseDouble(s.substring(start, pos));
        }

        String readString(char quote) {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == quote) return sb.toString();
                if (c == '\\' && pos < s.length()) sb.append(s.charAt(pos++));
                else sb.append(c);
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        String readIdent() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_') pos++;
                else break;
            }
            return s.substring(start, pos);
        }
    }

    /** Replace {@code ${expr}} placeholders in a template. */
    public static String interpolate(String template, Map<String, Object> vars,
                                     String currentState, Map<String, Object> payload) {
        if (template == null || template.indexOf("${") < 0) return template;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("${", i);
            if (start < 0) { sb.append(template, i, template.length()); break; }
            sb.append(template, i, start);
            int end = template.indexOf('}', start + 2);
            if (end < 0) throw new IllegalArgumentException("Unterminated ${...}");
            Object v = eval(template.substring(start + 2, end), vars, currentState, payload);
            sb.append(v == null ? "" : String.valueOf(v));
            i = end + 1;
        }
        return sb;
    }
}
