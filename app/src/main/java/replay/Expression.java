package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Expression {
    private final String source;
    private final List<Token> tokens;
    private int cursor;

    private Expression(String source) {
        this.source = source;
        this.tokens = tokenize(source);
    }

    public static Object evaluate(String expression, EvalContext context) {
        if (expression == null || expression.isBlank()) return Boolean.TRUE;
        return new Expression(expression).parseExpression(context);
    }

    public static boolean matches(String expression, EvalContext context) {
        Object value = evaluate(expression, context);
        return truth(value);
    }

    public interface EvalContext {
        Object root(String name);
    }

    private Object parseExpression(EvalContext context) {
        Object value = parseOr(context);
        if (cursor < tokens.size()) {
            throw error("Unexpected token " + tokens.get(cursor).text);
        }
        return value;
    }

    private Object parseOr(EvalContext context) {
        Object value = parseAnd(context);
        while (match("||")) {
            Object right = parseAnd(context);
            value = truth(value) || truth(right);
        }
        return value;
    }

    private Object parseAnd(EvalContext context) {
        Object value = parseEquality(context);
        while (match("&&")) {
            Object right = parseEquality(context);
            value = truth(value) && truth(right);
        }
        return value;
    }

    private Object parseEquality(EvalContext context) {
        Object value = parseComparison(context);
        while (true) {
            Token token = peek();
            if (has("==")) {
                cursor++;
                value = equalsValue(value, parseComparison(context));
            } else if (has("!=")) {
                cursor++;
                value = !equalsValue(value, parseComparison(context));
            } else {
                return value;
            }
        }
    }

    private Object parseComparison(EvalContext context) {
        Object value = parseAdditive(context);
        while (true) {
            Token token = peek();
            if (token != null && (token.text.equals(">=") || token.text.equals("<=") || token.text.equals(">") || token.text.equals("<"))) {
                cursor++;
                Object right = parseAdditive(context);
                value = compare(value, right, token.text);
            } else {
                return value;
            }
        }
    }

    private Object parseAdditive(EvalContext context) {
        Object value = parseMultiplicative(context);
        while (true) {
            Token token = peek();
            if (has("+") || has("-")) {
                cursor++;
                Object right = parseMultiplicative(context);
                value = arithmetic(value, right, token.text);
            } else {
                return value;
            }
        }
    }

    private Object parseMultiplicative(EvalContext context) {
        Object value = parseUnary(context);
        while (true) {
            Token token = peek();
            if (has("*") || has("/") || has("%")) {
                cursor++;
                Object right = parseUnary(context);
                value = arithmetic(value, right, token.text);
            } else {
                return value;
            }
        }
    }

    private Object parseUnary(EvalContext context) {
        if (has("!")) {
            cursor++;
            return !truth(parseUnary(context));
        }
        if (has("-")) {
            cursor++;
            return -asNumber(parseUnary(context));
        }
        return parsePostfix(context);
    }

    private Object parsePostfix(EvalContext context) {
        Object value = parsePrimary(context);
        while (true) {
            if (has(".")) {
                cursor++;
                Token name = require(TokenType.NAME);
                value = getMember(value, name.text);
            } else if (has("[")) {
                cursor++;
                Object index = parseOr(context);
                require("]");
                value = getIndex(value, index);
            } else {
                return value;
            }
        }
    }

    private Object parsePrimary(EvalContext context) {
        Token token = next();
        if (token == null) throw error("Unexpected end of expression");
        return switch (token.type) {
            case NUMBER -> token.text.contains(".") ? Double.parseDouble(token.text) : Long.parseLong(token.text);
            case STRING -> token.text;
            case BOOLEAN -> Boolean.parseBoolean(token.text);
            case NULL -> null;
            case NAME -> context.root(token.text);
            case PUNCTUATION -> {
                if ("(".equals(token.text)) {
                    Object value = parseOr(context);
                    require(")");
                    yield value;
                }
                if ("[".equals(token.text)) {
                    List<Object> values = new ArrayList<>();
                    if (!has("]")) {
                        values.add(parseOr(context));
                        while (consumeIf(",")) values.add(parseOr(context));
                        require("]");
                    } else {
                        require("]");
                    }
                    yield values;
                }
                throw error("Unexpected " + token.text);
            }
        };
    }

    private boolean has(String text) {
        Token token = peek();
        return token != null && token.text.equals(text);
    }

    private boolean match(String text) {
        if (has(text)) {
            cursor++;
            return true;
        }
        return false;
    }

    private boolean consumeIf(String text) {
        return match(text);
    }

    private Token peek() {
        return cursor < tokens.size() ? tokens.get(cursor) : null;
    }

    private Token next() {
        return cursor < tokens.size() ? tokens.get(cursor++) : null;
    }

    private void require(String text) {
        Token token = next();
        if (token == null || !token.text.equals(text)) throw error("Expected " + text);
    }

    private Token require(TokenType type) {
        Token token = next();
        if (token == null || token.type != type) throw error("Expected " + type);
        return token;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Invalid expression '" + source + "': " + message);
    }

    private static boolean truth(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.doubleValue() != 0;
        if (value instanceof String text) return !text.isEmpty();
        return true;
    }

    private static boolean equalsValue(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return ((Number) left).doubleValue() == ((Number) right).doubleValue();
        }
        return left == null ? right == null : left.equals(right);
    }

    private static boolean compare(Object left, Object right, String operator) {
        if (left instanceof Number && right instanceof Number) {
            double a = ((Number) left).doubleValue();
            double b = ((Number) right).doubleValue();
            return switch (operator) {
                case ">" -> a > b;
                case "<" -> a < b;
                case ">=" -> a >= b;
                case "<=" -> a <= b;
                default -> false;
            };
        }
        if (left instanceof String && right instanceof String) {
            int result = ((String) left).compareTo((String) right);
            return switch (operator) {
                case ">" -> result > 0;
                case "<" -> result < 0;
                case ">=" -> result >= 0;
                case "<=" -> result <= 0;
                default -> false;
            };
        }
        throw new IllegalArgumentException("Cannot compare " + typeName(left) + " with " + typeName(right));
    }

    private static Object arithmetic(Object leftObject, Object rightObject, String operator) {
        if ("+".equals(operator) && (leftObject instanceof String || rightObject instanceof String)) {
            return String.valueOf(leftObject) + rightObject;
        }
        double left = asNumber(leftObject);
        double right = asNumber(rightObject);
        double result = switch (operator) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> left / right;
            case "%" -> left % right;
            default -> throw new IllegalArgumentException("Unknown operator " + operator);
        };
        return result == Math.rint(result) && Math.abs(result) < 9007199254740992.0 ? (long) result : result;
    }

    private static double asNumber(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Character character) return character;
        throw new IllegalArgumentException("Expected number but got " + typeName(value));
    }

    @SuppressWarnings("unchecked")
    private static Object getMember(Object value, String name) {
        if (value instanceof Map<?, ?> map) return map.get(name);
        throw new IllegalArgumentException("Cannot read member '" + name + "' from " + typeName(value));
    }

    @SuppressWarnings("unchecked")
    private static Object getIndex(Object value, Object index) {
        if (value instanceof Map<?, ?> map && index instanceof String key) return map.get(key);
        if (value instanceof List<?> list && index instanceof Number number) {
            int i = number.intValue();
            return i >= 0 && i < list.size() ? list.get(i) : null;
        }
        return null;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"' || c == '\'') {
                StringBuilder text = new StringBuilder();
                char quote = c;
                i++;
                while (i < source.length() && source.charAt(i) != quote) {
                    if (source.charAt(i) == '\\' && i + 1 < source.length()) {
                        i++;
                        text.append(unescape(source.charAt(i)));
                    } else {
                        text.append(source.charAt(i));
                    }
                    i++;
                }
                if (i >= source.length()) throw new IllegalArgumentException("Unterminated string in expression");
                i++;
                tokens.add(new Token(TokenType.STRING, text.toString()));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < source.length() && Character.isDigit(source.charAt(i))) i++;
                if (i < source.length() && source.charAt(i) == '.') {
                    i++;
                    while (i < source.length() && Character.isDigit(source.charAt(i))) i++;
                }
                tokens.add(new Token(TokenType.NUMBER, source.substring(start, i)));
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                int start = i;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_' || source.charAt(i) == '$')) i++;
                String word = source.substring(start, i);
                TokenType type = switch (word) {
                    case "true", "false" -> TokenType.BOOLEAN;
                    case "null" -> TokenType.NULL;
                    default -> TokenType.NAME;
                };
                tokens.add(new Token(type, word));
            } else {
                String two = i + 1 < source.length() ? source.substring(i, i + 2) : "";
                if (two.equals("==") || two.equals("!=") || two.equals(">=") || two.equals("<=") || two.equals("&&") || two.equals("||")) {
                    tokens.add(new Token(TokenType.PUNCTUATION, two));
                    i += 2;
                } else {
                    tokens.add(new Token(TokenType.PUNCTUATION, String.valueOf(c)));
                    i++;
                }
            }
        }
        return tokens;
    }

    private static char unescape(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            default -> c;
        };
    }

    private enum TokenType { NUMBER, STRING, BOOLEAN, NULL, NAME, PUNCTUATION }

    private record Token(TokenType type, String text) {}
}
