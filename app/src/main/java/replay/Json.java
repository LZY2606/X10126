package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Json {
    private Json() {}

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    public static String writeCanonical(Object value) {
        StringBuilder out = new StringBuilder();
        writeCanonical(value, out);
        return out.toString();
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.end()) {
            throw new IllegalArgumentException("Unexpected trailing JSON at " + parser.position);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        if (!(value instanceof List<?> array)) {
            throw new IllegalArgumentException("Expected JSON array");
        }
        return (List<Object>) array;
    }

    public static String string(Map<String, Object> map, String key) {
        Object value = required(map, key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return text;
    }

    public static String optionalString(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return text;
    }

    public static int integer(Map<String, Object> map, String key) {
        Object value = required(map, key);
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return number.intValue();
        }
        throw new IllegalArgumentException(key + " must be an integer");
    }

    public static long longValue(Map<String, Object> map, String key) {
        Object value = required(map, key);
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return number.longValue();
        }
        throw new IllegalArgumentException(key + " must be an integer");
    }

    public static long optionalLong(Map<String, Object> map, String key, long fallback) {
        if (!map.containsKey(key) || map.get(key) == null) return fallback;
        Object value = map.get(key);
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return number.longValue();
        }
        throw new IllegalArgumentException(key + " must be an integer");
    }

    public static Object required(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            writeString(text, out);
        } else if (value instanceof Boolean bool) {
            out.append(bool.booleanValue());
        } else if (value instanceof Integer number) {
            out.append(number.intValue());
        } else if (value instanceof Long number) {
            out.append(number.longValue());
        } else if (value instanceof Number number) {
            writeDouble(number.doubleValue(), out);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Cannot serialize " + value.getClass());
        }
    }

    private static void writeCanonical(Object value, StringBuilder out) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(entry.getKey(), out);
                out.append(':');
                writeCanonical(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) out.append(',');
                first = false;
                writeCanonical(item, out);
            }
            out.append(']');
        } else {
            write(value, out);
        }
    }

    private static void writeDouble(double value, StringBuilder out) {
        if (Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) < 9007199254740992.0) {
            out.append((long) value);
        } else {
            out.append(Double.toString(value));
        }
    }

    private static void writeString(String value, StringBuilder out) {
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
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String text;
        private int position;

        private Parser(String text) {
            this.text = text == null ? "" : text;
        }

        private boolean end() {
            return position >= text.length();
        }

        private Object readValue() {
            skipWhitespace();
            if (end()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = text.charAt(position);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBoolean();
            if (c == 'n') return readNull();
            return readNumber();
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) return result;
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                if (consume('}')) return result;
                expect(',');
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
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
            skipWhitespace();
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!end()) {
                char c = text.charAt(position++);
                if (c == '"') return result.toString();
                if (c == '\\') {
                    if (end()) throw new IllegalArgumentException("Bad escape");
                    char escaped = text.charAt(position++);
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
                            if (position + 4 > text.length()) throw new IllegalArgumentException("Bad unicode escape");
                            result.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            position += 4;
                        }
                        default -> throw new IllegalArgumentException("Bad escape: " + escaped);
                    }
                } else {
                    result.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Boolean readBoolean() {
            if (text.startsWith("true", position)) {
                position += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", position)) {
                position += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Expected boolean at " + position);
        }

        private Object readNull() {
            if (text.startsWith("null", position)) {
                position += 4;
                return null;
            }
            throw new IllegalArgumentException("Expected null at " + position);
        }

        private Number readNumber() {
            int start = position;
            if (consume('-')) {}
            readDigits();
            boolean floating = false;
            if (!end() && text.charAt(position) == '.') {
                floating = true;
                position++;
                readDigits();
            }
            if (!end() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                floating = true;
                position++;
                if (!end() && (text.charAt(position) == '+' || text.charAt(position) == '-')) position++;
                readDigits();
            }
            String token = text.substring(start, position);
            if (token.isEmpty() || "-".equals(token)) throw new IllegalArgumentException("Bad number at " + start);
            return floating ? Double.parseDouble(token) : Long.parseLong(token);
        }

        private void readDigits() {
            int start = position;
            while (!end() && Character.isDigit(text.charAt(position))) position++;
            if (position == start) throw new IllegalArgumentException("Expected digits at " + position);
        }

        private void skipWhitespace() {
            while (!end() && Character.isWhitespace(text.charAt(position))) position++;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (end() || text.charAt(position) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at " + position);
            }
            position++;
        }

        private boolean consume(char expected) {
            if (!end() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }
    }
}
