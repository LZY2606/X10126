package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Small deterministic expression language used by transition conditions
 * and action arguments.
 *
 * Literals: numbers, strings (single or double quoted), true/false/null.
 * Names: $state (current state name), $event (event object), $eventType,
 *        $eventTime, bare names read state variables; payload.x reads the
 *        current event payload.
 * Operators: ( ) ! - * / % + - < <= > >= == != && ||
 * Functions: min max abs floor round now() eventTime() random(bound)
 *            range(min,max) randomDouble()
 */
public final class Expr {

    private Expr() {
    }

    public static Object eval(String expression, Context context) {
        return new Parser(expression).parse().evaluate(context);
    }

    public static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0.0;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        return true;
    }

    /** Variable resolution and deterministic randomness available to expressions. */
    public interface Context {
        String currentState();

        Object variable(String name);

        Models.Event event();

        Rng rng();
    }

    @FunctionalInterface
    interface Node {
        Object evaluate(Context context);
    }

    // ---- tokenizer + recursive descent parser ----

    static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Node parse() {
            skipWs();
            Node node = parseOr();
            skipWs();
            if (pos < text.length()) {
                throw error("unexpected character '" + text.charAt(pos) + "'");
            }
            return node;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + pos + " in: " + text);
        }

        private void skipWs() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private boolean consume(String token) {
            skipWs();
            if (text.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        private Node parseOr() {
            Node left = parseAnd();
            while (consume("||")) {
                Node a = left;
                Node b = parseAnd();
                left = ctx -> isTruthy(a.evaluate(ctx)) || isTruthy(b.evaluate(ctx));
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseEquality();
            while (consume("&&")) {
                Node a = left;
                Node b = parseEquality();
                left = ctx -> isTruthy(a.evaluate(ctx)) && isTruthy(b.evaluate(ctx));
            }
            return left;
        }

        private Node parseEquality() {
            Node left = parseComparison();
            while (true) {
                if (consume("==")) {
                    Node a = left;
                    Node b = parseComparison();
                    left = ctx -> valuesEqual(a.evaluate(ctx), b.evaluate(ctx));
                } else if (consume("!=")) {
                    Node a = left;
                    Node b = parseComparison();
                    left = ctx -> !valuesEqual(a.evaluate(ctx), b.evaluate(ctx));
                } else {
                    return left;
                }
            }
        }

        private Node parseComparison() {
            Node left = parseAdditive();
            while (true) {
                String operator = null;
                if (consume("<=")) {
                    operator = "<=";
                } else if (consume(">=")) {
                    operator = ">=";
                } else if (consume("<")) {
                    operator = "<";
                } else if (consume(">")) {
                    operator = ">";
                }
                if (operator == null) {
                    return left;
                }
                Node a = left;
                Node b = parseAdditive();
                String op = operator;
                left = ctx -> compare(a.evaluate(ctx), b.evaluate(ctx), op);
            }
        }

        private Node parseAdditive() {
            Node left = parseMultiplicative();
            while (true) {
                if (consume("+")) {
                    Node a = left;
                    Node b = parseMultiplicative();
                    left = ctx -> add(a.evaluate(ctx), b.evaluate(ctx));
                } else if (consume("-")) {
                    Node a = left;
                    Node b = parseMultiplicative();
                    left = ctx -> math(a.evaluate(ctx), b.evaluate(ctx), '-');
                } else {
                    return left;
                }
            }
        }

        private Node parseMultiplicative() {
            Node left = parseUnary();
            while (true) {
                if (consume("*")) {
                    Node a = left;
                    Node b = parseUnary();
                    left = ctx -> math(a.evaluate(ctx), b.evaluate(ctx), '*');
                } else if (consume("/")) {
                    Node a = left;
                    Node b = parseUnary();
                    left = ctx -> math(a.evaluate(ctx), b.evaluate(ctx), '/');
                } else if (consume("%")) {
                    Node a = left;
                    Node b = parseUnary();
                    left = ctx -> math(a.evaluate(ctx), b.evaluate(ctx), '%');
                } else {
                    return left;
                }
            }
        }

        private Node parseUnary() {
            if (consume("!")) {
                Node node = parseUnary();
                return ctx -> !isTruthy(node.evaluate(ctx));
            }
            if (consume("-")) {
                Node node = parseUnary();
                return ctx -> -asNumber(node.evaluate(ctx), "negation");
            }
            return parsePostfix();
        }

        private Node parsePostfix() {
            Node node = parsePrimary();
            while (true) {
                if (consume(".")) {
                    skipWs();
                    String field = readName();
                    Node target = node;
                    node = ctx -> readField(target.evaluate(ctx), field);
                } else if (consume("[")) {
                    Node index = parseOr();
                    if (!consume("]")) {
                        throw error("expected ']'");
                    }
                    Node target = node;
                    node = ctx -> readIndex(target.evaluate(ctx), index.evaluate(ctx));
                } else {
                    return node;
                }
            }
        }

        private Node parsePrimary() {
            skipWs();
            if (pos >= text.length()) {
                throw error("unexpected end of expression");
            }
            char c = text.charAt(pos);
            if (c == '(') {
                pos++;
                Node node = parseOr();
                if (!consume(")")) {
                    throw error("expected ')'");
                }
                return node;
            }
            if (c == '"' || c == '\'') {
                return parseString(c);
            }
            if (Character.isDigit(c)) {
                return parseNumber(false);
            }
            if (c == '$' || Character.isLetter(c) || c == '_') {
                String name = readName();
                skipWs();
                if (pos < text.length() && text.charAt(pos) == '(') {
                    return parseCall(name);
                }
                return resolveName(name);
            }
            throw error("unexpected character '" + c + "'");
        }

        private Node parseString(char quote) {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < text.length() && text.charAt(pos) != quote) {
                char c = text.charAt(pos++);
                if (c == '\\' && pos < text.length()) {
                    sb.append(text.charAt(pos++));
                } else {
                    sb.append(c);
                }
            }
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            pos++;
            String value = sb.toString();
            return ctx -> value;
        }

        private Node parseNumber(boolean negative) {
            int start = pos;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            boolean isDouble = false;
            if (pos < text.length() && text.charAt(pos) == '.') {
                isDouble = true;
                pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            String token = text.substring(start, pos);
            double parsed = Double.parseDouble(token);
            double value = negative ? -parsed : parsed;
            boolean decimal = isDouble;
            if (decimal) {
                return ctx -> value;
            }
            long longValue = (long) value;
            return ctx -> longValue;
        }

        private String readName() {
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw error("expected a name");
            }
            return text.substring(start, pos);
        }

        private Node parseCall(String name) {
            pos++;
            List<Node> args = new ArrayList<>();
            skipWs();
            if (pos < text.length() && text.charAt(pos) != ')') {
                args.add(parseOr());
                while (consume(",")) {
                    args.add(parseOr());
                }
            }
            if (!consume(")")) {
                throw error("expected ')' after function arguments");
            }
            return ctx -> callFunction(name, args, ctx);
        }

        private Node resolveName(String name) {
            switch (name) {
                case "true":
                    return ctx -> Boolean.TRUE;
                case "false":
                    return ctx -> Boolean.FALSE;
                case "null":
                    return ctx -> null;
                case "$state":
                    return Context::currentState;
                case "$eventType":
                    return ctx -> ctx.event().type;
                case "$eventTime":
                    return ctx -> ctx.event().time;
                case "$event":
                    return Context::event;
                default:
                    return ctx -> {
                        if ("payload".equals(name)) {
                            return ctx.event().payload;
                        }
                        return ctx.variable(name);
            };
            }
        }
    }

    // ---- runtime helpers ----

    private static Object callFunction(String name, List<Node> args, Context ctx) {
        switch (name) {
            case "min":
                checkArity(name, args, 2);
                return Math.min(asNumber(args.get(0).evaluate(ctx), name),
                        asNumber(args.get(1).evaluate(ctx), name));
            case "max":
                checkArity(name, args, 2);
                return Math.max(asNumber(args.get(0).evaluate(ctx), name),
                        asNumber(args.get(1).evaluate(ctx), name));
            case "abs":
                checkArity(name, args, 1);
                return Math.abs(asNumber(args.get(0).evaluate(ctx), name));
            case "floor":
                checkArity(name, args, 1);
                return (long) Math.floor(asNumber(args.get(0).evaluate(ctx), name));
            case "round":
                checkArity(name, args, 1);
                return (long) Math.round(asNumber(args.get(0).evaluate(ctx), name));
            case "now":
            case "eventTime":
                checkArity(name, args, 0);
                return ctx.event().time;
            case "random": {
                checkArity(name, args, 1);
                long bound = ((Number) args.get(0).evaluate(ctx)).longValue();
                return ctx.rng().nextLong(bound);
            }
            case "range": {
                checkArity(name, args, 2);
                long min = ((Number) args.get(0).evaluate(ctx)).longValue();
                long max = ((Number) args.get(1).evaluate(ctx)).longValue();
                return ctx.rng().range(min, max);
            }
            case "randomDouble":
                checkArity(name, args, 0);
                return ctx.rng().nextDouble();
            default:
                throw new IllegalArgumentException("unknown function: " + name);
        }
    }

    private static void checkArity(String name, List<Node> args, int expected) {
        if (args.size() != expected) {
            throw new IllegalArgumentException(
                    name + " expects " + expected + " arguments but got " + args.size());
        }
    }

    private static double asNumber(Object value, String what) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        throw new IllegalArgumentException(what + " expects a number, got " + typeName(value));
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static Object math(Object a, Object b, char op) {
        double x = asNumber(a, String.valueOf(op));
        double y = asNumber(b, String.valueOf(op));
        double result;
        switch (op) {
            case '+':
                result = x + y;
                break;
            case '-':
                result = x - y;
                break;
            case '*':
                result = x * y;
                break;
            case '/':
                result = x / y;
                break;
            case '%':
                result = x % y;
                break;
            default:
                throw new IllegalStateException();
        }
        if (a instanceof Long && b instanceof Long) {
            return (long) result;
        }
        return result;
    }

    private static Object add(Object a, Object b) {
        if (a instanceof String || b instanceof String) {
            return String.valueOf(a) + String.valueOf(b);
        }
        return math(a, b, '+');
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a == null ? b == null : a.equals(b);
    }

    private static Object compare(Object a, Object b, String operator) {
        int cmp;
        if (a instanceof Number && b instanceof Number) {
            cmp = Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        } else {
            cmp = String.valueOf(a).compareTo(String.valueOf(b));
        }
        switch (operator) {
            case "<":
                return cmp < 0;
            case "<=":
                return cmp <= 0;
            case ">":
                return cmp > 0;
            default:
                return cmp >= 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object readField(Object value, String field) {
        if (value instanceof Models.Event) {
            Models.Event event = (Models.Event) value;
            switch (field) {
                case "id":
                    return event.id;
                case "type":
                    return event.type;
                case "time":
                    return event.time;
                case "priority":
                    return event.priority;
                case "seq":
                    return event.seq;
                case "payload":
                    return event.payload;
                default:
                    return null;
            }
        }
        if (value instanceof Map) {
            return ((Map<String, Object>) value).get(field);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object readIndex(Object value, Object index) {
        if (value instanceof Map && index instanceof String) {
            return ((Map<String, Object>) value).get(index);
        }
        if (value instanceof List && index instanceof Number) {
            List<Object> list = (List<Object>) value;
            int i = ((Number) index).intValue();
            if (i < 0 || i >= list.size()) {
                return null;
            }
            return list.get(i);
        }
        return null;
    }
}
