package com.replayroom.expr;

import com.replayroom.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tiny deterministic expression language used for transition conditions and actions.
 * Supports: literals, variables, `state`, `payload.x`, arithmetic, comparison,
 * boolean operators and the seeded `randInt(n)` function.
 */
public final class Expr {
    private Expr() {}

    public interface RandomSource {
        long nextLong(long bound);
    }

    public static final class EvalException extends RuntimeException {
        public EvalException(String message) { super(message); }
    }

    public static final class Scope {
        public final Map<String, Object> vars;
        public final String state;
        public final Map<String, Object> payload;
        public final RandomSource random;

        public Scope(Map<String, Object> vars, String state, Map<String, Object> payload, RandomSource random) {
            this.vars = vars;
            this.state = state;
            this.payload = payload;
            this.random = random;
        }
    }

    public static Object eval(String source, Scope scope) {
        Parser p = new Parser(source);
        Node node = p.parseOr();
        p.expect(Token.Kind.EOF);
        return node.eval(scope);
    }

    /** Parse-only validation (used when a definition is registered). */
    public static void validate(String source) {
        Parser p = new Parser(source);
        p.parseOr();
        p.expect(Token.Kind.EOF);
    }

    public static boolean truthy(Object v) {
        if (v instanceof Boolean b) return b;
        throw new EvalException("expected boolean, got " + stringOf(v));
    }

    public static String stringOf(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return s;
        if (v instanceof Double d) {
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return Long.toString((long) d.doubleValue());
            return d.toString();
        }
        if (v instanceof Map || v instanceof List) return Json.canonical(v);
        return String.valueOf(v);
    }

    // ---------- AST ----------

    private interface Node {
        Object eval(Scope s);
    }

    private record Lit(Object value) implements Node {
        public Object eval(Scope s) { return value; }
    }

    private record Var(String name) implements Node {
        public Object eval(Scope s) {
            switch (name) {
                case "state": return s.state;
                case "payload": return s.payload;
                default:
                    if (s.vars.containsKey(name)) return s.vars.get(name);
                    throw new EvalException("undefined variable '" + name + "'");
            }
        }
    }

    private record Access(Node base, String key) implements Node {
        @SuppressWarnings("unchecked")
        public Object eval(Scope s) {
            Object b = base.eval(s);
            if (!(b instanceof Map)) throw new EvalException("cannot access field '" + key + "' of " + stringOf(b));
            return ((Map<String, Object>) b).get(key);
        }
    }

    private record Unary(String op, Node a) implements Node {
        public Object eval(Scope s) {
            Object v = a.eval(s);
            switch (op) {
                case "!": return !truthy(v);
                case "-":
                    if (v instanceof Long l) return -l;
                    if (v instanceof Number n) return -n.doubleValue();
                    throw new EvalException("cannot negate " + stringOf(v));
                default: throw new EvalException("unknown unary operator " + op);
            }
        }
    }

    private record Call(String name, List<Node> args) implements Node {
        public Object eval(Scope s) {
            if ("randInt".equals(name)) {
                if (args.size() != 1) throw new EvalException("randInt expects 1 argument");
                long bound = asLong(args.get(0).eval(s));
                if (bound <= 0) throw new EvalException("randInt bound must be positive");
                if (s.random == null) throw new EvalException("random source not available");
                return s.random.nextLong(bound);
            }
            throw new EvalException("unknown function '" + name + "'");
        }
    }

    private record Bin(String op, Node l, Node r) implements Node {
        public Object eval(Scope s) {
            switch (op) {
                case "&&": return truthy(l.eval(s)) && truthy(r.eval(s));
                case "||": return truthy(l.eval(s)) || truthy(r.eval(s));
                case "==": return looseEquals(l.eval(s), r.eval(s));
                case "!=": return !looseEquals(l.eval(s), r.eval(s));
                case "<": return compare(l.eval(s), r.eval(s)) < 0;
                case "<=": return compare(l.eval(s), r.eval(s)) <= 0;
                case ">": return compare(l.eval(s), r.eval(s)) > 0;
                case ">=": return compare(l.eval(s), r.eval(s)) >= 0;
                default: return arithmetic(op, l.eval(s), r.eval(s));
            }
        }
    }

