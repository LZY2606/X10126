package com.replayroom.session;

import com.replayroom.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Flattened key-path diff used by the before/after UI and branch comparison. */
public final class Diff {

    private Diff() {
    }

    public static Map<String, Object> between(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> flatBefore = new LinkedHashMap<>();
        Map<String, Object> flatAfter = new LinkedHashMap<>();
        flatten("", before, flatBefore);
        flatten("", after, flatAfter);

        TreeSet<String> allPaths = new TreeSet<>();
        allPaths.addAll(flatBefore.keySet());
        allPaths.addAll(flatAfter.keySet());

        List<Map<String, Object>> changes = new ArrayList<>();
        for (String path : allPaths) {
            boolean had = flatBefore.containsKey(path);
            boolean has = flatAfter.containsKey(path);
            Object oldValue = flatBefore.get(path);
            Object newValue = flatAfter.get(path);
            if (had && has) {
                if (!Objects.equals(Json.canonical(oldValue), Json.canonical(newValue))) {
                    changes.add(entry("changed", path, oldValue, newValue));
                }
            } else if (had) {
                changes.add(entry("removed", path, oldValue, null));
            } else {
                changes.add(entry("added", path, null, newValue));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changes", changes);
        result.put("changeCount", changes.size());
        return result;
    }

    private static Map<String, Object> entry(String kind, String path, Object before, Object after) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", kind);
        entry.put("path", path);
        entry.put("before", before);
        entry.put("after", after);
        return entry;
    }

    private static void flatten(String prefix, Object value, Map<String, Object> output) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty() && !prefix.isEmpty()) {
                output.put(prefix, new LinkedHashMap<String, Object>());
                return;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String path = prefix.isEmpty() ? key : prefix + "." + key;
                flatten(path, entry.getValue(), output);
            }
        } else {
            output.put(prefix, value);
        }
    }
}
