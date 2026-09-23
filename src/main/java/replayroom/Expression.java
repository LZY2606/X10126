package replayroom;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class Expression {
    private final String input;
    private int pos;

    private Expression(String input) {
        this.input = input;
    }

    static Object evaluate(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return Boolean.TRUE;
        }
        return new Expression(expression).parseExpression().evaluate(context);
    }

    static boolean test(String expression, Map<String, Object> context) {
        return truth(evaluate(expression, context));
    }

    private Node parseExpression() {
        Node node = parseOr();
        skipWhitespace();
        if (pos < input.length()) {
            throw error("unexpected character");
        }
        return node;
    }

    private Node parseOr() {
        Node node = parseAnd();
        while (true) {
            skipWhitespace();
            if (!consume("||")) {
                return node;
            }
            node = new Binary("or", node, parseAnd());
        }
    }

    private Node parseAnd() {
        Node node = parseComparison();
        while (true) {
            skipWhitespace();
            if (!consume("&&")) {
                return node;
            }
            node = new Binary("and", node, parseComparison());
        }
    }

    private Node parseComparison() {
        Node node = parseAddition();
        skipWhitespace();
        for (String operator : List.of("==", "!=", "<=", ">=", "<", ">")) {
            if (consume(operator)) {
                return new Binary(operator, node, parseAddition());
            }
        }
        return node;
    }

    private Node parseAddition() {
        Node node = parseMultiplication();
        while (true) {
            skipWhitespace();
            if (consume("+")) {
                node = new Binary("+", node, parseMultiplication());
            } else if (consume("-")) {
                node = new Binary("-", node, parseMultiplication());
            } else {
                return node;
            }
        }
    }

    private Node parseMultiplication() {
        Node node = parseUnary();
        while (true) {
            skipWhitespace();
            if (consume("*")) {
                node = new Binary("*", node, parseUnary());
            } else if (consume("/")) {
                node = new Binary("/", node, parseUnary());
            } else {
                return node;
            }
        }
    }

    private Node parseUnary() {
        skipWhitespace();
        if (consume("!")) {
            return new Not(parseUnary());
        }
        if (consume("-")) {
            return new Negative(parseUnary());
        }
        return parsePrimary();
    }

    private Node parsePrimary() {
        skipWhitespace();
        if (consume("(")) {
            Node node = parseOr();
            skipWhitespace();
            expect(')');
            return node;
        }
        char c = peek();
        if (c == '"' || c == '\'') {
            return new Literal(readString(c));
        }
        if (Character.isDigit(c)) {
            return readNumber();
        }
        if (Character.isLetter(c) || c == '_') {
            String name = readIdentifier();
            skipWhitespace();
            if (consume("(")) {
                List<Node> args = new ArrayList<>();
                skipWhitespace();
                if (peek() != ')') {
                    args.add(parseOr());
                    skipWhitespace();
                    while (consume(",")) {
                        args.add(parseOr());
                        skipWhitespace();
                    }
                }
                expect(')');
                return new Call(name, args);
            }
            List<String> path = new ArrayList<>(List.of(name));
            while (consume(".")) {
                path.add(readIdentifier());
            }
            return new Variable(path);
        }
        throw error("expected expression value");
    }

    private Node readNumber() {
        int start = pos;
        while (Character.isDigit(peek())) {
            pos++;
        }
        if (peek() == '.') {
            pos++;
            while (Character.isDigit(peek())) {
                pos++;
            }
        }
        return new Literal(new BigDecimal(input.substring(start, pos)));
    }

    private String readString(char quote) {
        expect(quote);
        StringBuilder result = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c == quote) {
                return result.toString();
            }
            if (c == '\\' && pos < input.length()) {
                result.append(input.charAt(pos++));
            } else {
                result.append(c);
            }
        }
        throw error("unterminated string");
    }

    private String readIdentifier() {
        int start = pos;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_') {
                pos++;
            } else {
                break;
            }
        }
        return input.substring(start, pos);
    }

    private void skipWhitespace() {
        while (Character.isWhitespace(peek())) {
            pos++;
        }
    }

    private char peek() {
        return pos >= input.length() ? '\0' : input.charAt(pos);
    }

    private boolean consume(String value) {
        if (input.startsWith(value, pos)) {
            pos += value.length();
            return true;
        }
        return false;
    }

    private void expect(char value) {
        if (peek() != value) {
            throw error("expected '" + value + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " in expression at " + pos);
    }

    static boolean truth(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        return true;
    }

    private interface Node {
        Object evaluate(Map<String, Object> context);
    }

    private record Literal(Object value) implements Node {
        @Override
        public Object evaluate(Map<String, Object> context) {
            return Models.deepCopy(value);
        }
    }

    private record Variable(List<String> path) implements Node {
        @Override
        public Object evaluate(Map<String, Object> context) {
            Object current = context.get(path.get(0));
            for (int i = 1; i < path.size() && current != null; i++) {
                if (current instanceof Map<?, ?> map) {
                    current = map.get(path.get(i));
                } else {
                    return null;
                }
            }
            return current;
        }
    }

    private record Binary(String operator, Node left, Node right) implements Node {
        @Override
        public Object evaluate(Map<String, Object> context) {
            return switch (operator) {
                case "and" -> truth(left.evaluate(context)) && truth(right.evaluate(context));
                case "or" -> truth(left.evaluate(context)) || truth(right.evaluate(context));
                case "+", "-", "*", "/" -> arithmetic(context);
                default -> compare(context);
            };
        }

        private Object arithmetic(Map<String, Object> context) {
            BigDecimal leftValue = decimal(left.evaluate(context));
            BigDecimal rightValue = decimal(right.evaluate(context));
            return switch (operator) {
                case "+" -> leftValue.add(rightValue);
                case "-" -> leftValue.subtract(rightValue);
                case "*" -> leftValue.multiply(rightValue);
                case "/" -> leftValue.divide(rightValue);
                default -> throw new IllegalStateException(operator);
            };
        }

        private boolean compare(Map<String, Object> context) {
            Object leftValue = left.evaluate(context);
            Object rightValue = right.evaluate(context);
            int comparison;
            Number leftNumber = Numbers.number(leftValue);
            Number rightNumber = Numbers.number(rightValue);
            if (leftNumber != null && rightNumber != null) {
                comparison = new BigDecimal(leftNumber.toString())
                        .compareTo(new BigDecimal(rightNumber.toString()));
            } else {
                comparison = String.valueOf(leftValue).compareTo(String.valueOf(rightValue));
            }
            return switch (operator) {
                case "==" -> comparison == 0 && Objects.equals(leftValue == null, rightValue == null);
                case "!=" -> comparison != 0;
                case "<" -> comparison < 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case ">=" -> comparison >= 0;
                default -> throw new IllegalStateException(operator);
            };
        }

        private BigDecimal decimal(Object value) {
            Number number = Numbers.number(value);
            if (number == null) {
                throw new IllegalArgumentException("expected numeric value: " + value);
            }
            return new BigDecimal(number.toString());
        }
    }

    private record Not(Node node) implements Node {
        @Override
        public Object evaluate(Map<String, Object> context) {
            return !truth(node.evaluate(context));
        }
    }

    private record Negative(Node node) implements Node {
        @Override
        public Object evaluate(Map<String, Object> context) {
            Object value = node.evaluate(context);
            Number number = Numbers.number(value);
            if (number == null) {
                throw new IllegalArgumentException("cannot negate: " + value);
            }
            return new BigDecimal(number.toString()).negate();
        }
    }

    private record Call(String name, List<Node> arguments) implements Node {
        @Override
        public Object evaluate(Map<String, Object> context) {
            List<Object> values = arguments.stream().map(node -> node.evaluate(context)).toList();
            return switch (name) {
                case "exists" -> values.get(0) != null;
                case "missing" -> values.get(0) == null;
                case "length" -> length(values.get(0));
                case "contains" -> String.valueOf(values.get(0)).contains(String.valueOf(values.get(1)));
                default -> throw new IllegalArgumentException("unknown function: " + name);
            };
        }

        private long length(Object value) {
            if (value instanceof String text) {
                return text.length();
            }
            if (value instanceof List<?> list) {
                return list.size();
            }
            if (value instanceof Map<?, ?> map) {
                return map.size();
            }
            throw new IllegalArgumentException("length requires text, list, or object");
        }
    }
}
