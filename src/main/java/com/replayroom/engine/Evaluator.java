package com.replayroom.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Safe expression language used by transition conditions and action values.
 *
 * Grammar (lowest to highest precedence):
 *   or  := and ('||' and)*
 *   and := cmp ('&&' cmp)*
 *   cmp := add (('==' | '!=' | '<' | '<=' | '>' | '>=') add)*
 *   add := mul (('+' | '-') mul)*
 *   mul := unary (('*' | '/' | '%') unary)*
 *   unary := '!' unary | '-' unary | primary
 *   primary := literal | path | function-call | '(' or ')'
 *
 * '+' concatenates when either side is a string. Missing data paths
 * evaluate to null; referencing an unknown identifier or calling an unknown
 * function raises EvalException, which rolls back the whole action step.
 */
public final class Evaluator {

    private final String source;
    private final List<Token> tokens;
    private int tokenIndex;
    private final EvalContext context;

    private Evaluator(String source, EvalContext context) {
        this.source = source;
        this.context = context;
        this.tokens = Tokenizer.tokenize(source);
    }

    public static Object evaluate(String expression, EvalContext context) {
        Evaluator evaluator = new Evaluator(expression, context);
        Object value = evaluator.parseOr();
        if (evaluator.peek().type != TokenType.END) {
            throw new EvalException("unexpected token '" + evaluator.peek().text + "' in expression");
        }
        return value;
    }

    public static boolean evaluateBoolean(String expression, EvalContext context) {
        return truthy(evaluate(expression, context));
    }

    private Object parseOr() {
        Object value = parseAnd();
        while (match("||")) {
            Object right = parseAnd();
            value = truthy(value) || truthy(right);
        }
        return value;
    }

    private Object parseAnd() {
        Object value = parseComparison();
        while (match("&&")) {
            Object right = parseComparison();
            value = truthy(value) && truthy(right);
        }
        return value;
    }

    private Object parseComparison() {
        Object value = parseAdd();
        while (true) {
            Token token = peek();
            if (token.type == TokenType.OP
                    && (token.text.equals("==") || token.text.equals("!=")
                    || token.text.equals("<") || token.text.equals("<=")
                    || token.text.equals(">") || token.text.equals(">="))) {
                next();
                Object right = parseAdd();
                value = compare(token.text, value, right);
            } else {
                return value;
            }
        }
    }

    private Object parseAdd() {
        Object value = parseMul();
        while (true) {
            Token token = peek();
            if (token.type == TokenType.OP && (token.text.equals("+") || token.text.equals("-"))) {
                next();
                Object right = parseMul();
                value = token.text.equals("+") ? add(value, right) : subtract(value, right);
            } else {
                return value;
            }
        }
    }

    private Object parseMul() {
        Object value = parseUnary();
        while (true) {
            Token token = peek();
            if (token.type == TokenType.OP
                    && (token.text.equals("*") || token.text.equals("/") || token.text.equals("%"))) {
                next();
                Object right = parseUnary();
                value = arithmetic(token.text, value, right);
            } else {
                return value;
            }
        }
    }

    private Object parseUnary() {
        Token token = peek();
        if (token.type == TokenType.OP && token.text.equals("!")) {
            next();
            return !truthy(parseUnary());
        }
        if (token.type == TokenType.OP && token.text.equals("-")) {
            next();
            Number number = asNumber(parseUnary());
            return negate(number);
        }
        return parsePrimary();
    }

    private Object parsePrimary() {
        Token token = next();
        switch (token.type) {
            case NUMBER:
                return token.value;
            case STRING:
                return token.text;
            case TRUE:
                return Boolean.TRUE;
            case FALSE:
                return Boolean.FALSE;
            case NULL:
                return null;
            case LPAREN: {
                Object value = parseOr();
                expectRParen();
                return value;
            }
            case IDENT: {
                if (peek().type == TokenType.LPAREN) {
                    next();
                    List<Object> args = new ArrayList<>();
                    if (peek().type != TokenType.RPAREN) {
                        args.add(parseOr());
                        while (matchComma()) {
                            args.add(parseOr());
                        }
                    }
                    expectRParen();
                    return callFunction(token.text, args);
                }
                return resolvePath(token.text);
            }
            default:
                throw new EvalException("unexpected token '" + token.text + "' in expression");
        }
    }

