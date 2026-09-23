package replay.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, deterministic JSON parser and writer.
 * No external dependencies; maps preserve insertion order (LinkedHashMap).
 */
public final class Json {

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
        public JsonException(String message, Throwable cause) { super(message, cause); }
    }

    private Json() {}

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (!p.eof()) {
            throw new JsonException("Unexpected trailing content at position " + p.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("Expected a JSON object");
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        boolean eof() { return pos >= s.length(); }

        char peek() { return s.charAt(pos); }

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
            if (eof()) throw new JsonException("Unexpected end of input");
            char c = peek();
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': case 'f': return readBoolean();
                case 'n': return readNull();
                default: return readNumber();
            }
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWs();
                char c = next();
                if (c == '}') return map;
                if (c != ',') throw new JsonException("Expected ',' or '}' at position " + pos);
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                Object value = readValue();
                list.add(value);
                skipWs();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw new JsonException("Expected ',' or ']' at position " + pos);
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new JsonException("Unterminated string");
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
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                char h = next();
                                code <<= 4;
                                if (h >= '0' && h <= '9') code |= h - '0';
                                else if (h >= 'a' && h <= 'f') code |= h - 'a' + 10;
                                else if (h >= 'A' && h <= 'F') code |= h - 'A' + 10;
                                else throw new JsonException("Invalid unicode escape");
                            }
                            sb.append((char) code);
                            break;
                        default: throw new JsonException("Invalid escape \\" + e);
                    }
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
            boolean floating = false;
            if (peek() == '-') pos++;
            while (!eof()) {
                char c = peek();
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    if (c == '.' || c == 'e' || c == 'E') floating = true;
                    pos++;
                } else {
                    break;
                }
            }
            String token = s.substring(start, pos);
            if (token.isEmpty()) throw new JsonException("Invalid number at position " + start);
            try {
                if (floating) return Double.parseDouble(token);
                return Long.parseLong(token);
            } catch (NumberFormatException e) {
                throw new JsonException("Invalid number: " + token);
            }
        }

        char next() {
            if (eof()) throw new JsonException("Unexpected end of input");
            return s.charAt(pos++);
        }

        void expect(char c) {
            skipWs();
            char actual = next();
            if (actual != c) {
                throw new JsonException("Expected '" + c + "' but found '" + actual + "' at position " + (pos - 1));
            }
        }
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    public static String write(Object value) {
        return write(value, "  ");
    }

    public static String write(Object value, String indent) {
        StringBuilder sb = new StringBuilder();
        appendValue(sb, value, indent, 0);
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, Object value, String indent, int depth) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            appendString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue());
        } else if (value instanceof Integer || value instanceof Long) {
            sb.append(value.toString());
        } else if (value instanceof Number) {
            sb.append(formatNumber((Number) value));
        } else if (value instanceof Map) {
            appendObject(sb, (Map<?, ?>) value, indent, depth);
        } else if (value instanceof Iterable) {
            appendArray(sb, (Iterable<?>) value, indent, depth);
        } else {
            throw new JsonException("Cannot serialize value of type " + value.getClass());
        }
    }

    private static void appendObject(StringBuilder sb, Map<?, ?> map, String indent, int depth) {
        if (map.isEmpty()) { sb.append("{}"); return; }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            if (!indent.isEmpty()) sb.append('\n').append(indent.repeat(depth + 1));
            appendString(sb, String.valueOf(entry.getKey()));
            sb.append(indent.isEmpty() ? ":" : ": ");
            appendValue(sb, entry.getValue(), indent, depth + 1);
        }
        if (!indent.isEmpty()) sb.append('\n').append(indent.repeat(depth));
        sb.append('}');
    }

    private static void appendArray(StringBuilder sb, Iterable<?> items, String indent, int depth) {
        boolean first = true;
        boolean hasItems = false;
        for (Object ignored : items) { hasItems = true; break; }
        if (!hasItems) { sb.append("[]"); return; }
        sb.append('[');
        for (Object item : items) {
            if (!first) sb.append(',');
            first = false;
            if (!indent.isEmpty()) sb.append('\n').append(indent.repeat(depth + 1));
            appendValue(sb, item, indent, depth + 1);
        }
        if (!indent.isEmpty()) sb.append('\n').append(indent.repeat(depth));
        sb.append(']');
    }

    private static void appendString(StringBuilder sb, String s) {
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
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    static String formatNumber(Number number) {
        if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("Non-finite numbers cannot be serialized");
            }
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e16) {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        return number.toString();
    }

    // ------------------------------------------------------------------
    // Canonical form used for fingerprints: sorted keys, no whitespace.
    // ------------------------------------------------------------------

    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        appendCanonical(sb, value);
        return sb.toString();
    }

    private static void appendCanonical(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            appendString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue());
        } else if (value instanceof Number) {
            sb.append(formatNumber((Number) value));
        } else if (value instanceof Map) {
            List<String> keys = new ArrayList<>();
            for (Object key : ((Map<?, ?>) value).keySet()) keys.add(String.valueOf(key));
            java.util.Collections.sort(keys);
            sb.append('{');
            boolean first = true;
            for (String key : keys) {
                if (!first) sb.append(',');
                first = false;
                appendString(sb, key);
                sb.append(':');
                appendCanonical(sb, ((Map<?, ?>) value).get(key));
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) sb.append(',');
                first = false;
                appendCanonical(sb, item);
            }
            sb.append(']');
        } else {
            throw new JsonException("Cannot canonicalize value of type " + value.getClass());
        }
    }
}
