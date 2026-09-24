package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal JSON parser/serializer with canonical (sorted-key) output for hashing. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        return new Parser(text).parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected JSON object");
        return (Map<String, Object>) v;
    }

    /** Canonical form: object keys sorted, no whitespace. Used for fingerprints. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(value, sb);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(value, sb, 0);
        return sb.toString();
    }

    private static void writeCanonical(Object v, StringBuilder sb) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { writeString(s, sb); return; }
        if (v instanceof Boolean || v instanceof Long || v instanceof Integer) { sb.append(v); return; }
        if (v instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) sb.append(d.longValue());
            else sb.append(d);
            return;
        }
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) sorted.put(String.valueOf(e.getKey()), e.getValue());
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(e.getKey(), sb);
                sb.append(':');
                writeCanonical(e.getValue(), sb);
            }
            sb.append('}');
            return;
        }
        if (v instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                writeCanonical(list.get(i), sb);
            }
            sb.append(']');
            return;
        }
        throw new IllegalArgumentException("cannot serialize: " + v.getClass());
    }

    private static void writePretty(Object v, StringBuilder sb, int indent) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) sorted.put(String.valueOf(e.getKey()), e.getValue());
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append("  ".repeat(indent + 1));
                writeString(e.getKey(), sb);
                sb.append(": ");
                writePretty(e.getValue(), sb, indent + 1);
            }
            sb.append('\n').append("  ".repeat(indent)).append('}');
            return;
        }
        if (v instanceof List<?> list) {
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",\n");
                sb.append("  ".repeat(indent + 1));
                writePretty(list.get(i), sb, indent + 1);
            }
            sb.append('\n').append("  ".repeat(indent)).append(']');
            return;
        }
        writeCanonical(v, sb);
    }

    private static void writeString(String s, StringBuilder sb) {
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

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        Object parseValue() {
            skipWs();
            Object v = readValue();
            skipWs();
            if (pos != s.length()) throw error("trailing content");
            return v;
        }

        private Object readValue() {
            skipWs();
            if (pos >= s.length()) throw error("unexpected end");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> { expect("true"); yield Boolean.TRUE; }
                case 'f' -> { expect("false"); yield Boolean.FALSE; }
                case 'n' -> { expect("null"); yield null; }
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek('}')) { pos++; return map; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw error("expected ':'");
                pos++;
                map.put(key, readValue());
                skipWs();
                if (peek(',')) { pos++; continue; }
                if (peek('}')) { pos++; return map; }
                throw error("expected ',' or '}'");
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek(']')) { pos++; return list; }
            while (true) {
                list.add(readValue());
                skipWs();
                if (peek(',')) { pos++; continue; }
                if (peek(']')) { pos++; return list; }
                throw error("expected ',' or ']'");
            }
        }

        private String readString() {
            if (pos >= s.length() || s.charAt(pos) != '"') throw error("expected string");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw error("bad escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("unterminated string");
        }

        private Object readNumber() {
            int start = pos;
            if (peek('-')) pos++;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.' || s.charAt(pos) == 'e'
                    || s.charAt(pos) == 'E' || s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
            if (start == pos) throw error("unexpected character '" + s.charAt(pos) + "'");
            String num = s.substring(start, pos);
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            return Long.parseLong(num);
        }

        private void expect(String word) {
            if (!s.startsWith(word, pos)) throw error("expected " + word);
            pos += word.length();
        }

        private boolean peek(char c) { return pos < s.length() && s.charAt(pos) == c; }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON parse error at " + pos + ": " + msg);
        }
    }
}
