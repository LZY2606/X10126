package sm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal dependency-free JSON parser/writer with a canonical form used for stable fingerprints. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.parseValue();
        p.skipWs();
        if (p.pos != p.text.length()) throw new IllegalArgumentException("trailing characters in JSON at " + p.pos);
        return v;
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v, false);
        return sb.toString();
    }

    /** Canonical form: object keys sorted, no whitespace. Stable across runs for equal content. */
    public static String canonical(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v, true);
        return sb.toString();
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        if (v == null) return new LinkedHashMap<>();
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected object, got " + v);
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        if (v == null) return new ArrayList<>();
        if (!(v instanceof List)) throw new IllegalArgumentException("expected array, got " + v);
        return (List<Object>) v;
    }

    public static String str(Object v) {
        return v == null ? null : v.toString();
    }

    public static long num(Object v, long dflt) {
        if (v instanceof Number) return ((Number) v).longValue();
        return dflt;
    }

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                out.put(e.getKey(), deepCopy(e.getValue()));
            }
            return out;
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<Object>) v) out.add(deepCopy(o));
            return out;
        }
        return v;
    }

    private static void writeValue(StringBuilder sb, Object v, boolean canonical) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Number) {
            sb.append(formatNumber((Number) v));
        } else if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            if (canonical) m = new TreeMap<>(m);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue(), canonical);
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, o, canonical);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize " + v.getClass());
        }
    }

    private static String formatNumber(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            return Double.toString(d);
        }
        return n.toString();
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
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
            skipWs();
            if (pos >= text.length()) throw new IllegalArgumentException("unexpected end of JSON");
            char c = text.charAt(pos);
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return parseNumber();
            }
        }

        void expect(String s) {
            if (!text.startsWith(s, pos)) throw new IllegalArgumentException("expected " + s + " at " + pos);
            pos += s.length();
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
                if (text.charAt(pos) != ':') throw new IllegalArgumentException("expected ':' at " + pos);
                pos++;
                m.put(key, parseValue());
                skipWs();
                char c = text.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return m; }
                throw new IllegalArgumentException("expected ',' or '}' at " + pos);
            }
        }

        List<Object> parseArray() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (pos < text.length() && text.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                l.add(parseValue());
                skipWs();
                char c = text.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return l; }
                throw new IllegalArgumentException("expected ',' or ']' at " + pos);
            }
        }

        String parseString() {
            if (text.charAt(pos) != '"') throw new IllegalArgumentException("expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Number parseNumber() {
            int start = pos;
            if (pos < text.length() && text.charAt(pos) == '-') pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            boolean floating = false;
            if (pos < text.length() && text.charAt(pos) == '.') {
                floating = true;
                pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                floating = true;
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            }
            if (start == pos) throw new IllegalArgumentException("bad number at " + pos);
            String s = text.substring(start, pos);
            try {
                return floating ? (Number) Double.parseDouble(s) : (Number) Long.parseLong(s);
            } catch (NumberFormatException e) {
                return Double.parseDouble(s);
            }
        }
    }
}
