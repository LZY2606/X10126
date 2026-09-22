package replay.expr;

import java.util.List;
import java.util.Map;

/**
 * Tiny deterministic expression language for conditions and action values.
 * Supports: numbers, 'strings', true/false/null, identifiers, dotted paths
 * (payload.x), arithmetic (+ - * / %), comparisons, && || ! and parentheses.
 */
public final class Expr {

    public interface Scope {
        /** Resolve a dotted path such as "state", "count" or "payload.sensor". */
        Object resolve(List<String> path);
    }

    public static final class ExprException extends RuntimeException {
        public ExprException(String msg) { super(msg); }
    }

    private final Node root;

    private Expr(Node root) { this.root = root; }

    public static Expr parse(String text) {
        Parser p = new Parser(text);
        Expr e = new Expr(p.parseOr());
        p.skipWs();
        if (!p.atEnd()) throw new ExprException("unexpected trailing input at " + p.pos + " in: " + text);
        return e;
    }

    public Object eval(Scope scope) { return root.eval(scope); }

    public boolean evalBoolean(Scope scope) { return truthy(root.eval(scope)); }

    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    // ---------- AST ----------

    private interface Node {
        Object eval(Scope scope);
    }

    private static final class Literal implements Node {
        final Object value;
        Literal(Object value) { this.value = value; }
        public Object eval(Scope scope) { return value; }
    }

    private static final class Path implements Node {
        final List<String> parts;
        Path(List<String> parts) { this.parts = parts; }
        public Object eval(Scope scope) { return scope.resolve(parts); }
    }

    private static final class Not implements Node {
        final Node inner;
        Not(Node inner) { this.inner = inner; }
        public Object eval(Scope scope) { return !truthy(inner.eval(scope)); }
    }

    private static final class Neg implements Node {
        final Node inner;
        Neg(Node inner) { this.inner = inner; }
        public Object eval(Scope scope) { return -toDouble(inner.eval(scope)); }
    }

    private static final class And implements Node {
        final Node l, r;
        And(Node l, Node r) { this.l = l; this.r = r; }
        public Object eval(Scope scope) { return truthy(l.eval(scope)) && truthy(r.eval(scope)); }
    }

    private static final class Or implements Node {
        final Node l, r;
        Or(Node l, Node r) { this.l = l; this.r = r; }
        public Object eval(Scope scope) { return truthy(l.eval(scope)) || truthy(r.eval(scope)); }
    }

    private static final class Bin implements Node {
        final String op;
        final Node l, r;
        Bin(String op, Node l, Node r) { this.op = op; this.l = l; this.r = r; }
        public Object eval(Scope scope) {
            Object a = l.eval(scope);
            Object b = r.eval(scope);
            switch (op) {
                case "==": return looseEquals(a, b);
                case "!=": return !looseEquals(a, b);
                case "<": return compare(a, b) < 0;
                case "<=": return compare(a, b) <= 0;
                case ">": return compare(a, b) > 0;
                case ">=": return compare(a, b) >= 0;
                case "+":
                    if (a instanceof String || b instanceof String) return str(a) + str(b);
                    return num(a, b, (x, y) -> x + y);
                case "-": return num(a, b, (x, y) -> x - y);
                case "*": return num(a, b, (x, y) -> x * y);
                case "/": return num(a, b, (x, y) -> x / y);
                case "%": return num(a, b, (x, y) -> x % y);
                default: throw new ExprException("unknown operator " + op);
            }
        }
    }

    private interface DoubleOp { double apply(double x, double y); }

    private static Object num(Object a, Object b, DoubleOp op) {
        double x = toDouble(a);
        double y = toDouble(b);
        double r = op.apply(x, y);
        if (a instanceof Long && b instanceof Long && r == Math.floor(r) && !Double.isInfinite(r)) {
            return (long) r;
        }
        return r;
    }

