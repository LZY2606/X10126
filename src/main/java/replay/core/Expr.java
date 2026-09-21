package replay.core;

import java.util.List;
import java.util.Map;

/**
 * Tiny deterministic expression evaluator used for event conditions and action values.
 * Supports: literals (numbers, 'strings', true/false/null), identifiers
 * ({@code state}, variables, {@code payload.field}), arithmetic, comparisons,
 * && / || / ! and parentheses.
 */
public final class Expr {

    public interface Scope {
        Object resolve(List<String> path);
    }

    private Expr() {}

    public static Object eval(String source, Scope scope) {
        List<Token> tokens = tokenize(source);
        Parser p = new Parser(tokens, scope);
        Object v = p.or();
        p.expectEnd();
        return v;
    }

    public static boolean evalBool(String source, Scope scope) {
        return truthy(eval(source, scope));
    }

    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) return !s.isEmpty();
        return true;
    }

    public static String stringify(Object v) {
        if (v == null) return "null";
        if (v instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) return Long.toString((long) (double) d);
            return Double.toString(d);
        }
        return String.valueOf(v);
    }

    /** Expands ${expr} templates inside a message. */
    public static String template(String message, Scope scope) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < message.length()) {
            int start = message.indexOf("${", i);
            if (start < 0) {
                out.append(message, i, message.length());
                break;
            }
            int end = message.indexOf('}', start + 2);
            if (end < 0) throw new IllegalArgumentException("unterminated ${...} in template");
            out.append(message, i, start);
            out.append(stringify(eval(message.substring(start + 2, end), scope)));
            i = end + 1;
        }
        return out.toString();
    }

    // ---- tokenizer ----

    private record Token(String kind, String text) {}

    private static List<Token> tokenize(String s) {
        List<Token> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                int j = i;
                boolean dot = false;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    if (s.charAt(j) == '.') {
                        if (dot) break;
                        dot = true;
                    }
                    j++;
                }
                out.add(new Token("num", s.substring(i, j)));
                i = j;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int j = i;
                while (j < s.length() && Character.isJavaIdentifierPart(s.charAt(j))) j++;
                out.add(new Token("ident", s.substring(i, j)));
                i = j;
                continue;
            }
            if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != c) {
                    if (s.charAt(j) == '\\' && j + 1 < s.length()) {
                        char n = s.charAt(j + 1);
                        sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
                        j += 2;
                    } else {
                        sb.append(s.charAt(j++));
                    }
                }
                if (j >= s.length()) throw new IllegalArgumentException("unterminated string in expression");
                out.add(new Token("str", sb.toString()));
                i = j + 1;
                continue;
            }
            String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
            if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=")
                    || two.equals("<=") || two.equals(">=")) {
                out.add(new Token("op", two));
                i += 2;
                continue;
            }
            if ("+-*/%()!<>.".indexOf(c) >= 0) {
                out.add(new Token("op", String.valueOf(c)));
                i++;
                continue;
            }
            throw new IllegalArgumentException("unexpected character '" + c + "' in expression");
        }
        return out;
    }

    // ---- parser ----

    private static final class Parser {
        private final List<Token> tokens;
        private final Scope scope;
        private int pos;

        Parser(List<Token> tokens, Scope scope) {
            this.tokens = tokens;
            this.scope = scope;
        }

        private Token peek() { return pos < tokens.size() ? tokens.get(pos) : null; }

        private boolean at(String text) {
            Token t = peek();
            return t != null && t.text.equals(text);
        }

        private void take(String text) {
            if (!at(text)) throw new IllegalArgumentException("expected '" + text + "' in expression");
            pos++;
        }

        void expectEnd() {
            if (pos != tokens.size()) throw new IllegalArgumentException("trailing tokens in expression");
        }

        Object or() {
            Object left = and();
            while (at("||")) {
                pos++;
                Object right = and();
                left = truthy(left) || truthy(right);
            }
            return left;
        }

        Object and() {
            Object left = not();
            while (at("&&")) {
                pos++;
                Object right = not();
                left = truthy(left) && truthy(right);
            }
            return left;
        }

        Object not() {
            if (at("!")) {
                pos++;
                return !truthy(not());
            }
            return cmp();
        }

        Object cmp() {
            Object left = add();
            Token t = peek();
            if (t != null && List.of("==", "!=", "<", "<=", ">", ">=").contains(t.text)) {
                pos++;
                Object right = add();
                return switch (t.text) {
                    case "==" -> eq(left, right);
                    case "!=" -> !eq(left, right);
                    case "<" -> compare(left, right) < 0;
                    case "<=" -> compare(left, right) <= 0;
                    case ">" -> compare(left, right) > 0;
                    default -> compare(left, right) >= 0;
                };
            }
            return left;
        }

        Object add() {
            Object left = mul();
            while (at("+") || at("-")) {
                String op = tokens.get(pos++).text;
                Object right = mul();
                if (op.equals("+") && (left instanceof String || right instanceof String)) {
                    left = stringify(left) + stringify(right);
                } else {
                    left = num(op.equals("+") ? toDouble(left) + toDouble(right) : toDouble(left) - toDouble(right), left, right);
                }
            }
            return left;
        }

        Object mul() {
            Object left = unary();
            while (at("*") || at("/") || at("%")) {
                String op = tokens.get(pos++).text;
                Object right = unary();
                double a = toDouble(left), b = toDouble(right);
                double r = switch (op) {
                    case "*" -> a * b;
                    case "/" -> a / b;
                    default -> a % b;
                };
                left = num(r, left, right);
            }
            return left;
        }

        Object unary() {
            if (at("-")) {
                pos++;
                Object v = unary();
                return v instanceof Double ? -toDouble(v) : -toLong(v);
            }
            return primary();
        }

        Object primary() {
            Token t = peek();
            if (t == null) throw new IllegalArgumentException("unexpected end of expression");
            if (t.kind.equals("num")) {
                pos++;
                return t.text.contains(".") ? (Object) Double.parseDouble(t.text) : Long.parseLong(t.text);
            }
            if (t.kind.equals("str")) {
                pos++;
                return t.text;
            }
            if (at("(")) {
                pos++;
                Object v = or();
                take(")");
                return v;
            }
            if (t.kind.equals("ident")) {
                if (t.text.equals("true")) { pos++; return Boolean.TRUE; }
                if (t.text.equals("false")) { pos++; return Boolean.FALSE; }
                if (t.text.equals("null")) { pos++; return null; }
                List<String> path = new java.util.ArrayList<>();
                path.add(t.text);
                pos++;
                while (at(".")) {
                    pos++;
                    Token part = peek();
                    if (part == null || !part.kind.equals("ident"))
                        throw new IllegalArgumentException("expected field after '.'");
                    path.add(part.text);
                    pos++;
                }
                return scope.resolve(path);
            }
            throw new IllegalArgumentException("unexpected token '" + t.text + "'");
        }

        private static boolean eq(Object a, Object b) {
            if (a instanceof Number && b instanceof Number)
                return toDouble(a) == toDouble(b);
            return java.util.Objects.equals(a, b);
        }

        private static int compare(Object a, Object b) {
            if (a instanceof Number && b instanceof Number)
                return Double.compare(toDouble(a), toDouble(b));
            if (a instanceof String && b instanceof String)
                return ((String) a).compareTo((String) b);
            if (a instanceof Boolean && b instanceof Boolean)
                return ((Boolean) a).compareTo((Boolean) b);
            throw new IllegalArgumentException("cannot compare " + a + " and " + b);
        }

        private static double toDouble(Object v) {
            if (v instanceof Number n) return n.doubleValue();
            throw new IllegalArgumentException("expected number, got " + v);
        }

        private static long toLong(Object v) {
            if (v instanceof Number n) return n.longValue();
            throw new IllegalArgumentException("expected number, got " + v);
        }

        private static Object num(double r, Object a, Object b) {
            if (a instanceof Double || b instanceof Double) return r;
            if (r == Math.floor(r) && !Double.isInfinite(r)) return (long) r;
            return r;
        }
    }
}
