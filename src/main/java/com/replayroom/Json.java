package com.replayroom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 自包含 JSON 解析/序列化，无外部依赖。canonical() 产出键排序的确定序列化，用于指纹。 */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (!p.end()) throw new IllegalArgumentException("JSON 解析失败：末尾有多余内容，位置 " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new IllegalArgumentException("期望 JSON 对象，实际为: " + write(o));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        if (o instanceof List) return (List<Object>) o;
        throw new IllegalArgumentException("期望 JSON 数组，实际为: " + write(o));
    }

    public static String str(Object o) {
        if (o == null) return null;
        if (o instanceof String) return (String) o;
        throw new IllegalArgumentException("期望字符串，实际为: " + write(o));
    }

    public static long num(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        throw new IllegalArgumentException("期望数字，实际为: " + write(o));
    }

    public static Object deepCopy(Object o) {
        if (o instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : obj(o).entrySet()) m.put(e.getKey(), deepCopy(e.getValue()));
            return m;
        }
        if (o instanceof List) {
            List<Object> l = new ArrayList<>();
            for (Object e : arr(o)) l.add(deepCopy(e));
            return l;
        }
        return o;
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        writeInto(sb, o, false);
        return sb.toString();
    }

    /** 键按字典序排序的确定序列化，任何相同内容产生相同字节。 */
    public static String canonical(Object o) {
        StringBuilder sb = new StringBuilder();
        writeInto(sb, o, true);
        return sb.toString();
    }

    private static void writeInto(StringBuilder sb, Object o, boolean sortKeys) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { quote(sb, (String) o); return; }
        if (o instanceof Boolean) { sb.append(o); return; }
        if (o instanceof Number) {
            if (o instanceof Double || o instanceof Float) {
                double d = ((Number) o).doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) sb.append((long) d);
                else sb.append(d);
            } else sb.append(o);
            return;
        }
        if (o instanceof Map) {
            Map<String, Object> m = obj(o);
            if (sortKeys) m = new TreeMap<>(m);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                writeInto(sb, e.getValue(), sortKeys);
            }
            sb.append('}');
            return;
        }
        if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object e : arr(o)) {
                if (!first) sb.append(',');
                first = false;
                writeInto(sb, e, sortKeys);
            }
            sb.append(']');
            return;
        }
        throw new IllegalArgumentException("无法序列化类型: " + o.getClass());
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        boolean end() { return pos >= s.length(); }

        void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        char peek() {
            if (end()) throw new IllegalArgumentException("JSON 解析失败：意外结束");
            return s.charAt(pos);
        }

        void expect(char c) {
            if (end() || s.charAt(pos) != c)
                throw new IllegalArgumentException("JSON 解析失败：位置 " + pos + " 期望 '" + c + "'");
            pos++;
        }

        Object value() {
            char c = peek();
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') { literal("true"); return Boolean.TRUE; }
            if (c == 'f') { literal("false"); return Boolean.FALSE; }
            if (c == 'n') { literal("null"); return null; }
            return number();
        }

        void literal(String lit) {
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("JSON 解析失败：位置 " + pos);
            pos += lit.length();
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return m; }
                throw new IllegalArgumentException("JSON 解析失败：位置 " + pos);
            }
        }

        List<Object> array() {
            expect('[');
            List<Object> l = new ArrayList<>();
            ws();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return l; }
                throw new IllegalArgumentException("JSON 解析失败：位置 " + pos);
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (end()) throw new IllegalArgumentException("JSON 解析失败：字符串未闭合");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new IllegalArgumentException("JSON 解析失败：非法转义 \\" + e);
                    }
                } else sb.append(c);
            }
        }

        Object number() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!end() && Character.isDigit(s.charAt(pos))) pos++;
            boolean dbl = false;
            if (!end() && s.charAt(pos) == '.') {
                dbl = true;
                pos++;
                while (!end() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (!end() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                dbl = true;
                pos++;
                if (!end() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (!end() && Character.isDigit(s.charAt(pos))) pos++;
            }
            String t = s.substring(start, pos);
            if (t.isEmpty() || t.equals("-")) throw new IllegalArgumentException("JSON 解析失败：位置 " + start + " 非法数字");
            try {
                return dbl ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t);
            } catch (NumberFormatException e) {
                return Double.parseDouble(t);
            }
        }
    }
}
