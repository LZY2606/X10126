package rr.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 轻量 JSON 值：支持 null/bool/number(String)/string/list/object，Map 保持插入顺序。 */
public final class Json {
    private Json() {}

    public static final class JObj {
        public final Map<String, Object> m = new LinkedHashMap<>();
        public JObj put(String k, Object v) { m.put(k, wrap(v)); return this; }
        public Object get(String k) { return m.get(k); }
        public boolean has(String k) { return m.containsKey(k); }
    }

    public static JObj obj() { return new JObj(); }
    public static List<Object> arr() { return new ArrayList<>(); }
    public static List<Object> arr(Object... xs) {
        List<Object> l = new ArrayList<>();
        for (Object x : xs) l.add(wrap(x));
        return l;
    }

    @SuppressWarnings("unchecked")
    public static Object wrap(Object v) {
        if (v == null) return null;
        if (v instanceof JObj o) return o.m;
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) out.add(wrap(x));
            return out;
        }
        if (v instanceof Map<?, ?> mp) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : mp.entrySet()) out.put(String.valueOf(e.getKey()), wrap(e.getValue()));
            return out;
        }
        if (v instanceof Integer || v instanceof Long || v instanceof String || v instanceof Boolean
                || v instanceof Double || v instanceof Number) {
            return v;
        }
        throw new IllegalArgumentException("不能放入 JSON 的类型: " + v.getClass());
    }

    // ---------- 取值辅助 ----------
    public static Map<String, Object> asObj(Object v) {
        if (!(v instanceof Map)) throw new JsonException("期望 object，得到 " + typeName(v));
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    public static List<Object> asArr(Object v) {
        if (!(v instanceof List)) throw new JsonException("期望 array，得到 " + typeName(v));
        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>) v;
        return l;
    }

    public static String str(Object v, String key) {
        Object x = asObj(v).get(key);
        if (!(x instanceof String)) throw new JsonException("字段 " + key + " 必须是字符串");
        return (String) x;
    }

    public static String optStr(Object v, String key, String dflt) {
        Object x = asObj(v).get(key);
        return x == null ? dflt : (x instanceof String s ? s : dflt);
    }

    public static long lng(Object v, String key) {
        Object x = asObj(v).get(key);
        if (x instanceof Number n) return n.longValue();
        throw new JsonException("字段 " + key + " 必须是整数");
    }

    public static long optLng(Object v, String key, long dflt) {
        Object x = asObj(v).get(key);
        if (x == null) return dflt;
        if (x instanceof Number n) return n.longValue();
        throw new JsonException("字段 " + key + " 必须是整数");
    }

    public static double num(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        throw new JsonException("期望 number，得到 " + typeName(v));
    }

    public static double optNum(Object v, String key, double dflt) {
        Object x = asObj(v).get(key);
        return x == null ? dflt : num(x);
    }

    public static boolean optBool(Object v, String key, boolean dflt) {
        Object x = asObj(v).get(key);
        return x == null ? dflt : (x instanceof Boolean b ? b : dflt);
    }

    public static List<Object> optArr(Object v, String key) {
        Object x = asObj(v).get(key);
        if (x == null) return new ArrayList<>();
        return asArr(x);
    }

    public static Map<String, Object> optObj(Object v, String key) {
        Object x = asObj(v).get(key);
        if (x == null) return new LinkedHashMap<>();
        return asObj(x);
    }

    public static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof Map) return "object";
        if (v instanceof List) return "array";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Number) return "number";
        return "string";
    }

    // ---------- 序列化 ----------
    public static String write(Object v) { return write(v, false); }

    public static String write(Object v, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        writeVal(sb, wrap(v), pretty, 0);
        return sb.toString();
    }

    private static void writeVal(StringBuilder sb, Object v, boolean pretty, int depth) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean b) sb.append(b.booleanValue());
        else if (v instanceof Number n) sb.append(formatNumber(n));
        else if (v instanceof String s) writeStr(sb, s);
        else if (v instanceof List<?> l) {
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                if (pretty) { sb.append('\n'); indent(sb, depth + 1); }
                writeVal(sb, l.get(i), pretty, depth + 1);
            }
            if (pretty) { sb.append('\n'); indent(sb, depth); }
            sb.append(']');
        } else if (v instanceof Map<?, ?> mp) {
            if (mp.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            int i = 0;
            for (Map.Entry<?, ?> e : mp.entrySet()) {
                if (i++ > 0) sb.append(',');
                if (pretty) { sb.append('\n'); indent(sb, depth + 1); }
                writeStr(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                writeVal(sb, e.getValue(), pretty, depth + 1);
            }
            if (pretty) { sb.append('\n'); indent(sb, depth); }
            sb.append('}');
        } else throw new JsonException("不可序列化: " + v.getClass());
    }

    public static String formatNumber(Number n) {
        double d = n.doubleValue();
        if (!Double.isFinite(d)) throw new JsonException("数字必须有限");
        if (d == Math.rint(d) && !Double.toString(d).contains("E") && Math.abs(d) < 1e18) {
            return Long.toString((long) d);
        }
        if (n instanceof Double || n instanceof Float) {
            String s = Double.toString(d);
            return s;
        }
        return n.toString();
    }

    private static void indent(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth));
    }

    private static void writeStr(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** 规范化序列化：对象键按码点排序、无空白、整数归一。用于稳定指纹。 */
    public static String canonical(Object v) {
        StringBuilder sb = new StringBuilder();
        canonVal(sb, wrap(v));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void canonVal(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean b) sb.append(b.booleanValue());
        else if (v instanceof Number n) sb.append(formatNumber(n));
        else if (v instanceof String s) writeStr(sb, s);
        else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                canonVal(sb, l.get(i));
            }
            sb.append(']');
        } else if (v instanceof Map<?, ?> mp) {
            List<String> keys = new java.util.TreeSet<>(mp.keySet().stream().map(String::valueOf).toList())
                    .stream().toList();
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                writeStr(sb, keys.get(i));
                sb.append(':');
                canonVal(sb, ((Map<String, Object>) mp).get(keys.get(i)));
            }
            sb.append('}');
        } else throw new JsonException("不可规范化: " + v.getClass());
    }

    // ---------- 解析 ----------
    public static Object parse(String s) {
        Parser p = new Parser(s);
        Object v = p.readVal();
        p.ws();
        if (!p.eof()) throw new JsonException("尾部多余字符，位置 " + p.pos);
        return v;
    }

    static final class Parser {
        final String s; int pos;
        Parser(String s) { this.s = s; }
        boolean eof() { return pos >= s.length(); }
        void ws() { while (!eof() && Character.isWhitespace(s.charAt(pos))) pos++; }
        char peek() { ws(); return s.charAt(pos); }

        Object readVal() {
            ws();
            if (eof()) throw new JsonException("意外的输入结束");
            char c = s.charAt(pos);
            if (c == '{') return readObj();
            if (c == '[') return readArr();
            if (c == '"') return readStr();
            if (c == 't' || c == 'f') return readBool();
            if (c == 'n') return readNull();
            return readNum();
        }

        Map<String, Object> readObj() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            ws();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                ws();
                String k = readStr();
                ws();
                expect(':');
                Object v = readVal();
                m.put(k, v);
                ws();
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new JsonException("期望 , 或 }，位置 " + (pos - 1));
            }
        }

        List<Object> readArr() {
            List<Object> l = new ArrayList<>();
            expect('[');
            ws();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                l.add(readVal());
                ws();
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new JsonException("期望 , 或 ]，位置 " + (pos - 1));
            }
        }

        String readStr() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new JsonException("字符串未闭合");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (eof()) throw new JsonException("非法转义");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) throw new JsonException("非法 unicode 转义");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new JsonException("非法转义 \\" + e);
                    }
                } else if (c < 0x20) {
                    throw new JsonException("字符串中存在未转义控制字符");
                } else sb.append(c);
            }
        }

        Boolean readBool() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new JsonException("非法字面量，位置 " + pos);
        }

        Object readNull() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw new JsonException("非法字面量，位置 " + pos);
        }

        Number readNum() {
            int start = pos;
            if (peek() == '-') pos++;
            boolean dot = false, exp = false;
            while (!eof()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') pos++;
                else if (c == '.' && !dot && !exp) { dot = true; pos++; }
                else if ((c == 'e' || c == 'E') && !exp) {
                    exp = true; pos++;
                    if (!eof() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                } else break;
            }
            String t = s.substring(start, pos);
            if (t.isEmpty() || t.equals("-")) throw new JsonException("非法数字，位置 " + start);
            try {
                return dot || exp ? Double.parseDouble(t) : Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new JsonException("非法数字 " + t);
            }
        }

        void expect(char c) {
            ws();
            if (eof() || s.charAt(pos) != c) throw new JsonException("期望 '" + c + "'，位置 " + pos);
            pos++;
        }
    }

    public static class JsonException extends RuntimeException {
        public JsonException(String m) { super(m); }
    }
}
