package replay.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 零依赖 JSON 解析/序列化，含键排序的 canonical 形式（用于稳定指纹）。 */
public final class Json {
    private Json() {}

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) throw new JsonException("unexpected trailing content at offset " + p.pos);
        return v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        char peek() {
            if (pos >= s.length()) throw new JsonException("unexpected end of input");
            return s.charAt(pos);
        }

        char next() { char c = peek(); pos++; return c; }

        void expect(char c) {
            char a = next();
            if (a != c) throw new JsonException("expected '" + c + "' but got '" + a + "' at offset " + (pos - 1));
        }

        void expectWord(String w) {
            if (!s.startsWith(w, pos)) throw new JsonException("invalid literal at offset " + pos);
            pos += w.length();
        }

        Object parseValue() {
            skipWs();
            char c = peek();
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expectWord("true"); return Boolean.TRUE;
                case 'f': expectWord("false"); return Boolean.FALSE;
                case 'n': expectWord("null"); return null;
                default: return parseNumber();
            }
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String k = parseString();
                skipWs();
                expect(':');
                m.put(k, parseValue());
                skipWs();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw new JsonException("expected ',' or '}' at offset " + (pos - 1));
            }
            return m;
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> l = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return m; }
            while (true) {
                l.add(parseValue());
                skipWs();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw new JsonException("expected ',' or ']' at offset " + (pos - 1));
            }
            return l;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw new JsonException("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = next();
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
                            if (pos + 4 > s.length()) throw new JsonException("bad \\u escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new JsonException("bad escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object parseNumber() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') pos++;
            boolean floating = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') { floating = true; pos++; }
                else break;
            }
            if (start == pos) throw new JsonException("invalid value at offset " + start);
            String num = s.substring(start, pos);
            if (!floating) {
                try { return Long.parseLong(num); } catch (NumberFormatException ignored) { }
            }
            try {
                return Double.parseDouble(num);
            } catch (NumberFormatException e) {
                throw new JsonException("invalid number '" + num + "'");
            }
        }
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v, false);
        return sb.toString();
    }

    public static String canonical(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v, true);
        return sb.toString();
    }

    public static String pretty(Object v) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, v, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v, boolean canon) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { writeString(sb, (String) v); return; }
        if (v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Number) { sb.append(formatNumber((Number) v)); return; }
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (canon) m = new TreeMap<>(m);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue(), canon);
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, o, canon);
            }
            sb.append(']');
            return;
        }
        writeString(sb, String.valueOf(v));
    }

    private static String formatNumber(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new JsonException("non-finite number not allowed");
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            return Double.toString(d);
        }
        return n.toString();
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

    @SuppressWarnings("unchecked")
    private static void writePretty(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                pad(sb, indent + 1);
                writeString(sb, e.getKey());
                sb.append(": ");
                writePretty(sb, e.getValue(), indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(",\n");
                first = false;
                pad(sb, indent + 1);
                writePretty(sb, o, indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append(']');
        } else {
            writeValue(sb, v, false);
        }
    }

    private static void pad(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append("  ");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v) {
        if (v instanceof Map) return (Map<String, Object>) v;
        throw new JsonException("expected object, got " + typeName(v));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object v) {
        if (v instanceof List) return (List<Object>) v;
        throw new JsonException("expected array, got " + typeName(v));
    }

    public static String asString(Object v) {
        if (v instanceof String) return (String) v;
        throw new JsonException("expected string, got " + typeName(v));
    }

    public static long asLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        throw new JsonException("expected number, got " + typeName(v));
    }

    public static String optString(Map<String, Object> m, String key, String dflt) {
        Object v = m.get(key);
        return v == null ? dflt : asString(v);
    }

    public static long optLong(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        return v == null ? dflt : asLong(v);
    }

    private static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof Map) return "object";
        if (v instanceof List) return "array";
        if (v instanceof String) return "string";
        if (v instanceof Number) return "number";
        if (v instanceof Boolean) return "boolean";
        return v.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> src = (Map<String, Object>) v;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : src.entrySet()) copy.put(e.getKey(), deepCopy(e.getValue()));
            return copy;
        }
        if (v instanceof List) {
            List<Object> src = (List<Object>) v;
            List<Object> copy = new ArrayList<>(src.size());
            for (Object o : src) copy.add(deepCopy(o));
            return copy;
        }
        return v;
    }

    public static Map<String, Object> obj() { return new LinkedHashMap<>(); }

    public static List<Object> arr() { return new ArrayList<>(); }

    @SuppressWarnings("unchecked")
    public static Object getPath(Object root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(seg);
            if (cur == null) return null;
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    public static void setPath(Map<String, Object> root, String path, Object value) {
        String[] segs = path.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            Object next = cur.get(segs[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                cur.put(segs[i], next);
            }
            cur = (Map<String, Object>) next;
        }
        cur.put(segs[segs.length - 1], value);
    }
}
