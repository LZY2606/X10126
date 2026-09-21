package replay.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal dependency-free JSON parser/serializer with canonical (sorted-key) output for hashing. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        return new Parser(text).parse();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected JSON object");
        return (Map<String, Object>) v;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, false);
        return sb.toString();
    }

    /** Canonical form: object keys sorted, numbers normalized. Used for fingerprints. */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, true);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, StringBuilder sb, boolean canonical) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            quote(s, sb);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            sb.append(value);
        } else if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("non-finite number");
            sb.append(BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
        } else if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            sb.append('{');
            boolean first = true;
            Iterable<Map.Entry<String, Object>> entries = canonical
                    ? new TreeMap<>(map).entrySet() : map.entrySet();
            for (Map.Entry<String, Object> e : entries) {
                if (!first) sb.append(',');
                first = false;
                quote(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb, canonical);
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) value) {
                if (!first) sb.append(',');
                first = false;
                write(o, sb, canonical);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize: " + value.getClass());
        }
    }

    private static void quote(String s, StringBuilder sb) {
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
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        Object parse() {
            Object v = value();
            ws();
            if (pos != s.length()) throw err("trailing content");
            return v;
        }

        private void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private Object value() {
            ws();
            if (pos >= s.length()) throw err("unexpected end");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> { expect("true"); yield Boolean.TRUE; }
                case 'f' -> { expect("false"); yield Boolean.FALSE; }
                case 'n' -> { expect("null"); yield null; }
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            ws();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                ws();
                if (pos >= s.length() || s.charAt(pos) != '"') throw err("expected key");
                String key = string();
                ws();
                if (pos >= s.length() || s.charAt(pos) != ':') throw err("expected ':'");
                pos++;
                map.put(key, value());
                ws();
                if (pos >= s.length()) throw err("unexpected end in object");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return map; }
                throw err("expected ',' or '}'");
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++;
            ws();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(value());
                ws();
                if (pos >= s.length()) throw err("unexpected end in array");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                throw err("expected ',' or ']'");
            }
        }

        private String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw err("bad escape");
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
                            if (pos + 4 > s.length()) throw err("bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw err("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("unterminated string");
        }

        private Object number() {
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            boolean floating = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) { pos++; continue; }
                if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    if (c == '.' || c == 'e' || c == 'E') floating = true;
                    pos++;
                    continue;
                }
                break;
            }
            if (start == pos) throw err("unexpected character '" + s.charAt(pos) + "'");
            String num = s.substring(start, pos);
            try {
                if (floating) return Double.parseDouble(num);
                return Long.parseLong(num);
            } catch (NumberFormatException ex) {
                throw err("bad number '" + num + "'");
            }
        }

        private void expect(String word) {
            if (!s.startsWith(word, pos)) throw err("expected '" + word + "'");
            pos += word.length();
        }

        private IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("JSON parse error at " + pos + ": " + msg);
        }
    }
}
