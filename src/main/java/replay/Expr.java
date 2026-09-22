package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Tiny deterministic expression language for conditions and action arguments. */
public final class Expr {
    private Expr() {}

    public interface Env {
        Object get(String name);
        long rand(long bound);
    }

    public static final class EvalException extends RuntimeException {
        public EvalException(String message) { super(message); }
    }

    public static Object eval(String src, Env env) {
        return new Parser(src, env).parse();
    }

    private enum Kind { NUM, STR, IDENT, OP, LP, RP, COMMA, DOT, EOF }

    private record Tok(Kind kind, String text, Object val) {}

    private static final class Parser {
        private final List<Tok> toks;
        private final Env env;
        private int pos;

        Parser(String src, Env env) {
            this.toks = lex(src);
            this.env = env;
        }

        Object parse() {
            Object v = parseOr();
            if (peek().kind() != Kind.EOF) throw new EvalException("unexpected token: " + peek().text());
            return v;
        }

        private Tok peek() { return toks.get(pos); }

        private Tok next() { return toks.get(pos++); }

        private boolean isOp(String op) {
            Tok t = peek();
            return t.kind() == Kind.OP && t.text().equals(op);
        }

        private void expectOp(String op) {
            if (!isOp(op)) throw new EvalException("expected '" + op + "' but got " + peek().text());
            pos++;
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (isOp("||")) {
                pos++;
                Object right = parseAnd();
                left = bool(left, "||") || bool(right, "||");
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parseCmp();
            while (isOp("&&")) {
                pos++;
                Object right = parseCmp();
                left = bool(left, "&&") && bool(right, "&&");
            }
            return left;
        }

        private Object parseCmp() {
            Object left = parseAdd();
            while (true) {
                String op = null;
                for (String candidate : new String[] {"==", "!=", "<=", ">=", "<", ">"}) {
                    if (isOp(candidate)) {
                        op = candidate;
                        break;
                    }
                }
                if (op == null) return left;
                pos++;
                left = compare(op, left, parseAdd());
            }
        }

        private Object parseAdd() {
            Object left = parseMul();
            while (isOp("+") || isOp("-")) {
                String op = next().text();
                left = arith(op, left, parseMul());
            }
            return left;
        }

        private Object parseMul() {
            Object left = parseUnary();
            while (isOp("*") || isOp("/") || isOp("%")) {
                String op = next().text();
                left = arith(op, left, parseUnary());
            }
            return left;
        }

        private Object parseUnary() {
            if (isOp("!")) {
                pos++;
                return !bool(parseUnary(), "!");
            }
            if (isOp("-")) {
                pos++;
                Object v = parseUnary();
                if (v instanceof Long l) return -l;
                if (v instanceof Double d) return -d;
                throw new EvalException("cannot negate " + v);
            }
            return parsePrimary();
        }

        private Object parsePrimary() {
            Tok t = next();
            Object value;
            switch (t.kind()) {
                case NUM, STR -> value = t.val();
                case LP -> {
                    value = parseOr();
                    if (peek().kind() != Kind.RP) throw new EvalException("missing ')'");
                    pos++;
                }
                case IDENT -> {
                    String name = t.text();
                    switch (name) {
                        case "true" -> value = Boolean.TRUE;
                        case "false" -> value = Boolean.FALSE;
                        case "null" -> value = null;
                        case "rand" -> {
                            if (peek().kind() != Kind.LP) throw new EvalException("rand expects '('");
                            pos++;
                            Object bound = parseOr();
                            if (peek().kind() != Kind.RP) throw new EvalException("missing ')' after rand");
                            pos++;
                            if (!(bound instanceof Long b) || b <= 0) {
                                throw new EvalException("rand bound must be a positive integer");
                            }
                            value = env.rand(b);
                        }
                        default -> value = env.get(name);
                    }
                }
                default -> throw new EvalException("unexpected token: " + t.text());
            }
            while (peek().kind() == Kind.DOT) {
                pos++;
                Tok field = next();
                if (field.kind() != Kind.IDENT) throw new EvalException("expected field name after '.'");
                if (!(value instanceof Map)) {
                    throw new EvalException("cannot access field '" + field.text() + "' of " + value);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) value;
                if (!m.containsKey(field.text())) {
                    throw new EvalException("unknown field '" + field.text() + "'");
                }
                value = m.get(field.text());
            }
            return value;
        }

        private static boolean bool(Object v, String op) {
            if (v instanceof Boolean b) return b;
            throw new EvalException("operator '" + op + "' expects boolean, got " + v);
        }

        private static double asDouble(Object v, String op) {
            if (v instanceof Long l) return l.doubleValue();
            if (v instanceof Double d) return d;
            throw new EvalException("operator '" + op + "' expects number, got " + v);
        }

        private static Object arith(String op, Object a, Object b) {
            if (a instanceof Long x && b instanceof Long y) {
                return switch (op) {
                    case "+" -> x + y;
                    case "-" -> x - y;
                    case "*" -> x * y;
                    case "/" -> {
                        if (y == 0) throw new EvalException("division by zero");
                        yield x / y;
                    }
                    case "%" -> {
                        if (y == 0) throw new EvalException("modulo by zero");
                        yield x % y;
                    }
                    default -> throw new EvalException("bad operator " + op);
                };
            }
            double x = asDouble(a, op);
            double y = asDouble(b, op);
            return switch (op) {
                case "+" -> x + y;
                case "-" -> x - y;
                case "*" -> x * y;
                case "/" -> {
                    if (y == 0.0) throw new EvalException("division by zero");
                    yield x / y;
                }
                case "%" -> {
                    if (y == 0.0) throw new EvalException("modulo by zero");
                    yield x % y;
                }
                default -> throw new EvalException("bad operator " + op);
            };
        }

        private static Object compare(String op, Object a, Object b) {
            switch (op) {
                case "==": return eq(a, b);
                case "!=": return !eq(a, b);
            }
            if (a instanceof String x && b instanceof String y) {
                int c = x.compareTo(y);
                return switch (op) {
                    case "<" -> c < 0;
                    case "<=" -> c <= 0;
                    case ">" -> c > 0;
                    case ">=" -> c >= 0;
                    default -> throw new EvalException("bad operator " + op);
                };
            }
            double x = asDouble(a, op);
            double y = asDouble(b, op);
            return switch (op) {
                case "<" -> x < y;
                case "<=" -> x <= y;
                case ">" -> x > y;
                case ">=" -> x >= y;
                default -> throw new EvalException("bad operator " + op);
            };
        }

        private static boolean eq(Object a, Object b) {
            if (a == null || b == null) return a == b;
            boolean an = a instanceof Long || a instanceof Double;
            boolean bn = b instanceof Long || b instanceof Double;
            if (an && bn) return asDouble(a, "==") == asDouble(b, "==");
            return a.equals(b);
        }
    }

