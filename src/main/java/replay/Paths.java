package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Paths {
    static Object get(Object root, String path) {
        List<String> parts = parts(path);
        Object current = root;
        for (String part : parts) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
            if (current == null) return null;
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    static void set(Map<String, Object> root, String path, Object value) {
        if (!path.startsWith("data.")) {
            throw new IllegalArgumentException("only data paths are writable: " + path);
        }
        List<String> parts = parts(path);
        Map<String, Object> current = root;
        for (int i = 0; i < parts.size() - 1; i++) {
            String part = parts.get(i);
            Object next = current.get(part);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                current.put(part, next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(parts.get(parts.size() - 1), value);
    }

    private static List<String> parts(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        List<String> parts = new ArrayList<>();
        for (String part : path.split("\\.")) {
            if (!part.isBlank()) parts.add(part);
        }
        if (parts.isEmpty()) throw new IllegalArgumentException("invalid path: " + path);
        return parts;
    }

    private Paths() {
    }
}
