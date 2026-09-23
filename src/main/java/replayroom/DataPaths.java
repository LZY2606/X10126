package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DataPaths {
    private DataPaths() {
    }

    static Object get(Map<String, Object> root, String path) {
        if (path == null || path.isBlank()) {
            return root;
        }
        Object current = root;
        for (String part : parse(path)) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    static void set(Map<String, Object> root, String path, Object value) {
        if (path == null || path.isBlank()) {
            if (!(value instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("root data must be an object");
            }
            root.clear();
            root.putAll((Map<String, Object>) Models.deepCopy(value));
            return;
        }
        List<String> parts = parse(path);
        Map<String, Object> current = root;
        for (int i = 0; i < parts.size() - 1; i++) {
            Object next = current.get(parts.get(i));
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                current.put(parts.get(i), next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(parts.get(parts.size() - 1), Models.deepCopy(value));
    }

    static List<String> parse(String path) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\' && i + 1 < path.length()) {
                current.append(path.charAt(++i));
            } else if (c == '.') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        if (parts.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("invalid data path: " + path);
        }
        return parts;
    }
}
