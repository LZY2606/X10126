package replay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writeCompact(value, builder);
        return builder.toString();
    }

    public static String pretty(Object value) {
        StringBuilder builder = new StringBuilder();
        writePretty(value, builder, 0);
        builder.append('\n');
        return builder.toString();
    }

    public static String canonical(Object value) {
        StringBuilder builder = new StringBuilder();
        writeCanonical(value, builder);
        return builder.toString();
    }

    public static String sha256(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical(value).getBytes(StandardCharsets.UTF_8));
            return hex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xf, 16));
            builder.append(Character.forDigit(b & 0xf, 16));
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    public static Object copy(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put((String) entry.getKey(), copy(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                result.add(copy(item));
            }
            return result;
        }
        return value;
    }

    public static Map<String, Object> object(Object value, String path) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    public static List<Object> list(Object value, String path) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(path + " must be a list");
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) value;
        return list;
    }

    public static String string(Object value, String path) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return (String) value;
    }

    public static long integer(Object value, String path) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException(path + " must be an integer");
    }

    private static void writeCompact(Object value, StringBuilder builder) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String) {
            writeString((String) value, builder);
        } else if (value instanceof Boolean) {
            builder.append(value.toString());
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("JSON cannot encode non-finite number");
            }
            builder.append(number);
        } else if (value instanceof Number) {
            builder.append(value.toString());
        } else if (value instanceof List<?>) {
            builder.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) builder.append(',');
                first = false;
                writeCompact(item, builder);
            }
            builder.append(']');
        } else if (value instanceof Map<?, ?>) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) builder.append(',');
                first = false;
                writeString(String.valueOf(entry.getKey()), builder);
                builder.append(':');
                writeCompact(entry.getValue(), builder);
            }
            builder.append('}');
        } else {
            throw new IllegalArgumentException("Cannot encode " + value.getClass());
        }
    }

    private static void writePretty(Object value, StringBuilder builder, int indent) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            builder.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) builder.append(",\n");
                first = false;
                indent(builder, indent + 1);
                writeString(String.valueOf(entry.getKey()), builder);
                builder.append(": ");
                writePretty(entry.getValue(), builder, indent + 1);
            }
            builder.append('\n');
            indent(builder, indent);
            builder.append('}');
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            builder.append("[\n");
            boolean first = true;
            for (Object item : list) {
                if (!first) builder.append(",\n");
                first = false;
                indent(builder, indent + 1);
                writePretty(item, builder, indent + 1);
            }
            builder.append('\n');
            indent(builder, indent);
            builder.append(']');
        } else {
            writeCompact(value, builder);
        }
    }

    private static void indent(StringBuilder builder, int level) {
        builder.append("  ".repeat(level));
    }

    private static void writeCanonical(Object value, StringBuilder builder) {
        if (value instanceof Map<?, ?>) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            builder.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) builder.append(',');
                first = false;
                writeString(entry.getKey(), builder);
                builder.append(':');
                writeCanonical(entry.getValue(), builder);
            }
            builder.append('}');
        } else if (value instanceof List<?>) {
            builder.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) builder.append(',');
                first = false;
                writeCanonical(item, builder);
            }
            builder.append(']');
        } else {
            writeCompact(value, builder);
        }
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

        private boolean end() {
            return position >= text.length();
        }

        private void skipWhitespace() {
            while (!end() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
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
            skipWhitespace();
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (true) {
                if (end()) throw new IllegalArgumentException("Unterminated string");
                char c = text.charAt(position++);
                if (c == '"') return builder.toString();
                if (c == '\\') {
                    if (end()) throw new IllegalArgumentException("Unterminated escape");
                    char escaped = text.charAt(position++);
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
                            if (position + 4 > text.length()) throw new IllegalArgumentException("Bad unicode escape");
                            builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            position += 4;
                        }
                        default -> throw new IllegalArgumentException("Bad escape: " + escaped);
                    }
                } else {
                    builder.append(c);
                }
            }
        }

        private Boolean readBoolean() {
            if (text.startsWith("true", position)) {
                position += 4;
                return true;
            }
            if (text.startsWith("false", position)) {
                position += 5;
                return false;
            }
            throw new IllegalArgumentException("Invalid JSON literal at " + position);
        }

        private Object readNull() {
            if (text.startsWith("null", position)) {
                position += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid JSON literal at " + position);
        }

        private Number readNumber() {
            int start = position;
            if (peek() == '-') position++;
            readDigits();
            boolean floating = false;
            if (!end() && peek() == '.') {
                floating = true;
                position++;
                readDigits();
            }
            if (!end() && (peek() == 'e' || peek() == 'E')) {
                floating = true;
                position++;
                if (!end() && (peek() == '+' || peek() == '-')) position++;
                readDigits();
            }
            String token = text.substring(start, position);
            if (token.isEmpty() || "-".equals(token)) {
                throw new IllegalArgumentException("Invalid number at " + start);
            }
            return floating ? Double.parseDouble(token) : Long.parseLong(token);
        }

        private void readDigits() {
            if (end() || !Character.isDigit(peek())) {
                throw new IllegalArgumentException("Expected digits at " + position);
            }
            while (!end() && Character.isDigit(peek())) position++;
        }

        private char peek() {
            return text.charAt(position);
        }

        private boolean consume(char c) {
            if (!end() && text.charAt(position) == c) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            skipWhitespace();
            if (!consume(c)) {
                throw new IllegalArgumentException("Expected '" + c + "' at position " + position);
            }
        }
    }
}
