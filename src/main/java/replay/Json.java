package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal self-contained JSON parser/writer with a canonical form used for hashing. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != p.s.length()) throw err("trailing content at " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o, String what) {
        if (!(o instanceof Map)) throw new IllegalArgumentException(what + " must be an object");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o, String what) {
        if (!(o instanceof List)) throw new IllegalArgumentException(what + " must be an array");
        return (List<Object>) o;
    }

    public static String str(Map<String, Object> m, String key, String dflt) {
        Object v = m.get(key);
        return v == null ? dflt : String.valueOf(v);
    }

    public static long lng(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        if (v == null) return dflt;
        if (v instanceof Number) return ((Number) v).longValue();
        throw new IllegalArgumentException(key + " must be a number");
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        writeTo(o, sb, false, 0);
        return sb.toString();
    }

    /** Canonical: sorted keys, no whitespace, integral doubles as integers. Stable across runs. */
    public static String canonical(Object o) {
        StringBuilder sb = new StringBuilder();
        writeTo(o, sb, true, 0);
        return sb.toString();
    }

    private static void writeTo(Object o, StringBuilder sb, boolean canon, int indent) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { quote((String) o, sb); return; }
        if (o instanceof Boolean) { sb.append(o); return; }
        if (o instanceof Long || o instanceof Integer) { sb.append(((Number) o).longValue()); return; }
        if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 9.0e15) sb.append((long) d);
            else sb.append(Double.toString(d));
            return;
        }
        if (o instanceof Map) {
            Map<String, Object> m = canon ? new TreeMap<>((Map<String, Object>) o) : (Map<String, Object>) o;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                if (!canon) nl(sb, indent + 1);
                quote(e.getKey(), sb);
                sb.append(canon ? ":" : ": ");
                writeTo(e.getValue(), sb, canon, indent + 1);
            }
            if (!canon && !m.isEmpty()) nl(sb, indent);
            sb.append('}');
            return;
        }
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            sb.append('[');
            boolean first = true;
            for (Object v : l) {
                if (!first) sb.append(',');
                first = false;
                if (!canon) nl(sb, indent + 1);
                writeTo(v, sb, canon, indent + 1);
            }
            if (!canon && !l.isEmpty()) nl(sb, indent);
            sb.append(']');
            return;
        }
        throw new IllegalArgumentException("not JSON-serializable: " + o.getClass());
    }

    private static void nl(StringBuilder sb, int indent) {
        sb.append('\n');
        sb.append("  ".repeat(Math.max(0, indent)));
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

    static IllegalArgumentException err(String msg) { return new IllegalArgumentException("JSON: " + msg); }

    private static final class Parser {
        final String s; int pos;
        Parser(String s) { this.s = s; }
        void ws() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }
        Object value() {
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
        void expect(String w) {
            if (!s.startsWith(w, pos)) throw err("expected " + w + " at " + pos);
            pos += w.length();
        }
        Map<String, Object> object() {
            pos++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (pos >= s.length() || s.charAt(pos) != ':') throw err("expected ':' at " + pos);
                pos++;
                ws();
                m.put(k, value());
                ws();
                if (pos >= s.length()) throw err("unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw err("expected ',' at " + (pos - 1));
            }
        }
        List<Object> array() {
            pos++;
            List<Object> l = new ArrayList<>();
            ws();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (pos >= s.length()) throw err("unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw err("expected ',' at " + (pos - 1));
            }
        }
        String string() {
            if (pos >= s.length() || s.charAt(pos) != '"') throw err("expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) break;
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
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw err("bad escape \\" + e);
                    }
                } else sb.append(c);
            }
            throw err("unterminated string");
        }
        Object number() {
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            boolean dbl = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') { dbl = true; pos++; }
                else break;
            }
            if (start == pos) throw err("unexpected '" + s.charAt(pos) + "' at " + pos);
            String n = s.substring(start, pos);
            try {
                return dbl ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
            } catch (NumberFormatException e) {
                throw err("bad number " + n);
            }
        }
    }
}
