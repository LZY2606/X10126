package rr.engine;

import rr.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极小表达式语言（Pratt 递归下降）。
 * 字面量：数字、true/false、null、'字符串'（双引号也可）
 * 变量路径：state / data / event / output，点号路径，方括号 ["x"]
 * 运算：|| && == != > >= < <= + - * / % ! -（一元）
 * 函数：exists(path), len(x), str(x), num(x), not(x), and(a,b), or(a,b)
 * 条件缺少变量时直接为 false（由 Engine 捕获 EvalException）。
 */
public final class Eval {
    private Eval() {}

    public static final class EvalException extends RuntimeException {
        public EvalException(String m) { super(m); }
    }

    public static Object eval(String expr, Map<String, Object> root) {
        Parser p = new Parser(expr, root);
        Object v = p.parseExpr(0);
        p.ws();
        if (!p.eof()) throw new EvalException("表达式尾部多余字符，位置 " + p.pos);
        return v;
    }

    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof List<?> l) return !l.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    // ---------- 词法/语法 ----------
    static final class Parser {
        final String s;
        final Map<String, Object> rootVars;
        int pos;
        Parser(String s, Map<String, Object> rootVars) { this.s = s == null ? "" : s; this.rootVars = rootVars; }
        boolean eof() { return pos >= s.length(); }
        void ws() { while (!eof() && Character.isWhitespace(s.charAt(pos))) pos++; }

        Object parseExpr(int minBp) {
            Object left = parsePrefix();
            while (true) {
                Op op = peekOp();
                if (op == null || op.lbp < minBp) break;
                pos += op.text.length();
                Object right = parseExpr(op.rbp);
                left = apply(op, left, right);
            }
            return left;
        }

        record Op(String text, int lbp, int rbp) {}

        private Op peekOp() {
            ws();
            String[] two = {"==", "!=", ">=", "<=", "&&", "||"};
            if (pos + 2 <= s.length()) {
                String t = s.substring(pos, pos + 2);
                for (String x : two) if (x.equals(t)) return new Op(t, x.equals("||") ? 6 : x.equals("&&") ? 7 : 11, (x.equals("||") || x.equals("&&")) ? 8 : 12);
            }
            if (!eof()) {
                char c = s.charAt(pos);
                if (c == '>' || c == '<' || c == '+' || c == '-' || c == '*' || c == '/' || c == '%')
                    return new Op(String.valueOf(c), c == '+' || c == '-' ? 10 : 12, 11);
            }
            return null;
        }

        private Object parsePrefix() {
            ws();
            if (eof()) throw new EvalException("意外结束");
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Object v = parseExpr(0);
                expect(')');
                return v;
            }
            if (c == '!') { pos++; return !truthy(parseExpr(30)); }
            if (c == '-') { pos++; Object v = parseExpr(30); return -Json.num(v); }
            if (c == '\'' || c == '"') return readString(c);
            if (Character.isDigit(c)) return readNumber();
            if (Character.isJavaIdentifierStart(c)) return readWord();
            throw new EvalException("无法解析的字符 '" + c + "'，位置 " + pos);
        }

        private Object readWord() {
            int start = pos;
            while (!eof() && Character.isJavaIdentifierPart(s.charAt(pos))) pos++;
            String word = s.substring(start, pos);
            return switch (word) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                default -> readPathOrCall(word);
            };
        }

        private Object readPathOrCall(String first) {
            ws();
            Object value;
            if (!eof() && s.charAt(pos) == '(' && first.equals("exists")) {
                // exists(data.a.b) / exists(event.payload.x)：惰性路径探测，缺失返回 false
                pos++;
                ws();
                int idStart = pos;
                while (!eof() && Character.isJavaIdentifierPart(s.charAt(pos))) pos++;
                String rootName = s.substring(idStart, pos);
                if (!rootVars.containsKey(rootName)) throw new EvalException("exists 的根必须是 state/data/event/output");
                Object cur = rootVars.get(rootName);
                boolean alive = true;
                ws();
                while (!eof() && (s.charAt(pos) == '.' || s.charAt(pos) == '[')) {
                    char c = s.charAt(pos);
                    if (c == '.') {
                        pos++; ws();
                        int st2 = pos;
                        while (!eof() && Character.isJavaIdentifierPart(s.charAt(pos))) pos++;
                        String key = s.substring(st2, pos);
                        if (key.isEmpty()) throw new EvalException("exists 路径属性名为空");
                        if (alive) {
                            try { cur = getField(cur, key); } catch (EvalException e) { alive = false; }
                        }
                    } else {
                        pos++;
                        Object key = parseExpr(0);
                        expect(']');
                        if (alive) {
                            try { cur = getField(cur, key); } catch (EvalException e) { alive = false; }
                        }
                    }
                    ws();
                }
                expect(')');
                return alive;
            }
            if (!eof() && s.charAt(pos) == '(') {
                pos++;
                List<Object> args = new ArrayList<>();
                ws();
                if (!eof() && s.charAt(pos) != ')') {
                    args.add(parseExpr(0));
                    ws();
                    while (!eof() && s.charAt(pos) == ',') {
                        pos++;
                        args.add(parseExpr(0));
                        ws();
                    }
                }
                expect(')');
                value = call(first, args);
            } else {
                value = resolveRoot(first);
            }
            return readSuffix(value);
        }

        private Object readSuffix(Object value) {
            while (true) {
                ws();
                if (!eof() && s.charAt(pos) == '.') {
                    pos++;
                    ws();
                    int start = pos;
                    while (!eof() && Character.isJavaIdentifierPart(s.charAt(pos))) pos++;
                    if (start == pos) throw new EvalException("属性名为空，位置 " + pos);
                    value = getField(value, s.substring(start, pos));
                } else if (!eof() && s.charAt(pos) == '[') {
                    pos++;
                    Object key = parseExpr(0);
                    expect(']');
                    value = getField(value, key);
                } else break;
            }
            return value;
        }

        private Object resolveRoot(String name) {
            if (!rootVars.containsKey(name)) throw new EvalException("未知变量: " + name);
            return rootVars.get(name);
        }

        @SuppressWarnings("unchecked")
        private Object getField(Object value, Object key) {
            if (value == null) throw new EvalException("在 null 上取字段");
            if (value instanceof Map<?, ?> m) {
                Map<String, Object> mm = (Map<String, Object>) m;
                String k = String.valueOf(key);
                if (!mm.containsKey(k)) throw new EvalException("字段不存在: " + k);
                return mm.get(k);
            }
            if (value instanceof List<?> l) {
                long idx;
                if (key instanceof Number n) idx = n.longValue();
                else {
                    try { idx = Long.parseLong(String.valueOf(key)); }
                    catch (NumberFormatException e) { throw new EvalException("数组下标必须是数字"); }
                }
                if (idx < 0 || idx >= l.size()) throw new EvalException("数组下标越界: " + idx);
                return l.get((int) idx);
            }
            throw new EvalException("不能在 " + Json.typeName(value) + " 上取字段");
        }

        private Object call(String name, List<Object> args) {
            return switch (name) {
                case "len" -> {
                    Object a = args.get(0);
                    if (a instanceof String st) yield (long) st.length();
                    if (a instanceof List<?> l) yield (long) l.size();
                    if (a instanceof Map<?, ?> m) yield (long) m.size();
                    throw new EvalException("len 需要字符串/数组/对象");
                }
                case "str" -> {
                    Object a = args.get(0);
                    yield a == null ? "" : (a instanceof String st ? st : Json.write(a));
                }
                case "num" -> {
                    Object a = args.get(0);
                    if (a instanceof Number n) yield n.doubleValue();
                    try { yield Double.parseDouble(String.valueOf(a)); }
                    catch (Exception e) { throw new EvalException("num 转换失败"); }
                }
                case "not" -> !truthy(args.get(0));
                case "and" -> truthy(args.get(0)) && truthy(args.get(1));
                case "or" -> truthy(args.get(0)) || truthy(args.get(1));
                default -> throw new EvalException("未知函数: " + name);
            };
        }

        private Object readNumber() {
            int start = pos;
            boolean dot = false;
            while (!eof()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) pos++;
                else if (c == '.' && !dot) { dot = true; pos++; }
                else break;
            }
            String t = s.substring(start, pos);
            return dot ? Double.parseDouble(t) : Long.parseLong(t);
        }

        private String readString(char quote) {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new EvalException("字符串未闭合");
                char c = s.charAt(pos++);
                if (c == quote) return sb.toString();
                if (c == '\\' && !eof()) {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        default -> sb.append(e);
                    }
                } else sb.append(c);
            }
        }

        void expect(char c) {
            ws();
            if (eof() || s.charAt(pos) != c) throw new EvalException("期望 '" + c + "'，位置 " + pos);
            pos++;
        }
    }

    private static final Map<String, Boolean> ROOT = Map.of("state", true, "data", true, "event", true, "output", true);

    /** 绑定本次求值的根变量。 */
    public static Scope scope(Object stateName, Map<String, Object> data, Map<String, Object> event, Object output) {
        return new Scope(stateName, data, event, output);
    }

    public static final class Scope {
        final Map<String, Object> root;
        Scope(Object stateName, Map<String, Object> data, Map<String, Object> event, Object output) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("state", stateName);
            r.put("data", data);
            r.put("event", event);
            r.put("output", output);
            this.root = r;
        }

        public Object eval(String expr) { return Eval.eval(expr, root); }

        public boolean condition(String expr) {
            if (expr == null || expr.isBlank()) return true;
            try {
                return truthy(eval(expr));
            } catch (EvalException e) {
                return false;
            }
        }
    }

    private static Object apply(Parser.Op op, Object l, Object r) {
        return switch (op.text) {
            case "||" -> truthy(l) || truthy(r);
            case "&&" -> truthy(l) && truthy(r);
            case "==" -> valueEquals(l, r);
            case "!=" -> !valueEquals(l, r);
            case ">" -> compare(l, r) > 0;
            case ">=" -> compare(l, r) >= 0;
            case "<" -> compare(l, r) < 0;
            case "<=" -> compare(l, r) <= 0;
            case "+" -> add(l, r);
            case "-" -> Json.num(l) - Json.num(r);
            case "*" -> Json.num(l) * Json.num(r);
            case "/" -> Json.num(l) / Json.num(r);
            case "%" -> Json.num(l) % Json.num(r);
            default -> throw new EvalException("未知运算符 " + op.text);
        };
    }

    private static boolean valueEquals(Object l, Object r) {
        if (l == null && r == null) return true;
        if (l == null || r == null) return false;
        if (l instanceof Number && r instanceof Number)
            return ((Number) l).doubleValue() == ((Number) r).doubleValue();
        if ((l instanceof Boolean || r instanceof Boolean) && !(l instanceof Boolean b1 && r instanceof Boolean b2 && b1 == b2))
            return false;
        return l.equals(r);
    }

    private static int compare(Object l, Object r) {
        if (l instanceof Number && r instanceof Number)
            return Double.compare(((Number) l).doubleValue(), ((Number) r).doubleValue());
        if (l instanceof String && r instanceof String)
            return ((String) l).compareTo((String) r);
        throw new EvalException("只能比较数字或字符串");
    }

    private static Object add(Object l, Object r) {
        if (l instanceof String || r instanceof String) {
            String ls = l instanceof String sx ? sx : Json.write(l);
            String rs = r instanceof String rx ? rx : Json.write(r);
            return ls + rs;
        }
        if (l instanceof Number && r instanceof Number)
            return Json.num(l) + Json.num(r);
        throw new EvalException("+ 只能用于数字或字符串拼接");
    }
}
