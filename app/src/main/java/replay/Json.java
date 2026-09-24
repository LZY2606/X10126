package replay;

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
            throw new IllegalArgumentException("Trailing JSON content at " + parser.pos);
        }
        return value;
    }

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writePretty(value, builder, 0);
        return builder.toString();
    }

    public static String canonical(Object value) {
        StringBuilder builder = new StringBuilder();
        writeCanonical(value, builder);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) value;
    }

    public static Map<String, Object> at(Map<String, Object> value, String key) {
        Object child = value.get(key);
        if (child == null) return Map.of();
        return object(child);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("Expected JSON array");
        }
        return (List<Object>) value;
    }

    public static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Expected string field " + key);
        }
        return (String) value;
    }

    public static String optionalString(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Expected string field " + key);
        }
        return (String) value;
    }

    public static long longValue(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        return longValue(value, key);
    }

    public static long longValue(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Expected integer field " + name);
        }
        double floating = number.doubleValue();
        if (!Double.isFinite(floating) || Math.floor(floating) != floating) {
            throw new IllegalArgumentException("Expected integer field " + name);
        }
        return number.longValue();
    }

    public static Map<String, Object> cloneObject(Object value) {
        return clone(object(value));
    }

    @SuppressWarnings("unchecked")
    public static <T> T clone(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put((String) entry.getKey(), clone(entry.getValue()));
            }
            return (T) result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(clone(item));
            }
            return (T) result;
        }
        return value;
    }

    private Object readValue() {
        skipWhitespace();
        if (pos >= text.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
        char c = text.charAt(pos);
        if (c == '{') return readObject();
        if (c == '[') return readArray();
        if (c == '"') return readString();
        if (c == 't' || c == 'f') return readBoolean();
        if (c == 'n') return readNull();
        return readNumber();
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (consume('}')) return result;
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            Object value = readValue();
            result.put(key, value);
            skipWhitespace();
            if (consume('}')) return result;
            expect(',');
        }
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (consume(']')) return result;
        while (true) {
            result.add(readValue());
            skipWhitespace();
            if (consume(']')) return result;
            expect(',');
        }
    }

    private String readString() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') return builder.toString();
            if (c == '\\') {
                if (pos >= text.length()) break;
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw new IllegalArgumentException("Bad unicode escape");
                        }
                        builder.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad escape: " + escaped);
                }
            } else {
                builder.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated string");
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
        throw new IllegalArgumentException("Expected boolean at " + pos);
    }

    private Object readNull() {
        if (text.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("Expected null at " + pos);
    }

    private Number readNumber() {
        int start = pos;
        if (pos < text.length() && text.charAt(pos) == '-') pos++;
        readDigits();
        boolean floating = false;
        if (pos < text.length() && text.charAt(pos) == '.') {
            floating = true;
            pos++;
            readDigits();
        }
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            floating = true;
            pos++;
            if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
            readDigits();
        }
        String number = text.substring(start, pos);
        if (number.isEmpty() || "-".equals(number)) {
            throw new IllegalArgumentException("Bad number at " + start);
        }
        return floating ? Double.parseDouble(number) : Long.parseLong(number);
    }

    private void readDigits() {
        int start = pos;
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        if (start == pos) throw new IllegalArgumentException("Expected digits at " + pos);
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private boolean consume(char c) {
        if (pos < text.length() && text.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!consume(c)) {
            throw new IllegalArgumentException("Expected '" + c + "' at " + pos);
        }
    }

    private static void writeCanonical(Object value, StringBuilder builder) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) keys.add((String) key);
            keys.sort(String::compareTo);
            builder.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) builder.append(',');
                writeEscaped(keys.get(i), builder);
                builder.append(':');
                writeCanonical(map.get(keys.get(i)), builder);
            }
            builder.append('}');
        } else if (value instanceof List<?> list) {
            builder.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) builder.append(',');
                writeCanonical(list.get(i), builder);
            }
            builder.append(']');
        } else if (value instanceof String string) {
            writeEscaped(string, builder);
        } else if (value instanceof Boolean bool) {
            builder.append(bool.booleanValue());
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) throw new IllegalArgumentException("Canonical JSON cannot encode " + number);
            builder.append(number);
        } else if (value instanceof Number number) {
            builder.append(number.longValue());
        } else {
            throw new IllegalArgumentException("Cannot encode " + value.getClass());
        }
    }

    private static void writePretty(Object value, StringBuilder builder, int indent) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                builder.append("{}");
                return;
            }
            builder.append("{\n");
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                indent(builder, indent + 1);
                writeEscaped((String) entry.getKey(), builder);
                builder.append(": ");
                writePretty(entry.getValue(), builder, indent + 1);
                if (++index < map.size()) builder.append(',');
                builder.append('\n');
            }
            indent(builder, indent);
            builder.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                builder.append("[]");
                return;
            }
            builder.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(builder, indent + 1);
                writePretty(list.get(i), builder, indent + 1);
                if (i + 1 < list.size()) builder.append(',');
                builder.append('\n');
            }
            indent(builder, indent);
            builder.append(']');
        } else {
            writeCanonical(value, builder);
        }
    }

    private static void indent(StringBuilder builder, int level) {
        builder.append("  ".repeat(level));
    }

    static void writeEscaped(String value, StringBuilder builder) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
    }
}
