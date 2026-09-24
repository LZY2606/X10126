package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Diff {
    private Diff() {}

    public static List<Map<String, Object>> maps(Map<String, Object> before, Map<String, Object> after) {
        List<Map<String, Object>> changes = new ArrayList<>();
        for (Map.Entry<String, Object> entry : before.entrySet()) {
            String key = entry.getKey();
            if (!after.containsKey(key)) {
                changes.add(change("removed", key, entry.getValue(), null));
            } else if (!Json.canonical(entry.getValue()).equals(Json.canonical(after.get(key)))) {
                changes.add(change("changed", key, entry.getValue(), after.get(key)));
            }
        }
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey())) {
                changes.add(change("added", key, null, entry.getValue()));
            }
        }
        return changes;
    }

    private static Map<String, Object> change(String kind, String path, Object before, Object after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("kind", kind);
        change.put("path", path);
        change.put("before", before);
        change.put("after", after);
        return change;
    }
}
