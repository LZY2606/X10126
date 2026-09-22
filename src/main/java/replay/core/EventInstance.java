package replay.core;

import replay.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One event occurrence in a replay queue. Ordering key is
 * (logicalTime, sourcePriority, seq) — all deterministic.
 */
public final class EventInstance implements Comparable<EventInstance> {

    public final String id;
    public final String name;
    public final long logicalTime;
    public final String source;
    public final long sourcePriority;
    public final long seq;
    public final Map<String, Object> payload;
    public final boolean internal;

    public EventInstance(String id, String name, long logicalTime, String source,
                         long sourcePriority, long seq, Map<String, Object> payload, boolean internal) {
        this.id = id;
        this.name = name;
        this.logicalTime = logicalTime;
        this.source = source;
        this.sourcePriority = sourcePriority;
        this.seq = seq;
        this.payload = payload;
        this.internal = internal;
    }

    @Override
    public int compareTo(EventInstance o) {
        int c = Long.compare(logicalTime, o.logicalTime);
        if (c != 0) return c;
        c = Long.compare(sourcePriority, o.sourcePriority);
        if (c != 0) return c;
        return Long.compare(seq, o.seq);
    }

    /** Identity used by merge compatibility checks: content excluding scheduling keys. */
    public String contentKey() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("logicalTime", logicalTime);
        m.put("source", source);
        m.put("payload", payload);
        return Json.canonical(m);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("time", logicalTime);
        m.put("source", source);
        m.put("priority", sourcePriority);
        m.put("seq", seq);
        m.put("payload", Json.deepCopy(payload));
        m.put("internal", internal);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static EventInstance fromJson(Map<String, Object> m) {
        return new EventInstance(
                Json.asString(m.get("id"), "event.id"),
                Json.asString(m.get("name"), "event.name"),
                Json.asLong(m.get("time"), "event.time"),
                Json.asString(m.get("source"), "event.source"),
                Json.asLong(m.get("priority"), "event.priority"),
                Json.asLong(m.get("seq"), "event.seq"),
                m.containsKey("payload") && m.get("payload") != null
                        ? (Map<String, Object>) Json.deepCopy(Json.asMap(m.get("payload"), "event.payload"))
                        : new LinkedHashMap<>(),
                Boolean.TRUE.equals(m.get("internal")));
    }
}
