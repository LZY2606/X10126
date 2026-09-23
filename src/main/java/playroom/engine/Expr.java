package playroom.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tiny expression language for transition conditions.
 *
 * Grammar (precedence low -> high):
 *   or  := and ('||' and)*
 *   and := not ('&&' not)*
 *   not := '!' not | cmp
 *   cmp := add (('==' | '!=' | '<=' | '>=' | '<' | '>') add)?
 *   add := mul (('+' | '-') mul)*
 *   mul := unary (('*' | '/' | '%') unary)*
 *   atom := NUMBER | STRING | 'true' | 'false' | 'null'
 *         | PATH ('.' NAME | '[' STRING ']')* | '(' or ')'
 *
 * Paths: state (string), data.<key>, event.type / event.data.<key>,
 * seed (long), rng (long internal state).
 */
public final class Expr {

    @SuppressWarnings("serial")
    public static class EvalError extends RuntimeException {
        EvalError(String m) { super(m); }
    }

    private final String text;
    private final List<Token> tokens;

    private Expr(String text, List<Token> tokens) {
        this.text = text;
        this.tokens = tokens;
    }

    public static Expr compile(String text) {
        return new Expr(text, Lexer.lex(text));
    }

    public boolean test(Map<String, Object> root) {
        Object v = new Interpreter(tokens, root).run();
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        if (v instanceof String s) return !s.isEmpty();
        return true;
    }

    public Object eval(Map<String, Object> root) {
        return new Interpreter(tokens, root).run();
    }

    @Override
    public String toString() { return text; }

    // ---- tokens ----

    enum Kind { NUM, STR, IDENT, OP, LPAREN, RPAREN, LBRACK, RBRACK, DOT, EOF }

    record Token(Kind kind, String text, double num) {}

    static final class Lexer {
        static List<Token> lex(String s) {
            List<Token> out = new ArrayList<>();
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                if (Character.isDigit(c) || (c == '-' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                    int start = i++;
                    while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
                    String tok = s.substring(start, i);
                    if (tok.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
                        out.add(new Token(Kind.NUM, tok, Double.parseDouble(tok)));
                    } else throw new EvalError("bad number: " + tok);
                    continue;
                }
                if (c == '\'' || c == '"') {
                    char q = c;
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (i < s.length() && s.charAt(i) != q) {
                        char x = s.charAt(i++);
                        if (x == '\\' && i < s.length()) x = unescape(s.charAt(i++));
                        sb.append(x);
                    }
                    if (i >= s.length()) throw new EvalError("unterminated string");
                    i++;
                    out.add(new Token(Kind.STR, sb.toString(), 0));
                    continue;
                }
                if (Character.isJavaIdentifierStart(c)) {
                    int start = i++;
                    while (i < s.length() && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '-')) i++;
                    String word = s.substring(start, i);
                    if (word.equals("true") || word.equals("false") || word.equals("null")) {
                        out.add(new Token(Kind.OP, word, 0));
                    } else {
                        out.add(new Token(Kind.IDENT, word, 0));
                    }
                    continue;
                }
                switch (c) {
                    case '(' -> out.add(new Token(Kind.LPAREN, "(", 0));
                    case ')' -> out.add(new Token(Kind.RPAREN, ")", 0));
                    case '[' -> out.add(new Token(Kind.LBRACK, "[", 0));
                    case ']' -> out.add(new Token(Kind.RBRACK, "]", 0));
                    case '.' -> out.add(new Token(Kind.DOT, ".", 0));
                    case '+', '-', '*', '/', '%', '!', '<', '>', '&', '|', '=' -> {
                        String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                        if (two.equals("==") || two.equals("!=") || two.equals("<=")
                                || two.equals(">=") || two.equals("&&") || two.equals("||")) {
                            out.add(new Token(Kind.OP, two, 0));
                            i += 2;
                        } else if ("+-*/%!<>".indexOf(c) >= 0) {
                            out.add(new Token(Kind.OP, String.valueOf(c), 0));
                            i++;
                        } else {
                            throw new EvalError("bad operator near " + c);
                        }
                        continue;
                    }
                    default -> throw new EvalError("unexpected character: " + c);
                }
                i++;
            }
            out.add(new Token(Kind.EOF, "", 0));
            return out;
        }

