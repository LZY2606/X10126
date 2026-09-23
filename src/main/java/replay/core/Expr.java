package replay.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tiny deterministic expression language used by rule conditions and actions.
 * Values are Long, String or Boolean. Provides variables, arithmetic,
 * comparisons, boolean operators and rand(n) bound to the engine RNG.
 */
public final class Expr {

    public interface Context {
        Object variable(String name);
        long nextRandom(long bound);
    }

    public static Object eval(String src, Context ctx) {
        Parser p = new Parser(src, ctx);
        Object v = p.or();
        p.skipWs();
        if (p.pos != p.s.length()) throw new EvalException("trailing input in expression: " + src);
        return v;
    }

    public static boolean truthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Long l) return l != 0;
        if (v instanceof String s) return !s.isEmpty();
        if (v == null) return false;
        return true;
    }

    public static long asLong(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { /* fall through */ }
        }
        throw new EvalException("not a number: " + v);
    }

    public static String asString(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean b) return b ? "true" : "false";
        return v.toString();
    }

    public static final class EvalException extends RuntimeException {
        public EvalException(String msg) { super(msg); }
    }

    private static final class Parser {
        final String s;
        final Context ctx;
        int pos;

        Parser(String s, Context ctx) { this.s = s; this.ctx = ctx; }

        void skipWs() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }

        boolean eat(String tok) {
            skipWs();
            if (s.startsWith(tok, pos)) {
                int end = pos + tok.length();
                // avoid matching "==" when eating "=" etc. handled by caller order; check ident boundary for words
                if (Character.isLetter(tok.charAt(tok.length() - 1))) {
                    if (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_')) return false;
                }
                pos = end;
                return true;
            }
            return false;
        }

        char peek() { skipWs(); return pos < s.length() ? s.charAt(pos) : '\0'; }

        Object or() {
            Object l = and();
            while (eat("||")) l = truthy(l) || truthy(and());
            return l;
        }

        Object and() {
            Object l = cmp();
            while (eat("&&")) l = truthy(l) && truthy(cmp());
            return l;
        }

        Object cmp() {
            Object l = add();
            skipWs();
            if (eat("==")) return valuesEqual(l, add());
            if (eat("!=")) return !valuesEqual(l, add());
            if (eat("<=")) return asLong(l) <= asLong(add());
            if (eat(">=")) return asLong(l) >= asLong(add());
            if (eat("<")) return asLong(l) < asLong(add());
            if (eat(">")) return asLong(l) > asLong(add());
            return l;
        }

        static boolean valuesEqual(Object a, Object b) {
            if (a == null || b == null) return a == b;
            if (a instanceof Boolean || b instanceof Boolean) return a.equals(b);
            if (a instanceof Long && b instanceof Long) return a.equals(b);
            return asString(a).equals(asString(b));
        }

        Object add() {
            Object l = mul();
            while (true) {
                skipWs();
                if (eat("+")) {
                    Object r = mul();
                    if (l instanceof String || r instanceof String) l = asString(l) + asString(r);
                    else l = asLong(l) + asLong(r);
                } else if (eat("-")) {
                    l = asLong(l) - asLong(mul());
                } else return l;
            }
        }

        Object mul() {
            Object l = unary();
            while (true) {
                skipWs();
                if (eat("*")) l = asLong(l) * asLong(unary());
                else if (eat("/")) {
                    long r = asLong(unary());
                    if (r == 0) throw new EvalException("division by zero");
                    l = asLong(l) / r;
                } else if (eat("%")) {
                    long r = asLong(unary());
                    if (r == 0) throw new EvalException("modulo by zero");
                    l = asLong(l) % r;
                } else return l;
            }
        }

        Object unary() {
            skipWs();
            if (eat("!")) return !truthy(unary());
            if (eat("-")) return -asLong(unary());
            return primary();
        }

        Object primary() {
            skipWs();
            char c = peek();
            if (c == '(') {
                pos++;
                Object v = or();
                skipWs();
                if (peek() != ')') throw new EvalException("missing ')' in: " + s);
                pos++;
                return v;
            }
            if (c == '\'' || c == '"') return string(c);
            if (Character.isDigit(c)) return number();
            if (Character.isLetter(c) || c == '_') {
                String ident = ident();
                skipWs();
                if (peek() == '(') {
                    pos++;
                    List<Object> args = new ArrayList<>();
                    skipWs();
                    if (peek() != ')') {
                        args.add(or());
                        while (eat(",")) args.add(or());
                    }
                    if (peek() != ')') throw new EvalException("missing ')' after args of " + ident);
                    pos++;
                    return function(ident, args);
                }
                return switch (ident) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    default -> ctx.variable(ident);
                };
            }
            throw new EvalException("unexpected character '" + c + "' in: " + s);
        }

        Object function(String name, List<Object> args) {
            if (name.equals("rand")) {
                if (args.size() != 1) throw new EvalException("rand takes 1 argument");
                long bound = asLong(args.get(0));
                if (bound <= 0) throw new EvalException("rand bound must be positive");
                return ctx.nextRandom(bound);
            }
            throw new EvalException("unknown function: " + name);
        }

        String ident() {
            int start = pos;
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) pos++;
            return s.substring(start, pos);
        }

        Object number() {
            int start = pos;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            return Long.parseLong(s.substring(start, pos));
        }

        String string(char quote) {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == quote) return sb.toString();
                if (c == '\\' && pos < s.length()) {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        default -> sb.append(e);
                    }
                } else sb.append(c);
            }
            throw new EvalException("unterminated string in: " + s);
        }
    }

    private Expr() {}
}
