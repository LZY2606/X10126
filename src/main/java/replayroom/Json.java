package replayroom;

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
            throw new JsonException("Trailing characters at position " + parser.pos);
        }
        return value;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writePretty(out, value, 0);
        return out.toString();
    }

    public static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        writeCanonical(out, value);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value, String path) {
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        throw new JsonException(path + " must be an object");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value, String path) {
        if (value instanceof List<?>) {
            return (List<Object>) value;
        }
        throw new JsonException(path + " must be an array");
    }

    public static String string(Object value, String path) {
        if (value instanceof String s) {
            return s;
        }
        throw new JsonException(path + " must be a string");
    }

    public static String optionalString(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        return string(value, key);
    }

    public static long integer(Object value, String path) {
        if (value instanceof BigDecimal number) {
            try {
                return number.longValueExact();
            } catch (ArithmeticException e) {
                throw new JsonException(path + " must be an integer");
            }
        }
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof Integer number) {
            return number.longValue();
        }
        throw new JsonException(path + " must be an integer");
    }

    public static Object cloneValue(Object value) {
        return clone(value);
    }

    public static Map<String, Object> cloneObject(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return (Map<String, Object>) clone(value);
    }

    public static List<Object> cloneList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        return (List<Object>) clone(value);
    }

    private static Object clone(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), clone(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(clone(item));
            }
            return copy;
        }
        return value;
    }

    private Object readValue() {
        skipWhitespace();
        if (pos >= text.length()) {
            throw new JsonException("Unexpected end of JSON");
        }
        char c = text.charAt(pos);
        if (c == '{') return readObject();
        if (c == '[') return readArray();
        if (c == '"') return readString();
        if (c == 't' || c == 'f') return readBoolean();
        if (c == 'n') return readNull();
        if (c == '-' || Character.isDigit(c)) return readNumber();
        throw new JsonException("Unexpected character at position " + pos);
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (take('}')) return result;
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            Object value = readValue();
            result.put(key, value);
            skipWhitespace();
            if (take('}')) return result;
            expect(',');
        }
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (take(']')) return result;
        while (true) {
            Object value = readValue();
            result.add(value);
            skipWhitespace();
            if (take(']')) return result;
            expect(',');
        }
    }

    private String readString() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') return result.toString();
            if (c == '\\') {
                if (pos >= text.length()) throw new JsonException("Unterminated escape");
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw new JsonException("Bad unicode escape");
                        result.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new JsonException("Invalid escape: " + escaped);
                }
            } else {
                result.append(c);
            }
        }
        throw new JsonException("Unterminated string");
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
        throw new JsonException("Invalid literal at position " + pos);
    }

    private Object readNull() {
        if (text.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new JsonException("Invalid literal at position " + pos);
    }

    private BigDecimal readNumber() {
        int start = pos;
        if (take('-')) {}
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        if (pos < text.length() && text.charAt(pos) == '.') {
            pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        }
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            pos++;
            if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        }
        return new BigDecimal(text.substring(start, pos));
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private boolean take(char c) {
        if (pos < text.length() && text.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!take(c)) {
            throw new JsonException("Expected '" + c + "' at position " + pos);
        }
    }

    private static void writePretty(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue());
        } else if (value instanceof BigDecimal number) {
            out.append(number.toPlainString());
        } else if (value instanceof Number number) {
            out.append(number.toString());
        } else if (value instanceof String s) {
            writeString(out, s);
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
                indent(out, depth + 1);
                writeString(out, String.valueOf(entry.getKey()));
                out.append(": ");
                writePretty(out, entry.getValue(), depth + 1);
            }
            out.append('\n');
            indent(out, depth);
            out.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            boolean first = true;
            for (Object item : list) {
                if (!first) out.append(",\n");
                first = false;
                indent(out, depth + 1);
                writePretty(out, item, depth + 1);
            }
            out.append('\n');
            indent(out, depth);
            out.append(']');
        } else {
            throw new JsonException("Unsupported JSON value: " + value.getClass());
        }
    }

    private static void writeCanonical(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue());
        } else if (value instanceof BigDecimal number) {
            out.append(number.toPlainString());
        } else if (value instanceof Number number) {
            out.append(number.toString());
        } else if (value instanceof String s) {
            writeString(out, s);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeCanonical(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) out.append(',');
                first = false;
                writeCanonical(out, item);
            }
            out.append(']');
        } else {
            throw new JsonException("Unsupported JSON value: " + value.getClass());
        }
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
