package replayroom;

import java.util.ArrayList;
import java.util.Collections;
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
            throw new IllegalArgumentException("Unexpected trailing JSON at position " + parser.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("Expected JSON array");
        }
        return (List<Object>) value;
    }

    public static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Expected string field " + key);
        }
        return (String) value;
    }

    public static String requireString(Map<String, Object> map, String key) {
        String value = string(map, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing string field " + key);
        }
        return value;
    }

    public static long integer(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number && !(value instanceof Double) && !(value instanceof Float)) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException("Expected integer field " + key);
    }

    public static Map<String, Object> objectField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return object(value);
    }

    public static List<Object> listField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return new ArrayList<>();
        }
        return list(value);
    }

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writePretty(builder, value, 0);
        return builder.toString();
    }

    public static String canonical(Object value) {
        StringBuilder builder = new StringBuilder();
        writeCanonical(builder, value);
        return builder.toString();
    }

    private static void writePretty(StringBuilder builder, Object value, int indent) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String) {
            writeString(builder, (String) value);
        } else if (value instanceof Boolean || value instanceof Number) {
            builder.append(value);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                builder.append("{}");
                return;
            }
            builder.append("{\n");
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                indent(builder, indent + 1);
                writeString(builder, String.valueOf(entry.getKey()));
                builder.append(": ");
                writePretty(builder, entry.getValue(), indent + 1);
                if (++index < map.size()) {
                    builder.append(',');
                }
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
                writePretty(builder, list.get(i), indent + 1);
                if (i + 1 < list.size()) {
                    builder.append(',');
                }
                builder.append('\n');
            }
            indent(builder, indent);
            builder.append(']');
        } else {
            throw new IllegalArgumentException("Cannot serialize " + value.getClass());
        }
    }

    private static void writeCanonical(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String) {
            writeString(builder, (String) value);
        } else if (value instanceof Boolean || value instanceof Number) {
            builder.append(value);
        } else if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            map.keySet().forEach(key -> keys.add(String.valueOf(key)));
            Collections.sort(keys);
            builder.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                writeString(builder, keys.get(i));
                builder.append(':');
                writeCanonical(builder, map.get(keys.get(i)));
            }
            builder.append('}');
        } else if (value instanceof List<?> list) {
            builder.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                writeCanonical(builder, list.get(i));
            }
            builder.append(']');
        } else {
            throw new IllegalArgumentException("Cannot canonicalize " + value.getClass());
        }
    }

    private static void indent(StringBuilder builder, int level) {
        builder.append("  ".repeat(level));
    }

    private static void writeString(StringBuilder builder, String value) {
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
        if (c == '-' || Character.isDigit(c)) return readNumber();
        throw new IllegalArgumentException("Unexpected JSON character " + c + " at " + pos);
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (consume('}')) {
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            Object value = readValue();
            map.put(key, value);
            skipWhitespace();
            if (consume('}')) {
                return map;
            }
            expect(',');
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (consume(']')) {
            return list;
        }
        while (true) {
            list.add(readValue());
            skipWhitespace();
            if (consume(']')) {
                return list;
            }
            expect(',');
        }
    }

    private String readString() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return builder.toString();
            }
            if (c == '\\') {
                if (pos >= text.length()) {
                    throw new IllegalArgumentException("Bad string escape");
                }
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
                    default -> throw new IllegalArgumentException("Unsupported escape " + escaped);
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
            return true;
        }
        if (text.startsWith("false", pos)) {
            pos += 5;
            return false;
        }
        throw new IllegalArgumentException("Invalid literal at " + pos);
    }

    private Object readNull() {
        if (text.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("Invalid literal at " + pos);
    }

    private Number readNumber() {
        int start = pos;
        if (consume('-')) {
            // no-op
        }
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
            if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                pos++;
            }
            readDigits();
        }
        String number = text.substring(start, pos);
        return floating ? Double.parseDouble(number) : Long.parseLong(number);
    }

    private void readDigits() {
        int start = pos;
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("Expected digits at " + pos);
        }
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private boolean consume(char expected) {
        if (pos < text.length() && text.charAt(pos) == expected) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            char actual = pos >= text.length() ? '\0' : text.charAt(pos);
            throw new IllegalArgumentException("Expected " + expected + " but found " + actual);
        }
    }
}
