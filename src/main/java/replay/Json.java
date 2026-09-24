package replay;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos != parser.text.length()) {
            throw parser.error("Trailing content");
        }
        return value;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        out.append('\n');
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value, String name) {
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return (List<Object>) raw;
    }

    static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    static String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String result) || result.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-empty string");
        }
        return result;
    }

    static long requireLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValueExact();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    static long optionalLong(Map<String, Object> map, String key, long fallback) {
        return map.containsKey(key) && map.get(key) != null ? requireLong(map, key) : fallback;
    }

    static boolean optionalBoolean(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean result) {
            return result;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal result) {
            return result;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), copy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> values) {
            List<Object> copy = new ArrayList<>(values.size());
            for (Object item : values) {
                copy.add(copy(item));
            }
            return copy;
        }
        return value;
    }

    static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.keySet().stream().map(String::valueOf).sorted().forEach(key ->
                    copy.put(key, canonical(map.get(key))));
            return copy;
        }
        if (value instanceof List<?> values) {
            List<Object> copy = new ArrayList<>(values.size());
            for (Object item : values) {
                copy.add(canonical(item));
            }
            return copy;
        }
        return value;
    }

    private Object readValue() {
        skipWhitespace();
        if (pos >= text.length()) {
            throw error("Unexpected end of JSON");
        }
        char ch = text.charAt(pos);
        if (ch == '{') return readObject();
        if (ch == '[') return readArray();
        if (ch == '"') return readString();
        if (ch == 't' || ch == 'f') return readBoolean();
        if (ch == 'n') return readNull();
        return readNumber();
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (take('}')) return result;
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            result.put(key, readValue());
            skipWhitespace();
            if (take('}')) return result;
            expect(',');
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> result = new ArrayList<>();
        skipWhitespace();
        if (take(']')) return result;
        while (true) {
            result.add(readValue());
            skipWhitespace();
            if (take(']')) return result;
            expect(',');
        }
    }

    private String readString() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (pos < text.length()) {
            char ch = text.charAt(pos++);
            if (ch == '"') return result.toString();
            if (ch == '\\') {
                if (pos >= text.length()) throw error("Bad escape");
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"', '\\', '/' -> result.append(escape);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw error("Bad unicode escape");
                        result.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("Unsupported escape");
                }
            } else {
                result.append(ch);
            }
        }
        throw error("Unterminated string");
    }

    private Boolean readBoolean() {
        if (text.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw error("Invalid literal");
    }

    private Object readNull() {
        if (text.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw error("Invalid literal");
    }

    private BigDecimal readNumber() {
        int start = pos;
        if (pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) pos++;
        while (pos < text.length() && "-+0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        if (start == pos) throw error("Invalid number");
        return new BigDecimal(text.substring(start, pos));
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private boolean take(char ch) {
        if (pos < text.length() && text.charAt(pos) == ch) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char ch) {
        if (!take(ch)) throw error("Expected '" + ch + "'");
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at position " + pos);
    }

    private static void write(StringBuilder out, Object value, int indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(",\n");
                first = false;
                indent(out, indent + 1);
                write(out, String.valueOf(entry.getKey()), indent + 1);
                out.append(": ");
                write(out, entry.getValue(), indent + 1);
            }
            out.append('\n');
            indent(out, indent);
            out.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(",\n");
                indent(out, indent + 1);
                write(out, list.get(i), indent + 1);
            }
            out.append('\n');
            indent(out, indent);
            out.append(']');
        } else if (value instanceof String string) {
            writeString(out, string);
        } else if (value instanceof Boolean bool) {
            out.append(bool.booleanValue());
        } else if (value instanceof BigDecimal decimal) {
            out.append(decimal.stripTrailingZeros().toPlainString());
        } else if (value instanceof Number number) {
            out.append(number);
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        out.append('"');
    }

    private static void indent(StringBuilder out, int level) {
        out.append("  ".repeat(level));
    }
}