    // ------------------------------------------------------------------
    // Name and function resolution
    // ------------------------------------------------------------------

    private Object resolvePath(String firstSegment) {
        List<String> path = new ArrayList<>();
        path.add(firstSegment);
        while (peek().type == TokenType.DOT) {
            next();
            Token segment = next();
            if (segment.type != TokenType.IDENT) {
                throw new EvalException("expected property name after '.'");
            }
            path.add(segment.text);
        }
        Object root = context.lookupRoot(path.get(0));
        if (root == EvalContext.MISSING) {
            throw new EvalException("unknown identifier '" + path.get(0) + "'");
        }
        Object current = root;
        for (int i = 1; i < path.size(); i++) {
            if (current == null) {
                return null;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(path.get(i));
            } else {
                throw new EvalException("cannot read property '" + path.get(i)
                        + "' of non-object value");
            }
        }
        return current;
    }

    private Object callFunction(String name, List<Object> args) {
        switch (name) {
            case "rand": {
                if (args.size() != 1) {
                    throw new EvalException("rand(bound) expects exactly one argument");
                }
                Number bound = asNumber(args.get(0));
                long boundValue = bound.longValue();
                if (bound.doubleValue() != (double) boundValue) {
                    throw new EvalException("rand bound must be an integer");
                }
                return context.rng().nextBounded(boundValue);
            }
            case "rand01":
                if (!args.isEmpty()) {
                    throw new EvalException("rand01() takes no arguments");
                }
                return context.rng().nextUnit();
            case "abs":
                checkArgCount(name, args, 1);
                return abs(asNumber(args.get(0)));
            case "min":
                if (args.isEmpty()) {
                    throw new EvalException("min() requires at least one argument");
                }
                return minMax(args, true);
            case "max":
                if (args.isEmpty()) {
                    throw new EvalException("max() requires at least one argument");
                }
                return minMax(args, false);
            case "len":
                checkArgCount(name, args, 1);
                return length(args.get(0));
            case "not":
                checkArgCount(name, args, 1);
                return !truthy(args.get(0));
            case "concat": {
                StringBuilder sb = new StringBuilder();
                for (Object arg : args) {
                    sb.append(stringify(arg));
                }
                return sb.toString();
            }
            default:
                throw new EvalException("unknown function '" + name + "()'");
        }
    }

    private void checkArgCount(String name, List<Object> args, int expected) {
        if (args.size() != expected) {
            throw new EvalException(name + "() expects " + expected + " argument(s), got " + args.size());
        }
    }

    // ------------------------------------------------------------------
    // Operator semantics
    // ------------------------------------------------------------------

    private Object add(Object left, Object right) {
        if (left instanceof String || right instanceof String) {
            return stringify(left) + stringify(right);
        }
        Number a = asNumber(left);
        Number b = asNumber(right);
        if (a instanceof Double || b instanceof Double) {
            return a.doubleValue() + b.doubleValue();
        }
        return a.longValue() + b.longValue();
    }

    private Object subtract(Object left, Object right) {
        Number a = asNumber(left);
        Number b = asNumber(right);
        if (a instanceof Double || b instanceof Double) {
            return a.doubleValue() - b.doubleValue();
        }
        return a.longValue() - b.longValue();
    }

    private Object arithmetic(String operator, Object left, Object right) {
        Number a = asNumber(left);
        Number b = asNumber(right);
        boolean floating = a instanceof Double || b instanceof Double;
        return switch (operator) {
            case "*" -> floating ? a.doubleValue() * b.doubleValue() : a.longValue() * b.longValue();
            case "/" -> divide(a, b, floating);
            case "%" -> modulo(a, b, floating);
            default -> throw new EvalException("unknown operator " + operator);
        };
    }

    private Object divide(Number a, Number b, boolean floating) {
        if (floating) {
            double divisor = b.doubleValue();
            if (divisor == 0.0d) {
                throw new EvalException("division by zero");
            }
            return a.doubleValue() / divisor;
        }
        long divisor = b.longValue();
        if (divisor == 0L) {
            throw new EvalException("division by zero");
        }
        return a.longValue() / divisor;
    }

