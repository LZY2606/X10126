package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal dependency-free JSON parser/serializer with canonical (sorted-key) output for hashing. */
public final class Json {
    private Json() {}

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
    }

    public static Object parse(String text) {
        Cursor c = new Cursor(text);
        Object value = readValue(c);
        c.ws();
        if (!c.end()) throw new JsonException("unexpected trailing content at offset " + c.pos);
        return value;
    }

    public static String write(Object value) { return render(value, false); }

    public static String canonical(Object value) { return render(value, true); }

    private static String render(Object value, boolean canonical) {
        StringBuilder sb = new StringBuilder();
        renderInto(value, canonical, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void renderInto(Object value, boolean canonical, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            quote(s, sb);
        } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer) {
            sb.append(value);
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) throw new JsonException("non-finite double");
            sb.append(Double.toString(d));
        } else if (value instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) value;
            if (canonical) m = new TreeMap<>(m);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(e.getKey(), sb);
                sb.append(':');
                renderInto(e.getValue(), canonical, sb);
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) value) {
                if (!first) sb.append(',');
                first = false;
                renderInto(o, canonical, sb);
            }
            sb.append(']');
        } else {
            throw new JsonException("cannot serialize " + value.getClass());
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        if (v instanceof Map) return (Map<String, Object>) v;
        throw new JsonException("expected object, got " + v);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        if (v instanceof List) return (List<Object>) v;
        throw new JsonException("expected array, got " + v);
    }

    public static String str(Object v) {
        if (v instanceof String s) return s;
        throw new JsonException("expected string, got " + v);
    }

    public static long num(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Double d) return d.longValue();
        throw new JsonException("expected number, got " + v);
    }

    @SuppressWarnings("unchecked")
    public static Object copy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                out.put(e.getKey(), copy(e.getValue()));
            }
            return out;
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<Object>) v) out.add(copy(o));
            return out;
        }
        return v;
    }

    private static final class Cursor {
        final String s;
        int pos;

        Cursor(String s) { this.s = s; }

        boolean end() { return pos >= s.length(); }

        void ws() { while (!end() && Character.isWhitespace(s.charAt(pos))) pos++; }

        void expect(char c) {
            if (end() || s.charAt(pos) != c) {
                throw new JsonException("expected '" + c + "' at offset " + pos);
            }
            pos++;
        }
    }

    private static Object readValue(Cursor c) {
        c.ws();
        if (c.end()) throw new JsonException("unexpected end of input");
        char ch = c.s.charAt(c.pos);
        switch (ch) {
            case '{': return readObject(c);
            case '[': return readArray(c);
            case '"': return readString(c);
            case 't': expectWord(c, "true"); return Boolean.TRUE;
            case 'f': expectWord(c, "false"); return Boolean.FALSE;
            case 'n': expectWord(c, "null"); return null;
            default: return readNumber(c);
        }
    }

    private static void expectWord(Cursor c, String word) {
        if (!c.s.startsWith(word, c.pos)) throw new JsonException("invalid literal at offset " + c.pos);
        c.pos += word.length();
    }

    private static Map<String, Object> readObject(Cursor c) {
        Map<String, Object> map = new LinkedHashMap<>();
        c.expect('{');
        c.ws();
        if (!c.end() && c.s.charAt(c.pos) == '}') { c.pos++; return map; }
        while (true) {
            c.ws();
            String key = readString(c);
            c.ws();
            c.expect(':');
            map.put(key, readValue(c));
            c.ws();
            if (c.end()) throw new JsonException("unterminated object");
            char ch = c.s.charAt(c.pos);
            if (ch == ',') { c.pos++; continue; }
            if (ch == '}') { c.pos++; return map; }
            throw new JsonException("expected ',' or '}' at offset " + c.pos);
        }
    }

    private static List<Object> readArray(Cursor c) {
        List<Object> list = new ArrayList<>();
        c.expect('[');
        c.ws();
        if (!c.end() && c.s.charAt(c.pos) == ']') { c.pos++; return list; }
        while (true) {
            list.add(readValue(c));
            c.ws();
            if (c.end()) throw new JsonException("unterminated array");
            char ch = c.s.charAt(c.pos);
            if (ch == ',') { c.pos++; continue; }
            if (ch == ']') { c.pos++; return list; }
            throw new JsonException("expected ',' or ']' at offset " + c.pos);
        }
    }

    private static String readString(Cursor c) {
        c.expect('"');
        StringBuilder sb = new StringBuilder();
        while (!c.end()) {
            char ch = c.s.charAt(c.pos++);
            if (ch == '"') return sb.toString();
            if (ch == '\\') {
                if (c.end()) break;
                char esc = c.s.charAt(c.pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(c.s.substring(c.pos, c.pos + 4), 16));
                        c.pos += 4;
                    }
                    default -> throw new JsonException("bad escape \\" + esc);
                }
            } else {
                sb.append(ch);
            }
        }
        throw new JsonException("unterminated string");
    }

    private static Object readNumber(Cursor c) {
        int start = c.pos;
        if (!c.end() && c.s.charAt(c.pos) == '-') c.pos++;
        boolean isDouble = false;
        while (!c.end()) {
            char ch = c.s.charAt(c.pos);
            if (Character.isDigit(ch)) { c.pos++; continue; }
            if (ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') {
                if (ch == '.' || ch == 'e' || ch == 'E') isDouble = true;
                c.pos++;
                continue;
            }
            break;
        }
        if (start == c.pos) throw new JsonException("expected value at offset " + start);
        String text = c.s.substring(start, c.pos);
        try {
            return isDouble ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new JsonException("bad number '" + text + "'");
        }
    }
}
