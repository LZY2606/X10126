package replayroom;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Expr {
    private final String source;
    private final List<Token> tokens;
    private int position;
    private final EvalContext context;

    private Expr(String source, EvalContext context) {
        this.source = source;
        this.tokens = tokenize(source);
        this.context = context;
    }

    public static Object evaluate(String source, EvalContext context) {
        return new Expr(source, context).parseExpression(0);
    }

    public static Object evaluateExpression(Object expression, EvalContext context) {
        if (expression instanceof Map<?, ?> map && map.containsKey("$expr")) {
            return evaluate(Json.string(map, "$expr"), context);
        }
        return Json.deepCopy(expression);
    }

    public interface EvalContext {
        Object variable(String name);
        BigDecimal nextRandom();
        long counter();
    }

    private Object parseExpression(int minBindingPower) {
        Token token = nextToken();
        Object left = parsePrefix(token);
        while (true) {
            Token operator = peekToken();
            Integer infix = infixPower(operator.text);
            if (operator.type != TokenType.OPERATOR || infix == null || infix < minBindingPower) return left;
            nextToken();
            int rightPower = leftAssociative(operator.text) ? infix + 1 : infix;
            Object right = parseExpression(rightPower);
            left = applyBinary(operator.text, left, right);
        }
    }

    private Object parsePrefix(Token token) {
        return switch (token.type) {
            case NUMBER -> new BigDecimal(token.text);
            case STRING -> token.text;
            case IDENTIFIER -> parseIdentifier(token.text);
            case OPERATOR -> switch (token.text) {
                case "!" -> !truthy(parseExpression(30));
                case "-" -> negate(parseExpression(30));
                case "(" -> {
                    Object value = parseExpression(0);
                    expect(")");
                    yield value;
                }
                case "[" -> parseArrayPrefix(token);
                default -> throw error("Unexpected " + token.text);
            };
        };
    }

    private Object parseArrayPrefix(Token opening) {
        List<Object> values = new ArrayList<>();
        if (consume("]")) return values;
        while (true) {
            values.add(parseExpression(0));
            if (consume("]")) return values;
            expect(",");
        }
    }

    private Object parseIdentifier(String name) {
        Object value = context.variable(name);
        while (true) {
            Token token = peekToken();
            if (token.type == TokenType.OPERATOR && token.text.equals(".")) {
                nextToken();
                Token property = nextToken();
                if (property.type != TokenType.IDENTIFIER) throw error("Expected property name");
                value = property(value, property.text);
            } else if (token.type == TokenType.OPERATOR && token.text.equals("[")) {
                nextToken();
                Object key = parseExpression(0);
                expect("]");
                value = index(value, key);
            } else if (token.type == TokenType.OPERATOR && token.text.equals("(")) {
                nextToken();
                List<Object> args = new ArrayList<>();
                if (!consume(")")) {
                    while (true) {
                        args.add(parseExpression(0));
                        if (consume(")")) break;
                        expect(",");
                    }
                }
                value = call(name, value, args);
                name = null;
            } else {
                return value;
            }
            name = null;
        }
    }

    private Object call(String function, Object receiver, List<Object> args) {
        if (function != null) return callGlobal(function, args);
        return callMethod(receiver, args);
    }

    private Object callGlobal(String name, List<Object> args) {
        return switch (name) {
            case "now" -> BigDecimal.valueOf(context.variable("$now") == null ? 0L : Json.longValue(context.variable("$now")));
            case "random" -> context.nextRandom();
            case "randInt" -> {
                checkArgs(name, args, 1, 1);
                BigDecimal max = number(args.get(0), name);
                yield BigDecimal.valueOf(Math.floorMod(context.nextRandom().movePointRight(10).longValue(), max.longValue()));
            }
            case "len" -> {
                checkArgs(name, args, 1, 1);
                Object value = args.get(0);
                if (value instanceof List<?> list) yield BigDecimal.valueOf(list.size());
                if (value instanceof Map<?, ?> map) yield BigDecimal.valueOf(map.size());
                if (value instanceof String text) yield BigDecimal.valueOf(text.length());
                yield BigDecimal.ZERO;
            }
            case "contains" -> {
                checkArgs(name, args, 2, 2);
                if (args.get(0) instanceof List<?> list) yield list.contains(args.get(1));
                if (args.get(0) instanceof Map<?, ?> map) yield map.containsKey(String.valueOf(args.get(1)));
                if (args.get(0) instanceof String text && args.get(1) instanceof String part) yield text.contains(part);
                yield false;
            }
            case "not" -> {
                checkArgs(name, args, 1, 1);
                yield !truthy(args.get(0));
            }
            case "coalesce" -> {
                Object first = null;
                for (Object candidate : args) {
                    if (candidate != null) { first = candidate; break; }
                }
                yield first;
            }
            case "concat" -> {
                StringBuilder builder = new StringBuilder();
                for (Object arg : args) builder.append(stringValue(arg));
                yield builder.toString();
            }
            case "substring" -> {
                checkArgs(name, args, 2, 3);
                String text = String.valueOf(args.get(0));
                int start = number(args.get(1), name).intValue();
                int end = args.size() == 3 ? number(args.get(2), name).intValue() : text.length();
                yield text.substring(Math.max(0, start), Math.min(text.length(), end));
            }
            case "str" -> {
                checkArgs(name, args, 1, 1);
                yield stringValue(args.get(0));
            }
            case "int" -> {
                checkArgs(name, args, 1, 1);
                yield number(args.get(0), name).setScale(0, RoundingMode.DOWN);
            }
            case "min" -> aggregate(args, true);
            case "max" -> aggregate(args, false);
            case "abs" -> {
                checkArgs(name, args, 1, 1);
                yield number(args.get(0), name).abs();
            }
            default -> throw error("Unknown function " + name);
        };
    }

    private Object callMethod(Object receiver, List<Object> args) {
        if (receiver instanceof List<?> list && args.size() == 1) {
            int index = number(args.get(0), "[]").intValue();
            if (index < 0) index += list.size();
            return index >= 0 && index < list.size() ? list.get(index) : null;
        }
        throw error("Unsupported method call");
    }

    private Object applyBinary(String operator, Object left, Object right) {
        return switch (operator) {
            case "+" -> add(left, right);
            case "-" -> number(left, operator).subtract(number(right, operator));
            case "*" -> number(left, operator).multiply(number(right, operator));
            case "/" -> divide(left, right);
            case "%" -> number(left, operator).remainder(number(right, operator));
            case "==" -> Json.equalJson(left, right);
            case "!=" -> !Json.equalJson(left, right);
            case ">" -> compareNumbers(left, right, operator) > 0;
            case "<" -> compareNumbers(left, right, operator) < 0;
            case ">=" -> compareNumbers(left, right, operator) >= 0;
            case "<=" -> compareNumbers(left, right, operator) <= 0;
            case "&&" -> truthy(left) && truthy(right);
            case "||" -> truthy(left) || truthy(right);
            case "in" -> right instanceof List<?> list && list.contains(left);
            default -> throw error("Unsupported operator " + operator);
        };
    }

    private Object add(Object left, Object right) {
        if (left instanceof String || right instanceof String) return stringValue(left) + stringValue(right);
        if (left instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            copy.add(right);
            return copy;
        }
        return number(left, "+").add(number(right, "+"));
    }

    private BigDecimal divide(Object left, Object right) {
        BigDecimal a = number(left, "/");
        BigDecimal b = number(right, "/");
        if (b.signum() == 0) throw new ExprException("Division by zero");
        return a.divide(b, 10, RoundingMode.HALF_UP).stripTraZerosSafe();
    }

    private Object property(Object value, String name) {
        if (value instanceof Map<?, ?> map) return map.get(name);
        if (value == null) return null;
        if ("length".equals(name)) {
            if (value instanceof List<?> list) return BigDecimal.valueOf(list.size());
            if (value instanceof String text) return BigDecimal.valueOf(text.length());
        }
        return null;
    }

    private Object index(Object value, Object key) {
        if (value instanceof Map<?, ?> map) return map.get(String.valueOf(key));
        if (value instanceof List<?> list) {
            int index = number(key, "[]").intValue();
            if (index < 0) index += list.size();
            return index >= 0 && index < list.size() ? list.get(index) : null;
        }
        return null;
    }

    private static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof BigDecimal number) return number.signum() != 0;
        if (value instanceof String text) return !text.isEmpty();
        if (value instanceof List<?> list) return !list.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return true;
    }

    private static BigDecimal number(Object value, String context) {
        if (value instanceof BigDecimal number) return number;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (value instanceof String text) {
            try { return new BigDecimal(text); } catch (NumberFormatException ignored) {}
        }
        throw new ExprException("Expected number for " + context + ", got " + value);
    }

    private static Object negate(Object value) {
        return number(value, "-").negate();
    }

    private static int compareNumbers(Object left, Object right, String operator) {
        return number(left, operator).compareTo(number(right, operator));
    }

    private static Object aggregate(List<Object> values, boolean minimum) {
        if (values.isEmpty()) return null;
        BigDecimal result = number(values.get(0), minimum ? "min" : "max");
        for (int i = 1; i < values.size(); i++) {
            BigDecimal candidate = number(values.get(i), minimum ? "min" : "max");
            result = minimum ? result.min(candidate) : result.max(candidate);
        }
        return result;
    }

    private static String stringValue(Object value) {
        if (value == null) return "";
        if (value instanceof BigDecimal number) return number.stripTrailingZeros().toPlainString();
        return String.valueOf(value);
    }

    private static void checkArgs(String name, List<Object> args, int min, int max) {
        if (args.size() < min || args.size() > max) throw new ExprException("Bad arguments for " + name);
    }

    private static Integer infixPower(String text) {
        return switch (text) {
            case "||" -> 1;
            case "&&" -> 2;
            case "==", "!=", "in" -> 3;
            case "<", ">", "<=", ">=" -> 4;
            case "+", "-" -> 5;
            case "*", "/", "%" -> 6;
            default -> null;
        };
    }

    private static boolean leftAssociative(String text) {
        return true;
    }

    private Token nextToken() {
        if (position >= tokens.size()) throw error("Unexpected end of expression");
        return tokens.get(position++);
    }

    private Token peekToken() {
        return position < tokens.size() ? tokens.get(position) : new Token(TokenType.END, "", position);
    }

    private boolean consume(String text) {
        if (peekToken().text.equals(text)) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(String text) {
        if (!consume(text)) throw error("Expected " + text);
    }

    private IllegalArgumentException error(String message) {
        return new ExprException(message + " in expression: " + source);
    }

    private static List<Token> tokenize(String source) {
        List<Token> result = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int start = i;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_' || source.charAt(i) == '$')) i++;
                result.add(new Token(TokenType.IDENTIFIER, source.substring(start, i), start));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.')) i++;
                result.add(new Token(TokenType.NUMBER, source.substring(start, i), start));
            } else if (c == '"' || c == '\'') {
                int start = i++;
                StringBuilder builder = new StringBuilder();
                while (i < source.length() && source.charAt(i) != c) {
                    if (source.charAt(i) == '\\' && i + 1 < source.length()) {
                        i++;
                        char escaped = source.charAt(i++);
                        switch (escaped) {
                            case 'n' -> builder.append('\n');
                            case 't' -> builder.append('\t');
                            case 'r' -> builder.append('\r');
                            default -> builder.append(escaped);
                        }
                    } else {
                        builder.append(source.charAt(i++));
                    }
                }
                if (i >= source.length()) throw new ExprException("Unterminated string at " + start);
                i++;
                result.add(new Token(TokenType.STRING, builder.toString(), start));
            } else {
                String two = i + 1 < source.length() ? source.substring(i, i + 2) : "";
                if (two.equals("==") || two.equals("!=") || two.equals(">=") || two.equals("<=") || two.equals("&&") || two.equals("||")) {
                    result.add(new Token(TokenType.OPERATOR, two, i));
                    i += 2;
                } else {
                    result.add(new Token(TokenType.OPERATOR, String.valueOf(c), i));
                    i++;
                }
            }
        }
        return result;
    }

    private enum TokenType { NUMBER, STRING, IDENTIFIER, OPERATOR, END }
    private record Token(TokenType type, String text, int position) {}

    public static class ExprException extends RuntimeException {
        public ExprException(String message) { super(message); }
    }
}
