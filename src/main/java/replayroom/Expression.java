package replayroom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Expression {
    interface Node {
        Object evaluate(Map<String, Object> root);
    }

    private final String source;
    private final Node root;
    private int pos;

    private Expression(String source) {
        this.source = source;
        this.pos = 0;
        this.root = parseExpression();
        skipWhitespace();
        if (pos < source.length()) {
            throw error("Unexpected trailing expression");
        }
    }

    static Object evaluate(String source, Map<String, Object> context) {
        if (source == null || source.isBlank()) {
            return true;
        }
        String trimmed = source.trim();
        if (trimmed.startsWith("=")) {
            return new Expression(trimmed.substring(1)).root.evaluate(context);
        }
        return source;
    }

    static boolean condition(String source, Map<String, Object> context) {
        return truthy(evaluate(source, context));
    }

    static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof String s) return !s.isEmpty();
        return true;
    }

    private Node parseExpression() {
        Node node = parseTernaryTail(parseOr());
        return node;
    }

    private Node parseTernaryTail(Node node) {
        skipWhitespace();
        if (match("?")) {
            Node whenTrue = parseExpression();
            skipWhitespace();
            expect(':');
            Node whenFalse = parseExpression();
            return context -> truthy(node.evaluate(context)) ? whenTrue.evaluate(context) : whenFalse.evaluate(context);
        }
        return node;
    }

    private Node parseOr() {
        Node left = parseAnd();
        while (true) {
            skipWhitespace();
            if (!match("||")) return left;
            Node right = parseAnd();
            Node previous = left;
            left = context -> truthy(previous.evaluate(context)) || truthy(right.evaluate(context));
        }
    }

    private Node parseAnd() {
        Node left = parseEquality();
        while (true) {
            skipWhitespace();
            if (!match("&&")) return left;
            Node right = parseEquality();
            Node previous = left;
            left = context -> truthy(previous.evaluate(context)) && truthy(right.evaluate(context));
        }
    }

    private Node parseEquality() {
        Node left = parseComparison();
        while (true) {
            skipWhitespace();
            String operator = match("==") ? "==" : match("!=") ? "!=" : null;
            if (operator == null) return left;
            Node right = parseComparison();
            Node previous = left;
            left = context -> compareEquals(previous.evaluate(context), right.evaluate(context), operator);
        }
    }

    private Node parseComparison() {
        Node left = parseAdditive();
        while (true) {
            skipWhitespace();
            String operator = null;
            for (String candidate : List.of("<=", ">=", "<", ">")) {
                if (match(candidate)) {
                    operator = candidate;
                    break;
                }
            }
            if (operator == null) return left;
            Node right = parseAdditive();
            Node previous = left;
            left = context -> compareNumbers(previous.evaluate(context), right.evaluate(context), operator);
        }
    }

    private Node parseAdditive() {
        Node left = parseMultiplicative();
        while (true) {
            skipWhitespace();
            String operator = match("+") ? "+" : match("-") ? "-" : null;
            if (operator == null) return left;
            Node right = parseMultiplicative();
            Node previous = left;
            left = context -> arithmetic(previous.evaluate(context), right.evaluate(context), operator);
        }
    }

    private Node parseMultiplicative() {
        Node left = parseUnary();
        while (true) {
            skipWhitespace();
            String operator = match("*") ? "*" : match("/") ? "/" : null;
            if (operator == null) return left;
            Node right = parseUnary();
            Node previous = left;
            left = context -> arithmetic(previous.evaluate(context), right.evaluate(context), operator);
        }
    }

    private Node parseUnary() {
        skipWhitespace();
        if (match("!")) {
            Node node = parseUnary();
            return context -> !truthy(node.evaluate(context));
        }
        if (match("-")) {
            Node node = parseUnary();
            return context -> arithmetic(0L, node.evaluate(context), "-");
        }
        return parsePrimary();
    }

    private Node parsePrimary() {
        skipWhitespace();
        if (match("(")) {
            Node node = parseExpression();
            skipWhitespace();
            expect(')');
            return node;
        }
        if (peek() == '\'' || peek() == '"') return parseString(peek());
        if (Character.isDigit(peek())) return parseNumber();
        if (matchKeyword("true")) return context -> true;
        if (matchKeyword("false")) return context -> false;
        if (matchKeyword("null")) return context -> null;
        return parseReferenceOrCall();
    }

    private Node parseString(char quote) {
        expect(quote);
        StringBuilder builder = new StringBuilder();
        while (pos < source.length() && source.charAt(pos) != quote) {
            char c = source.charAt(pos++);
            if (c == '\\' && pos < source.length()) {
                char escaped = source.charAt(pos++);
                switch (escaped) {
                    case 'n' -> builder.append('\n');
                    case 't' -> builder.append('\t');
                    default -> builder.append(escaped);
                }
            } else {
                builder.append(c);
            }
        }
        if (pos >= source.length()) throw error("Unterminated string");
        pos++;
        String value = builder.toString();
        return context -> value;
    }

    private Node parseNumber() {
        int start = pos;
        while (pos < source.length() && Character.isDigit(source.charAt(pos))) pos++;
        boolean floating = false;
        if (pos < source.length() && source.charAt(pos) == '.') {
            floating = true;
            pos++;
            while (pos < source.length() && Character.isDigit(source.charAt(pos))) pos++;
        }
        String text = source.substring(start, pos);
        Number number = floating ? Double.parseDouble(text) : Long.parseLong(text);
        return context -> number;
    }

    private Node parseReferenceOrCall() {
        String name = readIdentifier();
        skipWhitespace();
        if (peek() == '(') {
            expect('(');
            List<Node> arguments = new ArrayList<>();
            skipWhitespace();
            if (peek() != ')') {
                arguments.add(parseExpression());
                skipWhitespace();
                while (peek() == ',') {
                    pos++;
                    arguments.add(parseExpression());
                    skipWhitespace();
                }
            }
            expect(')');
            return context -> callFunction(name, arguments, context);
        }
        List<String> path = new ArrayList<>();
        path.add(name);
        while (peek() == '.') {
            pos++;
            path.add(readIdentifier());
        }
        return context -> resolvePath(context, path);
    }

    private String readIdentifier() {
        skipWhitespace();
        int start = pos;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_') pos++;
            else break;
        }
        if (start == pos) throw error("Expected identifier");
        return source.substring(start, pos);
    }

    private Object callFunction(String name, List<Node> arguments, Map<String, Object> root) {
        if (name.equals("size") && arguments.size() == 1) {
            Object value = arguments.get(0).evaluate(root);
            if (value instanceof List<?> list) return (long) list.size();
            if (value instanceof Map<?, ?> map) return (long) map.size();
            if (value instanceof String string) return (long) string.length();
            return 0L;
        }
        if (name.equals("contains") && arguments.size() == 2) {
            Object container = arguments.get(0).evaluate(root);
            Object item = arguments.get(1).evaluate(root);
            if (container instanceof List<?> list) return list.contains(item);
            return container != null && container.toString().contains(String.valueOf(item));
        }
        if (name.equals("coalesce") && arguments.size() >= 2) {
            for (Node argument : arguments) {
                Object value = argument.evaluate(root);
                if (value != null) return value;
            }
            return null;
        }
        if (name.equals("string") && arguments.size() == 1) {
            Object value = arguments.get(0).evaluate(root);
            return value == null ? "" : String.valueOf(value);
        }
        throw new IllegalArgumentException("Unknown function " + name);
    }

    private Object resolvePath(Object current, List<String> path) {
        for (String part : path) {
            if (current instanceof Map<?, ?> map) current = map.get(part);
            else return null;
        }
        return current;
    }

    private static Object compareEquals(Object left, Object right, String operator) {
        boolean equals = valuesEqual(left, right);
        return operator.equals("==") == equals;
    }

    private static boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return ((Number) left).doubleValue() == ((Number) right).doubleValue();
        }
        return left == null ? right == null : left.equals(right);
    }

    private static boolean compareNumbers(Object left, Object right, String operator) {
        if (!(left instanceof Number) || !(right instanceof Number)) return false;
        double a = ((Number) left).doubleValue();
        double b = ((Number) right).doubleValue();
        return switch (operator) {
            case "<" -> a < b;
            case ">" -> a > b;
            case "<=" -> a <= b;
            default -> a >= b;
        };
    }

    private static Object arithmetic(Object left, Object right, String operator) {
        if (operator.equals("+") && (left instanceof String || right instanceof String)) {
            return String.valueOf(left) + String.valueOf(right);
        }
        if (!(left instanceof Number) || !(right instanceof Number)) {
            throw new IllegalArgumentException("Operator " + operator + " requires numbers");
        }
        double a = ((Number) left).doubleValue();
        double b = ((Number) right).doubleValue();
        double result = switch (operator) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> a / b;
            default -> throw new IllegalStateException();
        };
        if (left instanceof Double || right instanceof Double || (operator.equals("/") && a % b != 0)) return result;
        return (long) result;
    }

    private char peek() {
        return pos >= source.length() ? '\0' : source.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++;
    }

    private boolean match(String token) {
        if (source.startsWith(token, pos)) {
            pos += token.length();
            return true;
        }
        return false;
    }

    private boolean matchKeyword(String keyword) {
        if (source.startsWith(keyword, pos)) {
            int next = pos + keyword.length();
            if (next >= source.length() || !Character.isLetterOrDigit(source.charAt(next))) {
                pos = next;
                return true;
            }
        }
        return false;
    }

    private void expect(char c) {
        if (peek() != c) throw error("Expected " + c);
        pos++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " in expression '" + source + "'");
    }

    static Map<String, Object> context(Map<String, Object> vars, Map<String, Object> event, long rngState) {
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("state", new java.util.LinkedHashMap<>(vars));
        context.put("event", event == null ? new java.util.LinkedHashMap<>() : event);
        context.put("rngState", rngState);
        return context;
    }
}
