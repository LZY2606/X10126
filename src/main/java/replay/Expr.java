package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tiny deterministic expression evaluator used by conditions and actions.
 * Supports numbers, strings, booleans, variables, arithmetic, comparison
 * and logical operators. Unknown variables or arithmetic errors raise
 * {@link EvalException} so the failing action can be rolled back.
 */
public final class Expr {

    public static class EvalException extends RuntimeException {
        public EvalException(String message) { super(message); }
    }

    private Expr() {}

    public static Object eval(String source, Map<String, Object> vars) {
        List<Token> tokens = lex(source);
        Parser parser = new Parser(tokens, vars);
        Object value = parser.parseOr();
        if (parser.pos != tokens.size()) {
            throw new EvalException("unexpected token '" + tokens.get(parser.pos).text + "' in: " + source);
        }
        return value;
    }

    public static boolean truthy(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof String s) return !s.isEmpty();
        return value != null;
    }

    // ---------- lexer ----------

    private record Token(String kind, String text, Object literal) {}

    private static List<Token> lex(String src) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (Character.isDigit(c)) {
                int j = i;
                boolean dot = false;
                while (j < src.length() && (Character.isDigit(src.charAt(j)) || src.charAt(j) == '.')) {
                    if (src.charAt(j) == '.') dot = true;
                    j++;
                }
                String text = src.substring(i, j);
                out.add(new Token("num", text, dot ? Double.parseDouble(text) : Long.parseLong(text)));
                i = j; continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < src.length() && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_')) j++;
                String word = src.substring(i, j);
                switch (word) {
                    case "true" -> out.add(new Token("num", word, Boolean.TRUE));
                    case "false" -> out.add(new Token("num", word, Boolean.FALSE));
                    default -> out.add(new Token("ident", word, null));
                }
                i = j; continue;
            }
            if (c == '\'' || c == '"') {
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < src.length() && src.charAt(j) != c) {
                    if (src.charAt(j) == '\\' && j + 1 < src.length()) j++;
                    sb.append(src.charAt(j));
                    j++;
                }
                if (j >= src.length()) throw new EvalException("unterminated string in: " + src);
                out.add(new Token("num", sb.toString(), sb.toString()));
                i = j + 1; continue;
            }
            String two = i + 1 < src.length() ? src.substring(i, i + 2) : "";
            if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")
                    || two.equals("&&") || two.equals("||")) {
                out.add(new Token("op", two, null));
                i += 2; continue;
            }
            if ("+-*/%<>=!()".indexOf(c) >= 0) {
                out.add(new Token("op", String.valueOf(c), null));
                i++; continue;
            }
            throw new EvalException("unexpected character '" + c + "' in: " + src);
        }
        return out;
    }

    // ---------- parser ----------

    private static final class Parser {
        final List<Token> tokens;
        final Map<String, Object> vars;
        int pos = 0;

        Parser(List<Token> tokens, Map<String, Object> vars) {
            this.tokens = tokens;
            this.vars = vars;
        }

        private Token peek() { return pos < tokens.size() ? tokens.get(pos) : null; }

        private boolean atOp(String op) {
            Token t = peek();
            return t != null && t.kind.equals("op") && t.text.equals(op);
        }

        private void expect(String op) {
            if (!atOp(op)) throw new EvalException("expected '" + op + "'");
            pos++;
        }

        Object parseOr() {
            Object left = parseAnd();
            while (atOp("||")) { pos++; left = truthy(left) || truthy(parseAnd()); }
            return left;
        }

        Object parseAnd() {
            Object left = parseEq();
            while (atOp("&&")) { pos++; left = truthy(left) && truthy(parseEq()); }
            return left;
        }

        Object parseEq() {
            Object left = parseRel();
            while (true) {
                if (atOp("==")) { pos++; left = Values.eq(left, parseRel()); }
                else if (atOp("!=")) { pos++; left = !Values.eq(left, parseRel()); }
                else return left;
            }
        }

        Object parseRel() {
            Object left = parseAdd();
            while (true) {
                if (atOp("<")) { pos++; left = Values.cmp(left, parseAdd()) < 0; }
                else if (atOp("<=")) { pos++; left = Values.cmp(left, parseAdd()) <= 0; }
                else if (atOp(">")) { pos++; left = Values.cmp(left, parseAdd()) > 0; }
                else if (atOp(">=")) { pos++; left = Values.cmp(left, parseAdd()) >= 0; }
                else return left;
            }
        }

        Object parseAdd() {
            Object left = parseMul();
            while (true) {
                if (atOp("+")) { pos++; left = Values.add(left, parseMul()); }
                else if (atOp("-")) { pos++; left = Values.sub(left, parseMul()); }
                else return left;
            }
        }

        Object parseMul() {
            Object left = parseUnary();
            while (true) {
                if (atOp("*")) { pos++; left = Values.mul(left, parseUnary()); }
                else if (atOp("/")) { pos++; left = Values.div(left, parseUnary()); }
                else if (atOp("%")) { pos++; left = Values.mod(left, parseUnary()); }
                else return left;
            }
        }

        Object parseUnary() {
            if (atOp("!")) { pos++; return !truthy(parseUnary()); }
            if (atOp("-")) { pos++; return Values.negate(parseUnary()); }
            return parsePrimary();
        }

        Object parsePrimary() {
            Token t = peek();
            if (t == null) throw new EvalException("unexpected end of expression");
            if (t.kind.equals("num")) { pos++; return t.literal; }
            if (t.kind.equals("ident")) {
                pos++;
                if (!vars.containsKey(t.text)) throw new EvalException("unknown variable: " + t.text);
                return vars.get(t.text);
            }
            if (atOp("(")) {
                pos++;
                Object v = parseOr();
                expect(")");
                return v;
            }
            throw new EvalException("unexpected token '" + t.text + "'");
        }
    }

    // ---------- value arithmetic ----------

    static final class Values {
        static boolean eq(Object a, Object b) {
            if (a instanceof Number x && b instanceof Number y) {
                return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
            }
            return a == null ? b == null : a.equals(b);
        }

        static int cmp(Object a, Object b) {
            if (a instanceof Number x && b instanceof Number y) {
                return Double.compare(x.doubleValue(), y.doubleValue());
            }
            if (a instanceof String x && b instanceof String y) return x.compareTo(y);
            throw new EvalException("cannot compare " + a + " and " + b);
        }

        private static boolean dbl(Object a, Object b) {
            return a instanceof Double || b instanceof Double;
        }

        private static long lng(Object v, String op) {
            if (v instanceof Number n) return n.longValue();
            throw new EvalException("operator " + op + " needs numbers, got: " + v);
        }

        static Object add(Object a, Object b) {
            if (a instanceof String || b instanceof String) return String.valueOf(a) + String.valueOf(b);
            if (dbl(a, b)) return ((Number) a).doubleValue() + ((Number) b).doubleValue();
            return lng(a, "+") + lng(b, "+");
        }

        static Object sub(Object a, Object b) {
            if (dbl(a, b)) return ((Number) a).doubleValue() - ((Number) b).doubleValue();
            return lng(a, "-") - lng(b, "-");
        }

        static Object mul(Object a, Object b) {
            if (dbl(a, b)) return ((Number) a).doubleValue() * ((Number) b).doubleValue();
            return lng(a, "*") * lng(b, "*");
        }

        static Object div(Object a, Object b) {
            if (dbl(a, b)) {
                double r = ((Number) b).doubleValue();
                if (r == 0) throw new EvalException("division by zero");
                return ((Number) a).doubleValue() / r;
            }
            long r = lng(b, "/");
            if (r == 0) throw new EvalException("division by zero");
            return lng(a, "/") / r;
        }

        static Object mod(Object a, Object b) {
            long r = lng(b, "%");
            if (r == 0) throw new EvalException("modulo by zero");
            return lng(a, "%") % r;
        }

        static Object negate(Object v) {
            if (v instanceof Double d) return -d;
            if (v instanceof Number n) return -n.longValue();
            throw new EvalException("cannot negate: " + v);
        }
    }
}
