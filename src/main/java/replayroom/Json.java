package replayroom;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.end()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        write(builder, value);
        return builder.toString();
    }

    public static String pretty(Object value) {
        StringBuilder builder = new StringBuilder();
        writePretty(builder, value, 0);
        return builder.toString();
    }

    public static String canonical(Object value) {
        StringBuilder builder = new StringBuilder();
        writeCanonical(builder, value);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("Expected JSON array");
        }
        return (List<Object>) value;
    }

    public static String string(Object value, String key) {
        Object result = object(value).get(key);
        if (!(result instanceof String)) {
            throw new IllegalArgumentException("Expected string field " + key);
        }
        return (String) result;
    }

    public static String optionalString(Object value, String key, String fallback) {
        Object result = object(value).get(key);
        if (result == null) return fallback;
        if (!(result instanceof String)) {
            throw new IllegalArgumentException("Expected string field " + key);
        }
        return (String) result;
    }

    public static long longValue(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try { return Long.parseLong((String) value); } catch (NumberFormatException ignored) {}
        }
        throw new IllegalArgumentException("Expected integer, got " + typeName(value));
    }

    public static Map<String, Object> newObject() {
        return new LinkedHashMap<>();
    }

    public static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, val) -> copy.put(String.valueOf(key), deepCopy(val)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(deepCopy(item));
            return copy;
        }
        return value;
    }

    public static boolean equalJson(Object a, Object b) {
        if (a instanceof Map<?, ?> am && b instanceof Map<?, ?> bm) {
            if (am.size() != bm.size()) return false;
            for (Object key : am.keySet()) {
                if (!bm.containsKey(key) || !equalJson(am.get(key), bm.get(key))) return false;
            }
            return true;
        }
        if (a instanceof List<?> al && b instanceof List<?> bl) {
            if (al.size() != bl.size()) return false;
            for (int i = 0; i < al.size(); i++) {
                if (!equalJson(al.get(i), bl.get(i))) return false;
            }
            return true;
        }
        if (a instanceof Number && b instanceof Number) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return java.util.Objects.equals(a, b);
    }

    private static void write(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String text) {
            writeString(builder, text);
        } else if (value instanceof Boolean bool) {
            builder.append(bool.booleanValue());
        } else if (value instanceof BigDecimal number) {
            builder.append(number.stripTrailingZeros().toPlainString());
        } else if (value instanceof Number number) {
            builder.append(number.toString());
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) builder.append(',');
                first = false;
                writeString(builder, String.valueOf(entry.getKey()));
                builder.append(':');
                write(builder, entry.getValue());
            }
            builder.append('}');
        } else if (value instanceof Collection<?> items) {
            builder.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) builder.append(',');
                first = false;
                write(builder, item);
            }
            builder.append(']');
        } else {
            writeString(builder, String.valueOf(value));
        }
    }

    private static void writePretty(StringBuilder builder, Object value, int depth) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            builder.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) builder.append(",\n");
                first = false;
                indent(builder, depth + 1);
                writeString(builder, String.valueOf(entry.getKey()));
                builder.append(": ");
                writePretty(builder, entry.getValue(), depth + 1);
            }
            builder.append('\n');
            indent(builder, depth);
            builder.append('}');
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            builder.append("[\n");
            boolean first = true;
            for (Object item : list) {
                if (!first) builder.append(",\n");
                first = false;
                indent(builder, depth + 1);
                writePretty(builder, item, depth + 1);
            }
            builder.append('\n');
            indent(builder, depth);
            builder.append(']');
        } else {
            write(builder, value);
        }
    }

    private static void writeCanonical(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String text) {
            writeString(builder, text);
        } else if (value instanceof Boolean bool) {
            builder.append(bool.booleanValue());
        } else if (value instanceof BigDecimal number) {
            String normalized = number.stripTrailingZeros().toPlainString();
            if (normalized.equals("-0")) normalized = "0";
            builder.append(normalized);
        } else if (value instanceof Number number) {
            builder.append(new BigDecimal(number.toString()).stripTrailingZeros().toPlainString());
        } else if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            map.keySet().forEach(key -> keys.add(String.valueOf(key)));
            java.util.Collections.sort(keys);
            builder.append('{');
            boolean first = true;
            for (String key : keys) {
                if (!first) builder.append(',');
                first = false;
                writeString(builder, key);
                builder.append(':');
                writeCanonical(builder, map.get(key));
            }
            builder.append('}');
        } else if (value instanceof Collection<?> items) {
            builder.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) builder.append(',');
                first = false;
                writeCanonical(builder, item);
            }
            builder.append(']');
        } else {
            writeString(builder, String.valueOf(value));
        }
    }

    private static void indent(StringBuilder builder, int depth) {
        builder.append("  ".repeat(depth));
    }

    private static void writeString(StringBuilder builder, String text) {
        builder.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
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

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static final class Parser {
        private final String text;
        private int pos;

        private Parser(String text) {
            this.text = text;
        }

        private Object readValue() {
            skipWhitespace();
            if (end()) throw error("Unexpected end of JSON");
            char c = text.charAt(pos);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBoolean();
            if (c == 'n') return readNull();
            return readNumber();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
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

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (consume(']')) return list;
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (consume(']')) return list;
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (true) {
                if (end()) throw error("Unterminated string");
                char c = text.charAt(pos++);
                if (c == '"') return builder.toString();
                if (c == '\\') {
                    if (end()) throw error("Unterminated escape");
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
                            if (pos + 4 > text.length()) throw error("Bad unicode escape");
                            builder.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("Unsupported escape");
                    }
                } else {
                    builder.append(c);
                }
            }
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
            if (peek() == '-') pos++;
            readDigits();
            if (!end() && peek() == '.') {
                pos++;
                readDigits();
            }
            if (!end() && (peek() == 'e' || peek() == 'E')) {
                pos++;
                if (!end() && (peek() == '+' || peek() == '-')) pos++;
                readDigits();
            }
            try {
                return new BigDecimal(text.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error("Invalid number");
            }
        }

        private void readDigits() {
            int start = pos;
            while (!end() && Character.isDigit(peek())) pos++;
            if (start == pos) throw error("Expected digits");
        }

        private void skipWhitespace() {
            while (!end() && Character.isWhitespace(peek())) pos++;
        }

        private char peek() {
            return text.charAt(pos);
        }

        private boolean end() {
            return pos >= text.length();
        }

        private boolean consume(char expected) {
            if (!end() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (end() || text.charAt(pos) != expected) {
                throw error("Expected '" + expected + "'");
            }
            pos++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + pos);
        }
    }
}
