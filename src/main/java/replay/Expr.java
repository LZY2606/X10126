package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Small deterministic expression language for conditions and action values.
 * Supports numbers, strings, booleans, identifiers (vars, "state", "payload.x"),
 * arithmetic, comparisons, &&/||/!, parentheses and randInt(n).
 */
public final class Expr {
    private final Node root;

    private Expr(Node root) { this.root = root; }

    public static Expr parse(String source) {
        Parser p = new Parser(source);
        Expr e = new Expr(p.parseOr());
        p.expectEnd();
        return e;
    }

    public Object eval(EvalContext ctx) { return root.eval(ctx); }

    public boolean evalBoolean(EvalContext ctx) { return truthy(root.eval(ctx)); }

    public static boolean truthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) return !s.isEmpty();
        return v != null;
    }

    public interface EvalContext {
        Object resolve(String name);
        long randInt(long bound);
    }

    private interface Node {
        Object eval(EvalContext ctx);
    }

    private record Literal(Object value) implements Node {
        public Object eval(EvalContext ctx) { return value; }
    }

    private record Ident(String name) implements Node {
        public Object eval(EvalContext ctx) { return ctx.resolve(name); }
    }

    private record RandInt(Node bound) implements Node {
        public Object eval(EvalContext ctx) {
            Object b = bound.eval(ctx);
            if (!(b instanceof Number n)) throw new EvalException("randInt bound must be a number");
            return ctx.randInt(n.longValue());
        }
    }

    private record Unary(String op, Node operand) implements Node {
        public Object eval(EvalContext ctx) {
            Object v = operand.eval(ctx);
            return switch (op) {
                case "!" -> !truthy(v);
                case "-" -> -asDouble(v, op);
                default -> throw new EvalException("unknown unary op " + op);
            };
        }
    }

    private record Binary(String op, Node left, Node right) implements Node {
        public Object eval(EvalContext ctx) {
            switch (op) {
                case "&&": return truthy(left.eval(ctx)) && truthy(right.eval(ctx));
                case "||": return truthy(left.eval(ctx)) || truthy(right.eval(ctx));
                default: {
                    Object l = left.eval(ctx);
                    Object r = right.eval(ctx);
                    return switch (op) {
                        case "==" -> valuesEqual(l, r);
                        case "!=" -> !valuesEqual(l, r);
                        case "<" -> asDouble(l, op) < asDouble(r, op);
                        case "<=" -> asDouble(l, op) <= asDouble(r, op);
                        case ">" -> asDouble(l, op) > asDouble(r, op);
                        case ">=" -> asDouble(l, op) >= asDouble(r, op);
                        case "+" -> {
                            if (l instanceof String || r instanceof String) yield String.valueOf(l) + r;
                            yield numeric(l, r, op);
                        }
                        case "-", "*", "/", "%" -> numeric(l, r, op);
                        default -> throw new EvalException("unknown op " + op);
                    };
                }
            }
        }

        private static Object numeric(Object l, Object r, String op) {
            double a = asDouble(l, op);
            double b = asDouble(r, op);
            boolean integral = l instanceof Long && r instanceof Long;
            double result = switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> {
                    if (b == 0) throw new EvalException("division by zero");
                    yield a / b;
                }
                case "%" -> {
                    if (b == 0) throw new EvalException("modulo by zero");
                    yield a % b;
                }
                default -> throw new EvalException("unknown op " + op);
            };
            if (integral && op.equals("/")) {
                if (result == Math.floor(result)) return (long) result;
                return result;
            }
            if (integral && !op.equals("/")) return (long) result;
            return result;
        }
    }

    static boolean valuesEqual(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        return java.util.Objects.equals(a, b);
    }

    static double asDouble(Object v, String op) {
        if (v instanceof Number n) return n.doubleValue();
        throw new EvalException("operator " + op + " needs numbers, got: " + v);
    }

    public static final class EvalException extends RuntimeException {
        public EvalException(String msg) { super(msg); }
    }

    // ---- recursive descent parser ----
    private static final class Parser {
        private final List<String> tokens = new ArrayList<>();
        private int pos;

        Parser(String src) { tokenize(src); }

        private void tokenize(String src) {
            int i = 0;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                if (Character.isDigit(c)) {
                    int start = i;
                    while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
                    tokens.add("num:" + src.substring(start, i));
                    continue;
                }
                if (Character.isLetter(c) || c == '_') {
                    int start = i;
                    while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_' || src.charAt(i) == '.')) i++;
                    tokens.add("id:" + src.substring(start, i));
                    continue;
                }
                if (c == '"' || c == '\'') {
                    char quote = c;
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (i < src.length() && src.charAt(i) != quote) {
                        if (src.charAt(i) == '\\' && i + 1 < src.length()) {
                            i++;
                            sb.append(switch (src.charAt(i)) {
                                case 'n' -> '\n';
                                case 't' -> '\t';
                                default -> src.charAt(i);
                            });
                        } else sb.append(src.charAt(i));
                        i++;
                    }
                    i++; // closing quote
                    tokens.add("str:" + sb);
                    continue;
                }
                String two = i + 1 < src.length() ? src.substring(i, i + 2) : "";
                if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=") || two.equals("&&") || two.equals("||")) {
                    tokens.add("op:" + two);
                    i += 2;
                    continue;
                }
                if ("+-*/%<>!()".indexOf(c) >= 0) {
                    tokens.add("op:" + c);
                    i++;
                    continue;
                }
                if (c == ',') { tokens.add("op:,"); i++; continue; }
                throw new EvalException("unexpected character '" + c + "' in expression");
            }
        }

        private String peek() { return pos < tokens.size() ? tokens.get(pos) : ""; }

        private String next() { return pos < tokens.size() ? tokens.get(pos++) : ""; }

        private boolean accept(String op) {
            if (peek().equals("op:" + op)) { pos++; return true; }
            return false;
        }

        private void expectEnd() {
            if (pos != tokens.size()) throw new EvalException("unexpected token: " + peek());
        }

        Node parseOr() {
            Node left = parseAnd();
            while (accept("||")) left = new Binary("||", left, parseAnd());
            return left;
        }

        private Node parseAnd() {
            Node left = parseEquality();
            while (accept("&&")) left = new Binary("&&", left, parseEquality());
            return left;
        }

        private Node parseEquality() {
            Node left = parseComparison();
            while (true) {
                if (accept("==")) left = new Binary("==", left, parseComparison());
                else if (accept("!=")) left = new Binary("!=", left, parseComparison());
                else return left;
            }
        }

        private Node parseComparison() {
            Node left = parseAdditive();
            while (true) {
                if (accept("<=")) left = new Binary("<=", left, parseAdditive());
                else if (accept(">=")) left = new Binary(">=", left, parseAdditive());
                else if (accept("<")) left = new Binary("<", left, parseAdditive());
                else if (accept(">")) left = new Binary(">", left, parseAdditive());
                else return left;
            }
        }

        private Node parseAdditive() {
            Node left = parseMultiplicative();
            while (true) {
                if (accept("+")) left = new Binary("+", left, parseMultiplicative());
                else if (accept("-")) left = new Binary("-", left, parseMultiplicative());
                else return left;
            }
        }

        private Node parseMultiplicative() {
            Node left = parseUnary();
            while (true) {
                if (accept("*")) left = new Binary("*", left, parseUnary());
                else if (accept("/")) left = new Binary("/", left, parseUnary());
                else if (accept("%")) left = new Binary("%", left, parseUnary());
                else return left;
            }
        }

        private Node parseUnary() {
            if (accept("!")) return new Unary("!", parseUnary());
            if (accept("-")) return new Unary("-", parseUnary());
            return parsePrimary();
        }

        private Node parsePrimary() {
            String tok = next();
            if (tok.isEmpty()) throw new EvalException("unexpected end of expression");
            if (tok.equals("op:(")) {
                Node inner = parseOr();
                if (!accept(")")) throw new EvalException("missing ')'");
                return inner;
            }
            if (tok.startsWith("num:")) {
                String n = tok.substring(4);
                return new Literal(n.contains(".") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n));
            }
            if (tok.startsWith("str:")) return new Literal(tok.substring(4));
            if (tok.startsWith("id:")) {
                String name = tok.substring(3);
                switch (name) {
                    case "true": return new Literal(Boolean.TRUE);
                    case "false": return new Literal(Boolean.FALSE);
                    case "null": return new Literal(null);
                    case "randInt": {
                        if (!accept("(")) throw new EvalException("randInt needs (bound)");
                        Node bound = parseOr();
                        if (!accept(")")) throw new EvalException("randInt missing ')'");
                        return new RandInt(bound);
                    }
                    default: return new Ident(name);
                }
            }
            throw new EvalException("unexpected token: " + tok);
        }
    }
}
