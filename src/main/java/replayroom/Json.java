package replayroom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class Json {
    private Json() {
    }

    static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.end()) {
            throw new IllegalArgumentException("JSON 后仍有多余内容，位置 " + parser.position);
        }
        return value;
    }

    static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writePretty(builder, value, 0);
        return builder.toString();
    }

    static String canonical(Object value) {
        StringBuilder builder = new StringBuilder();
        writeCanonical(builder, value);
        return builder.toString();
    }

    static String sha256(Object value) {
        return sha256(canonical(value));
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : hash) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("需要 JSON 对象");
        }
        return (Map<String, Object>) value;
    }

    static List<Object> list(Object value) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("需要 JSON 数组");
        }
        return (List<Object>) value;
    }

    static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("字段 " + key + " 必须是非空字符串");
        }
        return text;
    }

    static String optionalString(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("字段 " + key + " 必须是字符串");
        }
        return text;
    }

    static long integer(Object value, String name) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            long result = number.longValue();
            if (result == number.doubleValue()) {
                return result;
            }
        }
        throw new IllegalArgumentException("字段 " + name + " 必须是整数");
    }

    @SuppressWarnings("unchecked")
    static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), copy(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> items) {
            List<Object> result = new ArrayList<>(items.size());
            for (Object item : items) {
                result.add(copy(item));
            }
            return result;
        }
        return value;
    }

    private static void writePretty(StringBuilder builder, Object value, int depth) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof Boolean || value instanceof Number) {
            builder.append(value);
        } else if (value instanceof String text) {
            writeString(builder, text);
        } else if (value instanceof List<?> items) {
            if (items.isEmpty()) {
                builder.append("[]");
                return;
            }
            builder.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append('\n').append("  ".repeat(depth + 1));
                writePretty(builder, items.get(i), depth + 1);
            }
            builder.append('\n').append("  ".repeat(depth)).append(']');
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                builder.append("{}");
                return;
            }
            builder.append('{');
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (i++ > 0) {
                    builder.append(',');
                }
                builder.append('\n').append("  ".repeat(depth + 1));
                writeString(builder, String.valueOf(entry.getKey()));
                builder.append(": ");
                writePretty(builder, entry.getValue(), depth + 1);
            }
            builder.append('\n').append("  ".repeat(depth)).append('}');
        } else {
            throw new IllegalArgumentException("不支持序列化的值: " + value.getClass());
        }
    }

    private static void writeCanonical(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof Boolean || value instanceof Number) {
            builder.append(value);
        } else if (value instanceof String text) {
            writeString(builder, text);
        } else if (value instanceof List<?> items) {
            builder.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                writeCanonical(builder, items.get(i));
            }
            builder.append(']');
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            builder.append('{');
            int i = 0;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (i++ > 0) {
                    builder.append(',');
                }
                writeString(builder, entry.getKey());
                builder.append(':');
                writeCanonical(builder, entry.getValue());
            }
            builder.append('}');
        } else {
            throw new IllegalArgumentException("不支持规范化的值: " + value.getClass());
        }
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

    private static final class Parser {
        private final String text;
        private int position;

        private Parser(String text) {
            this.text = text;
        }

        private Object readValue() {
            skipWhitespace();
            if (end()) {
                throw new IllegalArgumentException("意外的 JSON 结尾");
            }
            char c = text.charAt(position);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (take('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                if (take('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> readArray() {
            List<Object> result = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (take(']')) {
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (take(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!end()) {
                char c = text.charAt(position++);
                if (c == '"') {
                    return result.toString();
                }
                if (c == '\\') {
                    if (end()) {
                        throw new IllegalArgumentException("字符串转义不完整");
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
                                throw new IllegalArgumentException("Unicode 转义不完整");
                            }
                            result.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            position += 4;
                        }
                        default -> throw new IllegalArgumentException("未知字符串转义: " + escaped);
                    }
                } else {
                    result.append(c);
                }
            }
            throw new IllegalArgumentException("字符串没有结束");
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
            throw new IllegalArgumentException("位置 " + position + " 不是合法布尔值");
        }

        private Object readNull() {
            if (text.startsWith("null", position)) {
                position += 4;
                return null;
            }
            throw new IllegalArgumentException("位置 " + position + " 不是合法 null");
        }

        private Number readNumber() {
            int start = position;
            if (peek() == '-') {
                position++;
            }
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
                if (!end() && (peek() == '+' || peek() == '-')) {
                    position++;
                }
                readDigits();
            }
            String token = text.substring(start, position);
            if (token.isBlank() || "-".equals(token) || ".".equals(token)) {
                throw new IllegalArgumentException("位置 " + start + " 不是合法数字");
            }
            return floating ? Double.parseDouble(token) : Long.parseLong(token);
        }

        private void readDigits() {
            int start = position;
            while (!end() && Character.isDigit(peek())) {
                position++;
            }
            if (start == position) {
                throw new IllegalArgumentException("位置 " + position + " 缺少数字");
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
                throw new IllegalArgumentException("位置 " + position + " 应为 " + expected);
            }
            position++;
        }

        private boolean take(char expected) {
            if (!end() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }
    }
}
