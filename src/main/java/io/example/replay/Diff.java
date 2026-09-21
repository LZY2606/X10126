package io.example.replay;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Diff {
    private Diff() {
    }

    public static List<Change> compute(Map<String, Object> before, Map<String, Object> after) {
        List<Change> changes = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>();
        collectPaths("$", before, paths);
        collectPaths("$", after, paths);
        for (String path : paths) {
            Object left = Paths.get(before, path);
            Object right = Paths.get(after, path);
            boolean leftLeaf = isLeaf(left);
            boolean rightLeaf = isLeaf(right);
            if (leftLeaf && rightLeaf && !java.util.Objects.equals(left, right)) {
                changes.add(new Change(path, left, right));
            } else if (leftLeaf != rightLeaf) {
                changes.add(new Change(path, left, right));
            }
        }
        return changes;
    }

    private static void collectPaths(String prefix, Object value, Set<String> paths) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collectPaths(prefix + "." + entry.getKey(), entry.getValue(), paths);
            }
            if (map.isEmpty() && !"$".equals(prefix)) {
                paths.add(prefix);
            }
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                paths.add(prefix);
            }
            for (int i = 0; i < list.size(); i++) {
                collectPaths(prefix + "[" + i + "]", list.get(i), paths);
            }
        } else {
            paths.add(prefix);
        }
    }

    private static boolean isLeaf(Object value) {
        return !(value instanceof Map<?, ?> || value instanceof List<?>);
    }

    public record Change(String path, Object before, Object after) {
        public Map<String, Object> toJson() {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("path", path);
            result.put("before", before);
            result.put("after", after);
            return result;
        }
    }
}
