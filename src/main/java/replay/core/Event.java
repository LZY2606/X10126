package replay.core;

import java.util.LinkedHashMap;
import java.util.Map;
import replay.json.Json;

public final class Event {
    public static final String EXTERNAL = "external";
    public static final String INTERNAL = "internal";

    public long id;
    public String kind;
    public long time;
    public String source;
    public long seq;
    public String type;
    public Map<String, Object> payload;

    public Event(long id, String kind, long time, String source, long seq, String type,
            Map<String, Object> payload) {
        this.id = id;
        this.kind = kind;
        this.time = time;
        this.source = source;
        this.seq = seq;
        this.type = type;
        this.payload = payload == null ? new LinkedHashMap<>() : payload;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("kind", kind);
        map.put("time", time);
        map.put("source", source);
        map.put("seq", seq);
        map.put("type", type);
        map.put("payload", payload);
        return map;
    }

    public static Event fromJson(Map<String, Object> map) {
        return new Event(
                Json.lng(map, "id", -1),
                map.get("kind") instanceof String k ? k : EXTERNAL,
                Json.lng(map, "time", 0),
                map.get("source") instanceof String s ? s : "unknown",
                Json.lng(map, "seq", 0),
                Json.str(map, "type"),
                map.get("payload") instanceof Map<?, ?> p ? Json.deepCopy(p) : new LinkedHashMap<>());
    }

    public Event copy() {
        return new Event(id, kind, time, source, seq, type, Json.deepCopy(payload));
    }

    public String summary() {
        return type + "@t" + time + "/" + source + "#" + seq;
    }
}
