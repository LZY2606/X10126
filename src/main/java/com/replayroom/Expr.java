package com.replayroom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 小型确定性表达式求值器。
 * 支持: 字面量(数字/字符串/布尔/null)、state、vars.x、event.x、event.data.x、
 * randInt(n)、比较/逻辑/算术运算、括号。无任何外部输入，结果只取决于传入上下文。
 */
public final class Expr {
    public interface Context {
        Object resolve(String path);          // 解析 state / vars.x / event.x
        long randInt(long bound);             // 会话级确定性随机源
    }

    private final String src;
    private final Node root;

    private Expr(String src, Node root) {
        this.src = src;
        this.root = root;
    }

    public static Expr compile(String src) {
        Lexer lx = new Lexer(src);
        Parser p = new Parser(lx.tokens());
        Node n = p.parseOr();
        p.expectEnd();
        return new Expr(src, n);
    }

    public Object eval(Context ctx) { return root.eval(ctx); }

    public String source() { return src; }

    // ---------- 值运算 ----------

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    static boolean eq(Object a, Object b) {
        if (a instanceof Number && b instanceof Number)
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue()) == 0;
        if (a == null || b == null) return a == b;
        return a.equals(b);
    }

    static int cmp(Object a, Object b) {
        if (a instanceof Number && b instanceof Number)
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        if (a instanceof String && b instanceof String) return ((String) a).compareTo((String) b);
        throw new IllegalArgumentException("无法比较: " + a + " 与 " + b);
    }

    static Object add(Object a, Object b) {
        if (a instanceof String || b instanceof String) return str(a) + str(b);
        return num(a) + num(b);
    }

    static Object numeric(Object a, Object b, char op) {
        if (a instanceof Double || b instanceof Double) {
            double x = ((Number) a).doubleValue(), y = ((Number) b).doubleValue();
            switch (op) {
                case '+': return x + y;
                case '-': return x - y;
                case '*': return x * y;
                case '/': return x / y;
                case '%': return x % y;
            }
        } else {
            long x = ((Number) a).longValue(), y = ((Number) b).longValue();
            switch (op) {
                case '+': return x + y;
                case '-': return x - y;
                case '*': return x * y;
                case '/':
                    if (y == 0) throw new IllegalArgumentException("除以零");
                    return x / y;
                case '%':
                    if (y == 0) throw new IllegalArgumentException("对零取模");
                    return x % y;
            }
        }
        throw new IllegalArgumentException("未知运算符 " + op);
    }

    private static double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        throw new IllegalArgumentException("期望数字，实际为: " + o);
    }

    private static String str(Object o) { return o == null ? "null" : o.toString(); }

    // ---------- 语法树 ----------

    private interface Node { Object eval(Context ctx); }

    private static final class Lit implements Node {
        final Object v;
        Lit(Object v) { this.v = v; }
        public Object eval(Context ctx) { return v; }
    }

    private static final class Ident implements Node {
        final String path;
        Ident(String path) { this.path = path; }
        public Object eval(Context ctx) { return ctx.resolve(path); }
    }

    private static final class RandInt implements Node {
        final Node bound;
        RandInt(Node bound) { this.bound = bound; }
        public Object eval(Context ctx) {
            Object b = bound.eval(ctx);
            if (!(b instanceof Number)) throw new IllegalArgumentException("randInt 需要数字参数");
            return ctx.randInt(((Number) b).longValue());
        }
    }

    private static final class Unary implements Node {
        final String op;
        final Node n;
        Unary(String op, Node n) { this.op = op; this.n = n; }
        public Object eval(Context ctx) {
            Object v = n.eval(ctx);
            if (op.equals("!")) return !truthy(v);
            if (v instanceof Double) return -((Double) v);
            if (v instanceof Number) return -((Number) v).longValue();
            throw new IllegalArgumentException("无法取负: " + v);
        }
    }

    private static final class Binary implements Node {
        final String op;
        final Node l, r;
        Binary(String op, Node l, Node r) { this.op = op; this.l = l; this.r = r; }
        public Object eval(Context ctx) {
            switch (op) {
                case "&&": return truthy(l.eval(ctx)) && truthy(r.eval(ctx));
                case "||": return truthy(l.eval(ctx)) || truthy(r.eval(ctx));
            }
            Object a = l.eval(ctx), b = r.eval(ctx);
            switch (op) {
                case "==": return eq(a, b);
                case "!=": return !eq(a, b);
                case "<": return cmp(a, b) < 0;
                case "<=": return cmp(a, b) <= 0;
                case ">": return cmp(a, b) > 0;
                case ">=": return cmp(a, b) >= 0;
                case "+": return add(a, b);
                case "-": case "*": case "/": case "%":
                    return numeric(a, b, op.charAt(0));
            }
            throw new IllegalArgumentException("未知运算符 " + op);
        }
    }

    // ---------- 词法 ----------

    private static final class Tok {
        final String text;
        final int kind; // 0=标识符/关键字 1=数字 2=字符串 3=运算符
        Tok(String t, int k) { text = t; kind = k; }
    }

    private static final class Lexer {
        final String s;
        int pos;
        Lexer(String s) { this.s = s; }

        List<Tok> tokens() {
            List<Tok> out = new ArrayList<>();
            while (true) {
                while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
                if (pos >= s.length()) break;
                char c = s.charAt(pos);
                if (Character.isLetter(c) || c == '_') {
                    int st = pos;
                    while (pos < s.length()) {
                        char d = s.charAt(pos);
                        if (Character.isLetterOrDigit(d) || d == '_' || d == '.') pos++;
                        else break;
                    }
                    out.add(new Tok(s.substring(st, pos), 0));
                } else if (Character.isDigit(c)) {
                    int st = pos;
                    while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
                    out.add(new Tok(s.substring(st, pos), 1));
                } else if (c == '\'' || c == '"') {
                    pos++;
                    StringBuilder sb = new StringBuilder();
                    while (pos < s.length() && s.charAt(pos) != c) {
                        if (s.charAt(pos) == '\\' && pos + 1 < s.length()) {
                            pos++;
                            char e = s.charAt(pos);
                            sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
                        } else sb.append(s.charAt(pos));
                        pos++;
                    }
                    if (pos >= s.length()) throw new IllegalArgumentException("表达式字符串未闭合: " + s);
                    pos++;
                    out.add(new Tok(sb.toString(), 2));
                } else {
                    String two = pos + 1 < s.length() ? s.substring(pos, pos + 2) : "";
                    if (two.equals("&&") || two.equals("||") || two.equals("==")
                            || two.equals("!=") || two.equals("<=") || two.equals(">=")) {
                        out.add(new Tok(two, 3));
                        pos += 2;
                    } else if ("()!<>+-*/%,".indexOf(c) >= 0) {
                        out.add(new Tok(String.valueOf(c), 3));
                        pos++;
                    } else {
                        throw new IllegalArgumentException("表达式含非法字符 '" + c + "': " + s);
                    }
                }
            }
            return out;
        }
    }

    // ---------- 语法 ----------

    private static final class Parser {
        final List<Tok> toks;
        int pos;
        Parser(List<Tok> toks) { this.toks = toks; }

        Tok peek() {
            if (pos >= toks.size()) throw new IllegalArgumentException("表达式意外结束");
            return toks.get(pos);
        }

        Tok next() { Tok t = peek(); pos++; return t; }

        boolean at(String t) { return pos < toks.size() && toks.get(pos).text.equals(t); }

        void expectEnd() {
            if (pos != toks.size()) throw new IllegalArgumentException("表达式末尾有多余内容: " + peek().text);
        }

        Node parseOr() {
            Node n = parseAnd();
            while (at("||")) { next(); n = new Binary("||", n, parseAnd()); }
            return n;
        }

        Node parseAnd() {
            Node n = parseEq();
            while (at("&&")) { next(); n = new Binary("&&", n, parseEq()); }
            return n;
        }

        Node parseEq() {
            Node n = parseRel();
            while (at("==") || at("!=")) { String op = next().text; n = new Binary(op, n, parseRel()); }
            return n;
        }

        Node parseRel() {
            Node n = parseAdd();
            while (at("<") || at("<=") || at(">") || at(">=")) { String op = next().text; n = new Binary(op, n, parseAdd()); }
            return n;
        }

        Node parseAdd() {
            Node n = parseMul();
            while (at("+") || at("-")) { String op = next().text; n = new Binary(op, n, parseMul()); }
            return n;
        }

        Node parseMul() {
            Node n = parseUnary();
            while (at("*") || at("/") || at("%")) { String op = next().text; n = new Binary(op, n, parseUnary()); }
            return n;
        }

        Node parseUnary() {
            if (at("!")) { next(); return new Unary("!", parseUnary()); }
            if (at("-")) { next(); return new Unary("-", parseUnary()); }
            return parsePrimary();
        }

        Node parsePrimary() {
            Tok t = next();
            if (t.kind == 3 && t.text.equals("(")) {
                Node n = parseOr();
                if (!at(")")) throw new IllegalArgumentException("缺少右括号");
                next();
                return n;
            }
            if (t.kind == 1) {
                return new Lit(t.text.contains(".") ? (Object) Double.parseDouble(t.text) : (Object) Long.parseLong(t.text));
            }
            if (t.kind == 2) return new Lit(t.text);
            if (t.kind == 0) {
                switch (t.text) {
                    case "true": return new Lit(Boolean.TRUE);
                    case "false": return new Lit(Boolean.FALSE);
                    case "null": return new Lit(null);
                    case "randInt": {
                        if (!at("(")) throw new IllegalArgumentException("randInt 需要括号参数");
                        next();
                        Node arg = parseOr();
                        if (!at(")")) throw new IllegalArgumentException("randInt 缺少右括号");
                        next();
                        return new RandInt(arg);
                    }
                    default: return new Ident(t.text);
                }
            }
            throw new IllegalArgumentException("表达式无法解析: " + t.text);
        }
    }
}
