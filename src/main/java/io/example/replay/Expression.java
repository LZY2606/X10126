package io.example.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Expression {
    private final String source;
    private int pos;

    private Expression(String source) {
        this.source = source;
    }

    public static Object evaluate(String expression, EvalContext context) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        return new Expression(expression).parseExpression(context);
    }

    public static boolean isTrue(String expression, EvalContext context) {
        return truth(evaluate(expression, context));
    }

    public static boolean truth(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        return true;
    }

    private Object parseExpression(EvalContext context) {
        Object value = parseOr(context);
        skip();
        if (pos < source.length()) {
            throw error("Unexpected trailing expression");
        }
        return value;
    }

    private Object parseOr(EvalContext context) {
        Object result = parseAnd(context);
        while (matchOperator("||")) {
            Object right = parseAnd(context);
            result = truth(result) || truth(right);
        }
        return result;
    }

    private Object parseAnd(EvalContext context) {
        Object result = parseEquality(context);
        while (matchOperator("&&")) {
            Object right = parseEquality(context);
            result = truth(result) && truth(right);
        }
        return result;
    }

    private Object parseEquality(EvalContext context) {
        Object result = parseComparison(context);
        while (true) {
            skip();
            if (matchOperator("==")) {
                Object right = parseComparison(context);
                result = equalsValue(result, right);
            } else if (matchOperator("!=")) {
                Object right = parseComparison(context);
                result = !equalsValue(result, right);
            } else {
                return result;
            }
        }
    }

    private Object parseComparison(EvalContext context) {
        Object result = parseAdditive(context);
        while (true) {
            skip();
            String operator = comparisonOperator();
            if (operator == null) {
                return result;
            }
            pos += operator.length();
            Object right = parseAdditive(context);
            result = compare(result, right, operator);
        }
    }

    private Object parseAdditive(EvalContext context) {
        Object result = parseMultiplicative(context);
        while (true) {
            skip();
            if (consume('+')) {
                result = add(result, parseMultiplicative(context));
            } else if (consume('-')) {
                result = subtract(result, parseMultiplicative(context));
            } else {
                return result;
            }
        }
    }

    private Object parseMultiplicative(EvalContext context) {
        Object result = parseUnary(context);
        while (true) {
            skip();
            if (consume('*')) {
                result = multiply(result, parseUnary(context));
            } else if (consume('/')) {
                result = divide(result, parseUnary(context));
            } else if (consume('%')) {
                result = modulo(result, parseUnary(context));
            } else {
                return result;
            }
        }
    }

    private Object parseUnary(EvalContext context) {
        skip();
        if (consume('!')) {
            return !truth(parseUnary(context));
        }
        if (consume('-')) {
            return number(parseUnary(context), "negate") * -1d;
        }
        return parsePrimary(context);
    }

    private Object parsePrimary(EvalContext context) {
        skip();
        if (consume('(')) {
            Object result = parseOr(context);
            skip();
            expect(')');
            return result;
        }
        if (consume('\'')) {
            return readSingleQuoted('\'');
        }
        if (consume('"')) {
            return readSingleQuoted('"');
        }
        if (Character.isDigit(current()) || current() == '.') {
            return readNumber();
        }
        if (isIdentifierStart(current())) {
            return readIdentifierChain(context);
        }
        throw error("Expected value");
    }

    private Object readIdentifierChain(EvalContext context) {
        String name = readIdentifier();
        Object value = switch (name) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "null" -> null;
            case "state" -> context.state();
            case "data" -> context.state();
            case "vars" -> context.variables();
            case "event" -> context.eventMap();
            case "payload" -> context.payload();
            case "seed" -> (double) context.seed();
            case "random", "randomInt" -> name;
            default -> {
                if (name.startsWith("$")) {
                    yield context.variables().get(name);
                }
                throw error("Unknown identifier " + name);
            }
        };
        return readSuffix(value, context);
    }

    private Object readSuffix(Object value, EvalContext context) {
        while (true) {
            skip();
            if (consume('.')) {
                String key = readIdentifier();
                value = getPath(value, key);
            } else if (consume('[')) {
                Object index = parseOr(context);
                expect(']');
                if (index instanceof Number n) {
                    List<Object> list = asList(value);
                    int i = n.intValue();
                    if (i < 0 || i >= list.size()) {
                        value = null;
                    } else {
                        value = list.get(i);
                    }
                } else {
                    value = getPath(value, String.valueOf(index));
                }
            } else if (consume('(')) {
                value = callFunction(value, context);
                expect(')');
            } else {
                return value;
            }
        }
    }

    private Object callFunction(Object nameValue, EvalContext context) {
        if (!(nameValue instanceof String name)) {
            throw error("Only functions can be called");
        }
        List<Object> args = new ArrayList<>();
        skip();
        if (current() != ')') {
            args.add(parseOr(context));
            while (consume(',')) {
                args.add(parseOr(context));
            }
        }
        return switch (name) {
            case "not" -> !truth(one(args));
            case "exists" -> one(args) != null;
            case "abs" -> Math.abs(number(one(args), name));
            case "min" -> number(args.get(0), name) <= number(args.get(1), name)
                    ? numeric(args.get(0)) : numeric(args.get(1));
            case "max" -> number(args.get(0), name) >= number(args.get(1), name)
                    ? numeric(args.get(0)) : numeric(args.get(1));
            case "int" -> Math.floor(number(one(args), name));
            case "concat" -> {
                StringBuilder result = new StringBuilder();
                for (Object arg : args) {
                    result.append(stringValue(arg));
                }
                yield result.toString();
            }
            case "random" -> {
                if (!args.isEmpty()) {
                    throw error("random() takes no arguments");
                }
                yield context.random().nextUnit();
            }
            case "randomInt" -> {
                if (args.size() != 2) {
                    throw error("randomInt(min,max) requires two arguments");
                }
                yield context.random().nextInt((long) Math.ceil(number(args.get(0), name)),
                        (long) Math.floor(number(args.get(1), name)));
            }
            default -> throw error("Unknown function " + name);
        };
    }

    private Object one(List<Object> args) {
        if (args.size() != 1) {
            throw error("Function requires one argument");
        }
        return args.get(0);
    }

    private String readIdentifier() {
        int start = pos;
        while (pos < source.length() && (isIdentifierPart(source.charAt(pos)) || source.charAt(pos) == '$')) {
            pos++;
        }
        if (start == pos) {
            throw error("Expected identifier");
        }
        return source.substring(start, pos);
    }

    private String readSingleQuoted(char quote) {
        StringBuilder result = new StringBuilder();
        while (pos < source.length()) {
            char c = source.charAt(pos++);
            if (c == quote) {
                return result.toString();
            }
            if (c == '\\' && pos < source.length()) {
                result.append(source.charAt(pos++));
            } else {
                result.append(c);
            }
        }
        throw error("Unterminated string");
    }

    private Double readNumber() {
        int start = pos;
        while (pos < source.length() && (Character.isDigit(source.charAt(pos)) || source.charAt(pos) == '.')) {
            pos++;
        }
        return Double.parseDouble(source.substring(start, pos));
    }

    private static Object getPath(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    private static List<Object> asList(Object value) {
        return Json.list(value);
    }

    private static boolean equalsValue(Object left, Object right) {
        if (left instanceof Number || right instanceof Number) {
            if (left == null || right == null) {
                return false;
            }
            return number(left, "==") == number(right, "==");
        }
        return java.util.Objects.equals(left, right);
    }

    private static Boolean compare(Object left, Object right, String operator) {
        if (left == null || right == null) {
            return false;
        }
        double l = number(left, operator);
        double r = number(right, operator);
        return switch (operator) {
            case "<" -> l < r;
            case "<=" -> l <= r;
            case ">" -> l > r;
            case ">=" -> l >= r;
            default -> throw new IllegalArgumentException("Unknown comparison");
        };
    }

    private static Object add(Object left, Object right) {
        if (left instanceof String || right instanceof String) {
            return stringValue(left) + stringValue(right);
        }
        return number(left, "+") + number(right, "+");
    }

    private static double subtract(Object left, Object right) {
        return number(left, "-") - number(right, "-");
    }

    private static double multiply(Object left, Object right) {
        return number(left, "*") * number(right, "*");
    }

    private static double divide(Object left, Object right) {
        return number(left, "/") / number(right, "/");
    }

    private static double modulo(Object left, Object right) {
        return number(left, "%") % number(right, "%");
    }

    private static double number(Object value, String operation) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Cannot use string in " + operation);
            }
        }
        throw new IllegalArgumentException("Cannot use value in " + operation);
    }

    private static Object numeric(Object value) {
        double n = number(value, "numeric");
        if (Math.rint(n) == n && !Double.isInfinite(n)) {
            return (long) n;
        }
        return n;
    }

    public static Object normalizeNumber(Object value) {
        if (value instanceof Double d && !d.isInfinite() && !d.isNaN() && Math.rint(d) == d) {
            return d.longValue();
        }
        return value;
    }

    private static String stringValue(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private String comparisonOperator() {
        if (source.startsWith("<=", pos)) {
            return "<=";
        }
        if (source.startsWith(">=", pos)) {
            return ">=";
        }
        if (pos < source.length() && (source.charAt(pos) == '<' || source.charAt(pos) == '>')) {
            return String.valueOf(source.charAt(pos));
        }
        return null;
    }

    private boolean matchOperator(String operator) {
        skip();
        if (source.startsWith(operator, pos)) {
            pos += operator.length();
            return true;
        }
        return false;
    }

    private boolean consume(char expected) {
        if (pos < source.length() && source.charAt(pos) == expected) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            throw error("Expected " + expected);
        }
    }

    private void skip() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
            pos++;
        }
    }

    private char current() {
        return pos >= source.length() ? 0 : source.charAt(pos);
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " in expression '" + source + "' at " + pos);
    }

    public record EvalContext(Map<String, Object> state, Map<String, Object> variables,
                              Map<String, Object> eventMap, Map<String, Object> payload,
                              long seed, DeterministicRandom random) {
    }
}