    private Object modulo(Number a, Number b, boolean floating) {
        if (floating) {
            double divisor = b.doubleValue();
            if (divisor == 0.0d) {
                throw new EvalException("modulo by zero");
            }
            return a.doubleValue() % divisor;
        }
        long divisor = b.longValue();
        if (divisor == 0L) {
            throw new EvalException("modulo by zero");
        }
        return a.longValue() % divisor;
    }

    private Object compare(String operator, Object left, Object right) {
        int result;
        if (left instanceof Number && right instanceof Number) {
            result = Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        } else {
            result = stringify(left).compareTo(stringify(right));
        }
        return switch (operator) {
            case "==" -> valueEquals(left, right);
            case "!=" -> !valueEquals(left, right);
            case "<" -> result < 0;
            case "<=" -> result <= 0;
            case ">" -> result > 0;
            case ">=" -> result >= 0;
            default -> throw new EvalException("unknown comparison " + operator);
        };
    }

    private boolean valueEquals(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return ((Number) left).doubleValue() == ((Number) right).doubleValue();
        }
        if (left == null) {
            return right == null;
        }
        return left.equals(right) || stringify(left).equals(stringify(right));
    }

    private Number asNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof Boolean bool) {
            return bool ? 1L : 0L;
        }
        if (value == null) {
            throw new EvalException("null cannot be used as a number");
        }
        try {
            String text = String.valueOf(value).trim();
            if (text.contains(".") || text.contains("e") || text.contains("E")) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new EvalException("value '" + value + "' is not a number");
        }
    }

    private Number negate(Number number) {
        if (number instanceof Double) {
            return -number.doubleValue();
        }
        return -number.longValue();
    }

    private Number abs(Number number) {
        if (number instanceof Double) {
            return Math.abs(number.doubleValue());
        }
        return Math.abs(number.longValue());
    }

    private Number minMax(List<Object> args, boolean minimum) {
        Number result = asNumber(args.get(0));
        for (int i = 1; i < args.size(); i++) {
            Number candidate = asNumber(args.get(i));
            int cmp = Double.compare(result.doubleValue(), candidate.doubleValue());
            if ((minimum && cmp > 0) || (!minimum && cmp < 0)) {
                result = candidate;
            }
        }
        return result;
    }

    private long length(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof String text) {
            return text.length();
        }
        if (value instanceof List<?> list) {
            return list.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        throw new EvalException("len() expects a string, list or object");
    }

    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        return true;
    }

    private String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e16) {
            return Long.toString(d.longValue());
        }
        return String.valueOf(value);
    }

    // ------------------------------------------------------------------
    // Token stream helpers
    // ------------------------------------------------------------------

    private Token peek() {
        return tokens.get(tokenIndex);
    }

    private Token next() {
        return tokens.get(tokenIndex++);
    }

    private boolean match(String text) {
        if (peek().type == TokenType.OP && peek().text.equals(text)) {
            tokenIndex++;
            return true;
        }
        return false;
    }

    private boolean matchComma() {
        if (peek().type == TokenType.COMMA) {
            tokenIndex++;
            return true;
        }
        return false;
    }

    private void expectRParen() {
        Token token = next();
        if (token.type != TokenType.RPAREN) {
            throw new EvalException("expected ')' but found '" + token.text + "'");
        }
    }
}

enum TokenType {
    IDENT, NUMBER, STRING, OP, LPAREN, RPAREN, COMMA, DOT, TRUE, FALSE, NULL, END
}

final class Token {
    final TokenType type;
    final String text;
    final Object value;

    Token(TokenType type, String text, Object value) {
        this.type = type;
        this.text = text;
        this.value = value;
    }

    @Override
    public String toString() {
        return type + "(" + text + ")";
    }
}

final class Tokenizer {

    private final String source;
    private int pos;
    private final List<Token> tokens = new ArrayList<>();

    private Tokenizer(String source) {
        this.source = source;
    }

    static List<Token> tokenize(String source) {
        Tokenizer tokenizer = new Tokenizer(source);
        tokenizer.scan();
        tokenizer.tokens.add(new Token(TokenType.END, "", null));
        return tokenizer.tokens;
    }

