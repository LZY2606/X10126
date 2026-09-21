package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal dependency-free JSON parser / writer with a canonical form for hashing. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) throw new IllegalArgumentException("trailing content at " + p.pos);
        return v;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, false, 0);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, true, 0);
        return sb.toString();
    }

    /** Canonical serialization: object keys sorted, compact. Used for stable fingerprints. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeCanonical(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof String s) writeString(sb, s);
        else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) sb.append(v);
        else if (v instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) sb.append(d.longValue());
            else sb.append(d);
        }
        else if (v instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>((Map<String, Object>) v);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeCanonical(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                writeCanonical(sb, o);
            }
            sb.append(']');
        } else throw new IllegalArgumentException("cannot serialize " + v.getClass());
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v, boolean pretty, int indent) {
        if (v == null) sb.append("null");
        else if (v instanceof String s) writeString(sb, s);
        else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) sb.append(v);
        else if (v instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) sb.append(d.longValue());
            else sb.append(d);
        }
        else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                if (pretty) { sb.append('\n'); pad(sb, indent + 1); }
                first = false;
                writeString(sb, e.getKey());
                sb.append(pretty ? ": " : ":");
                writeValue(sb, e.getValue(), pretty, indent + 1);
            }
            if (pretty && !m.isEmpty()) { sb.append('\n'); pad(sb, indent); }
            sb.append('}');
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(',');
                if (pretty) { sb.append('\n'); pad(sb, indent + 1); }
                first = false;
                writeValue(sb, o, pretty, indent + 1);
            }
            if (pretty && !l.isEmpty()) { sb.append('\n'); pad(sb, indent); }
            sb.append(']');
        } else throw new IllegalArgumentException("cannot serialize " + v.getClass());
    }

    private static void pad(StringBuilder sb, int n) { sb.append("  ".repeat(Math.max(0, n))); }

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

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new IllegalArgumentException("expected object, got " + o);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        if (o instanceof List) return (List<Object>) o;
        throw new IllegalArgumentException("expected array, got " + o);
    }

    public static String asString(Object o) {
        if (o instanceof String s) return s;
        throw new IllegalArgumentException("expected string, got " + o);
    }

    public static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("expected number, got " + o);
    }

    public static Map<String, Object> map() { return new LinkedHashMap<>(); }
    public static List<Object> list() { return new ArrayList<>(); }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object parseValue() {
            skipWs();
            if (atEnd()) throw new IllegalArgumentException("unexpected end of input");
            char c = s.charAt(pos);
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

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("expected '" + lit + "' at " + pos);
            pos += lit.length();
        }

        private Map<String, Object> parseObject() {
            pos++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw new IllegalArgumentException("expected ':' at " + pos);
                pos++;
                m.put(key, parseValue());
                skipWs();
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected ',' at " + (pos - 1));
            }
        }

        private List<Object> parseArray() {
            pos++; // [
            List<Object> l = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                l.add(parseValue());
                skipWs();
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected ',' at " + (pos - 1));
            }
        }

        private String parseString() {
            if (pos >= s.length() || s.charAt(pos) != '"') throw new IllegalArgumentException("expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw new IllegalArgumentException("bad escape");
                    char e = s.charAt(pos++);
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
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else sb.append(c);
            }
        }

        private Object parseNumber() {
            int start = pos;
            boolean floating = false;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') { floating = true; pos++; }
                else break;
            }
            if (start == pos) throw new IllegalArgumentException("unexpected character '" + (pos < s.length() ? s.charAt(pos) : "EOF") + "' at " + pos);
            String num = s.substring(start, pos);
            try {
                return floating ? Double.parseDouble(num) : Long.parseLong(num);
            } catch (NumberFormatException e) {
                return Double.parseDouble(num);
            }
        }
    }
}