    private static boolean looseEquals(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
                return na.doubleValue() == nb.doubleValue();
            }
            return na.longValue() == nb.longValue();
        }
        if (a == null || b == null) return a == b;
        if (a instanceof Boolean || b instanceof Boolean || a instanceof String || b instanceof String) return a.equals(b);
        return Json.canonical(a).equals(Json.canonical(b));
    }

    private static int compare(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof String sa && b instanceof String sb) return sa.compareTo(sb);
        if (a instanceof Boolean ba && b instanceof Boolean bb) return ba.compareTo(bb);
        throw new EvalException("cannot compare " + stringOf(a) + " and " + stringOf(b));
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        throw new EvalException("expected number, got " + stringOf(v));
    }

    private static Object arithmetic(String op, Object a, Object b) {
        if ("+".equals(op) && (a instanceof String || b instanceof String)) {
            return stringOf(a) + stringOf(b);
        }
        if (a instanceof Long la && b instanceof Long lb) {
            switch (op) {
                case "+": return la + lb;
                case "-": return la - lb;
                case "*": return la * lb;
                case "/":
                    if (lb == 0) throw new EvalException("division by zero");
                    return la / lb;
                case "%":
                    if (lb == 0) throw new EvalException("modulo by zero");
                    return la % lb;
                default: break;
            }
        }
        if (a instanceof Number na && b instanceof Number nb) {
            double x = na.doubleValue(), y = nb.doubleValue();
            switch (op) {
                case "+": return x + y;
                case "-": return x - y;
                case "*": return x * y;
                case "/": return x / y;
                case "%": return x % y;
                default: break;
            }
        }
        throw new EvalException("cannot apply '" + op + "' to " + stringOf(a) + " and " + stringOf(b));
    }

    // ---------- parser ----------

    private static final class Token {
        enum Kind { NUM, STR, IDENT, OP, LPAREN, RPAREN, COMMA, DOT, EOF }
        final Kind kind; final String text; final Object value;
        Token(Kind kind, String text, Object value) { this.kind = kind; this.text = text; this.value = value; }
    }

    private static final class Parser {
        private final String src;
        private final List<Token> tokens = new ArrayList<>();
        private int pos;

        Parser(String src) {
            this.src = src;
            tokenize();
        }

        private void tokenize() {
            int i = 0, n = src.length();
            while (i < n) {
                char c = src.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                    int start = i;
                    boolean dbl = false;
                    while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) {
                        if (src.charAt(i) == '.') dbl = true;
                        i++;
                    }
                    String t = src.substring(start, i);
                    tokens.add(new Token(Token.Kind.NUM, t, dbl ? (Object) Double.parseDouble(t) : Long.parseLong(t)));
                    continue;
                }
                if (Character.isLetter(c) || c == '_') {
                    int start = i;
                    while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                    tokens.add(new Token(Token.Kind.IDENT, src.substring(start, i), null));
                    continue;
                }
                if (c == '"' || c == '\'') {
                    char quote = c;
                    i++;
                    StringBuilder sb = new StringBuilder();
                    while (i < n && src.charAt(i) != quote) {
                        char ch = src.charAt(i);
                        if (ch == '\\' && i + 1 < n) {
                            char e = src.charAt(i + 1);
                            switch (e) {
                                case 'n': sb.append('\n'); break;
                                case 't': sb.append('\t'); break;
                                case 'r': sb.append('\r'); break;
                                default: sb.append(e);
                            }
                            i += 2;
                        } else {
                            sb.append(ch);
                            i++;
                        }
                    }
                    if (i >= n) throw new EvalException("unterminated string in expression: " + src);
                    i++;
                    tokens.add(new Token(Token.Kind.STR, sb.toString(), sb.toString()));
                    continue;
                }
                switch (c) {
                    case '(': tokens.add(new Token(Token.Kind.LPAREN, "(", null)); i++; continue;
                    case ')': tokens.add(new Token(Token.Kind.RPAREN, ")", null)); i++; continue;
                    case ',': tokens.add(new Token(Token.Kind.COMMA, ",", null)); i++; continue;
                    case '.': tokens.add(new Token(Token.Kind.DOT, ".", null)); i++; continue;
                    default: break;
                }
                String two = i + 1 < n ? src.substring(i, i + 2) : "";
                if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=")
                        || two.equals("<=") || two.equals(">=")) {
                    tokens.add(new Token(Token.Kind.OP, two, null));
                    i += 2;
                    continue;
                }
                if ("+-*/%!<>".indexOf(c) >= 0) {
                    tokens.add(new Token(Token.Kind.OP, String.valueOf(c), null));
                    i++;
                    continue;
                }
                throw new EvalException("unexpected character '" + c + "' in expression: " + src);
            }
            tokens.add(new Token(Token.Kind.EOF, "", null));
        }

        private Token peek() { return tokens.get(pos); }
        private Token next() { return tokens.get(pos++); }

        void expect(Token.Kind kind) {
            if (peek().kind != kind) {
                throw new EvalException("unexpected token '" + peek().text + "' in expression: " + src);
            }
            pos++;
        }

        private boolean acceptOp(String... ops) {
            if (peek().kind == Token.Kind.OP) {
                for (String op : ops) {
                    if (peek().text.equals(op)) return true;
                }
            }
            return false;
        }

        Node parseOr() {
            Node l = parseAnd();
            while (acceptOp("||")) { next(); l = new Bin("||", l, parseAnd()); }
            return l;
        }

        private Node parseAnd() {
            Node l = parseCmp();
            while (acceptOp("&&")) { next(); l = new Bin("&&", l, parseCmp()); }
            return l;
        }

        private Node parseCmp() {
            Node l = parseAdd();
            if (acceptOp("==", "!=", "<", "<=", ">", ">=")) {
                String op = next().text;
                l = new Bin(op, l, parseAdd());
            }
            return l;
        }

        private Node parseAdd() {
            Node l = parseMul();
            while (acceptOp("+", "-")) { String op = next().text; l = new Bin(op, l, parseMul()); }
            return l;
        }

        private Node parseMul() {
            Node l = parseUnary();
            while (acceptOp("*", "/", "%")) { String op = next().text; l = new Bin(op, l, parseUnary()); }
            return l;
        }

        private Node parseUnary() {
            if (acceptOp("!", "-")) { String op = next().text; return new Unary(op, parseUnary()); }
            return parsePostfix();
        }

        private Node parsePostfix() {
            Node node = parsePrimary();
            while (peek().kind == Token.Kind.DOT) {
                next();
                Token id = next();
                if (id.kind != Token.Kind.IDENT) throw new EvalException("expected field name after '.' in: " + src);
                node = new Access(node, id.text);
            }
            return node;
        }

        private Node parsePrimary() {
            Token t = next();
            switch (t.kind) {
                case NUM: return new Lit(t.value);
                case STR: return new Lit(t.value);
                case LPAREN: {
                    Node inner = parseOr();
                    expect(Token.Kind.RPAREN);
                    return inner;
                }
                case IDENT:
                    switch (t.text) {
                        case "true": return new Lit(Boolean.TRUE);
                        case "false": return new Lit(Boolean.FALSE);
                        case "null": return new Lit(null);
                        default: break;
                    }
                    if (peek().kind == Token.Kind.LPAREN) {
                        next();
                        List<Node> args = new ArrayList<>();
                        if (peek().kind != Token.Kind.RPAREN) {
                            args.add(parseOr());
                            while (peek().kind == Token.Kind.COMMA) { next(); args.add(parseOr()); }
                        }
                        expect(Token.Kind.RPAREN);
                        return new Call(t.text, args);
                    }
                    return new Var(t.text);
                default:
                    throw new EvalException("unexpected token '" + t.text + "' in expression: " + src);
            }
        }
    }
}
