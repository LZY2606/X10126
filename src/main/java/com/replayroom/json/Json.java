package com.replayroom.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal dependency-free JSON parser/serializer with a canonical (sorted-key) mode. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        return new Parser(text).parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new JsonException("expected JSON object");
        return (Map<String, Object>) v;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, false);
        return sb.toString();
    }

    /** Canonical form: object keys sorted, no whitespace. Used for all fingerprints/hashes. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, true);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, boolean canonical) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Number n) {
            writeNumber(sb, n);
        } else if (value instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            Iterable<? extends Map.Entry<?, ?>> entries = canonical
                    ? new TreeMap<>((Map<String, Object>) m).entrySet()
                    : m.entrySet();
            for (Map.Entry<?, ?> e : entries) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue(), canonical);
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item, canonical);
            }
            sb.append(']');
        } else {
            throw new JsonException("cannot serialize " + value.getClass());
        }
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        double d = n.doubleValue();
        if (n instanceof Double || n instanceof Float) {
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new JsonException("non-finite number");
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else {
            sb.append(n);
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String msg) { super(msg); }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        Object parseValue() {
            skipWs();
            Object v = readValue();
            skipWs();
            if (pos != s.length()) throw error("trailing characters");
            return v;
        }

        private Object readValue() {
            skipWs();
            if (pos >= s.length()) throw error("unexpected end of input");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) { pos++; return map; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                map.put(key, readValue());
                skipWs();
                if (peek(',')) { pos++; continue; }
                expect('}');
                return map;
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek(']')) { pos++; return list; }
            while (true) {
                list.add(readValue());
                skipWs();
                if (peek(',')) { pos++; continue; }
                expect(']');
                return list;
            }
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw error("bad escape");
                    char e = s.charAt(pos++);
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
                            if (pos + 4 > s.length()) throw error("bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("unterminated string");
        }

        private Object readLiteral(String lit, Object value) {
            if (s.startsWith(lit, pos)) {
                pos += lit.length();
                return value;
            }
            throw error("invalid literal");
        }

        private Object readNumber() {
            int start = pos;
            if (peek('-')) pos++;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.'
                    || s.charAt(pos) == 'e' || s.charAt(pos) == 'E'
                    || ((s.charAt(pos) == '+' || s.charAt(pos) == '-') && pos > start
                        && (s.charAt(pos - 1) == 'e' || s.charAt(pos - 1) == 'E')))) {
                pos++;
            }
            if (pos == start) throw error("unexpected character '" + s.charAt(pos) + "'");
            String num = s.substring(start, pos);
            try {
                if (num.contains(".") || num.contains("e") || num.contains("E")) {
                    return Double.parseDouble(num);
                }
                long l = Long.parseLong(num);
                return l;
            } catch (NumberFormatException ex) {
                throw error("bad number '" + num + "'");
            }
        }

        private boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        private void expect(char c) {
            skipWs();
            if (!peek(c)) throw error("expected '" + c + "'");
            pos++;
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private JsonException error(String msg) {
            return new JsonException(msg + " at position " + pos);
        }
    }
}
