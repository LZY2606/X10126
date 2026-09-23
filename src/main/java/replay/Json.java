package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal dependency-free JSON parser / serializer. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (p.pos != p.text.length()) throw new IllegalArgumentException("Trailing content at " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) v;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, false);
        return sb.toString();
    }

    /** Canonical form: object keys sorted, no whitespace. Used for hashing. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, true);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        prettyValue(sb, value, 0);
        return sb.toString();
    }

    private static void prettyValue(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append("  ".repeat(indent + 1));
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                prettyValue(sb, e.getValue(), indent + 1);
            }
            sb.append("\n").append("  ".repeat(indent)).append("}");
        } else if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append("  ".repeat(indent + 1));
                prettyValue(sb, o, indent + 1);
            }
            sb.append("\n").append("  ".repeat(indent)).append("]");
        } else {
            writeValue(sb, v, false);
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v, boolean sortKeys) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { writeString(sb, (String) v); return; }
        if (v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Number) { writeNumber(sb, (Number) v); return; }
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (sortKeys) m = new TreeMap<>(m);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue(), sortKeys);
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, o, sortKeys);
            }
            sb.append(']');
            return;
        }
        throw new IllegalArgumentException("Not JSON-serializable: " + v.getClass());
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
                return;
            }
        }
        sb.append(n);
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String text;
        int pos;

        Parser(String text) { this.text = text; }

        void skipWs() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        Object parseValue() {
            if (pos >= text.length()) throw error("Unexpected end");
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

        Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (pos < text.length() && text.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                if (pos >= text.length() || text.charAt(pos) != ':') throw error("Expected ':'");
                pos++;
                skipWs();
                m.put(key, parseValue());
                skipWs();
                if (pos >= text.length()) throw error("Unterminated object");
                char c = text.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw error("Expected ',' or '}'");
            }
        }

        List<Object> parseArray() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (pos < text.length() && text.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                skipWs();
                l.add(parseValue());
                skipWs();
                if (pos >= text.length()) throw error("Unterminated array");
                char c = text.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw error("Expected ',' or ']'");
            }
        }

        String parseString() {
            if (pos >= text.length() || text.charAt(pos) != '"') throw error("Expected string");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= text.length()) throw error("Bad escape");
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > text.length()) throw error("Bad unicode escape");
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("Bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("Unterminated string");
        }

        Object parseNumber() {
            int start = pos;
            if (pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) pos++;
            boolean floating = false;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isDigit(c)) pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') { floating = true; pos++; }
                else break;
            }
            if (start == pos) throw error("Expected value");
            String s = text.substring(start, pos);
            try {
                if (floating) return Double.parseDouble(s);
                long l = Long.parseLong(s);
                return l;
            } catch (NumberFormatException e) {
                throw error("Bad number: " + s);
            }
        }

        void expect(String word) {
            if (!text.startsWith(word, pos)) throw error("Expected " + word);
            pos += word.length();
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at position " + pos);
        }
    }
}
