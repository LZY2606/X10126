package replay.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal dependency-free JSON parser/serializer with canonical (sorted-key) output. */
public final class Json {

    private Json() {}

    // ---------- parsing ----------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) throw new JsonException("trailing content at offset " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new JsonException("expected JSON object");
        return (Map<String, Object>) v;
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String msg) { super(msg); }
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
            if (atEnd()) throw new JsonException("unexpected end of input");
            return s.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void expect(char c) {
            if (next() != c) throw new JsonException("expected '" + c + "' at offset " + (pos - 1));
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

        void expectWord(String w) {
            if (!s.startsWith(w, pos)) throw new JsonException("invalid literal at offset " + pos);
            pos += w.length();
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                char c = next();
                if (c == '}') return map;
                if (c != ',') throw new JsonException("expected ',' or '}' at offset " + (pos - 1));
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw new JsonException("expected ',' or ']' at offset " + (pos - 1));
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
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
                            if (pos + 4 > s.length()) throw new JsonException("bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new JsonException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!atEnd() && Character.isDigit(s.charAt(pos))) pos++;
            boolean isDouble = false;
            if (!atEnd() && s.charAt(pos) == '.') {
                isDouble = true;
                pos++;
                while (!atEnd() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (!atEnd() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isDouble = true;
                pos++;
                if (!atEnd() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (!atEnd() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (start == pos) throw new JsonException("invalid value at offset " + start);
            String num = s.substring(start, pos);
            try {
                if (isDouble) return Double.parseDouble(num);
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new JsonException("invalid number '" + num + "'");
            }
        }
    }

    // ---------- writing ----------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, false);
        return sb.toString();
    }

    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePrettyValue(sb, value, 0);
        return sb.toString();
    }

    /** Canonical form: object keys sorted, no whitespace. Used for fingerprints/hashes. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, true);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v, boolean sortKeys) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { writeString(sb, (String) v); return; }
        if (v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Number) { writeNumber(sb, (Number) v); return; }
        if (v instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) v;
            sb.append('{');
            boolean first = true;
            Iterable<Map.Entry<String, Object>> entries = sortKeys
                    ? new TreeMap<>(map).entrySet() : map.entrySet();
            for (Map.Entry<String, Object> e : entries) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue(), sortKeys);
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
                writeValue(sb, item, sortKeys);
            }
            sb.append(']');
            return;
        }
        throw new JsonException("cannot serialize " + v.getClass());
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                sb.append(Long.toString((long) d));
            } else {
                sb.append(Double.toString(d));
            }
        } else {
            sb.append(n.toString());
        }
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

    private static void writePrettyValue(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) v;
            if (map.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                indent(sb, indent + 1);
                writeString(sb, e.getKey());
                sb.append(": ");
                writePrettyValue(sb, e.getValue(), indent + 1);
            }
            sb.append('\n');
            indent(sb, indent);
            sb.append('}');
        } else if (v instanceof List) {
            List<?> list = (List<?>) v;
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",\n");
                first = false;
                indent(sb, indent + 1);
                writePrettyValue(sb, item, indent + 1);
            }
            sb.append('\n');
            indent(sb, indent);
            sb.append(']');
        } else {
            writeValue(sb, v, false);
        }
    }

    private static void indent(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append("  ");
    }

    // ---------- typed accessors ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v, String what) {
        if (!(v instanceof Map)) throw new JsonException(what + " must be an object");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object v, String what) {
        if (!(v instanceof List)) throw new JsonException(what + " must be an array");
        return (List<Object>) v;
    }

    public static String asString(Object v, String what) {
        if (!(v instanceof String)) throw new JsonException(what + " must be a string");
        return (String) v;
    }

    public static long asLong(Object v, String what) {
        if (!(v instanceof Number)) throw new JsonException(what + " must be a number");
        return ((Number) v).longValue();
    }

    /** Deep copy of a JSON-shaped value. */
    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                copy.put(e.getKey(), deepCopy(e.getValue()));
            }
            return copy;
        }
        if (v instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<Object>) v) copy.add(deepCopy(item));
            return copy;
        }
        return v;
    }
}