    private static List<Tok> lex(String src) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char ch = src.charAt(i);
            if (Character.isWhitespace(ch)) { i++; continue; }
            if (Character.isDigit(ch)) {
                int start = i;
                boolean isDouble = false;
                while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) {
                    if (src.charAt(i) == '.') isDouble = true;
                    i++;
                }
                String text = src.substring(start, i);
                out.add(new Tok(Kind.NUM, text, isDouble ? (Object) Double.parseDouble(text)
                        : (Object) Long.parseLong(text)));
                continue;
            }
            if (ch == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && src.charAt(i) != '"') {
                    char c = src.charAt(i++);
                    if (c == '\\' && i < n) {
                        char esc = src.charAt(i++);
                        switch (esc) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            default -> sb.append(esc);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                if (i >= n) throw new EvalException("unterminated string literal");
                i++;
                out.add(new Tok(Kind.STR, sb.toString(), sb.toString()));
                continue;
            }
            if (Character.isLetter(ch) || ch == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                out.add(new Tok(Kind.IDENT, src.substring(start, i), null));
                continue;
            }
            switch (ch) {
                case '(' -> { out.add(new Tok(Kind.LP, "(", null)); i++; }
                case ')' -> { out.add(new Tok(Kind.RP, ")", null)); i++; }
                case ',' -> { out.add(new Tok(Kind.COMMA, ",", null)); i++; }
                case '.' -> { out.add(new Tok(Kind.DOT, ".", null)); i++; }
                default -> {
                    String two = i + 1 < n ? src.substring(i, i + 2) : "";
                    if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")
                            || two.equals("&&") || two.equals("||")) {
                        out.add(new Tok(Kind.OP, two, null));
                        i += 2;
                    } else if ("+-*/%!<>".indexOf(ch) >= 0) {
                        out.add(new Tok(Kind.OP, String.valueOf(ch), null));
                        i++;
                    } else {
                        throw new EvalException("unexpected character '" + ch + "'");
                    }
                }
            }
        }
        out.add(new Tok(Kind.EOF, "<eof>", null));
        return out;
    }
}
