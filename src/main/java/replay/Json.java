package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal dependency-free JSON parser/serializer with canonical (sorted-key) output for hashing. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) throw new IllegalArgumentException("JSON 末尾存在多余内容, 位置 " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new IllegalArgumentException("期望 JSON 对象, 实际为: " + o);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        if (o instanceof List) return (List<Object>) o;
        throw new IllegalArgumentException("期望 JSON 数组, 实际为: " + o);
    }

    public static String str(Object o) {
        if (o instanceof String) return (String) o;
        throw new IllegalArgumentException("期望字符串, 实际为: " + o);
    }

    public static long num(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        throw new IllegalArgumentException("期望数字, 实际为: " + o);
    }

    public static Map<String, Object> map() { return new LinkedHashMap<>(); }
    public static List<Object> list() { return new ArrayList<>(); }

    public static Object deepCopy(Object o) {
        if (o instanceof Map) {
            Map<String, Object> src = obj(o);
            Map<String, Object> out = map();
            for (Map.Entry<String, Object> e : src.entrySet()) out.put(e.getKey(), deepCopy(e.getValue()));
            return out;
        }
        if (o instanceof List) {
            List<Object> out = list();
            for (Object v : arr(o)) out.add(deepCopy(v));
            return out;
        }
        return o;
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, o, false);
        return sb.toString();
    }

    /** Canonical form: object keys sorted, no whitespace. Used for stable fingerprints. */
    public static String canonical(Object o) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, o, true);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object o, boolean canonical) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { writeString(sb, (String) o); return; }
        if (o instanceof Boolean) { sb.append(o); return; }
        if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) sb.append((long) d);
            else sb.append(d);
            return;
        }
        if (o instanceof Number) { sb.append(o); return; }
        if (o instanceof Map) {
            Map<String, Object> m = obj(o);
            sb.append('{');
            boolean first = true;
            if (canonical) m = new TreeMap<>(m);
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue(), true);
            }
            sb.append('}');
            return;
        }
        if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object v : arr(o)) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, v, canonical);
            }
            sb.append(']');
            return;
        }
        throw new IllegalArgumentException("无法序列化的类型: " + o.getClass());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }
        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object parseValue() {
            skipWs();
            if (atEnd()) throw new IllegalArgumentException("JSON 意外结束");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return parseNumber();
            }
        }

        private void expect(String kw) {
            if (s.startsWith(kw, pos)) { pos += kw.length(); return; }
            throw new IllegalArgumentException("JSON 解析失败, 位置 " + pos);
        }

        private Map<String, Object> parseObject() {
            pos++;
            Map<String, Object> m = map();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw new IllegalArgumentException("缺少 ':', 位置 " + pos);
                pos++;
                Object v = parseValue();
                m.put(key, v);
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') { pos++; continue; }
                if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
                throw new IllegalArgumentException("缺少 ',' 或 '}', 位置 " + pos);
            }
        }

        private List<Object> parseArray() {
            pos++;
            List<Object> l = list();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                l.add(parseValue());
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') { pos++; continue; }
                if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
                throw new IllegalArgumentException("缺少 ',' 或 ']', 位置 " + pos);
            }
        }

        private String parseString() {
            if (pos >= s.length() || s.charAt(pos) != '"') throw new IllegalArgumentException("期望字符串, 位置 " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) break;
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new IllegalArgumentException("非法转义: \\" + e);
                    }
                } else sb.append(c);
            }
            throw new IllegalArgumentException("字符串未闭合");
        }

        private Object parseNumber() {
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            boolean isDouble = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') { isDouble = true; pos++; }
                else break;
            }
            if (start == pos) throw new IllegalArgumentException("非法值, 位置 " + pos);
            String t = s.substring(start, pos);
            try {
                if (isDouble) return Double.parseDouble(t);
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("非法数字: " + t);
            }
        }
    }
}
