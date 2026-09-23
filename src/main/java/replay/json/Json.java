package replay.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 极简 JSON：解析为 Map/List/String/Long/Double/Boolean/null，并提供键排序的规范化序列化。 */
public final class Json {

    private Json() {}

    // ---------- 解析 ----------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) throw new JsonException("第 " + p.pos + " 个字符后存在多余内容");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new JsonException("期望 JSON 对象");
        return (Map<String, Object>) v;
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String msg) { super(msg); }
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
            if (atEnd()) throw new JsonException("意外结束");
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

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new JsonException("第 " + pos + " 个字符处期望 " + lit);
            pos += lit.length();
        }

        private Map<String, Object> parseObject() {
            pos++; // {
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (!atEnd() && s.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWs();
                if (atEnd() || s.charAt(pos) != '"') throw new JsonException("第 " + pos + " 个字符处期望对象键");
                String key = parseString();
                skipWs();
                if (atEnd() || s.charAt(pos) != ':') throw new JsonException("第 " + pos + " 个字符处期望 ':'");
                pos++;
                skipWs();
                map.put(key, parseValue());
                skipWs();
                if (atEnd()) throw new JsonException("对象未闭合");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return map; }
                throw new JsonException("第 " + pos + " 个字符处期望 ',' 或 '}'");
            }
        }

        private List<Object> parseArray() {
            pos++; // [
            List<Object> list = new ArrayList<>();
            skipWs();
            if (!atEnd() && s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                skipWs();
                list.add(parseValue());
                skipWs();
                if (atEnd()) throw new JsonException("数组未闭合");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                throw new JsonException("第 " + pos + " 个字符处期望 ',' 或 ']'");
            }
        }

        private String parseString() {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw new JsonException("字符串未闭合");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (atEnd()) throw new JsonException("转义序列不完整");
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
                            if (pos + 4 > s.length()) throw new JsonException("unicode 转义不完整");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new JsonException("非法转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            if (!atEnd() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            boolean floating = false;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') { floating = true; pos++; }
                else break;
            }
            if (start == pos) throw new JsonException("第 " + pos + " 个字符处期望数值");
            String num = s.substring(start, pos);
            try {
                if (floating) return Double.parseDouble(num);
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new JsonException("非法数值: " + num);
            }
        }
    }

    // ---------- 序列化 ----------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, false);
        return sb.toString();
    }

    /** 规范化序列化：对象键按字典序排列，保证同一逻辑内容得到同一字符串。 */
    public static String writeCanonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, true);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v, boolean canonical) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { writeString(sb, (String) v); return; }
        if (v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Long || v instanceof Integer) { sb.append(v); return; }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                sb.append((long) d).append(".0");
            } else {
                sb.append(Double.toString(d));
            }
            return;
        }
        if (v instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) v;
            if (canonical) map = new TreeMap<>(map);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue(), canonical);
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item, canonical);
            }
            sb.append(']');
            return;
        }
        throw new JsonException("无法序列化类型: " + v.getClass());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
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

    // ---------- 便捷取值 ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v, String what) {
        if (!(v instanceof Map)) throw new JsonException(what + " 必须是对象");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object v, String what) {
        if (!(v instanceof List)) throw new JsonException(what + " 必须是数组");
        return (List<Object>) v;
    }

    public static String asString(Object v, String what) {
        if (!(v instanceof String)) throw new JsonException(what + " 必须是字符串");
        return (String) v;
    }

    public static long asLong(Object v, String what) {
        if (!(v instanceof Number)) throw new JsonException(what + " 必须是数值");
        return ((Number) v).longValue();
    }
}
