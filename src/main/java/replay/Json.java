package replay;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal JSON parser/writer. Objects are LinkedHashMap<String,Object>,
 * arrays are List<Object>, numbers are Long or Double, plus String/Boolean/null.
 */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw new JsonException("Trailing content at offset " + p.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("Expected JSON object");
        }
        return (Map<String, Object>) v;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, false);
        return sb.toString();
    }

    /** Canonical form: object keys sorted, no insignificant whitespace. Used for hashing. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, true);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value, boolean canonical) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof Long || value instanceof Integer) {
            sb.append(((Number) value).longValue());
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
            }
        } else if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            if (canonical) {
                map = new TreeMap<>(map);
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue(), canonical);
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item, canonical);
            }
            sb.append(']');
        } else {
            throw new JsonException("Cannot serialize: " + value.getClass());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v, String what) {
        if (!(v instanceof Map)) throw new JsonException(what + " must be an object");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object v, String what) {
        if (!(v instanceof List)) throw new JsonException(what + " must be an array");
        return (List<Object>) v;
    }

    public static String asString(Object v, String what) {
        if (!(v instanceof String)) throw new JsonException(what + " must be a string");
        return (String) v;
    }

    public static long asLong(Object v, String what) {
        if (!(v instanceof Number)) throw new JsonException(what + " must be a number");
        return ((Number) v).longValue();
    }

    /** Deep copy of a parsed JSON value. */
    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                copy.put(e.getKey(), deepCopy(e.getValue()));
            }
            return copy;
        }
        if (v instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<Object>) v) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return v;
    }

    public static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    public static List<Object> list() {
        return new ArrayList<>();
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWs() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object parseValue() {
            if (atEnd()) throw new JsonException("Unexpected end of input");
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> { expect("true"); yield Boolean.TRUE; }
                case 'f' -> { expect("false"); yield Boolean.FALSE; }
                case 'n' -> { expect("null"); yield null; }
                default -> parseNumber();
            };
        }

        private void expect(String word) {
            if (!text.startsWith(word, pos)) throw new JsonException("Expected '" + word + "' at " + pos);
            pos += word.length();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (!atEnd() && text.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                if (atEnd() || text.charAt(pos) != ':') throw new JsonException("Expected ':' at " + pos);
                pos++;
                skipWs();
                map.put(key, parseValue());
                skipWs();
                if (atEnd()) throw new JsonException("Unterminated object");
                char c = text.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return map; }
                throw new JsonException("Expected ',' or '}' at " + pos);
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWs();
            if (!atEnd() && text.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                skipWs();
                list.add(parseValue());
                skipWs();
                if (atEnd()) throw new JsonException("Unterminated array");
                char c = text.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                throw new JsonException("Expected ',' or ']' at " + pos);
            }
        }

        private String parseString() {
            if (atEnd() || text.charAt(pos) != '"') throw new JsonException("Expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw new JsonException("Unterminated string");
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (atEnd()) throw new JsonException("Unterminated escape");
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > text.length()) throw new JsonException("Bad unicode escape");
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new JsonException("Bad escape: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            if (!atEnd() && text.charAt(pos) == '-') pos++;
            boolean isDouble = false;
            while (!atEnd()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { isDouble = true; pos++; }
                else break;
            }
            if (start == pos) throw new JsonException("Unexpected character at " + pos);
            String num = text.substring(start, pos);
            try {
                return isDouble ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new JsonException("Bad number: " + num);
            }
        }
    }
}
