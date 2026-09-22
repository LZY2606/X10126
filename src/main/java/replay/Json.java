package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal dependency-free JSON parser/serializer with canonical output. */
public final class Json {

    private Json() {}

    // ---------- types ----------
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new IllegalArgumentException("expected JSON object, got: " + typeName(o));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        if (o instanceof List) return (List<Object>) o;
        throw new IllegalArgumentException("expected JSON array, got: " + typeName(o));
    }

    public static String str(Object o) {
        if (o instanceof String) return (String) o;
        throw new IllegalArgumentException("expected JSON string, got: " + typeName(o));
    }

    public static String str(Object o, String dflt) {
        return o instanceof String ? (String) o : dflt;
    }

    public static long num(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        throw new IllegalArgumentException("expected JSON number, got: " + typeName(o));
    }

    public static long num(Object o, long dflt) {
        return o instanceof Number ? ((Number) o).longValue() : dflt;
    }

    public static boolean bool(Object o, boolean dflt) {
        return o instanceof Boolean ? (Boolean) o : dflt;
    }

    private static String typeName(Object o) {
        if (o == null) return "null";
        return o.getClass().getSimpleName();
    }

    public static Map<String, Object> newObj() {
        return new LinkedHashMap<>();
    }

    public static List<Object> newArr() {
        return new ArrayList<>();
    }

    // ---------- deep copy ----------
    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object o) {
        if (o instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                copy.put(e.getKey(), deepCopy(e.getValue()));
            }
            return copy;
        }
        if (o instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object v : (List<Object>) o) copy.add(deepCopy(v));
            return copy;
        }
        return o;
    }

    // ---------- canonical serialization (sorted keys, no whitespace) ----------
    public static String canonical(Object o) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(o, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeCanonical(Object o, StringBuilder sb) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            writeString((String) o, sb);
        } else if (o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Number) {
            writeNumber((Number) o, sb);
        } else if (o instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>((Map<String, Object>) o);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(e.getKey(), sb);
                sb.append(':');
                writeCanonical(e.getValue(), sb);
            }
            sb.append('}');
        } else if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object v : (List<Object>) o) {
                if (!first) sb.append(',');
                first = false;
                writeCanonical(v, sb);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("not JSON-serializable: " + o.getClass());
        }
    }

    /** Pretty serialization preserving insertion order. */
    public static String pretty(Object o) {
        StringBuilder sb = new StringBuilder();
        writePretty(o, sb, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writePretty(Object o, StringBuilder sb, int indent) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            writeString((String) o, sb);
        } else if (o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Number) {
            writeNumber((Number) o, sb);
        } else if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                pad(sb, indent + 1);
                writeString(e.getKey(), sb);
                sb.append(": ");
                writePretty(e.getValue(), sb, indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append('}');
        } else if (o instanceof List) {
            List<Object> l = (List<Object>) o;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            boolean first = true;
            for (Object v : l) {
                if (!first) sb.append(",\n");
                first = false;
                pad(sb, indent + 1);
                writePretty(v, sb, indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("not JSON-serializable: " + o.getClass());
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append("  ");
    }

    private static void writeNumber(Number n, StringBuilder sb) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(n.toString());
            }
        } else {
            sb.append(n.toString());
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---------- parsing ----------
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) throw new IllegalArgumentException("trailing characters at offset " + p.pos);
        return v;
    }

    public static Map<String, Object> parseObj(String text) {
        return obj(parse(text));
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object parseValue() {
            skipWs();
            if (atEnd()) throw error("unexpected end of input");
            char c = s.charAt(pos);
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

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw error("expected '" + lit + "'");
            pos += lit.length();
        }

        private Map<String, Object> parseObject() {
            pos++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (!atEnd() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                if (atEnd() || s.charAt(pos) != ':') throw error("expected ':'");
                pos++;
                Object v = parseValue();
                m.put(key, v);
                skipWs();
                if (atEnd()) throw error("unterminated object");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return m; }
                throw error("expected ',' or '}'");
            }
        }

        private List<Object> parseArray() {
            pos++; // [
            List<Object> l = new ArrayList<>();
            skipWs();
            if (!atEnd() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                l.add(parseValue());
                skipWs();
                if (atEnd()) throw error("unterminated array");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return l; }
                throw error("expected ',' or ']'");
            }
        }

        private String parseString() {
            if (atEnd() || s.charAt(pos) != '"') throw error("expected string");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw error("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (atEnd()) throw error("unterminated escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (pos + 4 > s.length()) throw error("bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw error("bad escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Number parseNumber() {
            int start = pos;
            if (!atEnd() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            boolean isDouble = false;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') { isDouble = true; pos++; }
                else break;
            }
            if (start == pos) throw error("expected value");
            String num = s.substring(start, pos);
            try {
                if (isDouble) return Double.parseDouble(num);
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw error("bad number '" + num + "'");
            }
        }

        private IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON parse error at offset " + pos + ": " + msg);
        }
    }
}
