package replay.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny deterministic expression evaluator used for transition conditions and action values.
 * Supports: numbers, 'strings', true/false/null, identifiers (dotted), == != < <= > >=,
 * && || ! + - * / %, parentheses, and function calls provided by the context (e.g. rand()).
 */
public final class Expr {
    private Expr() {}

    public interface Ctx {
        /** Resolve a dotted identifier such as "count" or "event.payload.code". */
        Object resolve(String name);
        /** Call a built-in function; throws ExprException for unknown names. */
        Object call(String name, List<Object> args);
    }

    public static final class ExprException extends RuntimeException {
        public ExprException(String msg) { super(msg); }
    }

    public static Object eval(String src, Ctx ctx) {
        Parser p = new Parser(src, ctx);
        Object v = p.parseOr();
        p.skipWs();
        if (!p.atEnd()) throw new ExprException("unexpected trailing input in expression: " + src);
        return v;
    }

    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    // ---------- parser ----------

    private static final class Parser {
        final String s;
        final Ctx ctx;
        int pos;
        Parser(String s, Ctx ctx) { this.s = s; this.ctx = ctx; }
        boolean atEnd() { return pos >= s.length(); }
        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
        boolean atEndOrWs() { skipWs(); return atEnd(); }
        boolean tryMatch(String op) {
            skipWs();
            if (s.startsWith(op, pos)) {
                // avoid matching "!" when "!=" etc. handled by ordering of calls
                pos += op.length();
                return true;
            }
            return false;
        }
        char peek() {
            if (atEnd()) throw new ExprException("unexpected end of expression");
            return s.charAt(pos);
        }

        Object parseOr() {
            Object left = parseAnd();
            while (true) {
                if (tryMatch("||")) left = truthy(left) || truthy(parseAnd());
                else return left;
            }
        }
        Object parseAnd() {
            Object left = parseEquality();
            while (true) {
                if (tryMatch("&&")) left = truthy(left) && truthy(parseEquality());
                else return left;
            }
        }
        Object parseEquality() {
            Object left = parseRelational();
            while (true) {
                if (tryMatch("==")) left = valuesEqual(left, parseRelational());
                else if (tryMatch("!=")) left = !valuesEqual(left, parseRelational());
                else return left;
            }
        }
        Object parseRelational() {
            Object left = parseAdditive();
            while (true) {
                if (tryMatch("<=")) left = compare(left, parseAdditive()) <= 0;
                else if (tryMatch(">=")) left = compare(left, parseAdditive()) >= 0;
                else if (tryMatch("<")) left = compare(left, parseAdditive()) < 0;
                else if (tryMatch(">")) left = compare(left, parseAdditive()) > 0;
                else return left;
            }
        }
        Object parseAdditive() {
            Object left = parseMultiplicative();
            while (true) {
                if (tryMatch("+")) {
                    Object right = parseMultiplicative();
                    if (left instanceof String || right instanceof String) left = str(left) + str(right);
                    else left = num(left, right, '+');
                } else if (tryMatch("-")) left = num(left, parseMultiplicative(), '-');
                else return left;
            }
        }
        Object parseMultiplicative() {
            Object left = parseUnary();
            while (true) {
                if (tryMatch("*")) left = num(left, parseUnary(), '*');
                else if (tryMatch("/")) left = num(left, parseUnary(), '/');
                else if (tryMatch("%")) left = num(left, parseUnary(), '%');
                else return left;
            }
        }
        Object parseUnary() {
            skipWs();
            if (tryMatch("!")) return !truthy(parseUnary());
            if (tryMatch("-")) return negate(parseUnary());
            return parsePrimary();
        }
        Object parsePrimary() {
            skipWs();
            char c = peek();
            if (c == '(') {
                pos++;
                Object v = parseOr();
                skipWs();
                if (atEnd() || s.charAt(pos) != ')') throw new ExprException("missing ')'");
                pos++;
                return v;
            }
            if (c == '\'' || c == '"') return parseString(c);
            if (Character.isDigit(c) || c == '.') return parseNumber();
            if (Character.isLetter(c) || c == '_') return parseIdentOrCall();
            throw new ExprException("unexpected character '" + c + "' in expression");
        }
        String parseString(char quote) {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw new ExprException("unterminated string");
                char c = s.charAt(pos++);
                if (c == quote) break;
                if (c == '\\' && !atEnd()) {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }
        Object parseNumber() {
            int start = pos;
            boolean dbl = false;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) pos++;
                else if (c == '.') { dbl = true; pos++; }
                else break;
            }
            String num = s.substring(start, pos);
            try {
                return dbl ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new ExprException("bad number '" + num + "'");
            }
        }
        Object parseIdentOrCall() {
            int start = pos;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '.') pos++;
                else break;
            }
            String name = s.substring(start, pos);
            switch (name) {
                case "true": return Boolean.TRUE;
                case "false": return Boolean.FALSE;
                case "null": return null;
                default: break;
            }
            skipWs();
            if (!atEnd() && s.charAt(pos) == '(') {
                pos++;
                List<Object> args = new ArrayList<>();
                skipWs();
                if (!atEnd() && s.charAt(pos) == ')') { pos++; return ctx.call(name, args); }
                while (true) {
                    args.add(parseOr());
                    skipWs();
                    if (atEnd()) throw new ExprException("missing ')' after arguments");
                    char c = s.charAt(pos++);
                    if (c == ')') break;
                    if (c != ',') throw new ExprException("expected ',' in arguments");
                }
                return ctx.call(name, args);
            }
            return ctx.resolve(name);
        }
    }

    // ---------- value semantics ----------

    static String str(Object v) {
        if (v == null) return "null";
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        }
        return v.toString();
    }

    static boolean valuesEqual(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue()) == 0;
        }
        return a.equals(b);
    }

    static int compare(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof String && b instanceof String) return ((String) a).compareTo((String) b);
        throw new ExprException("cannot compare " + str(a) + " and " + str(b));
    }

    static Object negate(Object v) {
        if (v instanceof Double || v instanceof Float) return -((Number) v).doubleValue();
        if (v instanceof Number) return -((Number) v).longValue();
        throw new ExprException("cannot negate " + str(v));
    }

    static Object num(Object a, Object b, char op) {
        if (!(a instanceof Number) || !(b instanceof Number)) {
            throw new ExprException("operator '" + op + "' needs numbers, got " + str(a) + " and " + str(b));
        }
        boolean dbl = a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float;
        double da = ((Number) a).doubleValue();
        double db = ((Number) b).doubleValue();
        if (dbl || op == '/') {
            switch (op) {
                case '+': return da + db;
                case '-': return da - db;
                case '*': return da * db;
                case '/': return da / db;
                case '%': return da % db;
                default: throw new ExprException("bad operator");
            }
        }
        long la = ((Number) a).longValue();
        long lb = ((Number) b).longValue();
        switch (op) {
            case '+': return la + lb;
            case '-': return la - lb;
            case '*': return la * lb;
            case '%':
                if (lb == 0) throw new ExprException("modulo by zero");
                return la % lb;
            default: throw new ExprException("bad operator");
        }
    }
}
