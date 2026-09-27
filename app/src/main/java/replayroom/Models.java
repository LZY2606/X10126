package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Models {
    private Models() {
    }

    public static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    public static List<Object> list() {
        return new ArrayList<>();
    }

    public static String str(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public static String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-empty string");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value, String name) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return (List<Object>) value;
    }

    public static long integer(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    public static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = map();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = list();
            for (Object item : list) copy.add(deepCopy(item));
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Object getPath(Object root, String path) {
        if (path == null || path.isBlank() || "$".equals(path) || "$.".equals(path)) return root;
        String normalized = path.startsWith("$.") ? path.substring(2) : (path.startsWith("$") ? path.substring(1) : path);
        Object current = root;
        for (String part : splitPath(normalized)) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list) {
                int index = Integer.parseInt(part);
                current = index >= 0 && index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    public static void setPath(Object root, String path, Object value) {
        String normalized = path.startsWith("$.") ? path.substring(2) : (path.startsWith("$") ? path.substring(1) : path);
        List<String> parts = splitPath(normalized);
        Object current = root;
        for (int i = 0; i < parts.size() - 1; i++) {
            String part = parts.get(i);
            if (current instanceof Map<?, ?> map) {
                Object next = map.get(part);
                if (!(next instanceof Map)) {
                    Map<String, Object> created = map();
                    ((Map<String, Object>) map).put(part, created);
                    next = created;
                }
                current = next;
            } else if (current instanceof List<?> list) {
                current = list.get(Integer.parseInt(part));
            } else {
                throw new IllegalArgumentException("Cannot descend path " + path);
            }
        }
        String last = parts.get(parts.size() - 1);
        if (current instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).put(last, value);
        } else if (current instanceof List<?> list) {
            ((List<Object>) list).set(Integer.parseInt(last), value);
        } else {
            throw new IllegalArgumentException("Cannot set path " + path);
        }
    }

    private static List<String> splitPath(String path) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                parts.add(current.toString());
                current.setLength(0);
            } else if (c == '[') {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                int end = path.indexOf(']', i + 1);
                if (end < 0) throw new IllegalArgumentException("Bad path: " + path);
                parts.add(path.substring(i + 1, end));
                i = end;
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) parts.add(current.toString());
        if (parts.isEmpty()) throw new IllegalArgumentException("Path is required");
        return parts;
    }
}
