package playroom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser and canonical serializer. No external dependencies.
 * Values: Map<String,Object> (insertion-ordered), List<Object>, String,
 * Double (numbers), Boolean, null.
 */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.eof()) throw p.error("trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected JSON object");
        return (Map<String, Object>) v;
    }

    /** Pretty serialization with 2-space indentation. */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0, false);
        return sb.toString();
    }

    /** Compact, key-sorted serialization used for fingerprinting. */
    @SuppressWarnings("unchecked")
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeCanonical(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Number n) {
            writeNumber(sb, n.doubleValue());
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Map<?, ?> m) {
            List<String> keys = new ArrayList<>();
            for (Object k : m.keySet()) keys.add(String.valueOf(k));
            Collections.sort(keys);
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                writeString(sb, keys.get(i));
                sb.append(':');
                writeCanonical(sb, ((Map<String, Object>) m).get(keys.get(i)));
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                writeCanonical(sb, list.get(i));
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize " + value.getClass());
        }
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object value, int depth, boolean inArray) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Number n) {
            writeNumber(sb, n.doubleValue());
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            int i = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                if (i++ > 0) sb.append(',');
                sb.append('\n');
                indent(sb, depth + 1);
                writeString(sb, e.getKey());
                sb.append(": ");
                write(sb, e.getValue(), depth + 1, false);
            }
            sb.append('\n');
            indent(sb, depth);
            sb.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('\n');
                indent(sb, depth + 1);
                write(sb, list.get(i), depth + 1, true);
            }
            sb.append('\n');
            indent(sb, depth);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize " + value.getClass());
        }
    }

    private static void indent(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth));
    }

    private static void writeNumber(StringBuilder sb, double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && d >= -1.0e15 && d <= 1.0e15) {
            sb.append((long) d);
        } else {
            sb.append(d);
        }
    }

    static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---- parsing ----

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        boolean eof() { return pos >= s.length(); }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON error at " + pos + ": " + msg);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object readValue() {
            skipWs();
            if (eof()) throw error("unexpected end");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                m.put(key, readValue());
                skipWs();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw error("expected , or }");
            }
            return m;
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw error("expected , or ]");
            }
            return list;
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw error("unterminated string");
                char c = next();
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = next();
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
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw error("bad escape");
                    }
                } else sb.append(c);
            }
        }

        Boolean readBoolean() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw error("bad literal");
        }

        Object readNull() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw error("bad literal");
        }

        Object readNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!eof() && "0123456789.eE+-".indexOf(peek()) >= 0) pos++;
            String token = s.substring(start, pos);
            if (token.isEmpty()) throw error("bad number");
            try {
                double d = Double.parseDouble(token);
                if (token.matches("-?\\d+")) return Long.parseLong(token);
                return d;
            } catch (NumberFormatException ex) {
                throw error("bad number");
            }
        }

        char peek() {
            if (eof()) throw error("unexpected end");
            return s.charAt(pos);
        }

        char next() {
            if (eof()) throw error("unexpected end");
            return s.charAt(pos++);
        }

        void expect(char c) {
            if (eof() || s.charAt(pos) != c) throw error("expected " + c);
            pos++;
        }
    }
}
