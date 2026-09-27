package replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, deterministic JSON parser / writer with no external dependencies.
 * Object keys preserve insertion order; {@link #canonical} sorts keys and
 * emits no whitespace so that fingerprints are stable across machines.
 */
public final class Json {
    private Json() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new IllegalArgumentException("expected JSON object");
        return (Map<String, Object>) value;
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (!p.eof()) throw p.error("trailing characters");
        return value;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, false);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, true);
        return sb.toString();
    }

    /** Canonical form: sorted object keys, no insignificant whitespace. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(sb, value);
        return sb.toString();
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public static long lng(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        if (v instanceof Number n) return ((Number) v).longValue();
        if (v instanceof String s && !s.isBlank()) return Long.parseLong(s.trim());
        return dflt;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean dflt) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return dflt;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return new LinkedHashMap<>();
        if (!(v instanceof Map)) throw new IllegalArgumentException(key + " must be an object");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return new ArrayList<>();
        if (!(v instanceof List)) throw new IllegalArgumentException(key + " must be an array");
        return (List<Object>) v;
    }

    @SuppressWarnings("unchecked")
    static void write(StringBuilder sb, Object v, boolean indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(numberText(v));
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(indent ? ", " : ",");
                writeString(sb, e.getKey().toString());
                sb.append(indent ? ": " : ":");
                write(sb, e.getValue(), indent);
                first = false;
            }
            sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(indent ? ", " : ",");
                write(sb, item, indent);
                first = false;
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize " + v.getClass());
        }
    }

    @SuppressWarnings("unchecked")
    static void writeCanonical(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(numberText(v));
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Map<?, ?> raw) {
            List<String> keys = new ArrayList<>();
            for (Object k : raw.keySet()) keys.add(k.toString());
            java.util.Collections.sort(keys);
            sb.append('{');
            boolean first = true;
            for (String key : keys) {
                if (!first) sb.append(',');
                writeString(sb, key);
                sb.append(':');
                writeCanonical(sb, ((Map<Object, Object>) raw).get(key));
                first = false;
            }
            sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                writeCanonical(sb, item);
                first = false;
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize " + v.getClass());
        }
    }

    private static String numberText(Object v) {
        if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) throw new IllegalArgumentException("non-finite number");
            double rint = Math.rint(d);
            if (d == rint && !Double.isInfinite(d) && Math.abs(d) < 9.007199254740992e15) {
                return Long.toString((long) rint);
            }
            return Double.toString(d);
        }
        return v.toString();
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

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) { this.text = text; }

        boolean eof() { return pos >= text.length(); }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at position " + pos);
        }

        void skipWs() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object readValue() {
            if (eof()) throw error("unexpected end");
            char c = text.charAt(pos);
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
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWs();
                if (peek() != '"') throw error("expected key");
                String key = readString();
                skipWs();
                if (pop() != ':') throw error("expected ':'");
                skipWs();
                map.put(key, readValue());
                skipWs();
                char c = pop();
                if (c == '}') return map;
                if (c != ',') throw error("expected ',' or '}'");
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                skipWs();
                list.add(readValue());
                skipWs();
                char c = pop();
                if (c == ']') return list;
                if (c != ',') throw error("expected ',' or ']'");
            }
        }

        String readString() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw error("unterminated string");
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (eof()) throw error("bad escape");
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
                            if (pos + 4 > text.length()) throw error("bad unicode escape");
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("bad escape");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Boolean readBoolean() {
            if (text.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (text.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw error("invalid token");
        }

        Object readNull() {
            if (text.startsWith("null", pos)) { pos += 4; return null; }
            throw error("invalid token");
        }

        Object readNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            readDigits();
            boolean floating = false;
            if (!eof() && peek() == '.') {
                floating = true;
                pos++;
                readDigits();
            }
            if (!eof() && (peek() == 'e' || peek() == 'E')) {
                floating = true;
                pos++;
                if (peek() == '+' || peek() == '-') pos++;
                readDigits();
            }
            String token = text.substring(start, pos);
            if (token.isEmpty() || token.equals("-")) throw error("invalid number");
            return floating ? Double.valueOf(token) : Long.valueOf(token);
        }

        void readDigits() {
            int start = pos;
            while (!eof() && Character.isDigit(peek())) pos++;
            if (pos == start) throw error("expected digits");
        }

        char peek() { return text.charAt(pos); }
        char pop() { return text.charAt(pos++); }
    }
}
