package io.replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零依赖 JSON：解析、普通序列化与规范化序列化（键排序）。
 * 数值统一以 Long / Double 表达；规范化输出保证相同逻辑值字节一致，用于指纹。
 */
public final class Json {
    private Json() {}

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
        public JsonException(String message, Throwable cause) { super(message, cause); }
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.eof()) throw p.err("尾部存在多余字符");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new JsonException("根节点不是对象");
        return (Map<String, Object>) v;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, false);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writeIndented(sb, value, 0);
        return sb.toString();
    }

    /** 规范化序列化：对象键按字典序，无多余空白。 */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, true);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object value, boolean canon) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Integer) {
            sb.append(((Integer) value).longValue());
        } else if (value instanceof Long) {
            sb.append(((Long) value).longValue());
        } else if (value instanceof Double) {
            sb.append(formatDouble((Double) value));
        } else if (value instanceof Float) {
            sb.append(formatDouble(((Float) value).doubleValue()));
        } else if (value instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) value;
            List<String> keys = new ArrayList<>(m.keySet());
            if (canon) keys.sort(String::compareTo);
            sb.append('{');
            boolean first = true;
            for (String k : keys) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, k);
                sb.append(':');
                write(sb, m.get(k), canon);
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<Object>) value) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o, canon);
            }
            sb.append(']');
        } else {
            writeString(sb, value.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeIndented(StringBuilder sb, Object value, int depth) {
        if (!(value instanceof Map) && !(value instanceof List)) {
            write(sb, value, false);
            return;
        }
        String indent = "  ".repeat(depth + 1);
        String closeIndent = "  ".repeat(depth);
        if (value instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) value;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(indent);
                writeString(sb, e.getKey());
                sb.append(": ");
                writeIndented(sb, e.getValue(), depth + 1);
            }
            sb.append('\n').append(closeIndent).append('}');
        } else {
            List<Object> l = (List<Object>) value;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(indent);
                writeIndented(sb, o, depth + 1);
            }
            sb.append('\n').append(closeIndent).append(']');
        }
    }

    private static String formatDouble(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            long l = (long) d;
            return Long.toString(l);
        }
        return Double.toString(d);
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

    // ---- 访问辅助 ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object node, String key) {
        return (Map<String, Object>) ((Map<?, ?>) node).get(key);
    }

    public static String str(Object node, String key) {
        Object v = ((Map<?, ?>) node).get(key);
        return v == null ? null : v.toString();
    }

    public static long lng(Object node, String key, long dflt) {
        Object v = ((Map<?, ?>) node).get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException ignored) {}
        }
        return dflt;
    }

    public static boolean bool(Object node, String key, boolean dflt) {
        Object v = ((Map<?, ?>) node).get(key);
        return v instanceof Boolean ? (Boolean) v : dflt;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object node, String key) {
        Object v = ((Map<?, ?>) node).get(key);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    public static Map<String, Object> newObject() {
        return new LinkedHashMap<>();
    }

    // ---- 解析器 ----

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        boolean eof() { return pos >= s.length(); }

        JsonException err(String msg) {
            int line = 1, col = 1;
            for (int i = 0; i < pos && i < s.length(); i++) {
                if (s.charAt(i) == '\n') { line++; col = 1; } else col++;
            }
            return new JsonException("JSON 解析错误（第" + line + "行第" + col + "列）：" + msg);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        char peek() {
            if (eof()) throw err("意外结束");
            return s.charAt(pos);
        }

        boolean consume(char c) {
            if (!eof() && s.charAt(pos) == c) { pos++; return true; }
            return false;
        }

        void expect(char c) {
            if (!consume(c)) throw err("期望字符 '" + c + "'");
        }

        Object readValue() {
            skipWs();
            if (eof()) throw err("意外结束");
            char c = peek();
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBoolean();
            if (c == 'n') return readNull();
            if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
            throw err("无法识别的值");
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (consume('}')) return m;
            while (true) {
                skipWs();
                if (peek() != '"') throw err("对象键必须是字符串");
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                if (m.containsKey(key)) throw err("重复的对象键：" + key);
                m.put(key, value);
                skipWs();
                if (consume(',')) continue;
                expect('}');
                return m;
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> l = new ArrayList<>();
            skipWs();
            if (consume(']')) return l;
            while (true) {
                l.add(readValue());
                skipWs();
                if (consume(',')) continue;
                expect(']');
                return l;
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw err("字符串未闭合");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (eof()) throw err("非法转义");
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
                            if (pos + 4 > s.length()) throw err("非法 \\u 转义");
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw err("非法 \\u 转义");
                            }
                            pos += 4;
                        }
                        default -> throw err("非法转义 \\" + e);
                    }
                } else if (c < 0x20) {
                    throw err("字符串中存在未转义控制字符");
                } else {
                    sb.append(c);
                }
            }
        }

        Boolean readBoolean() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw err("非法字面量");
        }

        Object readNull() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw err("非法字面量");
        }

        Object readNumber() {
            int start = pos;
            consume('-');
            if (eof()) throw err("非法数字");
            char c = peek();
            boolean isDouble = false;
            if (c == '0') {
                pos++;
            } else if (c >= '1' && c <= '9') {
                while (!eof() && Character.isDigit(peek())) pos++;
            } else {
                throw err("非法数字");
            }
            if (!eof() && peek() == '.') {
                isDouble = true;
                pos++;
                if (eof() || !Character.isDigit(peek())) throw err("小数部分缺失");
                while (!eof() && Character.isDigit(peek())) pos++;
            }
            if (!eof() && (peek() == 'e' || peek() == 'E')) {
                isDouble = true;
                pos++;
                if (!eof() && (peek() == '+' || peek() == '-')) pos++;
                if (eof() || !Character.isDigit(peek())) throw err("指数部分缺失");
                while (!eof() && Character.isDigit(peek())) pos++;
            }
            String token = s.substring(start, pos);
            if (!isDouble) {
                try {
                    return Long.parseLong(token);
                } catch (NumberFormatException ex) {
                    return Double.parseDouble(token);
                }
            }
            return Double.parseDouble(token);
        }
    }
}
