package room.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零外部依赖的 JSON 解析 / 序列化工具。
 * 规范序列化按键排序、无多余空白，用于确定性指纹。
 */
public final class Json {

    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (!p.eof()) throw new IllegalArgumentException("JSON 解析失败: 位置 " + p.pos + " 有多余字符");
        return v;
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, v);
        return sb.toString();
    }

    public static String canonical(Object v) {
        StringBuilder sb = new StringBuilder();
        canonicalTo(sb, v);
        return sb.toString();
    }

    public static String pretty(Object v) {
        StringBuilder sb = new StringBuilder();
        prettyTo(sb, v, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        if (v instanceof Map) return (Map<String, Object>) v;
        throw new IllegalArgumentException("期望 JSON 对象，实际为 " + typeName(v));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        if (v instanceof List) return (List<Object>) v;
        throw new IllegalArgumentException("期望 JSON 数组，实际为 " + typeName(v));
    }

    public static String str(Object v, String def) {
        return v instanceof String ? (String) v : def;
    }

    public static String reqStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String)) throw new IllegalArgumentException("缺少字符串字段 " + key);
        return (String) v;
    }

    public static Map<String, Object> newObj() {
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            Map<String, Object> c = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) c.put(e.getKey(), deepCopy(e.getValue()));
            return c;
        }
        if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            List<Object> c = new ArrayList<>(l.size());
            for (Object o : l) c.add(deepCopy(o));
            return c;
        }
        return v;
    }

    public static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Long) return "integer";
        if (v instanceof Double) return "number";
        if (v instanceof String) return "string";
        if (v instanceof Map) return "object";
        if (v instanceof List) return "array";
        return v.getClass().getSimpleName();
    }

    /** 取点分路径；任一节点缺失或非对象时返回 null。 */
    @SuppressWarnings("unchecked")
    public static Object getPath(Object root, String path) {
        if (path == null || path.isEmpty()) return root;
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (cur instanceof Map) cur = ((Map<String, Object>) cur).get(part);
            else return null;
        }
        return cur;
    }

    /** 沿点分路径建 Map（自动补齐），返回最末 Map；如中途撞到非 Map 抛异常。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> ensureMap(Map<String, Object> root, String path) {
        Map<String, Object> cur = root;
        if (path == null || path.isEmpty()) return cur;
        for (String part : path.split("\\.")) {
            Object next = cur.get(part);
            if (next == null) {
                Map<String, Object> n = new LinkedHashMap<>();
                cur.put(part, n);
                cur = n;
            } else if (next instanceof Map) {
                cur = (Map<String, Object>) next;
            } else {
                throw new IllegalArgumentException("路径 " + path + " 与现有标量冲突");
            }
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    static void writeTo(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean) sb.append(v);
        else if (v instanceof Long || v instanceof Double) sb.append(num(v));
        else if (v instanceof String) writeString(sb, (String) v);
        else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, o);
            }
            sb.append(']');
        } else throw new IllegalArgumentException("无法序列化类型 " + v.getClass());
    }

    @SuppressWarnings("unchecked")
    static void canonicalTo(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean) sb.append(v);
        else if (v instanceof Long || v instanceof Double) sb.append(num(v));
        else if (v instanceof String) writeString(sb, (String) v);
        else if (v instanceof Map) {
            sb.append('{');
            ((Map<String, Object>) v).keySet().stream().sorted().forEach(k -> {
                if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
                writeString(sb, k);
                sb.append(':');
                canonicalTo(sb, ((Map<String, Object>) v).get(k));
            });
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                canonicalTo(sb, o);
            }
            sb.append(']');
        } else throw new IllegalArgumentException("无法规范化类型 " + v.getClass());
    }

    @SuppressWarnings("unchecked")
    static void prettyTo(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map && !((Map<String, Object>) v).isEmpty()) {
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                pad(sb, indent + 1);
                writeString(sb, e.getKey());
                sb.append(": ");
                prettyTo(sb, e.getValue(), indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof List && !((List<Object>) v).isEmpty()) {
            sb.append("[\n");
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(",\n");
                first = false;
                pad(sb, indent + 1);
                prettyTo(sb, o, indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append(']');
        } else {
            writeTo(sb, v);
        }
    }

    static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    static String num(Object v) {
        if (v instanceof Long) return v.toString();
        double d = (Double) v;
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9007199254740992.0) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
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

    static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        boolean eof() { return pos >= s.length(); }

        char peek() {
            if (eof()) throw new IllegalArgumentException("JSON 意外结束，位置 " + pos);
            return s.charAt(pos);
        }

        void skipWs() {
            while (!eof() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        void expect(char c) {
            if (eof() || s.charAt(pos) != c)
                throw new IllegalArgumentException("JSON 解析失败: 位置 " + pos + " 期望 '" + c + "'");
            pos++;
        }

        Object value() {
            skipWs();
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't', 'f' -> bool();
                case 'n' -> nul();
                default -> number();
            };
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String k = string();
                skipWs();
                expect(':');
                Object v = value();
                m.put(k, v);
                skipWs();
                char c = peek();
                pos++;
                if (c == '}') break;
                if (c != ',') throw new IllegalArgumentException("JSON 解析失败: 位置 " + pos + " 期望 ',' 或 '}'");
            }
            return m;
        }

        List<Object> array() {
            expect('[');
            List<Object> l = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                l.add(value());
                skipWs();
                char c = peek();
                pos++;
                if (c == ']') break;
                if (c != ',') throw new IllegalArgumentException("JSON 解析失败: 位置 " + pos + " 期望 ',' 或 ']'");
            }
            return l;
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
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
                            int code = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            pos += 4;
                            sb.append((char) code);
                        }
                        default -> throw new IllegalArgumentException("JSON 非法转义 \\" + e);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }

        Boolean bool() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("JSON 解析失败: 位置 " + pos);
        }

        Object nul() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw new IllegalArgumentException("JSON 解析失败: 位置 " + pos);
        }

        Object number() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!eof() && (Character.isDigit(s.charAt(pos)) || ".eE+-".indexOf(s.charAt(pos)) >= 0)) pos++;
            String t = s.substring(start, pos);
            if (t.isEmpty()) throw new IllegalArgumentException("JSON 解析失败: 位置 " + start);
            if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
            return Long.parseLong(t);
        }
    }
}
