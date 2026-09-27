package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal deterministic JSON parser / serializer.
 *
 * Numbers parse as Long when integral, otherwise Double. Canonical output
 * sorts object keys and uses fixed number formatting, so equal values always
 * produce identical bytes - the basis of all fingerprints in the system.
 */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.eof()) {
            throw p.error("trailing characters");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("expected JSON object");
        }
        return (Map<String, Object>) v;
    }

    /** Stable, key-sorted serialization used for fingerprints and exports. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(sb, value);
        return sb.toString();
    }

    /** Human-readable serialization (2-space indent, insertion/key order). */
    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, value, 0, false);
        return sb.toString();
    }

    public static Map<String, Object> obj() {
        return new LinkedHashMap<>();
    }

    public static List<Object> arr() {
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object v) {
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("expected object, got " + typeName(v));
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object v) {
        if (!(v instanceof List)) {
            throw new IllegalArgumentException("expected array, got " + typeName(v));
        }
        return (List<Object>) v;
    }

    public static String asString(Object v) {
        if (!(v instanceof String)) {
            throw new IllegalArgumentException("expected string, got " + typeName(v));
        }
        return (String) v;
    }

    public static long asLong(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        throw new IllegalArgumentException("expected number, got " + typeName(v));
    }

    /** Deep copy of a JSON value tree (maps/arrays become mutable). */
    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> src = (Map<String, Object>) value;
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : src.entrySet()) {
                out.put(e.getKey(), deepCopy(e.getValue()));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                out.add(deepCopy(item));
            }
            return out;
        }
        return value;
    }

    private static String typeName(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void writeCanonical(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Long) {
            sb.append(((Long) value).longValue());
        } else if (value instanceof Integer) {
            sb.append(((Integer) value).longValue());
        } else if (value instanceof Double) {
            writeDouble(sb, ((Double) value).doubleValue());
        } else if (value instanceof Number) {
            writeDouble(sb, ((Number) value).doubleValue());
        } else if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            List<String> keys = new ArrayList<>(map.keySet());
            java.util.Collections.sort(keys);
            sb.append('{');
            boolean first = true;
            for (String key : keys) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, key);
                sb.append(':');
                writeCanonical(sb, map.get(key));
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) sb.append(',');
                first = false;
                writeCanonical(sb, item);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize " + typeName(value));
        }
    }

    @SuppressWarnings("unchecked")
    private static void writePretty(StringBuilder sb, Object value, int depth, boolean inKey) {
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                indent(sb, depth + 1);
                writeString(sb, e.getKey());
                sb.append(": ");
                writePretty(sb, e.getValue(), depth + 1, false);
            }
            sb.append('\n');
            indent(sb, depth);
            sb.append('}');
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",\n");
                first = false;
                indent(sb, depth + 1);
                writePretty(sb, item, depth + 1, false);
            }
            sb.append('\n');
            indent(sb, depth);
            sb.append(']');
        } else {
            writeCanonical(sb, value);
        }
    }

    private static void indent(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth));
    }

    private static void writeDouble(StringBuilder sb, double d) {
        if (!Double.isFinite(d)) {
            throw new IllegalArgumentException("non-finite number");
        }
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e16) {
            sb.append(Long.toString((long) d));
        } else {
            String s = Double.toString(d);
            sb.append(s);
        }
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
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // Parser
    // ------------------------------------------------------------------

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return pos >= s.length();
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON error at position " + pos + ": " + msg);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWs();
            if (eof()) throw error("unexpected end of input");
            char c = s.charAt(pos);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBoolean();
            if (c == 'n') return readNull();
            if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
            throw error("unexpected character '" + c + "'");
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (consume('}')) return map;
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWs();
                if (consume(',')) continue;
                if (consume('}')) break;
                throw error("expected ',' or '}'");
            }
            return map;
        }

        List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (consume(']')) return list;
            while (true) {
                Object value = readValue();
                list.add(value);
                skipWs();
                if (consume(',')) continue;
                if (consume(']')) break;
                throw error("expected ',' or ']'");
            }
            return list;
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw error("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (eof()) throw error("unterminated escape");
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
                            if (pos + 4 > s.length()) throw error("bad unicode escape");
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException nfe) {
                                throw error("bad unicode escape");
                            }
                        }
                        default -> throw error("bad escape '\\" + e + "'");
                    }
                } else {
                    if (c < 0x20) throw error("unescaped control character");
                    sb.append(c);
                }
            }
        }

        Boolean readBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw error("invalid literal");
        }

        Object readNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw error("invalid literal");
        }
+
        Object readNumber() {
            int start = pos;
            if (consume('-')) {}
            readDigits();
            boolean integral = true;
            if (!eof() && s.charAt(pos) == '.') {
                integral = false;
                pos++;
                readDigits();
            }
            if (!eof() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                integral = false;
                pos++;
                if (!eof() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                readDigits();
            }
            String token = s.substring(start, pos);
            if (integral) {
                try {
                    return Long.parseLong(token);
                } catch (NumberFormatException nfe) {
                    return Double.parseDouble(token);
                }
            }
            return Double.parseDouble(token);
        }

        private void readDigits() {
            int start = pos;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            if (pos == start) throw error("expected digits");
        }

        boolean consume(char c) {
            if (!eof() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        void expect(char c) {
            if (eof() || s.charAt(pos) != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }
    }
}
