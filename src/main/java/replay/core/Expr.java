package replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic boolean/value expression language used by transition guards.
 * Evaluation depends only on the supplied snapshot: no clock, no I/O.
 *
 * Grammar (precedence low to high):
 *   or  := and ('||' and)*
 *   and := not ('&&' not)*
 *   not := '!' not | cmp
 *   cmp := add (('=='|'!='|'<'|'<='|'>'|'>=') add)?
 *   add := mul (('+'|'-') mul)*
 *   mul := unary (('*'|'/'|'%') unary)*
 *   unary := '-' unary | postfix
 *   postfix := primary ('.' NAME | '[' expr ']')*
 *   primary := NUMBER | STRING | 'true' | 'false' | 'null'
 *             | NAME '(' args ')' | NAME | '(' or ')'
 */
public final class Expr {
    private final String source;
    private final Node root;

    private Expr(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    public static Expr compile(String source) {
        if (source == null || source.isBlank()) {
            return new Expr(source == null ? "" : source, Literal.TRUE);
        }
        Parser p = new Parser(source);
        Node node = p.parseOr();
        p.end();
        return new Expr(source, node);
    }

    public Object eval(Scope scope) { return root.eval(scope); }

    public boolean test(Scope scope) { return truthy(root.eval(scope)); }

    @Override public String toString() { return source; }

    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        if (v instanceof String s) return !s.isEmpty();
        return true;
    }

    private interface Node {
        Object eval(Scope scope);
    }

    private enum Literal implements Node {
        TRUE(Boolean.TRUE), FALSE(Boolean.FALSE), NULL(null);
        private final Object value;
        Literal(Object value) { this.value = value; }
        @Override public Object eval(Scope scope) { return value; }
    }

    private record NumberLit(double value) implements Node {
        @Override public Object eval(Scope scope) {
            double rint = Math.rint(value);
            if (value == rint && Math.abs(value) < 9.007199254740992e15) return (long) rint;
            return value;
        }
    }

    private record StringLit(String value) implements Node {
        @Override public Object eval(Scope scope) { return value; }
    }

    private record Var(String name) implements Node {
        @Override public Object eval(Scope scope) { return scope.lookup(name); }
    }

    private record Unary(String op, Node inner) implements Node {
        @Override public Object eval(Scope scope) {
            Object v = inner.eval(scope);
            return switch (op) {
                case "-" -> -asNumber(v);
                case "!" -> !truthy(v);
                default -> throw new IllegalStateException(op);
            };
        }
    }

    private record Binary(String op, Node left, Node right) implements Node {
        @Override public Object eval(Scope scope) {
            if (op.equals("&&")) return truthy(left.eval(scope)) && truthy(right.eval(scope));
            if (op.equals("||")) return truthy(left.eval(scope)) || truthy(right.eval(scope));
            Object a = left.eval(scope);
            Object b = right.eval(scope);
            return switch (op) {
                case "+" -> plus(a, b);
                case "-" -> asNumber(a) - asNumber(b);
                case "*" -> asNumber(a) * asNumber(b);
                case "/" -> {
                    double d = asNumber(b);
                    if (d == 0.0) throw new IllegalArgumentException("division by zero");
                    yield asNumber(a) / d;
                }
                case "%" -> {
                    double d = asNumber(b);
                    if (d == 0.0) throw new IllegalArgumentException("modulo by zero");
                    yield asNumber(a) % d;
                }
                case "==" -> equals(a, b);
                case "!=" -> !equals(a, b);
                case "<" -> compare(a, b) < 0;
                case "<=" -> compare(a, b) <= 0;
                case ">" -> compare(a, b) > 0;
                case ">=" -> compare(a, b) >= 0;
                default -> throw new IllegalStateException(op);
            };
        }
    }

    private record Call(String name, List<Node> args) implements Node {
        @Override public Object eval(Scope scope) {
            List<Object> values = new ArrayList<>(args.size());
            for (Node arg : args) values.add(arg.eval(scope));
            return scope.call(name, values);
        }
    }

