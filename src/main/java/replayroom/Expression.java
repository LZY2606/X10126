package replayroom;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Expression {
    private final String text;
    private final Map<String, Object> context;
    private int pos;

    private Expression(String text, Map<String, Object> context) {
        this.text = text;
        this.context = context;
    }

    public static Object evaluate(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return Boolean.TRUE;
        }
        Expression parser = new Expression(expression, context);
        Object value = parser.parseOr();
        parser.skipWhitespace();
        if (parser.pos != parser.text.length()) {
            throw new IllegalArgumentException("Unexpected expression text at position " + parser.pos + ": " + parser.text);
        }
        return value;
    }

    public static boolean isTrue(String expression, Map<String, Object> context) {
        return truthy(evaluate(expression, context));
    }

    private Object parseOr() {
        Object value = parseAnd();
        while (matchOperator("||")) {
            Object right = parseAnd();
            value = truthy(value) || truthy(right);
        }
        return value;
    }

    private Object parseAnd() {
        Object value = parseEquality();
        while (matchOperator("&&")) {
            Object right = parseEquality();
            value = truthy(value) && truthy(right);
        }
        return value;
    }

    private Object parseEquality() {
        Object value = parseComparison();
        while (true) {
            if (matchOperator("==")) {
                value = equalsValue(value, parseComparison());
            } else if (matchOperator("!=")) {
                value = !equalsValue(value, parseComparison());
            } else {
                return value;
            }
        }
    }

    private Object parseComparison() {
        Object value = parseAdditive();
        while (true) {
            if (matchOperator("<=")) {
                value = compare(value, parseAdditive()) <= 0;
            } else if (matchOperator(">=")) {
                value = compare(value, parseAdditive()) >= 0;
            } else if (matchOperator("<")) {
                value = compare(value, parseAdditive()) < 0;
            } else if (matchOperator(">")) {
                value = compare(value, parseAdditive()) > 0;
            } else {
                return value;
            }
        }
    }

    private Object parseAdditive() {
        Object value = parseMultiplicative();
        while (true) {
            skipWhitespace();
            if (take('+')) {
                value = add(value, parseMultiplicative());
            } else if (take('-')) {
                value = subtract(value, parseMultiplicative());
            } else {
                return value;
            }
        }
    }

    private Object parseMultiplicative() {
        Object value = parseUnary();
        while (true) {
            skipWhitespace();
            if (take('*')) {
                value = multiply(value, parseUnary());
            } else if (take('/')) {
                value = divide(value, parseUnary());
            } else if (take('%')) {
                value = modulo(value, parseUnary());
            } else {
                return value;
            }
        }
    }

    private Object parseUnary() {
        skipWhitespace();
        if (take('!')) return !truthy(parseUnary());
        if (take('-')) return multiply(parseUnary(), -1L);
        return parsePrimary();
    }

    private Object parsePrimary() {
        skipWhitespace();
        if (take('(')) {
            Object value = parseOr();
            skipWhitespace();
            expect(')');
            return value;
        }
        if (take('\'')) return readQuoted('\'');
        if (take('"')) return readQuoted('"');
        if (consume("true")) return Boolean.TRUE;
        if (consume("false")) return Boolean.FALSE;
        if (consume("null")) return null;
        if (isDigit(peek()) || peek() == '.') return readNumber();
        if (isIdentifierStart(peek())) {
            String name = readIdentifier();
            skipWhitespace();
            if (take('(')) {
                List<Object> arguments = new ArrayList<>();
                skipWhitespace();
                if (!take(')')) {
                    while (true) {
                        arguments.add(parseOr());
                        skipWhitespace();
                        if (take(')')) break;
                        expect(',');
                    }
                }
                return callFunction(name, arguments);
            }
            Object value = lookup(name);
            while (take('.')) {
                String child = readIdentifier();
                value = child(value, child);
            }
            return value;
        }
        throw new IllegalArgumentException("Unexpected expression at position " + pos + ": " + text);
    }

    private Object callFunction(String name, List<Object> arguments) {
        if (name.equals("randInt")) {
            if (arguments.size() < 1 || arguments.size() > 2) {
                throw new IllegalArgumentException("randInt accepts 1 or 2 arguments");
            }
            long exclusive;
            long inclusive;
            if (arguments.size() == 1) {
                inclusive = 0;
                exclusive = asLong(arguments.get(0));
            } else {
                inclusive = asLong(arguments.get(0));
                exclusive = asLong(arguments.get(1));
            }
            long bound = exclusive - inclusive;
            if (bound <= 0) {
                throw new IllegalArgumentException("randInt upper bound must be greater than lower bound");
            }
            long random = DeterministicRandom.next(contextRngState()) % bound;
            if (random < 0) random += bound;
            return inclusive + random;
        }
        if (name.equals("abs")) {
            requireArgumentCount(name, arguments, 1);
            long value = asLong(arguments.get(0));
            return Math.abs(value);
        }
        throw new IllegalArgumentException("Unknown function: " + name);
    }

    @SuppressWarnings("unchecked")
    private long[] contextRngState() {
        Object holder = ((Map<String, Object>) context.get("$rng")).get("state");
        if (!(holder instanceof long[] state)) {
            throw new IllegalStateException("Missing deterministic RNG state");
        }
        return state;
    }

    private Object lookup(String name) {
        if (!context.containsKey(name)) {
            throw new IllegalArgumentException("Unknown variable: " + name);
        }
        return context.get(name);
    }

    private static Object child(Object value, String name) {
        if (value instanceof Map<?, ?> map) {
            return map.get(name);
        }
        return null;
    }

    private String readQuoted(char quote) {
        StringBuilder result = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == quote) return result.toString();
            if (c == '\\' && pos < text.length()) {
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case 'n' -> result.append('\n');
                    case 't' -> result.append('\t');
                    default -> result.append(escaped);
                }
            } else {
                result.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private Object readNumber() {
        int start = pos;
        while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) pos++;
        return new BigDecimal(text.substring(start, pos));
    }

    private String readIdentifier() {
        int start = pos;
        while (pos < text.length() && isIdentifierPart(text.charAt(pos))) pos++;
        return text.substring(start, pos);
    }

    private boolean matchOperator(String operator) {
        skipWhitespace();
        return consume(operator);
    }

    private boolean consume(String value) {
        if (text.startsWith(value, pos)) {
            pos += value.length();
            return true;
        }
        return false;
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private boolean take(char c) {
        if (pos < text.length() && text.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!take(c)) {
            throw new IllegalArgumentException("Expected '" + c + "' at position " + pos);
        }
    }

    private char peek() {
        return pos >= text.length() ? '\0' : text.charAt(pos);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof BigDecimal number) return number.compareTo(BigDecimal.ZERO) != 0;
        if (value instanceof Number number) return number.doubleValue() != 0.0;
        if (value instanceof String s) return !s.isEmpty();
        return true;
    }

    private static boolean equalsValue(Object left, Object right) {
        if (left == null || right == null) return left == null && right == null;
        if (left instanceof BigDecimal || right instanceof BigDecimal || left instanceof Number || right instanceof Number) {
            return compare(left, right) == 0;
        }
        return left.equals(right);
    }

    private static int compare(Object left, Object right) {
        if (left instanceof Number || right instanceof Number) {
            return new BigDecimal(asNumber(left).toString()).compareTo(new BigDecimal(asNumber(right).toString()));
        }
        if (left instanceof String && right instanceof String) {
            return ((String) left).compareTo((String) right);
        }
        throw new IllegalArgumentException("Values are not comparable");
    }

    private static Object add(Object left, Object right) {
        if (left instanceof String || right instanceof String) {
            return String.valueOf(left) + String.valueOf(right);
        }
        return asNumber(left).add(asNumber(right));
    }

    private static Object subtract(Object left, Object right) {
        return asNumber(left).subtract(asNumber(right));
    }

    private static Object multiply(Object left, Object right) {
        return asNumber(left).multiply(asNumber(right));
    }

    private static Object divide(Object left, Object right) {
        BigDecimal divisor = asNumber(right);
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Division by zero");
        }
        return asNumber(left).divide(divisor, 10, java.math.RoundingMode.HALF_UP);
    }

    private static Object modulo(Object left, Object right) {
        long divisor = asLong(right);
        if (divisor == 0) throw new IllegalArgumentException("Modulo by zero");
        long value = asLong(left) % divisor;
        return value < 0 ? value + divisor : value;
    }

    private static BigDecimal asNumber(Object value) {
        if (value instanceof BigDecimal number) return number;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        throw new IllegalArgumentException("Expected number but got " + typeName(value));
    }

    private static long asLong(Object value) {
        return asNumber(value).longValue();
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static void requireArgumentCount(String name, List<Object> arguments, int count) {
        if (arguments.size() != count) {
            throw new IllegalArgumentException(name + " accepts " + count + " arguments");
        }
    }
}
