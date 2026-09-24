package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON：解析为 Map/List/String/Double/Boolean/null。
 * 提供确定性规范化序列化（对象键按 UTF-16 排序、无空白）供指纹使用。
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

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, v, 0);
        return sb.toString();
    }

    /** 规范化序列化：对象键排序、无多余空白、数字稳定格式。 */
    public static String canonical(Object v) {
        StringBuilder sb = new StringBuilder();
        writeCanon(sb, v);
        return sb.toString();
    }

    private static void writePretty(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(number(v));
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                indent(sb, indent + 1);
                writeString(sb, e.getKey());
                sb.append(": ");
                writePretty(sb, e.getValue(), indent + 1);
                if (++i < m.size()) sb.append(',');
                sb.append('\n');
            }
            indent(sb, indent);
            sb.append('}');
        } else if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                indent(sb, indent + 1);
                writePretty(sb, l.get(i), indent + 1);
                if (i < l.size() - 1) sb.append(',');
                sb.append('\n');
            }
            indent(sb, indent);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize " + v.getClass());
        }
    }

    private static void writeCanon(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(number(v));
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            List<String> keys = new ArrayList<>(m.keySet());
            keys.sort(String::compareTo);
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                writeString(sb, keys.get(i));
                sb.append(':');
                writeCanon(sb, m.get(keys.get(i)));
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            List<?> l = (List<?>) v;
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                writeCanon(sb, l.get(i));
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize " + v.getClass());
        }
    }

    private static String number(Object v) {
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d))
                throw new IllegalArgumentException("non-finite number");
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                long l = (long) d;
                if ((double) l == d) return Long.toString(l);
            }
            String s = Double.toString(d);
            return s;
        }
        return v.toString();
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
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append("  ");
    }

    // ---------- 读取辅助 ----------

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        if (v == null) return new LinkedHashMap<>();
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected object");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object v) {
        if (v == null) return new ArrayList<>();
        if (!(v instanceof List)) throw new IllegalArgumentException("expected array");
        return (List<Object>) v;
    }

    public static Map<String, Object> newObj() { return new LinkedHashMap<>(); }

    private static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        boolean eof() { return i >= s.length(); }
        char peek() { return s.charAt(i); }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON error at " + i + ": " + msg);
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object readValue() {
            skipWs();
            if (eof()) throw error("unexpected end");
            char c = peek();
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBool();
            if (c == 'n') return readNull();
            return readNumber();
        }

        Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            skipWs();
            if (!eof() && peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                if (eof() || peek() != '"') throw error("expected string key");
                String key = readString();
                skipWs();
                if (eof() || peek() != ':') throw error("expected ':'");
                i++;
                Object v = readValue();
                m.put(key, v);
                skipWs();
                if (eof()) throw error("unterminated object");
                c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw error("expected ',' or '}'");
            }
        }

        List<Object> readArray() {
            List<Object> l = new ArrayList<>();
            i++;
            skipWs();
            if (!eof() && peek() == ']') { i++; return l; }
            while (true) {
                Object v = readValue();
                l.add(v);
                skipWs();
                if (eof()) throw error("unterminated array");
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return l; }
                throw error("expected ',' or ']'");
            }
        }

        String readString() {
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw error("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (eof()) throw error("bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw error("bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw error("bad escape char");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Boolean readBool() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw error("bad literal");
        }

        Object readNull() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw error("bad literal");
        }

        Object readNumber() {
            int start = i;
            if (!eof() && (peek() == '-' || peek() == '+')) i++;
            while (!eof()) {
                char c = peek();
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                else break;
            }
            String t = s.substring(start, i);
            try {
                if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
                long l = Long.parseLong(t);
                if (l >= -9007199254740991L && l <= 9007199254740991L) return (double) l;
            } catch (NumberFormatException ex) {
                throw error("bad number " + t);
            }
        }
    }
}
