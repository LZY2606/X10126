package replayroom.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal, dependency-free JSON parser / writer.
 *
 * Supported values: null, Boolean, Long, Double, String, List<Object>,
 * Map<String,Object> (insertion-ordered LinkedHashMap).
 */
public final class Json {

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
        public JsonException(String message, Throwable cause) { super(message, cause); }
    }

    private Json() {}

    // --------------------------------------------------------------- parsing

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (!p.eof()) {
            throw new JsonException("Unexpected trailing content at position " + p.pos);
        }
        return value;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        boolean eof() { return pos >= s.length(); }

        char peek() { return s.charAt(pos); }

        void skipWhitespace() {
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
            skipWhitespace();
            if (eof()) throw new JsonException("Unexpected end of input");
            char c = peek();
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBoolean();
            if (c == 'n') return readNull();
            return readNumber();
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) return map;
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                if (consume('}')) return map;
                expect(',');
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) return list;
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (consume(']')) return list;
                expect(',');
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new JsonException("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (eof()) throw new JsonException("Unterminated escape");
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
                            if (pos + 4 > s.length()) throw new JsonException("Bad unicode escape");
                            String hex = s.substring(pos, pos + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException nfe) {
                                throw new JsonException("Bad unicode escape: " + hex);
                            }
                            pos += 4;
                        }
                        default -> throw new JsonException("Invalid escape: \\" + e);
                    }
                } else if (c < 0x20) {
                    throw new JsonException("Unescaped control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        Boolean readBoolean() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new JsonException("Invalid literal at position " + pos);
        }

        Object readNull() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw new JsonException("Invalid literal at position " + pos);
        }

        Object readNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!eof()) {
                char c = peek();
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String token = s.substring(start, pos);
            if (token.isEmpty()) throw new JsonException("Invalid number at position " + start);
            try {
                if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                    return Long.parseLong(token);
                }
                return Double.parseDouble(token);
            } catch (NumberFormatException nfe) {
                throw new JsonException("Invalid number: " + token);
            }
        }

        boolean consume(char c) {
            if (!eof() && peek() == c) { pos++; return true; }
            return false;
        }

        void expect(char c) {
            skipWhitespace();
            if (eof() || peek() != c) {
                throw new JsonException("Expected '" + c + "' at position " + pos);
            }
            pos++;
        }
    }

    // ----------------------------------------------------------------- write

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeInto(sb, value, "", "");
        return sb.toString();
    }

    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writeInto(sb, value, "", "  ");
        sb.append('\n');
        return sb.toString();
    }

    /** Deterministic form: sorted object keys, no whitespace. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeCanonical(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Number n) {
            sb.append(numberToken(n));
        } else if (value instanceof String str) {
            writeString(sb, str);
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeCanonical(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                writeCanonical(sb, item);
            }
            sb.append(']');
        } else {
            throw new JsonException("Cannot serialize value of type " + value.getClass().getName());
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeInto(StringBuilder sb, Object value, String indent, String step) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Number n) {
            sb.append(numberToken(n));
        } else if (value instanceof String str) {
            writeString(sb, str);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            String childIndent = indent + step;
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                if (!step.isEmpty()) { sb.append('\n').append(childIndent); }
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(step.isEmpty() ? ":" : ": ");
                writeInto(sb, e.getValue(), childIndent, step);
            }
            if (!step.isEmpty()) { sb.append('\n').append(indent); }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            String childIndent = indent + step;
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                if (!step.isEmpty()) { sb.append('\n').append(childIndent); }
                writeInto(sb, item, childIndent, step);
            }
            if (!step.isEmpty()) { sb.append('\n').append(indent); }
            sb.append(']');
        } else {
            throw new JsonException("Cannot serialize value of type " + value.getClass().getName());
        }
    }

    private static String numberToken(Number n) {
        if (n instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new JsonException("Non-finite double cannot be serialized");
            }
            double canonical = d.doubleValue();
            if (canonical == Math.rint(canonical) && !Double.isInfinite(canonical)
                    && Math.abs(canonical) < 1e16) {
                return Double.toString(canonical);
            }
            return Double.toString(canonical);
        }
        if (n instanceof Float f) {
            return Double.toString(f.doubleValue());
        }
        return n.toString();
    }

    private static void writeString(StringBuilder sb, String str) {
        sb.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
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

    // --------------------------------------------------------- type helpers

    public static Map<String, Object> object(Object value, String where) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        throw new JsonException(where + " must be a JSON object");
    }

    public static List<Object> list(Object value, String where) {
        if (value instanceof List<?> l) {
            @SuppressWarnings("unchecked")
            List<Object> typed = (List<Object>) l;
            return typed;
        }
        throw new JsonException(where + " must be a JSON array");
    }

    public static String string(Object value, String where) {
        if (value instanceof String s) return s;
        throw new JsonException(where + " must be a string");
    }

    public static long longValue(Object value, String where) {
        if (value instanceof Long l) return l;
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.longValue();
        }
        throw new JsonException(where + " must be an integer");
    }

    public static int intValue(Object value, String where) {
        long v = longValue(value, where);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new JsonException(where + " is out of integer range");
        }
        return (int) v;
    }

    public static boolean bool(Object value, String where) {
        if (value instanceof Boolean b) return b;
        throw new JsonException(where + " must be a boolean");
    }

    public static Map<String, Object> optObject(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return new LinkedHashMap<>();
        return object(value, key);
    }

    public static List<Object> optList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return new ArrayList<>();
        return list(value, key);
    }

    public static String optString(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        return string(value, key);
    }

    public static long optLong(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        return longValue(value, key);
    }

    public static Double optDoubleObj(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Double d) return d;
        if (value instanceof Number n) return n.doubleValue();
        throw new JsonException(key + " must be a number");
    }

    public static Map<String, Object> obj(Object... kv) {
        if ((kv.length & 1) != 0) throw new IllegalArgumentException("kv must be paired");
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopyMap(Map<String, Object> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            copy.put(e.getKey(), deepCopy(e.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) return deepCopyMap((Map<String, Object>) map);
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(deepCopy(item));
            return copy;
        }
        return value;
    }
}
