package replay.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small deterministic expression language used by transition conditions
 * and action templates ("$expr").
 *
 * Grammar (precedence low -> high):
 *   or, and, equality, relational, additive, multiplicative, unary, postfix.
 * Root bindings: $state, $vars, $event, $payload, $now, $source, $seq.
 * Functions: randInt(n), defined(path), min(a,b), max(a,b).
 */
public final class Expr {

    private final String text;
    private int pos;

    private Expr(String text) {
        this.text = text;
    }

    public static Object evaluate(String expression, Ctx ctx) {
        if (expression == null || expression.isBlank()) {
            return Boolean.TRUE;
        }
        Expr p = new Expr(expression);
        Object value = p.parseOr(ctx);
        p.skip();
        if (p.pos != p.text.length()) {
            throw p.error("unexpected trailing input");
        }
        return value;
    }

    public static boolean isTrue(String expression, Ctx ctx) {
        return truth(evaluate(expression, ctx));
    }

    /** Recursively resolve {"$expr": "..."} templates inside maps/lists. */
    @SuppressWarnings("unchecked")
    public static Object resolve(Object template, Ctx ctx) {
        if (template instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) template;
            if (map.size() == 1 && map.containsKey("$expr")) {
                return evaluate(String.valueOf(map.get("$expr")), ctx);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                out.put(e.getKey(), resolve(e.getValue(), ctx));
            }
            return out;
        }
        if (template instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) template) {
                out.add(resolve(item, ctx));
            }
            return out;
        }
        if (template instanceof String str && str.startsWith("$expr:")) {
            return evaluate(str.substring(6), ctx);
        }
        return template;
    }

    // ---------- parser ----------

    private Object parseOr(Ctx ctx) {
        Object left = parseAnd(ctx);
        while (match("||")) {
            Object right = parseAnd(ctx);
            left = truth(left) || truth(right);
        }
        return left;
    }

    private Object parseAnd(Ctx ctx) {
        Object left = parseEquality(ctx);
        while (match("&&")) {
            Object right = parseEquality(ctx);
            left = truth(left) && truth(right);
        }
        return left;
    }

    private Object parseEquality(Ctx ctx) {
        Object left = parseRel(ctx);
        while (true) {
            if (match("==")) {
                left = equalsValue(left, parseRel(ctx));
            } else if (match("!=")) {
                left = !equalsValue(left, parseRel(ctx));
            } else {
                return left;
            }
        }
    }

    private Object parseRel(Ctx ctx) {
        Object left = parseAdd(ctx);
        while (true) {
            if (match("<=")) {
                left = compare(left, parseAdd(ctx)) <= 0;
            } else if (match(">=")) {
                left = compare(left, parseAdd(ctx)) >= 0;
            } else if (match("<")) {
                left = compare(left, parseAdd(ctx)) < 0;
            } else if (match(">")) {
                left = compare(left, parseAdd(ctx)) > 0;
            } else {
                return left;
            }
        }
    }

    private Object parseAdd(Ctx ctx) {
        Object left = parseMul(ctx);
        while (true) {
            if (match("+")) {
                Object right = parseMul(ctx);
                if (left instanceof String || right instanceof String) {
                    left = String.valueOf(left) + String.valueOf(right);
                } else {
                    left = num(left) + num(right);
                }
            } else if (match("-")) {
                left = num(left) - num(parseMul(ctx));
            } else {
                return left;
            }
        }
    }

    private Object parseMul(Ctx ctx) {
        Object left = parseUnary(ctx);
        while (true) {
            if (match("*")) {
                left = num(left) * num(parseUnary(ctx));
            } else if (match("/")) {
                double d = num(parseUnary(ctx));
                if (d == 0.0) {
                    throw error("division by zero");
                }
                left = num(left) / d;
            } else if (match("%")) {
                double d = num(parseUnary(ctx));
                if (d == 0.0) {
                    throw error("modulo by zero");
                }
                left = num(left) % d;
            } else {
                return left;
            }
        }
    }

    private Object parseUnary(Ctx ctx) {
        if (match("!")) {
            return !truth(parseUnary(ctx));
        }
        if (match("-")) {
            return -num(parseUnary(ctx));
        }
        return parsePostfix(ctx);
    }

    private Object parsePostfix(Ctx ctx) {
        Object value = parsePrimary(ctx);
        while (true) {
            if (peek() == '.') {
                pos++;
                String key = readIdentifier();
                value = index(value, key);
            } else if (peek() == '[') {
                pos++;
                Object idx = parseOr(ctx);
                expect(']');
                value = index(value, idx);
            } else {
                return value;
            }
        }
    }

    private Object parsePrimary(Ctx ctx) {
        skip();
        char c = peek();
        if (c == '(') {
            pos++;
            Object v = parseOr(ctx);
            expect(')');
            return v;
        }
        if (c == '\'' || c == '"') {
            return readStringLiteral();
        }
        if (Character.isDigit(c)) {
            return readNumber();
        }
        if (Character.isLetter(c) || c == '_' || c == '$') {
            String ident = readIdentifier();
            if (peek() == '(') {
                return callFunction(ident, ctx);
            }
            return rootValue(ident, ctx);
        }
        throw error("expected value");
    }

    private Object callFunction(String name, Ctx ctx) {
        expect('(');
        List<Object> args = new ArrayList<>();
        skip();
        if (peek() != ')') {
            args.add(parseOr(ctx));
            while (match(",")) {
                args.add(parseOr(ctx));
            }
        }
        expect(')');
        return switch (name) {
            case "randInt" -> {
                requireArgCount(name, args, 1);
                long bound = (long) num(args.get(0));
                if (bound <= 0) {
                    throw error("randInt bound must be positive");
                }
                yield ctx.rng.nextLong(bound);
            }
            case "defined" -> {
                requireArgCount(name, args, 1);
                yield args.get(0) != null;
            }
            case "min" -> {
                requireArgCount(name, args, 2);
                yield Math.min(num(args.get(0)), num(args.get(1)));
            }
            case "max" -> {
                requireArgCount(name, args, 2);
                yield Math.max(num(args.get(0)), num(args.get(1)));
            }
            default -> throw error("unknown function: " + name);
        };
    }

    private Object rootValue(String name, Ctx ctx) {
        return switch (name) {
            case "state", "$state" -> ctx.state;
            case "vars", "$vars" -> ctx.vars;
            case "event", "$event" -> ctx.event;
            case "payload", "$payload" -> ctx.event == null ? null : ctx.event.get("payload");
            case "now", "$now" -> ctx.now;
            case "source", "$source" -> ctx.event == null ? null : ctx.event.get("source");
            case "seq", "$seq" -> ctx.event == null ? null : ctx.event.get("seq");
            default -> throw error("unknown binding: " + name);
        };
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private static Object index(Object target, Object key) {
        if (target instanceof Map) {
            return ((Map<String, Object>) target).get(String.valueOf(key));
        }
        if (target instanceof List && key instanceof Number) {
            int i = ((Number) key).intValue();
            List<Object> list = (List<Object>) target;
            return i >= 0 && i < list.size() ? list.get(i) : null;
        }
        return null;
    }

    private static boolean equalsValue(Object a, Object b) {
        if (a instanceof Number || b instanceof Number) {
            if (a == null || b == null || a instanceof Boolean || b instanceof Boolean) {
                return false;
            }
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    private static int compare(Object a, Object b) {
        if (!(a instanceof Number) || !(b instanceof Number)) {
            throw new ExprException("cannot compare non-numeric values");
        }
        return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
    }

    static boolean truth(Object v) {
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

    private static double num(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        throw new ExprException("expected number, got: " + typeName(v));
    }

    private static String typeName(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    private void requireArgCount(String name, List<Object> args, int n) {
        if (args.size() != n) {
            throw error(name + " expects " + n + " argument(s)");
        }
    }

    private boolean match(String token) {
        skip();
        if (text.startsWith(token, pos)) {
            char next = pos + token.length() < text.length() ? text.charAt(pos + token.length()) : ' ';
            if ("<>=!".contains(token) && "<>=!".indexOf(next) >= 0) {
                return false;
            }
            pos += token.length();
            return true;
        }
        return false;
    }

    private void expect(char c) {
        skip();
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private void skip() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private String readIdentifier() {
        skip();
        int start = pos;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') {
                if (c == '.' && start == pos) {
                    break;
                }
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw error("expected identifier");
        }
        return text.substring(start, pos);
    }

    private String readStringLiteral() {
        char quote = text.charAt(pos++);
        StringBuilder sb = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == quote) {
                return sb.toString();
            }
            if (c == '\\' && pos < text.length()) {
                char e = text.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw error("unterminated string");
    }

    private double readNumber() {
        int start = pos;
        while (pos < text.length() && "0123456789.".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        return Double.parseDouble(text.substring(start, pos));
    }

    private ExprException error(String msg) {
        return new ExprException(msg + " (in expression: " + text + ")");
    }

    /** Evaluation context: pre-event snapshot of state/vars plus the current event. */
    public static final class Ctx {
        public final String state;
        public final Map<String, Object> vars;
        public final Map<String, Object> event;
        public final long now;
        public final DetRandom rng;

        public Ctx(String state, Map<String, Object> vars,
                   Map<String, Object> event, long now, DetRandom rng) {
            this.state = state;
            this.vars = vars;
            this.event = event;
            this.now = now;
            this.rng = rng;
        }
    }

    public static class ExprException extends RuntimeException {
        public ExprException(String message) {
            super(message);
        }
    }
}
