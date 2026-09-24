package replay;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ExpressionParser {
    interface Node {
        Object eval(Map<String, Object> context);
    }

    static boolean evaluate(String expression, Map<String, Object> context) {
        Object value = compile(expression).eval(context);
        return truth(value);
    }

    static Node compile(String input) {
        Parser parser = new Parser(input);
        Node node = parser.parseOr();
        parser.expectEnd();
        return node;
    }

    private static boolean truth(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean result) return result;
        if (value instanceof BigDecimal number) return number.compareTo(BigDecimal.ZERO) != 0;
        if (value instanceof Number number) return number.doubleValue() != 0.0;
        if (value instanceof String result) return !result.isEmpty() && !"false".equalsIgnoreCase(result);
        return true;
    }

    private static final class Parser {
        private final String input;
        private final List<String> tokens;
        private int index;

        private Parser(String input) {
            this.input = input == null ? "true" : input;
            this.tokens = tokenize(this.input);
        }

        private Node parseOr() {
            Node left = parseAnd();
            while (match("or") || match("||")) {
                Node right = parseAnd();
                left = context -> truth(left.eval(context)) || truth(right.eval(context));
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseNot();
            while (match("and") || match("&&")) {
                Node right = parseNot();
                left = context -> truth(left.eval(context)) && truth(right.eval(context));
            }
            return left;
        }

        private Node parseNot() {
            if (match("not") || match("!")) {
                Node node = parseNot();
                return context -> !truth(node.eval(context));
            }
            return parseComparison();
        }

        private Node parseComparison() {
            Node left = parseAdd();
            String operator = null;
            if (check("==") || check("!=") || check(">=") || check("<=") || check(">") || check("<")) {
                operator = tokens.get(index++);
            } else if (match("=")) {
                operator = "==";
            } else if (match("eq")) {
                operator = "==";
            } else if (match("ne")) {
                operator = "!=";
            } else if (match("gt")) {
                operator = ">";
            } else if (match("lt")) {
                operator = "<";
            } else if (match("ge")) {
                operator = ">=";
            } else if (match("le")) {
                operator = "<=";
            }
            if (operator == null) return left;
            Node right = parseAdd();
            String op = operator;
            return context -> compare(left.eval(context), right.eval(context), op);
        }

        private Node parseAdd() {
            Node left = parseMultiply();
            while (check("+") || check("-")) {
                String op = tokens.get(index++);
                Node right = parseMultiply();
                left = context -> arithmetic(left.eval(context), right.eval(context), op);
            }
            return left;
        }

        private Node parseMultiply() {
            Node left = parseUnary();
            while (check("*") || check("/") || check("%")) {
                String op = tokens.get(index++);
                Node right = parseUnary();
                left = context -> arithmetic(left.eval(context), right.eval(context), op);
            }
            return left;
        }

        private Node parseUnary() {
            if (match("-")) {
                Node node = parsePrimary();
                return context -> Json.decimal(node.eval(context)).negate();
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            if (match("(")) {
                Node node = parseOr();
                if (!match(")")) throw error("expected ')'");
                return node;
            }
            if (index >= tokens.size()) throw error("unexpected end");
            String token = tokens.get(index++);
            if (token.startsWith("'") || token.startsWith("\"")) {
                String value = token.substring(1, token.length() - 1);
                return context -> value;
            }
            if ("true".equals(token)) return context -> Boolean.TRUE;
            if ("false".equals(token)) return context -> Boolean.FALSE;
            if ("null".equals(token)) return context -> null;
            try {
                BigDecimal number = new BigDecimal(token);
                return context -> number;
            } catch (NumberFormatException ignored) {
                return context -> Paths.get(buildContextRoot(context), token);
            }
        }

        private boolean check(String token) {
            return index < tokens.size() && tokens.get(index).equals(token);
        }

        private boolean match(String token) {
            if (check(token)) {
                index++;
                return true;
            }
            return false;
        }

        private void expectEnd() {
            if (index != tokens.size()) throw error("unexpected token " + tokens.get(index));
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " in expression: " + input);
        }
    }

    private static Map<String, Object> buildContextRoot(Map<String, Object> context) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("state", context.get("state"));
        root.put("data", context.getOrDefault("data", new LinkedHashMap<>()));
        root.put("event", context.getOrDefault("event", new LinkedHashMap<>()));
        return root;
    }

    private static Boolean compare(Object left, Object right, String operator) {
        if (Objects.equals(left, right)) {
            return switch (operator) {
                case "==", ">=", "<=" -> true;
                default -> false;
            };
        }
        if (left == null || right == null) {
            return "!=".equals(operator);
        }
        if (isNumber(left) && isNumber(right)) {
            int compared = Json.decimal(left).compareTo(Json.decimal(right));
            return switch (operator) {
                case "==" -> compared == 0;
                case "!=" -> compared != 0;
                case ">" -> compared > 0;
                case "<" -> compared < 0;
                case ">=" -> compared >= 0;
                case "<=" -> compared <= 0;
                default -> false;
            };
        }
        int compared = String.valueOf(left).compareTo(String.valueOf(right));
        return switch (operator) {
            case "==" -> false;
            case "!=" -> true;
            case ">" -> compared > 0;
            case "<" -> compared < 0;
            case ">=" -> compared >= 0;
            case "<=" -> compared <= 0;
            default -> false;
        };
    }

    private static Object arithmetic(Object left, Object right, String operator) {
        if (!isNumber(left) || !isNumber(right)) {
            throw new IllegalArgumentException("arithmetic requires numbers");
        }
        BigDecimal a = Json.decimal(left);
        BigDecimal b = Json.decimal(right);
        return switch (operator) {
            case "+" -> a.add(b);
            case "-" -> a.subtract(b);
            case "*" -> a.multiply(b);
            case "/" -> a.divide(b);
            case "%" -> a.remainder(b);
            default -> throw new IllegalArgumentException("unsupported operator " + operator);
        };
    }

    private static boolean isNumber(Object value) {
        return value instanceof Number;
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        int pos = 0;
        while (pos < input.length()) {
            char ch = input.charAt(pos);
            if (Character.isWhitespace(ch)) {
                pos++;
            } else if (ch == '\'' || ch == '"') {
                int start = pos++;
                while (pos < input.length() && input.charAt(pos) != ch) pos++;
                if (pos >= input.length()) throw new IllegalArgumentException("unterminated string");
                tokens.add(input.substring(start, ++pos));
            } else if (">=<=!=&&||".indexOf(ch) >= 0) {
                if (pos + 1 < input.length() && (ch == '>' || ch == '<' || ch == '!' || ch == '=')
                        && input.charAt(pos + 1) == '=') {
                    tokens.add(input.substring(pos, pos + 2));
                    pos += 2;
                } else if ((ch == '&' || ch == '|') && pos + 1 < input.length()
                        && input.charAt(pos + 1) == ch) {
                    tokens.add(input.substring(pos, pos + 2));
                    pos += 2;
                } else {
                    tokens.add(String.valueOf(ch));
                    pos++;
                }
            } else if ("()+-*/%".indexOf(ch) >= 0) {
                tokens.add(String.valueOf(ch));
                pos++;
            } else {
                int start = pos;
                while (pos < input.length()) {
                    char current = input.charAt(pos);
                    if (Character.isWhitespace(current) || "()+-*/%<>=!&|".indexOf(current) >= 0) break;
                    pos++;
                }
                tokens.add(input.substring(start, pos));
            }
        }
        return tokens;
    }

    private ExpressionParser() {
    }
}