        static char unescape(char c) {
            return switch (c) {
                case 'n' -> '\n';
                case 't' -> '\t';
                default -> c;
            };
        }
    }

    // ---- interpreter (recursive descent) ----

    static final class Interpreter {
        final List<Token> t;
        final Map<String, Object> root;
        int p;

        Interpreter(List<Token> t, Map<String, Object> root) {
            this.t = t;
            this.root = root;
        }

        Object run() {
            Object v = parseOr();
            if (peek().kind != Kind.EOF) throw new EvalError("unexpected token: " + peek().text);
            return v;
        }

        Token peek() { return t.get(p); }
        Token next() { return t.get(p++); }
        boolean matchOp(String op) {
            Token tk = peek();
            if (tk.kind == Kind.OP && tk.text.equals(op)) { p++; return true; }
            return false;
        }

        Object parseOr() {
            Object v = parseAnd();
            while (matchOp("||")) {
                Object r = parseAnd();
                v = truth(v) || truth(r);
            }
            return v;
        }

        Object parseAnd() {
            Object v = parseNot();
            while (matchOp("&&")) {
                Object r = parseNot();
                v = truth(v) && truth(r);
            }
            return v;
        }

        Object parseNot() {
            if (matchOp("!")) return !truth(parseNot());
            return parseCmp();
        }

        Object parseCmp() {
            Object v = parseAdd();
            Token tk = peek();
            if (tk.kind == Kind.OP && (tk.text.equals("==") || tk.text.equals("!=")
                    || tk.text.equals("<") || tk.text.equals(">")
                    || tk.text.equals("<=") || tk.text.equals(">="))) {
                next();
                Object r = parseAdd();
                return switch (tk.text) {
                    case "==" -> equals(v, r);
                    case "!=" -> !equals(v, r);
                    case "<" -> compare(v, r) < 0;
                    case ">" -> compare(v, r) > 0;
                    case "<=" -> compare(v, r) <= 0;
                    default -> compare(v, r) >= 0;
                };
            }
            return v;
        }

        Object parseAdd() {
            Object v = parseMul();
            while (true) {
                Token tk = peek();
                if (tk.kind != Kind.OP || (!tk.text.equals("+") && !tk.text.equals("-"))) return v;
                next();
                Object r = parseMul();
                if (tk.text.equals("+")) {
                    if (v instanceof String || r instanceof String) v = String.valueOf(v) + String.valueOf(r);
                    else v = num(v) + num(r);
                } else {
                    v = num(v) - num(r);
                }
            }
        }

        Object parseMul() {
            Object v = parseAtom();
            while (true) {
                Token tk = peek();
                if (tk.kind != Kind.OP || (
                        !tk.text.equals("*") && !tk.text.equals("/") && !tk.text.equals("%"))) return v;
                next();
                Object r = parseAtom();
                v = switch (tk.text) {
                    case "*" -> num(v) * num(r);
                    case "/" -> num(v) / num(r);
                    default -> {
                        double rv = num(r);
                        if (rv == 0.0) throw new EvalError("modulo by zero");
                        yield num(v) % rv;
                    }
                };
            }
        }

        @SuppressWarnings("unchecked")
        Object parseAtom() {
            Token tk = next();
            return switch (tk.kind) {
                case NUM -> tk.num;
                case STR -> tk.text;
                case LPAREN -> {
                    Object v = parseOr();
                    if (next().kind != Kind.RPAREN) throw new EvalError("missing )");
                    yield v;
                }
                case OP -> switch (tk.text) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    default -> null;
                };
                case IDENT -> resolvePath(tk.text);
                default -> throw new EvalError("unexpected token: " + tk.text);
            };
        }

        @SuppressWarnings("unchecked")
        private Object resolvePath(String first) {
            Object cur;
            if (first.equals("state")) {
                cur = root.get("state");
            } else if (first.equals("event")) {
                cur = root.get("event");
            } else if (first.equals("seed")) {
                cur = ((Number) root.getOrDefault("seed", 0L)).longValue();
            } else if (first.equals("rng")) {
                cur = root.get("rng");
            } else if (first.equals("data")) {
                Object d = root.get("data");
                cur = d instanceof Map ? d : Map.of();
            } else {
                // shorthand: bare identifier == data.<identifier>
                Object d = root.get("data");
                cur = d instanceof Map ? ((Map<String, Object>) d).get(first) : null;
            }
            while (true) {
                if (peek().kind == Kind.DOT) {
                    next();
                    Token name = next();
                    if (name.kind != Kind.IDENT) throw new EvalError("expected field name after '.'");
                    cur = child(cur, name.text);
                } else if (peek().kind == Kind.LBRACK) {
                    next();
                    Token key = next();
                    if (key.kind != Kind.STR && key.kind != Kind.NUM) {
                        throw new EvalError("[] key must be a string or number");
                    }
                    if (next().kind != Kind.RBRACK) throw new EvalError("missing ]");
                    cur = child(cur, key.kind == Kind.NUM
                            ? String.valueOf((long) key.num) : key.text);
                } else {
                    return cur;
                }
            }
        }

        @SuppressWarnings("unchecked")
        private static Object child(Object parent, String name) {
            if (parent instanceof Map<?, ?> m) return ((Map<String, Object>) m).get(name);
            if (parent instanceof List<?> l) {
                try {
                    int idx = Integer.parseInt(name);
                    return idx >= 0 && idx < l.size() ? l.get(idx) : null;
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            return null;
        }

        static boolean truth(Object v) {
            if (v == null) return false;
            if (v instanceof Boolean b) return b;
            if (v instanceof Number n) return n.doubleValue() != 0.0;
            if (v instanceof String s) return !s.isEmpty();
            return true;
        }

        static double num(Object v) {
            if (v instanceof Number n) return n.doubleValue();
            if (v instanceof Boolean b) return b ? 1 : 0;
            if (v == null) return 0;
            throw new EvalError("expected number, got: " + v);
        }

        static boolean equals(Object a, Object b) {
            if (a instanceof Number || b instanceof Number) {
                if (a == null || b == null || !(a instanceof Number) || !(b instanceof Number)) return false;
                return ((Number) a).doubleValue() == ((Number) b).doubleValue();
            }
            if (a == null) return b == null;
            return a.equals(b);
        }

        @SuppressWarnings("unchecked")
        static int compare(Object a, Object b) {
            if (a instanceof Number na && b instanceof Number nb) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            if (a instanceof String sa && b instanceof String sb) return sa.compareTo(sb);
            throw new EvalError("cannot compare " + a + " with " + b);
        }
    }
}
