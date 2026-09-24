package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Small deterministic expression language for conditions and action values.
 * Supports: numbers, 'strings', true/false/null, identifiers, dot access (payload.x),
 * == != < <= > >= && || ! + - * / % and parentheses. Keywords and/or/not also work.
 */
public final class Expr {

    public static final class EvalException extends RuntimeException {
        public EvalException(String msg) { super(msg); }
    }

    private Expr() {}

    public static Object eval(String src, Map<String, Object> scope) {
        List<Token> tokens = tokenize(src);
        Parser p = new Parser(tokens, scope);
        Object v = p.parseOr();
        if (p.peek().type != TokenType.EOF) throw new EvalException("表达式存在多余内容: " + src);
        return v;
    }

    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    enum TokenType { NUMBER, STRING, IDENT, OP, EOF }

    static final class Token {
        final TokenType type;
        final String text;
        Token(TokenType t, String x) { type = t; text = x; }
    }

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                int j = i;
                boolean dot = false;
                while (j < s.length()) {
                    char d = s.charAt(j);
                    if (Character.isDigit(d)) j++;
                    else if (d == '.' && !dot) { dot = true; j++; }
                    else break;
                }
                out.add(new Token(TokenType.NUMBER, s.substring(i, j)));
                i = j;
                continue;
            }
            if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                char quote = c;
                i++;
                while (i < s.length() && s.charAt(i) != quote) {
                    char d = s.charAt(i++);
                    if (d == '\\' && i < s.length()) {
                        char e = s.charAt(i++);
                        switch (e) {
                            case 'n': sb.append('\n'); break;
                            case 't': sb.append('\t'); break;
                            case 'r': sb.append('\r'); break;
                            default: sb.append(e);
                        }
                    } else sb.append(d);
                }
                if (i >= s.length()) throw new EvalException("字符串未闭合: " + s);
                i++;
                out.add(new Token(TokenType.STRING, sb.toString()));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                String word = s.substring(i, j);
                switch (word) {
                    case "and": out.add(new Token(TokenType.OP, "&&")); break;
                    case "or": out.add(new Token(TokenType.OP, "||")); break;
                    case "not": out.add(new Token(TokenType.OP, "!")); break;
                    default: out.add(new Token(TokenType.IDENT, word));
                }
                i = j;
                continue;
            }
            String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
            if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")
                    || two.equals("&&") || two.equals("||")) {
                out.add(new Token(TokenType.OP, two));
                i += 2;
                continue;
            }
            if ("+-*/%<>!().".indexOf(c) >= 0) {
                out.add(new Token(TokenType.OP, String.valueOf(c)));
                i++;
                continue;
            }
            throw new EvalException("无法识别的字符 '" + c + "' 于表达式: " + s);
        }
        out.add(new Token(TokenType.EOF, ""));
        return out;
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final Map<String, Object> scope;
        private int pos;

        Parser(List<Token> tokens, Map<String, Object> scope) {
            this.tokens = tokens;
            this.scope = scope;
        }

        Token peek() { return tokens.get(pos); }
        Token next() { return tokens.get(pos++); }

        boolean acceptOp(String op) {
            if (peek().type == TokenType.OP && peek().text.equals(op)) { pos++; return true; }
            return false;
        }

        Object parseOr() {
            Object l = parseAnd();
            while (acceptOp("||")) {
                Object r = parseAnd();
                l = truthy(l) || truthy(r);
            }
            return l;
        }

        Object parseAnd() {
            Object l = parseEquality();
            while (acceptOp("&&")) {
                Object r = parseEquality();
                l = truthy(l) && truthy(r);
            }
            return l;
        }

        Object parseEquality() {
            Object l = parseRelational();
            while (true) {
                if (acceptOp("==")) l = valuesEqual(l, parseRelational());
                else if (acceptOp("!=")) l = !valuesEqual(l, parseRelational());
                else return l;
            }
        }

        Object parseRelational() {
            Object l = parseAdditive();
            while (true) {
                if (acceptOp("<")) l = compare(l, parseAdditive()) < 0;
                else if (acceptOp("<=")) l = compare(l, parseAdditive()) <= 0;
                else if (acceptOp(">")) l = compare(l, parseAdditive()) > 0;
                else if (acceptOp(">=")) l = compare(l, parseAdditive()) >= 0;
                else return l;
            }
        }

        Object parseAdditive() {
            Object l = parseMultiplicative();
            while (true) {
                if (acceptOp("+")) {
                    Object r = parseMultiplicative();
                    if (l instanceof String || r instanceof String) l = stringify(l) + stringify(r);
                    else l = numeric(l, r, '+');
                } else if (acceptOp("-")) l = numeric(l, parseMultiplicative(), '-');
                else return l;
            }
        }

        Object parseMultiplicative() {
            Object l = parseUnary();
            while (true) {
                if (acceptOp("*")) l = numeric(l, parseUnary(), '*');
                else if (acceptOp("/")) l = numeric(l, parseUnary(), '/');
                else if (acceptOp("%")) l = numeric(l, parseUnary(), '%');
                else return l;
            }
        }

        Object parseUnary() {
            if (acceptOp("!")) return !truthy(parseUnary());
            if (acceptOp("-")) {
                Object v = parseUnary();
                if (v instanceof Double) return -((Double) v);
                if (v instanceof Number) return -((Number) v).longValue();
                throw new EvalException("无法对非数字取负: " + v);
            }
            return parsePrimary();
        }

        Object parsePrimary() {
            Token t = next();
            if (t.type == TokenType.NUMBER) {
                return t.text.contains(".") ? (Object) Double.parseDouble(t.text) : Long.parseLong(t.text);
            }
            if (t.type == TokenType.STRING) return t.text;
            if (t.type == TokenType.IDENT) {
                Object v;
                switch (t.text) {
                    case "true": v = Boolean.TRUE; break;
                    case "false": v = Boolean.FALSE; break;
                    case "null": v = null; break;
                    default:
                        if (!scope.containsKey(t.text)) throw new EvalException("未定义的变量: " + t.text);
                        v = scope.get(t.text);
                }
                while (acceptOp(".")) {
                    Token field = next();
                    if (field.type != TokenType.IDENT) throw new EvalException("'.' 后应为字段名");
                    if (v instanceof Map) v = Json.obj(v).get(field.text);
                    else throw new EvalException("无法访问字段 " + field.text + " (非对象)");
                }
                return v;
            }
            if (t.type == TokenType.OP && t.text.equals("(")) {
                Object v = parseOr();
                if (!acceptOp(")")) throw new EvalException("缺少 ')'");
                return v;
            }
            throw new EvalException("意外的记号: " + t.text);
        }

        private static String stringify(Object v) {
            return v == null ? "null" : v.toString();
        }

        private static boolean valuesEqual(Object a, Object b) {
            if (a instanceof Number && b instanceof Number) {
                return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue()) == 0;
            }
            return Objects.equals(a, b);
        }

        private static int compare(Object a, Object b) {
            if (a instanceof Number && b instanceof Number) {
                return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
            }
            if (a instanceof String && b instanceof String) return ((String) a).compareTo((String) b);
            if (a instanceof Boolean && b instanceof Boolean) return ((Boolean) a).compareTo((Boolean) b);
            throw new EvalException("无法比较: " + a + " 与 " + b);
        }

        private static Object numeric(Object a, Object b, char op) {
            if (!(a instanceof Number) || !(b instanceof Number)) {
                throw new EvalException("算术运算需要数字: " + a + " " + op + " " + b);
            }
            boolean integral = !(a instanceof Double) && !(b instanceof Double) && !(a instanceof Float) && !(b instanceof Float);
            if (integral) {
                long x = ((Number) a).longValue();
                long y = ((Number) b).longValue();
                switch (op) {
                    case '+': return x + y;
                    case '-': return x - y;
                    case '*': return x * y;
                    case '/':
                        if (y == 0) throw new EvalException("除数为零");
                        return x / y;
                    case '%':
                        if (y == 0) throw new EvalException("取模除数为零");
                        return x % y;
                }
            } else {
                double x = ((Number) a).doubleValue();
                double y = ((Number) b).doubleValue();
                switch (op) {
                    case '+': return x + y;
                    case '-': return x - y;
                    case '*': return x * y;
                    case '/':
                        if (y == 0) throw new EvalException("除数为零");
                        return x / y;
                    case '%':
                        if (y == 0) throw new EvalException("取模除数为零");
                        return x % y;
                }
            }
            throw new EvalException("未知运算符 " + op);
        }
    }
}
