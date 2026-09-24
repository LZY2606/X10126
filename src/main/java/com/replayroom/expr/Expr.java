package com.replayroom.expr;

import com.replayroom.core.LcgRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tiny deterministic expression language used for event conditions and
 * action expressions. Supports numbers, strings, booleans, variables,
 * {@code state}, {@code arg.<name>}, arithmetic, comparisons, logic
 * operators and {@code randInt(n)} driven by the session's seeded LCG.
 */
public final class Expr {

    public interface Context {
        Object variable(String name);

        String state();

        Object arg(String name);

        LcgRandom rng();
    }

    private Expr() {
    }

    public static Object eval(String source, Context ctx) {
        return new Parser(tokenize(source), ctx).parse();
    }

    public static boolean evalBool(String source, Context ctx) {
        return truthy(eval(source, ctx));
    }

    public static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        return value != null;
    }

    // ---------------- tokenizer ----------------

    private enum Kind { NUMBER, STRING, IDENT, OP, END }

    private record Token(Kind kind, String text) {
    }

    private static List<Token> tokenize(String src) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                int j = i;
                boolean dot = false;
                while (j < n && (Character.isDigit(src.charAt(j)) || (!dot && src.charAt(j) == '.'))) {
                    if (src.charAt(j) == '.') {
                        dot = true;
                    }
                    j++;
                }
                tokens.add(new Token(Kind.NUMBER, src.substring(i, j)));
                i = j;
                continue;
            }
            if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < n && src.charAt(j) != c) {
                    if (src.charAt(j) == '\\' && j + 1 < n) {
                        j++;
                        char esc = src.charAt(j);
                        sb.append(esc == 'n' ? '\n' : esc == 't' ? '\t' : esc);
                    } else {
                        sb.append(src.charAt(j));
                    }
                    j++;
                }
                if (j >= n) {
                    throw new IllegalArgumentException("unterminated string in expression: " + src);
                }
                tokens.add(new Token(Kind.STRING, sb.toString()));
                i = j + 1;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_' || src.charAt(j) == '.')) {
                    j++;
                }
                tokens.add(new Token(Kind.IDENT, src.substring(i, j)));
                i = j;
                continue;
            }
            String two = i + 1 < n ? src.substring(i, i + 2) : "";
            if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=")
                    || two.equals("<=") || two.equals(">=")) {
                tokens.add(new Token(Kind.OP, two));
                i += 2;
                continue;
            }
            if ("+-*/%!<>(),".indexOf(c) >= 0) {
                tokens.add(new Token(Kind.OP, String.valueOf(c)));
                i++;
                continue;
            }
            throw new IllegalArgumentException("unexpected character '" + c + "' in expression: " + src);
        }
        tokens.add(new Token(Kind.END, ""));
        return tokens;
    }

    // ---------------- parser / evaluator ----------------

    private static final class Parser {
        private final List<Token> tokens;
        private final Context ctx;
        private int pos;

        Parser(List<Token> tokens, Context ctx) {
            this.tokens = tokens;
            this.ctx = ctx;
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            return tokens.get(pos++);
        }

        private boolean atOp(String op) {
            Token t = peek();
            return t.kind() == Kind.OP && t.text().equals(op);
        }

        private void expectOp(String op) {
            if (!atOp(op)) {
                throw new IllegalArgumentException("expected '" + op + "' but found '" + peek().text() + "'");
            }
            next();
        }

        Object parse() {
            Object value = parseOr();
            if (peek().kind() != Kind.END) {
                throw new IllegalArgumentException("trailing input in expression near '" + peek().text() + "'");
            }
            return value;
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (atOp("||")) {
                next();
                Object right = parseAnd();
                left = truthy(left) || truthy(right);
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parseEq();
            while (atOp("&&")) {
                next();
                Object right = parseEq();
                left = truthy(left) && truthy(right);
            }
            return left;
        }

        private Object parseEq() {
            Object left = parseRel();
            while (atOp("==") || atOp("!=")) {
                String op = next().text();
                Object right = parseRel();
                boolean eq = valuesEqual(left, right);
                left = op.equals("==") ? eq : !eq;
            }
            return left;
        }

        private Object parseRel() {
            Object left = parseAdd();
            while (atOp("<") || atOp("<=") || atOp(">") || atOp(">=")) {
                String op = next().text();
                Object right = parseAdd();
                left = switch (op) {
                    case "<" -> compare(left, right) < 0;
                    case "<=" -> compare(left, right) <= 0;
                    case ">" -> compare(left, right) > 0;
                    default -> compare(left, right) >= 0;
                };
            }
            return left;
        }

        private Object parseAdd() {
            Object left = parseMul();
            while (atOp("+") || atOp("-")) {
                String op = next().text();
                Object right = parseMul();
                if (op.equals("+") && (left instanceof String || right instanceof String)) {
                    left = stringify(left) + stringify(right);
                } else if (op.equals("+")) {
                    left = numeric(left, right, (a, b) -> a + b, (a, b) -> a + b);
                } else {
                    left = numeric(left, right, (a, b) -> a - b, (a, b) -> a - b);
                }
            }
            return left;
        }

        private Object parseMul() {
            Object left = parseUnary();
            while (atOp("*") || atOp("/") || atOp("%")) {
                String op = next().text();
                Object right = parseUnary();
                left = switch (op) {
                    case "*" -> numeric(left, right, (a, b) -> a * b, (a, b) -> a * b);
                    case "/" -> numeric(left, right, (a, b) -> {
                        if (b == 0) {
                            throw new ArithmeticException("division by zero");
                        }
                        return a / b;
                    }, (a, b) -> a / b);
                    default -> numeric(left, right, (a, b) -> {
                        if (b == 0) {
                            throw new ArithmeticException("modulo by zero");
                        }
                        return a % b;
                    }, (a, b) -> a % b);
                };
            }
            return left;
        }

        private Object parseUnary() {
            if (atOp("!")) {
                next();
                return !truthy(parseUnary());
            }
            if (atOp("-")) {
                next();
                Object value = parseUnary();
                if (value instanceof Double d) {
                    return -d;
                }
                if (value instanceof Number n) {
                    return -n.longValue();
                }
                throw new IllegalArgumentException("cannot negate " + value);
            }
            return parsePrimary();
        }

        private Object parsePrimary() {
            Token t = next();
            switch (t.kind()) {
                case NUMBER -> {
                    if (t.text().contains(".")) {
                        return Double.parseDouble(t.text());
                    }
                    return Long.parseLong(t.text());
                }
                case STRING -> {
                    return t.text();
                }
                case IDENT -> {
                    String name = t.text();
                    switch (name) {
                        case "true" -> {
                            return Boolean.TRUE;
                        }
                        case "false" -> {
                            return Boolean.FALSE;
                        }
                        case "null" -> {
                            return null;
                        }
                        case "state" -> {
                            return ctx.state();
                        }
                        case "randInt" -> {
                            expectOp("(");
                            Object bound = parseOr();
                            expectOp(")");
                            long b = toLong(bound);
                            if (b <= 0) {
                                throw new IllegalArgumentException("randInt bound must be positive");
                            }
                            return ctx.rng().nextLong(b);
                        }
                        default -> {
                            if (name.startsWith("arg.")) {
                                return ctx.arg(name.substring("arg.".length()));
                            }
                            return ctx.variable(name);
                        }
                    }
                }
                case OP -> {
                    if (t.text().equals("(")) {
                        Object value = parseOr();
                        expectOp(")");
                        return value;
                    }
                    throw new IllegalArgumentException("unexpected '" + t.text() + "'");
                }
                default -> throw new IllegalArgumentException("unexpected end of expression");
            }
        }
    }

    // ---------------- value helpers ----------------

    private interface LongOp {
        long apply(long a, long b);
    }

    private interface DoubleOp {
        double apply(double a, double b);
    }

    private static Object numeric(Object a, Object b, LongOp longOp, DoubleOp doubleOp) {
        if (!(a instanceof Number na) || !(b instanceof Number nb)) {
            throw new IllegalArgumentException("numeric operator on non-numbers: " + a + ", " + b);
        }
        if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
            return doubleOp.apply(na.doubleValue(), nb.doubleValue());
        }
        return longOp.apply(na.longValue(), nb.longValue());
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException("expected number, got " + value);
    }

    public static boolean valuesEqual(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
                return na.doubleValue() == nb.doubleValue();
            }
            return na.longValue() == nb.longValue();
        }
        return java.util.Objects.equals(a, b);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            return Long.compare(na.longValue(), nb.longValue());
        }
        if (a instanceof Comparable c && b != null && a.getClass().isInstance(b)) {
            return c.compareTo(b);
        }
        throw new IllegalArgumentException("cannot compare " + a + " and " + b);
    }

    public static String stringify(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    /** Resolve dotted names like {@code arg.x} against a map. */
    public static Object lookupPath(Map<String, Object> map, String path) {
        Object current = map;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) {
                return null;
            }
            current = ((Map<String, Object>) m).get(part);
        }
        return current;
    }
}
