package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 条件/动作里使用的小型表达式语言：递归下降解析 + 确定性求值。 */
public final class Expr {
    public static class ExprException extends RuntimeException {
        public ExprException(String m) { super(m); }
    }

    interface Node { Object eval(Eval ctx); }

    public static Object eval(String text, Eval ctx) {
        return new Parser(text).parse().eval(ctx);
}

    static double num(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { throw new ExprException("not a number: " + v); }
    }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    static boolean equal(Object a, Object b) {
        if (a == null || b == null) return a == null && b == null;
        if (a instanceof Number || b instanceof Number) {
            if (a instanceof Boolean || b instanceof Boolean || a instanceof String || b instanceof String)
                return false;
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    static int compare(Object a, Object b) {
        if (a instanceof Number && b instanceof Number)
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        return a.toString().compareTo(b.toString());
    }

    static Object concatOrAdd(Object a, Object b) {
        if (a instanceof String || b instanceof String) return str(a) + str(b);
        return num(a) + num(b);
    }

    static String str(Object v) {
        if (v == null) return "null";
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d)) return Long.toString((long) d);
        }
        return v.toString();
    }

    static final class Lit implements Node {
        final Object value;
        Lit(Object v) { value = v; }
        public Object eval(Eval ctx) { return value; }
    }

    /** 变量路径，如 count / event.payload.amount / vars.x。 */
    static final class Path implements Node {
        final List<String> parts;
        Path(List<String> p) { parts = p; }

        @SuppressWarnings("unchecked")
        static Object read(Map<String, Object> m, String key) {
            Object v = m.get(key);
            if (v == null && m.containsKey("payload") && m.get("payload") instanceof Map)
                v = ((Map<String, Object>) m.get("payload")).get(key);
            return v;
        }

        public Object eval(Eval ctx) {
            String head = parts.get(0);
            Object cur;
            switch (head) {
                case "state": cur = ctx.snapshot; break;
                case "vars": cur = ctx.snapshot; break;
                case "event": cur = ctx.eventView(); break;
                case "snapshot": cur = ctx.snapshot; break;
                default:
                    cur = read(ctx.snapshot, head);
                    if (cur == null && !ctx.snapshot.containsKey(head))
                        cur = read(ctx.eventView(), head);
            }
            for (int i = 1; i < parts.size() && cur != null; i++) {
                if (cur instanceof Map) cur = ((Map<String, Object>) cur).get(parts.get(i));
                else return null;
            }
            return cur;
        }
    }

    static final class Binary implements Node {
        final String op; final Node l, r;
        Binary(String op, Node l, Node r) { this.op = op; this.l = l; this.r = r; }
        public Object eval(Eval ctx) {
            if (op.equals("&&")) return truthy(l.eval(ctx)) && truthy(r.eval(ctx));
            if (op.equals("||")) return truthy(l.eval(ctx)) || truthy(r.eval(ctx));
            Object a = l.eval(ctx);
            Object b = r.eval(ctx);
            switch (op) {
                case "+": return concatOrAdd(a, b);
                case "-": return num(a) - num(b);
                case "*": return num(a) * num(b);
                case "/": {
                    double d = num(b);
                    if (d == 0) throw new ExprException("division by zero");
                    return num(a) / d;
                }
                case "%": {
                    double d = num(b);
                    if (d == 0) throw new ExprException("modulo by zero");
                    return num(a) % d;
                }
                case "==": return equal(a, b);
                case "!=": return !equal(a, b);
                case ">": return compare(a, b) > 0;
                case ">=": return compare(a, b) >= 0;
                case "<": return compare(a, b) < 0;
                case "<=": return compare(a, b) <= 0;
                default: throw new ExprException("bad op " + op);
            }
        }
    }

    static final class Unary implements Node {
        final String op; final Node n;
        Unary(String op, Node n) { this.op = op; this.n = n; }
        public Object eval(Eval ctx) {
            Object v = n.eval(ctx);
            if (op.equals("-")) return -num(v);
            return !truthy(v);
        }
    }

    static final class Call implements Node {
        final String name; final List<Node> args;
        Call(String name, List<Node> args) { this.name = name; this.args = args; }
        public Object eval(Eval ctx) {
            List<Object> vs = new ArrayList<>();
            for (Node a : args) vs.add(a.eval(ctx));
            return Functions.call(name, vs, ctx);
        }
    }

    /** 求值上下文：vars 是处理事件前的快照（条件读取），rng 是事务内随机数。 */
    public static final class Eval {
        public final Map<String, Object> snapshot;
        public final Map<String, Object> event;
        public final DetRandom rng;

        public Eval(Map<String, Object> snapshot, Map<String, Object> event, DetRandom rng) {
            this.snapshot = snapshot;
            this.event = event;
            this.rng = rng;
        }

        Map<String, Object> eventView() { return event; }
    }

    static final class Functions {
        static Object call(String name, List<Object> v, Eval ctx) {
            switch (name) {
                case "rng": {
                    int bound = v.isEmpty() ? 100 : (int) num(v.get(0));
                    if (bound <= 0) throw new ExprException("rng bound must be positive");
                    return (double) ctx.rng.nextInt(bound);
                }
                case "rngDouble":
                    return ctx.rng.nextDouble();
                case "min":
                    return Math.min(num(v.get(0)), num(v.get(1)));
                case "max":
                    return Math.max(num(v.get(0)), num(v.get(1)));
                case "abs":
                    return Math.abs(num(v.get(0)));
                case "str":
                    return str(v.get(0));
                case "len": {
                    Object x = v.get(0);
                    if (x instanceof String) return (double) ((String) x).length();
                    if (x instanceof List) return (double) ((List<?>) x).size();
                    throw new ExprException("len expects string/array");
                }
                case "concat": {
                    StringBuilder sb = new StringBuilder();
                    for (Object o : v) sb.append(str(o));
                    return sb.toString();
                }
                case "contains": {
                    Object x = v.get(0);
                    Object y = v.get(1);
                    if (x instanceof String) return ((String) x).contains(str(y));
                    if (x instanceof List) {
                        for (Object o : (List<?>) x) if (equal(o, y)) return true;
                        return false;
                    }
                    throw new ExprException("contains expects string/array");
                }
                default:
                    throw new ExprException("unknown function: " + name);
            }
        }
    }

    static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        RuntimeException err(String m) { return new ExprException(m + " at " + i); }

        Node parse() {
            Node n = parseOr();
            skip();
            if (i < s.length()) throw err("unexpected character");
            return n;
        }

        void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        boolean consume(String kw) {
            skip();
            if (s.startsWith(kw, i)) { i += kw.length(); return true; }
            return false;
        }

        Node parseOr() {
            Node l = parseAnd();
            while (consume("||")) l = new Binary("||", l, parseAnd());
            return l;
        }

        Node parseAnd() {
            Node l = parseCmp();
            while (consume("&&")) l = new Binary("&&", l, parseCmp());
            return l;
        }

        Node parseCmp() {
            Node l = parseAdd();
            while (true) {
                skip();
                String op = null;
                if (consume("==")) op = "==";
                else if (consume("!=")) op = "!=";
                else if (consume(">=")) op = ">=";
                else if (consume("<=")) op = "<=";
                else if (consume(">")) op = ">";
                else if (consume("<")) op = "<";
                if (op == null) return l;
                l = new Binary(op, l, parseAdd());
            }
        }

        Node parseAdd() {
            Node l = parseMul();
            while (true) {
                skip();
                String op = null;
                if (consume("+")) op = "+";
                else if (consume("-")) op = "-";
                if (op == null) return l;
                l = new Binary(op, l, parseMul());
            }
        }

        Node parseMul() {
            Node l = parseUnary();
            while (true) {
                skip();
                String op = null;
                if (consume("*")) op = "*";
                else if (consume("/")) op = "/";
                else if (consume("%")) op = "%";
                if (op == null) return l;
                l = new Binary(op, l, parseUnary());
            }
        }

        Node parseUnary() {
            skip();
            if (consume("-")) return new Unary("-", parseUnary());
            if (consume("!")) return new Unary("!", parseUnary());
            return parsePrimary();
        }

        Node parsePrimary() {
            skip();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            if (c == '(') {
                i++;
                Node n = parseOr();
                if (!consume(")")) throw err("expected )");
                return n;
            }
            if (c == '"' || c == '\'') return parseString(c);
            if (Character.isDigit(c) || c == '.') return parseNumber();
            if (Character.isLetter(c) || c == '_') return parseWord();
            throw err("unexpected character " + c);
        }

        Node parseString(char quote) {
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length() && s.charAt(i) != quote) {
                char c = s.charAt(i++);
                if (c == '\\' && i < s.length()) c = s.charAt(i++);
                sb.append(c);
            }
            if (i >= s.length()) throw err("unterminated string");
            i++;
            return new Lit(sb.toString());
        }

        Node parseNumber() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                else break;
            }
            String t = s.substring(start, i);
            if (t.startsWith("+")) t = t.substring(1);
            try { return new Lit(Double.parseDouble(t)); }
            catch (NumberFormatException e) { throw err("bad number " + t); }
        }

        Node parseWord() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '_') i++;
                else break;
            }
            String word = s.substring(start, i);
            skip();
            if (word.equals("true")) return new Lit(Boolean.TRUE);
            if (word.equals("false")) return new Lit(Boolean.FALSE);
            if (word.equals("null")) return new Lit(null);
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                List<Node> args = new ArrayList<>();
                skip();
                if (i < s.length() && s.charAt(i) != ')') {
                    args.add(parseOr());
                    while (consume(",")) args.add(parseOr());
                }
                if (!consume(")")) throw err("expected )");
                return new Call(word, args);
            }
            List<String> parts = new ArrayList<>();
            parts.add(word);
            while (consume(".")) {
                skip();
                int pstart = i;
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (Character.isLetterOrDigit(c) || c == '_') i++;
                    else break;
                }
                if (i == pstart) throw err("expected field name");
                parts.add(s.substring(pstart, i));
            }
            return new Path(parts);
        }
    }
}
