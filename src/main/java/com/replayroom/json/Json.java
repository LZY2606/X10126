package com.replayroom.json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal JSON implementation: parser, pretty printer, canonical serializer
 * (RFC 8785 style: sorted object keys, no insignificant whitespace, fixed
 * number rendering) and SHA-256 fingerprinting.
 *
 * Object insertion order is preserved with LinkedHashMap so the pretty
 * output stays readable; canonicalization sorts keys only for hashing.
 */
public final class Json {

    private Json() {
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }

        public JsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.eof()) {
            throw parser.error("trailing characters after JSON value");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean eof() {
            return pos >= text.length();
        }

        JsonException error(String message) {
            return new JsonException("JSON parse error at position " + pos + ": " + message);
        }

        char peek() {
            return text.charAt(pos);
        }

        void skipWhitespace() {
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
            if (eof()) {
                throw error("unexpected end of input");
            }
            char c = peek();
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    readLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    readLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    readLiteral("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        void readLiteral(String literal) {
            if (!text.startsWith(literal, pos)) {
                throw error("expected '" + literal + "'");
            }
            pos += literal.length();
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (!eof() && peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (eof() || peek() != '"') {
                    throw error("expected string key in object");
                }
                String key = readString();
                skipWhitespace();
                if (eof() || peek() != ':') {
                    throw error("expected ':' after object key");
                }
                pos++;
                skipWhitespace();
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                if (eof()) {
                    throw error("unterminated object");
                }
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw error("expected ',' or '}' in object");
                }
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (!eof() && peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                if (eof()) {
                    throw error("unterminated array");
                }
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw error("expected ',' or ']' in array");
                }
            }
        }

        String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (eof()) {
                        throw error("unterminated escape");
                    }
                    char escape = text.charAt(pos++);
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
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
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
                        case 'u':
                            if (pos + 4 > text.length()) {
                                throw error("invalid unicode escape");
                            }
                            String hex = text.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw error("invalid unicode escape \\u" + hex);
                            }
                            break;
                        default:
                            throw error("invalid escape '\\" + escape + "'");
                    }
                } else {
                    if (c < 0x20) {
                        throw error("unescaped control character in string");
                    }
                    sb.append(c);
                }
            }
        }

        Number readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            readIntegerDigits();
            boolean isFloating = false;
            if (!eof() && peek() == '.') {
                isFloating = true;
                pos++;
                readIntegerDigits();
            }
            if (!eof() && (peek() == 'e' || peek() == 'E')) {
                isFloating = true;
                pos++;
                if (!eof() && (peek() == '+' || peek() == '-')) {
                    pos++;
                }
                readIntegerDigits();
            }
            String number = text.substring(start, pos);
            if (isFloating) {
                return Double.parseDouble(number);
            }
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException e) {
                return Double.parseDouble(number);
            }
        }

        void readIntegerDigits() {
            if (eof() || !(peek() >= '0' && peek() <= '9')) {
                throw error("expected digit");
            }
            while (!eof() && peek() >= '0' && peek() <= '9') {
                pos++;
            }
        }
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, value, 0);
        return sb.toString();
    }

    private static void writePretty(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(renderNumberOrLiteral(value));
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                appendIndent(sb, indent + 1);
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(": ");
                writePretty(sb, entry.getValue(), indent + 1);
                if (++index < map.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            appendIndent(sb, indent);
            sb.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                appendIndent(sb, indent + 1);
                writePretty(sb, list.get(i), indent + 1);
                if (i < list.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            appendIndent(sb, indent);
            sb.append(']');
        } else {
            throw new JsonException("cannot serialize value of type " + value.getClass().getName());
        }
    }

    private static void appendIndent(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    private static void writeString(StringBuilder sb, String value) {
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
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
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

    /**
     * RFC 8785 style canonical JSON: sorted object keys, no whitespace,
     * deterministic number rendering. The result is byte-stable across JVMs.
     */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(sb, value);
        return sb.toString();
    }

    private static void writeCanonical(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Number) {
            sb.append(renderNumber((Number) value));
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) {
                keys.add(String.valueOf(key));
            }
            keys.sort(null);
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeString(sb, keys.get(i));
                sb.append(':');
                writeCanonical(sb, map.get(keys.get(i)));
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeCanonical(sb, list.get(i));
            }
            sb.append(']');
        } else {
            throw new JsonException("cannot canonicalize value of type " + value.getClass().getName());
        }
    }

    private static String renderNumberOrLiteral(Object value) {
        if (value instanceof Number number) {
            return renderNumber(number);
        }
        return value.toString();
    }

    private static String renderNumber(Number number) {
        if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("cannot serialize non-finite number");
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e16) {
                long asLong = (long) d;
                if ((double) asLong == d) {
                    return Long.toString(asLong);
                }
            }
            return Double.toString(d);
        }
        return number.toString();
    }

    // ------------------------------------------------------------------
    // Fingerprints and deep copy
    // ------------------------------------------------------------------

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (Exception e) {
            throw new JsonException("SHA-256 unavailable", e);
        }
    }

    public static String fingerprint(Object value) {
        return sha256Hex(canonical(value));
    }

    public static String fingerprintConcat(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
            }
            return toHex(digest.digest());
        } catch (Exception e) {
            throw new JsonException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] hash) {
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }
}