    private record Access(Node target, String member) implements Node {
        @Override public Object eval(Scope scope) { return getMember(target.eval(scope), member); }
    }

    private record Index(Node target, Node key) implements Node {
        @Override public Object eval(Scope scope) {
            Object k = key.eval(scope);
            if (k instanceof Number n && Math.rint(n.doubleValue()) == n.doubleValue()) {
                return getIndex(target.eval(scope), (int) n.longValue());
            }
            return getMember(target.eval(scope), String.valueOf(k));
        }
    }

    private record ObjectLit(List<String> keys, List<Node> values) implements Node {
        @Override public Object eval(Scope scope) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) map.put(keys.get(i), values.get(i).eval(scope));
            return map;
        }
    }

    private record ArrayLit(List<Node> items) implements Node {
        @Override public Object eval(Scope scope) {
            List<Object> list = new ArrayList<>(items.size());
            for (Node item : items) list.add(item.eval(scope));
            return list;
        }
    }

    private static Object plus(Object a, Object b) {
        if (a instanceof String || b instanceof String) return String.valueOf(a) + String.valueOf(b);
        return asNumber(a) + asNumber(b);
    }

    private static boolean equals(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return java.util.Objects.equals(a, b);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof Comparable ca && b != null && a.getClass().equals(b.getClass())) {
            return ca.compareTo(b);
        }
        throw new IllegalArgumentException("cannot compare " + typeOf(a) + " with " + typeOf(b));
    }

    private static double asNumber(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0.0;
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("not a number: " + s); }
        }
        throw new IllegalArgumentException("not a number: " + typeOf(v));
    }

    @SuppressWarnings("unchecked")
    static Object getMember(Object target, String member) {
        if (target instanceof Map<?, ?> map) return ((Map<String, Object>) map).get(member);
        return null;
    }

    static Object getIndex(Object target, int index) {
        if (target instanceof List<?> list) {
            int i = index < 0 ? list.size() + index : index;
            return (i >= 0 && i < list.size()) ? list.get(i) : null;
        }
        return null;
    }

    private static String typeOf(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Number) return "number";
        if (v instanceof String) return "string";
        return v.getClass().getSimpleName();
    }

    public interface Scope {
        Object lookup(String name);
        Object call(String name, List<Object> args);
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException(msg + " at column " + (pos + 1) + " in expression: " + s);
        }

        void end() {
            skipWs();
            if (pos < s.length()) throw err("unexpected character '" + s.charAt(pos) + "'");
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Node parseOr() {
            Node left = parseAnd();
            while (consume("||")) left = new Binary("||", left, parseAnd());
            return left;
        }

        Node parseAnd() {
            Node left = parseNot();
            while (consume("&&")) left = new Binary("&&", left, parseNot());
            return left;
        }

        Node parseNot() {
            skipWs();
            if (consume("!")) return new Unary("!", parseNot());
            return parseCmp();
        }

        Node parseCmp() {
            Node left = parseAdd();
            skipWs();
            String op = matchOp("==", "!=", "<=", ">=", "<", ">");
            if (op != null) return new Binary(op, left, parseAdd());
            return left;
        }

        Node parseAdd() {
            Node left = parseMul();
            while (true) {
                skipWs();
                String op = matchOp("+", "-");
                if (op == null) return left;
                left = new Binary(op, left, parseMul());
            }
        }

        Node parseMul() {
            Node left = parseUnary();
            while (true) {
                skipWs();
                String op = matchOp("*", "/", "%");
                if (op == null) return left;
                left = new Binary(op, left, parseUnary());
            }
        }

        Node parseUnary() {
            skipWs();
            if (consume("-")) return new Unary("-", parseUnary());
            return parsePostfix();
        }

        Node parsePostfix() {
            Node node = parsePrimary();
            while (true) {
                skipWs();
                if (pos < s.length() && s.charAt(pos) == '.') {
                    pos++;
                    node = new Access(node, parseName());
                } else if (pos < s.length() && s.charAt(pos) == '[') {
                    pos++;
                    Node key = parseOr();
                    skipWs();
                    if (pos >= s.length() || s.charAt(pos) != ']') throw err("expected ']'");
                    pos++;
                    node = new Index(node, key);
                } else {
                    return node;
                }
            }
        }

        Node parsePrimary() {
            skipWs();
            if (pos >= s.length()) throw err("unexpected end of expression");
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Node inner = parseOr();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ')') throw err("expected ')'");
                pos++;
                return inner;
            }
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"' || c == '\'') return new StringLit(parseQuoted(c));
            if (Character.isDigit(c)) return new NumberLit(parseNumber());
            if (isNameStart(c)) {
                String name = parseName();
                skipWs();
                if (pos < s.length() && s.charAt(pos) == '(') {
                    pos++;
                    List<Node> args = new ArrayList<>();
                    skipWs();
                    if (pos < s.length() && s.charAt(pos) != ')') {
                        args.add(parseOr());
                        while (true) {
                            skipWs();
                            if (pos < s.length() && s.charAt(pos) == ',') {
                                pos++;
                                args.add(parseOr());
                            } else break;
                        }
                    }
                    skipWs();
                    if (pos >= s.length() || s.charAt(pos) != ')') throw err("expected ')'");
                    pos++;
                    return new Call(name, args);
                }
                return switch (name) {
                    case "true" -> Literal.TRUE;
                    case "false" -> Literal.FALSE;
                    case "null" -> Literal.NULL;
                    default -> new Var(name);
                };
            }
            throw err("unexpected character '" + c + "'");
        }

        Node parseObject() {
            pos++;
            List<String> keys = new ArrayList<>();
            List<Node> values = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return new ObjectLit(keys, values); }
            while (true) {
                skipWs();
                String key;
                if (pos < s.length() && (s.charAt(pos) == '"' || s.charAt(pos) == '\'')) {
                    key = parseQuoted(s.charAt(pos));
                } else {
                    key = parseName();
                }
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw err("expected ':'");
                pos++;
                values.add(parseOr());
                keys.add(key);
                skipWs();
                if (pos < s.length() && s.charAt(pos) == '}') { pos++; break; }
                if (pos >= s.length() || s.charAt(pos) != ',') throw err("expected ',' or '}'");
                pos++;
            }
            return new ObjectLit(keys, values);
        }

        Node parseArray() {
            pos++;
            List<Node> items = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return new ArrayLit(items); }
            while (true) {
                items.add(parseOr());
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ']') { pos++; break; }
                if (pos >= s.length() || s.charAt(pos) != ',') throw err("expected ',' or ']'");
                pos++;
                skipWs();
            }
            return new ArrayLit(items);
        }

        private double parseNumber() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c == '+' || c == '-') && pos > start
                        && s.charAt(pos - 1) != 'e' && s.charAt(pos - 1) != 'E') break;
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E'
                        || c == '+' || c == '-') pos++;
                else break;
            }
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw err("invalid number");
            }
        }

        private String parseQuoted(char quote) {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == quote) return sb.toString();
                if (c == '\\' && pos < s.length()) sb.append(s.charAt(pos++));
                else sb.append(c);
            }
            throw err("unterminated string");
        }

        private String parseName() {
            skipWs();
            int start = pos;
            while (pos < s.length() && isNamePart(s.charAt(pos))) pos++;
            if (start == pos) throw err("expected name");
            return s.substring(start, pos);
        }

        private boolean consume(String token) {
            skipWs();
            if (s.startsWith(token, pos)) { pos += token.length(); return true; }
            return false;
        }

        private String matchOp(String... ops) {
            for (String op : ops) if (consume(op)) return op;
            return null;
        }

        private static boolean isNameStart(char c) {
            return Character.isLetter(c) || c == '_' || c == '$';
        }

        private static boolean isNamePart(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$';
        }
    }
}
