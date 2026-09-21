package io.example.replay;

import java.util.Map;

public record StoredEvent(
        String id,
        String type,
        long time,
        String source,
        int priority,
        long originalSeq,
        boolean internal,
        long enqueueSeq,
        Map<String, Object> payload) {
    @SuppressWarnings("unchecked")
    public static StoredEvent external(Object json, long fallbackSeq) {
        Map<String, Object> object = Json.object(json);
        String id = requiredString(object, "id");
        String type = requiredString(object, "type");
        long time = Json.longValue(object, "time", 0L);
        String source = Json.string(object, "source");
        if (source == null) {
            source = "external";
        }
        long originalSeq = Json.longValue(object, "originalSeq", fallbackSeq);
        int priority = (int) Json.longValue(object, "priority", 100L);
        Object payload = object.get("payload");
        Map<String, Object> payloadMap = payload == null
                ? Map.of()
                : Json.object(Json.deepCopy(payload));
        return new StoredEvent(id, type, time, source, priority, originalSeq, false, 0L, payloadMap);
    }

    public static StoredEvent internal(String type, long time, long enqueueSeq, Map<String, Object> payload) {
        return new StoredEvent(
                "__internal_" + enqueueSeq,
                type,
                time,
                "__internal__",
                Integer.MAX_VALUE,
                enqueueSeq,
                true,
                enqueueSeq,
                Map.copyOf(payload));
    }

    public StoredEvent copy() {
        return new StoredEvent(id, type, time, source, priority, originalSeq, internal,
                enqueueSeq, Json.object(Json.deepCopy(payload)));
    }

    public Map<String, Object> toJsonMutable() {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("id", id);
        result.put("type", type);
        result.put("time", time);
        result.put("source", source);
        result.put("priority", priority);
        result.put("originalSeq", originalSeq);
        result.put("internal", internal);
        result.put("enqueueSeq", enqueueSeq);
        result.put("payload", Json.deepCopy(payload));
        return result;
    }

    public static StoredEvent fromJson(Object value) {
        Map<String, Object> object = Json.object(value);
        return new StoredEvent(
                requiredString(object, "id"),
                requiredString(object, "type"),
                Json.longValue(object, "time", 0L),
                Json.string(object, "source") == null ? "external" : Json.string(object, "source"),
                (int) Json.longValue(object, "priority", 100L),
                Json.longValue(object, "originalSeq", 0L),
                Boolean.TRUE.equals(object.get("internal")),
                Json.longValue(object, "enqueueSeq", 0L),
                object.get("payload") == null ? Map.of() : Json.object(Json.deepCopy(object.get("payload"))));
    }

    private static String requiredString(Map<String, Object> object, String key) {
        String value = Json.string(object, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Event requires " + key);
        }
        return value;
    }
}
