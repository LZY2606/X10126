package replay.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

/**
 * 极简 JSON 支持：值类型为 Map<String,Object> / List<Object> / String /
 * BigDecimal / Boolean / null。提供解析、缩进输出与确定性规范化输出。
 */
public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.pos != p.s.length()) {
            throw p.error("trailing characters");
        }
        return v;
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, value, 0);
        return sb.toString();
    }

    /** 规范化输出：对象键按 Unicode 码点排序、无多余空白、数字规范化。用于指纹。 */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected object, got " + typeName(value));
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object value) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("expected array, got " + typeName(value));
        }
        return (List<Object>) value;
    }

    public static String str(Object value) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("expected string, got " + typeName(value));
        }
        return (String) value;
    }

    public static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : str(v);
    }

    public static List<Object> getArray(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return new ArrayList<>();
        }
        return arr(v);
    }

    public static Map<String, Object> newObj() {
        return new LinkedHashMap<>();
    }

    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    public static List<Object> list(Object... items) {
        List<Object> l = new ArrayList<>();
        for (Object item : items) {
            l.add(item);
        }
        return l;
    }

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                copy.put(e.getKey(), deepCopy(e.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }

    public static boolean equals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof BigDecimal || b instanceof BigDecimal) {
            return (a instanceof Number) && (b instanceof Number)
                    && numbersEqual((Number) a, (Number) b);
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> ma = (Map<?, ?>) a;
            Map<?, ?> mb = (Map<?, ?>) b;
            if (ma.size() != mb.size()) {
                return false;
            }
            for (Object key : ma.keySet()) {
                if (!mb.containsKey(key) || !equals(ma.get(key), mb.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<?> la = (List<?>) a;
            List<?> lb = (List<?>) b;
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!equals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static boolean numbersEqual(Number a, Number b) {
        return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static void writePretty(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof BigDecimal) {
            sb.append(normalizeNumber((BigDecimal) value));
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof String) {
            writeJsonString(sb, (String) value);
        } else if (value instanceof Map) {
            writePrettyMap(sb, (Map<?, ?>) value, indent);
        } else if (value instanceof List) {
            writePrettyList(sb, (List<?>) value, indent);
        } else {
            throw new IllegalArgumentException("cannot serialize " + value.getClass());
        }
    }

    private static void writePrettyMap(StringBuilder sb, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            pad(sb, indent + 1);
            writeJsonString(sb, String.valueOf(e.getKey()));
            sb.append(": ");
            writePretty(sb, e.getValue(), indent + 1);
        }
        sb.append('\n');
        pad(sb, indent);
        sb.append('}');
    }

    private static void writePrettyList(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            pad(sb, indent + 1);
            writePretty(sb, item, indent + 1);
        }
        sb.append('\n');
        pad(sb, indent);
        sb.append(']');
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeCanonical(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof BigDecimal) {
            sb.append(normalizeNumber((BigDecimal) value));
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof String) {
            writeJsonString(sb, (String) value);
        } else if (value instanceof Map) {
            List<String> keys = new ArrayList<>(((Map<String, Object>) value).keySet());
            keys.sort(String::compareTo);
            sb.append('{');
            boolean first = true;
            for (String key : keys) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJsonString(sb, key);
                sb.append(':');
                writeCanonical(sb, ((Map<String, Object>) value).get(key));
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeCanonical(sb, item);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot canonicalize " + value.getClass());
        }
    }

    private static String normalizeNumber(BigDecimal n) {
        BigDecimal stripped = n.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        return stripped.toPlainString();
    }

    private static void writeJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        RuntimeException error(String msg) {
            return new IllegalArgumentException("JSON parse error at " + pos + ": " + msg);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWs();
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(pos);
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            if (c == 't' || c == 'f') {
                return readBool();
            }
            if (c == 'n') {
                return readNull();
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return readNumber();
            }
            throw error("unexpected character '" + c + "'");
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWs();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object value = readValue();
                list.add(value);
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
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
                        case 'n':
                            sb.append('\n');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u': {
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        }
                        default:
                            throw error("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Boolean readBool() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw error("invalid literal");
        }

        Object readNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw error("invalid literal");
        }

        Object readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            readDigits();
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                readDigits();
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                readDigits();
            }
            String token = s.substring(start, pos);
            return new BigDecimal(token);
        }

        private void readDigits() {
            int start = pos;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw error("expected digits");
            }
        }

        private char peek() {
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            return s.charAt(pos);
        }

        private char next() {
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            return s.charAt(pos++);
        }

        private void expect(char c) {
            char actual = next();
            if (actual != c) {
                throw error("expected '" + c + "' but got '" + actual + "'");
            }
        }
    }
}
