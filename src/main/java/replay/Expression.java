package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Expression {
    private final String source;
    private final Parser parser;

    private Expression(String source) {
        this.source = source;
        this.parser = new Parser(source);
    }

    static Object evaluate(String source, Map<String, Object> context) {
        return new Expression(source).evaluate(context);
    }

    static boolean evaluateBoolean(String source, Map<String, Object> context) {
        Object value = evaluate(source, context);
        return truth(value);
    }

    private Object evaluate(Map<String, Object> context) {
        Object value = parseExpression();
        parser.skipWhitespace();
        if (!parser.end()) {
            throw new IllegalArgumentException("Unexpected expression text at position " + parser.position + " in " + source);
        }
        return value;
    }

    private Object parseExpression() {
        return parseTernary();
    }

    private Object parseTernary() {
        Object condition = parseLogicalOr();
        parser.skipWhitespace();
        if (parser.consume('?')) {
            Object whenTrue = parseExpression();
            parser.skipWhitespace();
            parser.expect(':');
            Object whenFalse = parseTernary();
            return truth(condition) ? whenTrue : whenFalse;
        }
        return condition;
    }

    private Object parseLogicalOr() {
        Object value = parseLogicalAnd();
        while (true) {
            parser.skipWhitespace();
            if (!parser.match("||")) break;
            Object right = parseLogicalAnd();
            value = truth(value) || truth(right);
        }
        return value;
    }

    private Object parseLogicalAnd() {
        Object value = parseEquality();
        while (true) {
            parser.skipWhitespace();
            if (!parser.match("&&")) break;
            Object right = parseEquality();
            value = truth(value) && truth(right);
        }
        return value;
    }

    private Object parseEquality() {
        Object value = parseComparison();
        while (true) {
            parser.skipWhitespace();
            if (parser.match("==")) {
                value = equalsValue(value, parseComparison());
            } else if (parser.match("!=")) {
                value = !equalsValue(value, parseComparison());
            } else {
                break;
            }
        }
        return value;
    }

    private Object parseComparison() {
        Object value = parseAddition();
        while (true) {
            parser.skipWhitespace();
            int sign;
            if (parser.match(">=")) sign = 0;
            else if (parser.match("<=")) sign = 1;
            else if (parser.match(">")) sign = 2;
            else if (parser.match("<")) sign = 3;
            else break;
            Object right = parseAddition();
            value = switch (sign) {
                case 0 -> compare(value, right) >= 0;
                case 1 -> compare(value, right) <= 0;
                case 2 -> compare(value, right) > 0;
                default -> compare(value, right) < 0;
            };
        }
        return value;
    }

    private Object parseAddition() {
        Object value = parseMultiplication();
        while (true) {
            parser.skipWhitespace();
            if (parser.match("+")) {
                value = numeric(value, "+") + numeric(parseMultiplication(), "+");
            } else if (parser.match("-")) {
                value = numeric(value, "-") - numeric(parseMultiplication(), "-");
            } else {
                break;
            }
        }
        return value;
    }

    private Object parseMultiplication() {
        Object value = parseUnary();
        while (true) {
            parser.skipWhitespace();
            if (parser.match("*")) {
                value = numeric(value, "*") * numeric(parseUnary(), "*");
            } else if (parser.match("/")) {
                value = numeric(value, "/") / numeric(parseUnary(), "/");
            } else if (parser.match("%")) {
                value = numeric(value, "%") % numeric(parseUnary(), "%");
            } else {
                break;
            }
        }
        return value;
    }

    private Object parseUnary() {
        parser.skipWhitespace();
        if (parser.match("!")) return !truth(parseUnary());
        if (parser.match("-")) return -numeric(parseUnary(), "unary -");
        if (parser.match("+")) return numeric(parseUnary(), "unary +");
        return parsePostfix();
    }

    private Object parsePostfix() {
        Object value = parsePrimary();
        while (true) {
            parser.skipWhitespace();
            if (parser.match(".")) {
                parser.skipWhitespace();
                String name = parser.readIdentifier();
                Map<?, ?> map = value instanceof Map<?, ?> existing ? existing : Map.of();
                value = map.get(name);
            } else if (parser.peek() == '[') {
                parser.next();
                Object key = parseExpression();
                parser.skipWhitespace();
                parser.expect(']');
                value = index(value, key);
            } else {
                return value;
            }
        }
    }

    private Object parsePrimary() {
        parser.skipWhitespace();
        char c = parser.peek();
        if (c == '(') {
            parser.next();
            Object value = parseExpression();
            parser.skipWhitespace();
            parser.expect(')');
            return value;
        }
        if (c == '"' || c == '\'') return parser.readQuoted(c);
        if (Character.isDigit(c)) return parser.readNumber();
        if (Character.isLetter(c) || c == '_') {
            String name = parser.readIdentifier();
            if (parser.peek() == '(') {
                List<Object> args = parseArguments();
                return callFunction(name, args);
            }
            return switch (name) {
                case "true" -> true;
                case "false" -> false;
                case "null" -> null;
                default -> parser.root.get(name);
            };
        }
        throw new IllegalArgumentException("Expected expression at position " + parser.position);
    }

    private List<Object> parseArguments() {
        parser.expect('(');
        List<Object> args = new ArrayList<>();
        parser.skipWhitespace();
        if (parser.consume(')')) return args;
        while (true) {
            args.add(parseExpression());
            parser.skipWhitespace();
            if (parser.consume(')')) return args;
            parser.expect(',');
        }
    }

    private Object callFunction(String name, List<Object> args) {
        return switch (name) {
            case "abs" -> Math.abs(numberAt(args, 0, "abs"));
            case "min" -> Math.min(numberAt(args, 0, "min"), numberAt(args, 1, "min"));
            case "max" -> Math.max(numberAt(args, 0, "max"), numberAt(args, 1, "max"));
            case "int" -> (long) numberAt(args, 0, "int");
            case "str" -> String.valueOf(args.get(0));
            case "len" -> (long) length(args.get(0));
            case "get" -> getPath(args.get(0), Json.string(args.get(1), "get() path"),
                    args.size() > 2 ? args.get(2) : null);
            case "contains" -> contains(args.get(0), args.get(1));
            default -> throw new IllegalArgumentException("Unknown function " + name);
        };
    }

    private double numberAt(List<Object> args, int index, String name) {
        if (args.size() <= index) throw new IllegalArgumentException(name + " requires arguments");
        return numeric(args.get(index), name);
    }

    private static Object index(Object value, Object key) {
        if (value instanceof Map<?, ?> map && key instanceof String text) return map.get(text);
        if (value instanceof List<?> list) {
            int index = (int) ((Number) key).longValue();
            return index >= 0 && index < list.size() ? list.get(index) : null;
        }
        return null;
    }

    private static Object getPath(Object root, String path, Object fallback) {
        Object current = root;
        if (!path.isEmpty()) {
            for (String part : path.split("\\.", -1)) {
                current = index(current, part.matches("\\d+") ? Long.parseLong(part) : part);
                if (current == null) return fallback;
            }
        }
        return current;
    }

    private static int length(Object value) {
        if (value instanceof String text) return text.length();
        if (value instanceof List<?> list) return list.size();
        if (value instanceof Map<?, ?> map) return map.size();
        return 0;
    }

    private static boolean contains(Object value, Object item) {
        if (value instanceof Map<?, ?> map) return map.containsKey(item);
        if (value instanceof List<?> list) {
            for (Object candidate : list) if (equalsValue(candidate, item)) return true;
        }
        if (value instanceof String text && item instanceof String fragment) return text.contains(fragment);
        return false;
    }

    private static boolean truth(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof String text) return !text.isEmpty();
        if (value instanceof List<?> list) return !list.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return true;
    }

    private static boolean equalsValue(Object left, Object right) {
        if (left instanceof Number || right instanceof Number) {
            return numeric(left, "==") == numeric(right, "==");
        }
        return java.util.Objects.equals(left, right);
    }

    private static int compare(Object left, Object right) {
        double a = numeric(left, "compare");
        double b = numeric(right, "compare");
        return Double.compare(a, b);
    }

    private static double numeric(Object value, String operation) {
        if (value instanceof Number number) return number.doubleValue();
        throw new IllegalArgumentException("Non-numeric value used with " + operation + ": " + Json.write(value));
    }

    private static final class Parser {
        private final String text;
        private int position;
        private final Map<String, Object> root = new LinkedHashMap<>();

        private Parser(String text) {
            this.text = text;
        }

        private boolean end() {
            return position >= text.length();
        }

        private char peek() {
            return position < text.length() ? text.charAt(position) : '\0';
        }

        private char next() {
            return text.charAt(position++);
        }

        private void skipWhitespace() {
            while (!end() && Character.isWhitespace(text.charAt(position))) position++;
        }

        private boolean consume(char value) {
            if (peek() == value) {
                position++;
                return true;
            }
            return false;
        }

        private boolean match(String token) {
            if (text.startsWith(token, position)) {
                position += token.length();
                return true;
            }
            return false;
        }

        private void expect(char value) {
            if (!consume(value)) {
                throw new IllegalArgumentException("Expected '" + value + "' at position " + position);
            }
        }

        private String readIdentifier() {
            int start = position;
            if (end() || !(Character.isLetter(peek()) || peek() == '_')) {
                throw new IllegalArgumentException("Expected identifier at " + position);
            }
            while (!end() && (Character.isLetterOrDigit(peek()) || peek() == '_')) position++;
            return text.substring(start, position);
        }

        private String readQuoted(char quote) {
            expect(quote);
            StringBuilder builder = new StringBuilder();
            while (true) {
                if (end()) throw new IllegalArgumentException("Unterminated string");
                char c = next();
                if (c == quote) return builder.toString();
                if (c == '\\' && !end()) {
                    char escaped = next();
                    if (escaped == 'n') builder.append('\n');
                    else if (escaped == 't') builder.append('\t');
                    else builder.append(escaped);
                } else {
                    builder.append(c);
                }
            }
        }

        private Number readNumber() {
            int start = position;
            while (!end() && (Character.isDigit(peek()) || peek() == '.')) position++;
            String token = text.substring(start, position);
            return token.contains(".") ? Double.parseDouble(token) : Long.parseLong(token);
        }
    }
}
