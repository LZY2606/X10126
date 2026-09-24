package replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 实现：解析 + 稳定序列化，不依赖任何外部库。
 * 数字规则：整数解析为 Long，小数/指数解析为 Double。
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.eof()) {
            throw p.error("尾部存在多余字符");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("JSON 顶层必须是对象");
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return i >= s.length();
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON 解析错误（位置 " + i + "）：" + msg);
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWs();
            if (eof()) {
                throw error("意外结束");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                case 'f':
                    return readBool();
                case 'n':
                    return readNull();
                default:
                    return readNumber();
            }
        }

        Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw error("对象键必须是字符串");
                }
                String key = readString();
                skipWs();
                expect(':');
                Object val = readValue();
                m.put(key, val);
                skipWs();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw error("对象中应为 ',' 或 '}'");
                }
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("数组中应为 ',' 或 ']'");
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw error("字符串未闭合");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (eof()) {
                        throw error("转义未闭合");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw error("\\u 转义不完整");
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            throw error("非法转义 \\" + e);
                    }
                } else if (c < 0x20) {
                    throw error("字符串中出现控制字符");
                } else {
                    sb.append(c);
                }
            }
        }

        Object readBool() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw error("非法字面量");
        }

        Object readNull() {
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            throw error("非法字面量");
        }

        Object readNumber() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            while (!eof() && Character.isDigit(peek())) {
                i++;
            }
            boolean isDouble = false;
            if (!eof() && peek() == '.') {
                isDouble = true;
                i++;
                while (!eof() && Character.isDigit(peek())) {
                    i++;
                }
            }
            if (!eof() && (peek() == 'e' || peek() == 'E')) {
                isDouble = true;
                i++;
                if (!eof() && (peek() == '+' || peek() == '-')) {
                    i++;
                }
                while (!eof() && Character.isDigit(peek())) {
                    i++;
                }
            }
            String num = s.substring(start, i);
            if (num.isEmpty() || num.equals("-")) {
                throw error("非法数字");
            }
            if (isDouble) {
                return Double.parseDouble(num);
            }
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(num);
            }
        }

        char peek() {
            if (eof()) {
                throw error("意外结束");
            }
            return s.charAt(i);
        }

        char next() {
            if (eof()) {
                throw error("意外结束");
            }
            return s.charAt(i++);
        }

        void expect(char c) {
            if (eof() || s.charAt(i) != c) {
                throw error("期望 '" + c + "'");
            }
            i++;
        }
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, value);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof Long) {
            sb.append(value.toString());
        } else if (value instanceof Integer) {
            sb.append(value.toString());
        } else if (value instanceof Double) {
            double d = (Double) value;
            if (Double.isFinite(d)) {
                sb.append(d);
            } else {
                sb.append("null");
            }
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeTo(sb, item);
            }
            sb.append(']');
        } else if (value.getClass().isArray()) {
            sb.append('[');
            int n = java.lang.reflect.Array.getLength(value);
            for (int k = 0; k < n; k++) {
                if (k > 0) {
                    sb.append(',');
                }
                writeTo(sb, java.lang.reflect.Array.get(value, k));
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writePretty(StringBuilder sb, Object value, int indent) {
        if (value instanceof Map && !((Map<?, ?>) value).isEmpty()) {
            Map<?, ?> m = (Map<?, ?>) value;
            sb.append("{\n");
            int k = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, indent + 1);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                writePretty(sb, e.getValue(), indent + 1);
                if (++k < m.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        } else if (value instanceof List && !((List<?>) value).isEmpty()) {
            List<?> list = (List<?>) value;
            sb.append("[\n");
            for (int k = 0; k < list.size(); k++) {
                pad(sb, indent + 1);
                writePretty(sb, list.get(k), indent + 1);
                if (k < list.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            writeTo(sb, value);
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int k = 0; k < indent; k++) {
            sb.append("  ");
        }
    }

    static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
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

    // ------------------------------------------------------------------
    // 类型安全取值
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("期望对象，得到 " + typeName(value));
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("期望数组，得到 " + typeName(value));
        }
        return (List<Object>) value;
    }

    public static String str(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        throw new IllegalArgumentException("期望字符串，得到 " + typeName(value));
    }

    public static String strOr(Object value, String def) {
        return value == null ? def : str(value);
    }

    public static long longOr(Object value, long def) {
        if (value == null) {
            return def;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException("期望整数，得到 " + typeName(value));
    }

    public static int intOr(Object value, int def) {
        return (int) longOr(value, def);
    }

    public static boolean boolOr(Object value, boolean def) {
        if (value == null) {
            return def;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        throw new IllegalArgumentException("期望布尔值，得到 " + typeName(value));
    }

    public static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map) {
            return "object";
        }
        if (value instanceof List) {
            return "array";
        }
        return value.getClass().getSimpleName().toLowerCase();
    }

    private Json(Object ignored) {
        throw new AssertionError();
    }
}