    private void scan() {
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (Character.isLetter(c) || c == '_') {
                readIdentifier();
            } else if (Character.isDigit(c)) {
                readNumber(false);
            } else if (c == '"' || c == '\'') {
                readString(c);
            } else {
                readOperatorOrPunctuation(c);
            }
        }
    }

    private void readIdentifier() {
        int start = pos;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_') {
                pos++;
            } else {
                break;
            }
        }
        String word = source.substring(start, pos);
        switch (word) {
            case "true":
                tokens.add(new Token(TokenType.TRUE, word, Boolean.TRUE));
                break;
            case "false":
                tokens.add(new Token(TokenType.FALSE, word, Boolean.FALSE));
                break;
            case "null":
                tokens.add(new Token(TokenType.NULL, word, null));
                break;
            default:
                tokens.add(new Token(TokenType.IDENT, word, word));
        }
    }

    private void readNumber(boolean negative) {
        int start = pos;
        while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
            pos++;
        }
        boolean floating = false;
        if (pos < source.length() && source.charAt(pos) == '.') {
            floating = true;
            pos++;
            while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
                pos++;
            }
        }
        if (pos < source.length() && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E')) {
            floating = true;
            pos++;
            if (pos < source.length() && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) {
                pos++;
            }
            while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
                pos++;
            }
        }
        String text = source.substring(start, pos);
        Number value = floating ? Double.parseDouble(text) : Long.parseLong(text);
        tokens.add(new Token(TokenType.NUMBER, text, value));
    }

    private void readString(char quote) {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < source.length()) {
            char c = source.charAt(pos++);
            if (c == quote) {
                tokens.add(new Token(TokenType.STRING, sb.toString(), sb.toString()));
                return;
            }
            if (c == '\\' && pos < source.length()) {
                char escaped = source.charAt(pos++);
                switch (escaped) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '"':
                        sb.append('"');
                        break;
                    case '\'':
                        sb.append('\'');
                        break;
                    default:
                        sb.append(escaped);
                }
            } else {
                sb.append(c);
            }
        }
        throw new EvalException("unterminated string literal");
    }

    private void readOperatorOrPunctuation(char c) {
        switch (c) {
            case '(':
                tokens.add(new Token(TokenType.LPAREN, "(", null));
                pos++;
                return;
            case ')':
                tokens.add(new Token(TokenType.RPAREN, ")", null));
                pos++;
                return;
            case ',':
                tokens.add(new Token(TokenType.COMMA, ",", null));
                pos++;
                return;
            case '.':
                if (pos + 1 < source.length() && Character.isDigit(source.charAt(pos + 1))) {
                    readNumber(false);
                    return;
                }
                tokens.add(new Token(TokenType.DOT, ".", null));
                pos++;
                return;
            case '=':
                if (consumeTwo('=', '=')) {
                    tokens.add(new Token(TokenType.OP, "==", null));
                    return;
                }
                throw new EvalException("unexpected '='; use '==' for equality");
            case '!':
                if (consumeTwo('!', '=')) {
                    tokens.add(new Token(TokenType.OP, "!=", null));
                } else {
                    tokens.add(new Token(TokenType.OP, "!", null));
                }
                return;
            case '<':
                if (consumeTwo('<', '=')) {
                    tokens.add(new Token(TokenType.OP, "<=", null));
                } else {
                    tokens.add(new Token(TokenType.OP, "<", null));
                }
                return;
            case '>':
                if (consumeTwo('>', '=')) {
                    tokens.add(new Token(TokenType.OP, ">=", null));
                } else {
                    tokens.add(new Token(TokenType.OP, ">", null));
                }
                return;
            case '&':
                requireSecond('&', "&&");
                tokens.add(new Token(TokenType.OP, "&&", null));
                return;
            case '|':
                requireSecond('|', "||");
                tokens.add(new Token(TokenType.OP, "||", null));
                return;
            case '+', '-', '*', '/', '%':
                tokens.add(new Token(TokenType.OP, String.valueOf(c), null));
                pos++;
                return;
            default:
                throw new EvalException("unexpected character '" + c + "'");
        }
    }

    private boolean consumeTwo(char first, char second) {
        if (pos + 1 < source.length() && source.charAt(pos + 1) == second) {
            pos += 2;
            return true;
        }
        pos++;
        return false;
    }

    private void requireSecond(char second, String operator) {
        if (pos + 1 >= source.length() || source.charAt(pos + 1) != second) {
            throw new EvalException("malformed operator near '" + source.charAt(pos)
                    + "'; did you mean '" + operator + "'?");
        }
        pos += 2;
    }
}
