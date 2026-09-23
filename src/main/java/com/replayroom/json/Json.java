package com.replayroom.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Minimal self-contained JSON parser/serializer with canonical (sorted-key) output. */
public final class Json {
    private Json() {}

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
    }

    // ---------- parsing ----------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.value();
        p.ws();
        if (!p.eof()) throw new JsonException("trailing content at offset " + p.pos);
        return v;
    }

    private static final class Parser {
        final String s; int pos; final int len;
        Parser(String s) { this.s = s; this.len = s.length(); }
        boolean eof() { return pos >= len; }
        void ws() { while (!eof() && Character.isWhitespace(s.charAt(pos))) pos++; }
        char peek() { if (eof()) throw new JsonException("unexpected end of input"); return s.charAt(pos); }
        char next() { char c = peek(); pos++; return c; }
        void expect(char c) { if (next() != c) throw new JsonException("expected '" + c + "' at offset " + (pos - 1)); }

        Object value() {
            ws();
            char c = peek();
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': literal("true"); return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null"); return null;
                default: return number();
            }
        }

        void literal(String lit) {
            if (!s.startsWith(lit, pos)) throw new JsonException("invalid literal at offset " + pos);
            pos += lit.length();
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                ws();
                String k = string();
                ws(); expect(':');
                m.put(k, value());
                ws();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw new JsonException("expected ',' or '}' at offset " + (pos - 1));
            }
        }

        List<Object> array() {
            expect('[');
            List<Object> l = new ArrayList<>();
            ws();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                l.add(value());
                ws();
                char c = next();
                if (c == ']') return l;
                if (c != ',') throw new JsonException("expected ',' or ']' at offset " + (pos - 1));
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new JsonException("unterminated string");
                char c = next();
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = next();
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
                            if (pos + 4 > len) throw new JsonException("bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new JsonException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            if (!eof() && s.charAt(pos) == '-') pos++;
            while (!eof() && Character.isDigit(s.charAt(pos))) pos++;
            boolean dbl = false;
            if (!eof() && s.charAt(pos) == '.') {
                dbl = true; pos++;
                while (!eof() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (!eof() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                dbl = true; pos++;
                if (!eof() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (!eof() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (start == pos) throw new JsonException("invalid value at offset " + start);
            String txt = s.substring(start, pos);
            try {
                if (dbl) return Double.parseDouble(txt);
                return Long.parseLong(txt);
            } catch (NumberFormatException e) {
                try { return Double.parseDouble(txt); }
                catch (NumberFormatException e2) { throw new JsonException("bad number '" + txt + "'"); }
            }
        }
    }

    // ---------- writing ----------

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeInto(sb, v, false, 0);
        return sb.toString();
    }

    /** Canonical form: object keys sorted. Used for hashing. */
    public static String canonical(Object v) {
        StringBuilder sb = new StringBuilder();
        writeInto(sb, v, true, 0);
        return sb.toString();
    }

    public static String pretty(Object v) {
        StringBuilder sb = new StringBuilder();
        prettyInto(sb, v, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeInto(StringBuilder sb, Object v, boolean sortKeys, int indent) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String str) { quote(sb, str); return; }
        if (v instanceof Boolean b) { sb.append(b); return; }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) { sb.append(v); return; }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new JsonException("non-finite number");
            if (d == Math.rint(d) && Math.abs(d) < 1e15) { sb.append((long) d); return; }
            sb.append(d);
            return;
        }
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (sortKeys) m = new TreeMap<>(m);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                writeInto(sb, e.getValue(), sortKeys, indent);
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                writeInto(sb, o, sortKeys, indent);
            }
            sb.append(']');
            return;
        }
        throw new JsonException("cannot serialize " + v.getClass());
    }

    @SuppressWarnings("unchecked")
    private static void prettyInto(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append("  ".repeat(indent + 1));
                quote(sb, e.getKey());
                sb.append(": ");
                prettyInto(sb, e.getValue(), indent + 1);
            }
            sb.append('\n').append("  ".repeat(indent)).append('}');
            return;
        }
        if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append("  ".repeat(indent + 1));
                prettyInto(sb, o, indent + 1);
            }
            sb.append('\n').append("  ".repeat(indent)).append(']');
            return;
        }
        writeInto(sb, v, false, indent);
    }

    private static void quote(StringBuilder sb, String s) {
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

    // ---------- typed accessors ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v) {
        if (!(v instanceof Map)) throw new JsonException("expected object, got " + typeName(v));
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object v) {
        if (!(v instanceof List)) throw new JsonException("expected array, got " + typeName(v));
        return (List<Object>) v;
    }

    public static String asString(Object v) {
        if (!(v instanceof String)) throw new JsonException("expected string, got " + typeName(v));
        return (String) v;
    }

    public static long asLong(Object v) {
        if (!(v instanceof Number)) throw new JsonException("expected number, got " + typeName(v));
        return ((Number) v).longValue();
    }

    public static boolean asBool(Object v) {
        if (!(v instanceof Boolean)) throw new JsonException("expected boolean, got " + typeName(v));
        return (Boolean) v;
    }

    public static String optString(Map<String, Object> m, String key, String dflt) {
        Object v = m.get(key);
        return v == null ? dflt : asString(v);
    }

    public static long optLong(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        return v == null ? dflt : asLong(v);
    }

    private static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof Map) return "object";
        if (v instanceof List) return "array";
        return v.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                copy.put(e.getKey(), deepCopy(e.getValue()));
            }
            return copy;
        }
        if (v instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object o : (List<Object>) v) copy.add(deepCopy(o));
            return copy;
        }
        return v;
    }
}
