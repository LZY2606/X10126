package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Step(
    long index,
    String eventId,
    Map<String, Object> beforeState,
    Map<String, Object> afterState,
    Map<String, Object> event,
    List<Map<String, Object>> outputs,
    List<Map<String, Object>> spawnedInternal,
    String transition,
    boolean failed,
    String failure,
    String stepHash,
    String prevHash
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("index", index);
        map.put("eventId", eventId);
        map.put("beforeState", beforeState);
        map.put("afterState", afterState);
        map.put("event", event);
        map.put("outputs", outputs);
        map.put("spawnedInternal", spawnedInternal);
        map.put("transition", transition);
        map.put("failed", failed);
        if (failure != null) map.put("failure", failure);
        map.put("stepHash", stepHash);
        map.put("prevHash", prevHash);
        map.put("diff", diff());
        return map;
    }

    public static Step fromMap(Object raw) {
        Map<String, Object> map = Json.object(raw);
        return new Step(
            Json.integer(map, "index", 0L),
            Json.requireString(map, "eventId"),
            Json.objectField(map, "beforeState"),
            Json.objectField(map, "afterState"),
            Json.objectField(map, "event"),
            maps(Json.listField(map, "outputs")),
            maps(Json.listField(map, "spawnedInternal")),
            Json.string(map, "transition"),
            Boolean.TRUE.equals(map.get("failed")),
            Json.string(map, "failure"),
            Json.requireString(map, "stepHash"),
            Json.string(map, "prevHash")
        );
    }

    private static List<Map<String, Object>> maps(List<Object> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            result.add(Json.object(value));
        }
        return result;
    }

    public Map<String, Object> diff() {
        Map<String, Object> changes = new LinkedHashMap<>();
        Map<String, Object> before = beforeState;
        Map<String, Object> after = afterState;
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            Object oldValue = before.get(entry.getKey());
            if (!java.util.Objects.equals(oldValue, entry.getValue())) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", oldValue);
                change.put("to", entry.getValue());
                changes.put(entry.getKey(), change);
            }
        }
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", before.get(key));
                change.put("to", null);
                changes.put(key, change);
            }
        }
        return changes;
    }
}