    private static double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
        throw new ExprException("not a number: " + v);
    }

    private static String str(Object v) {
        return v == null ? "null" : v.toString();
    }

    private static boolean looseEquals(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    private static int compare(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof String && b instanceof String) {
            return ((String) a).compareTo((String) b);
        }
        throw new ExprException("cannot compare " + a + " and " + b);
    }

    // ---------- parser ----------

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        boolean tryMatch(String token) {
            skipWs();
            if (s.startsWith(token, pos)) {
                // avoid matching "==" when asked for "=" etc. handled by caller order
                pos += token.length();
                return true;
            }
            return false;
        }

        char peek() {
            skipWs();
            if (atEnd()) throw new ExprException("unexpected end of expression");
            return s.charAt(pos);
        }

        Node parseOr() {
            Node left = parseAnd();
            while (true) {
                if (tryMatch("||")) left = new Or(left, parseAnd());
                else return left;
            }
        }

        Node parseAnd() {
            Node left = parseNot();
            while (true) {
                if (tryMatch("&&")) left = new And(left, parseNot());
                else return left;
            }
        }

        Node parseNot() {
            if (tryMatch("!")) return new Not(parseNot());
            return parseCmp();
        }

        Node parseCmp() {
            Node left = parseAdd();
            skipWs();
            for (String op : new String[]{"==", "!=", "<=", ">=", "<", ">"}) {
                if (tryMatch(op)) return new Bin(op, left, parseAdd());
            }
            return left;
        }

        Node parseAdd() {
            Node left = parseMul();
            while (true) {
                if (tryMatch("+")) left = new Bin("+", left, parseMul());
                else if (tryMatch("-")) left = new Bin("-", left, parseMul());
                else return left;
            }
        }

        Node parseMul() {
            Node left = parseUnary();
            while (true) {
                if (tryMatch("*")) left = new Bin("*", left, parseUnary());
                else if (tryMatch("/")) left = new Bin("/", left, parseUnary());
                else if (tryMatch("%")) left = new Bin("%", left, parseUnary());
                else return left;
            }
        }

        Node parseUnary() {
            skipWs();
            if (tryMatch("-")) return new Neg(parseUnary());
            return parsePrimary();
        }

        Node parsePrimary() {
            skipWs();
            char c = peek();
            if (c == '(') {
                pos++;
                Node n = parseOr();
                skipWs();
                if (atEnd() || s.charAt(pos) != ')') throw new ExprException("missing ')'");
                pos++;
                return n;
            }
            if (c == '\'') return new Literal(parseString());
            if (Character.isDigit(c)) return new Literal(parseNumber());
            if (Character.isLetter(c) || c == '_') {
                String ident = parseIdent();
                switch (ident) {
                    case "true": return new Literal(Boolean.TRUE);
                    case "false": return new Literal(Boolean.FALSE);
                    case "null": return new Literal(null);
                    default:
                        java.util.List<String> parts = new java.util.ArrayList<>();
                        parts.add(ident);
                        while (tryMatch(".")) parts.add(parseIdent());
                        return new Path(parts);
                }
            }
            throw new ExprException("unexpected character '" + c + "' at " + pos);
        }

        String parseIdent() {
            skipWs();
            int start = pos;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_') pos++;
                else break;
            }
            if (start == pos) throw new ExprException("expected identifier at " + pos);
            return s.substring(start, pos);
        }

        String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw new ExprException("unterminated string");
                char c = s.charAt(pos++);
                if (c == '\'') return sb.toString();
                if (c == '\\' && !atEnd()) {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case '\'': sb.append('\''); break;
                        case '\\': sb.append('\\'); break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object parseNumber() {
            int start = pos;
            while (!atEnd() && Character.isDigit(s.charAt(pos))) pos++;
            boolean isDouble = false;
            if (!atEnd() && s.charAt(pos) == '.' && pos + 1 < s.length()
                    && Character.isDigit(s.charAt(pos + 1))) {
                isDouble = true;
                pos++;
                while (!atEnd() && Character.isDigit(s.charAt(pos))) pos++;
            }
            String num = s.substring(start, pos);
            return isDouble ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
        }
    }
}
