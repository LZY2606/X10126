package replay.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal self-contained JSON parser/writer with a canonical (sorted-key) mode. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != p.s.length()) throw new JsonException("trailing content at " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new JsonException("expected JSON object");
        return (Map<String, Object>) v;
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v, false);
        return sb.toString();
    }

    /** Canonical form: object keys sorted, no insignificant whitespace. Used for fingerprints. */
    public static String canonical(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v, true);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v, boolean canonical) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { writeString(sb, s); return; }
        if (v instanceof Boolean b) { sb.append(b); return; }
        if (v instanceof Long l) { sb.append(l); return; }
        if (v instanceof Integer i) { sb.append(i.longValue()); return; }
        if (v instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) sb.append(d.longValue());
            else sb.append(d);
            return;
        }
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            sb.append('{');
            boolean first = true;
            if (canonical) {
                for (Map.Entry<String, Object> e : new TreeMap<>(m).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeString(sb, e.getKey());
                    sb.append(':');
                    writeValue(sb, e.getValue(), true);
                }
            } else {
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeString(sb, e.getKey());
                    sb.append(':');
                    writeValue(sb, e.getValue(), false);
                }
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
                writeValue(sb, o, canonical);
            }
            sb.append(']');
            return;
        }
        throw new JsonException("cannot write value of type " + v.getClass());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String msg) { super(msg); }
    }

    private static final class Parser {
        final String s;
        int pos;
        Parser(String s) { this.s = s; }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value() {
            skipWs();
            if (pos >= s.length()) throw new JsonException("unexpected end of input");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> { expect("true"); yield Boolean.TRUE; }
                case 'f' -> { expect("false"); yield Boolean.FALSE; }
                case 'n' -> { expect("null"); yield null; }
                default -> number();
            };
        }

        void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new JsonException("expected '" + lit + "' at " + pos);
            pos += lit.length();
        }

        Map<String, Object> object() {
            pos++;
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw new JsonException("expected ':' at " + pos);
                pos++;
                m.put(key, value());
                skipWs();
                if (pos >= s.length()) throw new JsonException("unterminated object");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return m; }
                throw new JsonException("expected ',' or '}' at " + pos);
            }
        }

        List<Object> array() {
            pos++;
            List<Object> list = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(value());
                skipWs();
                if (pos >= s.length()) throw new JsonException("unterminated array");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                throw new JsonException("expected ',' or ']' at " + pos);
            }
        }

        String string() {
            if (pos >= s.length() || s.charAt(pos) != '"') throw new JsonException("expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) break;
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new JsonException("bad escape \\" + e);
                    }
                } else sb.append(c);
            }
            throw new JsonException("unterminated string");
        }

        Object number() {
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            boolean isDouble = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) { pos++; continue; }
                if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') { isDouble = true; pos++; continue; }
                break;
            }
            if (start == pos) throw new JsonException("unexpected character '" + (pos < s.length() ? s.charAt(pos) : "EOF") + "' at " + pos);
            String num = s.substring(start, pos);
            try {
                if (isDouble) return Double.parseDouble(num);
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new JsonException("bad number '" + num + "'");
            }
        }
    }
}
