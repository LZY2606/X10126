package replayroom.model;

import java.util.LinkedHashMap;
import java.util.Map;
import replayroom.json.Json;

/** A concrete event instance scheduled for processing. */
public record Envelope(
        String id,
        boolean internal,
        String name,
        String source,
        long time,
        long seq,
        int priority,
        Map<String, Object> data,
        String parentId
) {
    public Envelope {
        data = data == null ? new LinkedHashMap<>() : Json.deepCopyMap(data);
        if (source == null) source = internal ? "internal" : "external";
    }

    public static Envelope external(String id, String name, String source, long time, long seq, int priority,
                                    Map<String, Object> data) {
        return new Envelope(id, false, name, source, time, seq, priority, data, null);
    }

    public static Envelope internal(String id, String name, String source, long time, long seq, int priority,
                                    Map<String, Object> data, String parentId) {
        return new Envelope(id, true, name, source, time, seq, priority, data, parentId);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("kind", internal ? "internal" : "external");
        map.put("name", name);
        map.put("source", source);
        map.put("time", time);
        map.put("seq", seq);
        map.put("priority", priority);
        map.put("data", data);
        if (parentId != null) map.put("parentId", parentId);
        return map;
    }

    public static Envelope fromMap(Map<String, Object> map) {
        String kind = Json.optString(map, "kind", "external");
        boolean internal = "internal".equals(kind);
        return new Envelope(
                Json.string(map.get("id"), "envelope.id"),
                internal,
                Json.string(map.get("name"), "envelope.name"),
                Json.optString(map, "source", internal ? "internal" : "external"),
                Json.optLong(map, "time", 0L),
                Json.optLong(map, "seq", 0L),
                (int) Json.optLong(map, "priority", 0L),
                Json.optObject(map, "data"),
                map.containsKey("parentId") ? Json.optString(map, "parentId", null) : null);
    }

    /** External event ordering: logical time, then source priority (desc), then original seq. */
    public static int compareExternal(Envelope a, Envelope b) {
        int cmp = Long.compare(a.time, b.time);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(b.priority, a.priority);
        if (cmp != 0) return cmp;
        cmp = Long.compare(a.seq, b.seq);
        if (cmp != 0) return cmp;
        return a.id.compareTo(b.id);
    }
}
