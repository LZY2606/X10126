package replay.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal self-contained JSON parser / writer.
 * Canonical output sorts object keys, which is what all fingerprints are built on.
 */
public final class Json {
    private Json() {
    }

    // ---------- parsing ----------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.readValue();
        p.skipWs();
        if (p.pos != p.text.length()) {
            throw new IllegalArgumentException("trailing characters at position " + p.pos);
        }
        return value;
    }

    private static final class Parser {
        final String text;
        int pos;

        Parser(String text) {
            this.text = text;
        }

        void skipWs() {
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
            skipWs();
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            char c = text.charAt(pos);
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
                expect("null");
                return null;
            }
            return readNumber();
        }

        Map<String, Object> readObject() {
            expect("{");
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(":");
                Object value = readValue();
                map.put(key, value);
                skipWs();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or '}' at " + pos);
                }
            }
        }

        List<Object> readArray() {
            expect("[");
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or ']' at " + pos);
                }
            }
        }

        String readString() {
            skipWs();
            expect("\"");
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            String hex = text.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Boolean readBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("invalid literal at " + pos);
        }

        Object readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean floating = false;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = floating || c == '.' || c == 'e' || c == 'E';
                    pos++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, pos);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("invalid number at " + start);
            }
            if (floating) {
                return Double.parseDouble(raw);
            }
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(raw);
            }
        }

        char peek() {
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            return text.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void expect(String token) {
            if (!text.startsWith(token, pos)) {
                throw new IllegalArgumentException("expected '" + token + "' at " + pos);
            }
            pos += token.length();
        }
    }

    // ---------- writing ----------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value, false, 0);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value, true, 0);
        return sb.toString();
    }

    /** Canonical form: object keys sorted, no insignificant whitespace. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        appendCanonical(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void append(StringBuilder sb, Object value, boolean pretty, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            appendString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Number n) {
            appendNumber(sb, n);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            int i = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                if (i++ > 0) {
                    sb.append(',');
                }
                if (pretty) {
                    newline(sb, indent + 1);
                }
                appendString(sb, e.getKey());
                sb.append(pretty ? ": " : ":");
                append(sb, e.getValue(), pretty, indent + 1);
            }
            if (pretty) {
                newline(sb, indent);
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                if (pretty) {
                    newline(sb, indent + 1);
                }
                append(sb, list.get(i), pretty, indent + 1);
            }
            if (pretty) {
                newline(sb, indent);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize " + value.getClass());
        }
    }

    @SuppressWarnings("unchecked")
    private static void appendCanonical(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            appendString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Number n) {
            appendNumber(sb, n);
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            sorted.putAll((Map<String, Object>) map);
            sb.append('{');
            int i = 0;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (i++ > 0) {
                    sb.append(',');
                }
                appendString(sb, e.getKey());
                sb.append(':');
                appendCanonical(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendCanonical(sb, list.get(i));
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize " + value.getClass());
        }
    }

    private static void newline(StringBuilder sb, int indent) {
        sb.append('\n');
        sb.append("  ".repeat(Math.max(0, indent)));
    }

    private static void appendNumber(StringBuilder sb, Number n) {
        if (n instanceof Double d && (d.isNaN() || d.isInfinite())) {
            sb.append("null");
        } else {
            sb.append(n.toString());
        }
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
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

    // ---------- typed helpers ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object value, String what) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(what + " must be a JSON object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object value, String what) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(what + " must be a JSON array");
        }
        return (List<Object>) value;
    }

    public static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("'" + key + "' must be a string");
        }
        return s;
    }

    public static long lng(Map<String, Object> map, String key, long fallback) {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException("'" + key + "' must be a number");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopy(Object value) {
        return (Map<String, Object>) copy(value);
    }

    @SuppressWarnings("unchecked")
    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put((String) e.getKey(), copy(e.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(copy(item));
            }
            return copy;
        }
        return value;
    }
}
