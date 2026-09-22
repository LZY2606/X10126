package replay.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.end()) {
            throw new IllegalArgumentException("Unexpected trailing JSON at position " + parser.position);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        write(value, builder, 0);
        return builder.toString();
    }

    public static String canonical(Object value) {
        StringBuilder builder = new StringBuilder();
        writeCanonical(value, builder);
        return builder.toString();
    }

    private static void write(Object value, StringBuilder builder, int indent) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String string) {
            writeString(string, builder);
        } else if (value instanceof Boolean bool) {
            builder.append(bool.booleanValue());
        } else if (value instanceof Number number) {
            builder.append(number.toString());
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append('\n').append("  ".repeat(indent + 1));
                writeString(String.valueOf(entry.getKey()), builder);
                builder.append(": ");
                write(entry.getValue(), builder, indent + 1);
            }
            if (!first) {
                builder.append('\n').append("  ".repeat(indent));
            }
            builder.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            builder.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append('\n').append("  ".repeat(indent + 1));
                write(item, builder, indent + 1);
            }
            if (!first) {
                builder.append('\n').append("  ".repeat(indent));
            }
            builder.append(']');
        } else {
            writeString(value.toString(), builder);
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeCanonical(Object value, StringBuilder builder) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String string) {
            writeString(string, builder);
        } else if (value instanceof Boolean bool) {
            builder.append(bool.booleanValue());
        } else if (value instanceof Number number) {
            builder.append(stableNumber(number));
        } else if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) {
                keys.add(String.valueOf(key));
            }
            keys.sort(String::compareTo);
            builder.append('{');
            boolean first = true;
            for (String key : keys) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeString(key, builder);
                builder.append(':');
                writeCanonical(((Map<String, Object>) map).get(key), builder);
            }
            builder.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            builder.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeCanonical(item, builder);
            }
            builder.append(']');
        } else {
            writeString(value.toString(), builder);
        }
    }

    private static String stableNumber(Number number) {
        double value = number.doubleValue();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        if (number instanceof Double || number instanceof Float) {
            if (value == Math.rint(value) && Math.abs(value) < 1e16) {
                return Long.toString((long) value) + ".0";
            }
            return Double.toString(value);
        }
        return number.toString();
    }

    private static void writeString(String value, StringBuilder builder) {
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

    private static final class Parser {
        private final String text;
        private int position;

        private Parser(String text) {
            this.text = text;
        }

        private Object readValue() {
            skipWhitespace();
            if (end()) {
                throw error("Unexpected end of JSON");
            }
            char c = text.charAt(position);
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            if (c == 't' || c == 'f') {
                return readBoolean();
            }
            if (c == 'n') {
                return readNull();
            }
            return readNumber();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                result.put(key, value);
                skipWhitespace();
                if (consume('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> readArray() {
            List<Object> result = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (consume(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') {
                    return result.toString();
                }
                if (c == '\\') {
                    if (position >= text.length()) {
                        throw error("Unterminated escape");
                    }
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
                            if (position + 4 > text.length()) {
                                throw error("Invalid unicode escape");
                            }
                            result.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            position += 4;
                        }
                        default -> throw error("Invalid escape: " + escaped);
                    }
                } else {
                    result.append(c);
                }
            }
            throw error("Unterminated string");
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
            throw error("Invalid literal");
        }

        private Object readNull() {
            if (text.startsWith("null", position)) {
                position += 4;
                return null;
            }
            throw error("Invalid literal");
        }

        private Number readNumber() {
            int start = position;
            if (peek() == '-') {
                position++;
            }
            readIntegerDigits();
            boolean floating = false;
            if (!end() && peek() == '.') {
                floating = true;
                position++;
                readIntegerDigits();
            }
            if (!end() && (peek() == 'e' || peek() == 'E')) {
                floating = true;
                position++;
                if (!end() && (peek() == '+' || peek() == '-')) {
                    position++;
                }
                readIntegerDigits();
            }
            String number = text.substring(start, position);
            if (number.isEmpty() || "-".equals(number)) {
                throw error("Invalid number");
            }
            return floating ? Double.parseDouble(number) : Long.parseLong(number);
        }

        private void readIntegerDigits() {
            int start = position;
            while (!end() && Character.isDigit(peek())) {
                position++;
            }
            if (position == start) {
                throw error("Expected digits");
            }
        }

        private char peek() {
            return text.charAt(position);
        }

        private boolean end() {
            return position >= text.length();
        }

        private void skipWhitespace() {
            while (!end() && Character.isWhitespace(peek())) {
                position++;
            }
        }

        private void expect(char expected) {
            if (end() || text.charAt(position) != expected) {
                throw error("Expected '" + expected + "'");
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

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + position);
        }
    }
}
