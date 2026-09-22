package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser and canonical serializer with no third-party dependencies.
 * Object maps preserve insertion order on parse; {@code writeCanonical} sorts keys
 * so fingerprints are stable regardless of field ordering in user input.
 */
public final class Json {

    private Json() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("top-level JSON value is not an object");
        }
        return (Map<String, Object>) value;
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWs();
        Object value = parser.readValue();
        parser.skipWs();
        if (parser.pos < parser.text.length()) {
            throw parser.error("trailing characters");
        }
        return value;
    }

    /** Canonical JSON: sorted object keys, compact separators, no escaping surprises. */
    public static String writeCanonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(value, sb);
        return sb.toString();
    }

    /** Human readable JSON for API responses. */
    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(value, sb, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeCanonical(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Number) {
            writeNumber((Number) value, sb);
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) {
                keys.add(String.valueOf(key));
            }
            keys.sort(String::compareTo);
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeString(keys.get(i), sb);
                sb.append(':');
                writeCanonical(map.get(keys.get(i)), sb);
            }
            sb.append('}');
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeCanonical(list.get(i), sb);
            }
            sb.append(']');
        } else {
            throw new JsonException("cannot serialize " + value.getClass());
        }
    }

    private static void writePretty(Object value, StringBuilder sb, int indent) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                pad(sb, indent + 1);
                writeString(String.valueOf(entry.getKey()), sb);
                sb.append(": ");
                writePretty(entry.getValue(), sb, indent + 1);
                if (++i < map.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                pad(sb, indent + 1);
                writePretty(list.get(i), sb, indent + 1);
                if (i < list.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            writeCanonical(value, sb);
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    private static void writeNumber(Number number, StringBuilder sb) {
        if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else {
            sb.append(number.toString());
        }
    }

    private static void writeString(String value, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
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

    // ---- accessor helpers ----

    public static Map<String, Object> obj(Map<String, Object> map, String key) {
        return asObj(map.get(key), key);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObj(Object value, String what) {
        if (!(value instanceof Map)) {
            throw new JsonException(what + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    public static List<Object> arr(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return new ArrayList<>();
        }
        if (!(value instanceof List)) {
            throw new JsonException(key + " must be an array");
        }
        return (List<Object>) value;
    }

    public static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new JsonException(key + " must be a string");
        }
        return (String) value;
    }

    public static String requireStr(Map<String, Object> map, String key) {
        String value = str(map, key);
        if (value == null || value.isEmpty()) {
            throw new JsonException(key + " is required");
        }
        return value;
    }

    public static long lng(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number)) {
            throw new JsonException(key + " must be a number");
        }
        return ((Number) value).longValue();
    }

    public static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean)) {
            throw new JsonException(key + " must be a boolean");
        }
        return (Boolean) value;
    }

    public static Map<String, Object> copyObj(Map<String, Object> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object value) {
        if (value instanceof Map) {
            return copyObj((Map<String, Object>) value);
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                copy.add(copyValue(item));
            }
            return copy;
        }
        return value;
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        final String text;
        int pos;

        Parser(String text) {
            this.text = text;
        }

        JsonException error(String message) {
            return new JsonException(message + " at position " + pos);
        }

        void skipWs() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWs();
            if (pos >= text.length()) {
                throw error("unexpected end of input");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                case 'f':
                    return readBoolean();
                case 'n':
                    return readNull();
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWs();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char escape = next();
                    switch (escape) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u':
                            String hex = text.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            throw error("invalid escape \\" + escape);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Boolean readBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw error("invalid literal");
        }

        Object readNull() {
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw error("invalid literal");
        }

        Number readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < text.length() && "0123456789".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            boolean isDouble = false;
            if (pos < text.length() && text.charAt(pos) == '.') {
                isDouble = true;
                pos++;
                while (pos < text.length() && "0123456789".indexOf(text.charAt(pos)) >= 0) {
                    pos++;
                }
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                isDouble = true;
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < text.length() && "0123456789".indexOf(text.charAt(pos)) >= 0) {
                    pos++;
                }
            }
            String token = text.substring(start, pos);
            return isDouble ? Double.valueOf(token) : Long.valueOf(token);
        }

        char peek() {
            if (pos >= text.length()) {
                throw error("unexpected end of input");
            }
            return text.charAt(pos);
        }

        char next() {
            if (pos >= text.length()) {
                throw error("unexpected end of input");
            }
            return text.charAt(pos++);
        }

        void expect(char expected) {
            char actual = next();
            if (actual != expected) {
                throw error("expected '" + expected + "' but found '" + actual + "'");
            }
        }
    }
}
