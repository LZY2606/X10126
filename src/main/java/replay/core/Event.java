package replay.core;

import replay.json.Json;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single event. Ordering is fully deterministic:
 * logical time, then source priority (lower value = earlier; internal events always last
 * at the same timestamp), then the original sequence number from the imported log.
 */
public final class Event {
    public static final String INTERNAL_SOURCE = "$internal";
    public static final int INTERNAL_PRIORITY = Integer.MAX_VALUE;

    public String id;
    public long time;
    public String source;
    public String type;
    public Map<String, Object> payload = new LinkedHashMap<>();
    public long seq;
    public boolean internal;
    /** Trace-step number of the event whose actions produced this internal event; -1 for external. */
    public long batchId = -1;

    public int priority(Definition def) {
        if (internal) return INTERNAL_PRIORITY;
        return def.sourcePriority(source);
    }

    public static Comparator<Event> order(Definition def) {
        return Comparator
                .comparingLong((Event e) -> e.time)
                .thenComparingInt(e -> e.priority(def))
                .thenComparingLong(e -> e.seq);
    }

    /** Content key used to detect "same id, different content" conflicts during merge. */
    public String contentKey(Definition def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", time);
        m.put("source", source);
        m.put("type", type);
        m.put("payload", payload);
        m.put("seq", seq);
        m.put("internal", internal);
        return Json.canonical(m);
    }

    /** Position key used to detect ambiguous ordering between distinct events. */
    public String positionKey(Definition def) {
        return time + "|" + priority(def) + "|" + seq;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("time", time);
        m.put("source", source);
        m.put("type", type);
        m.put("payload", Json.deepCopy(payload));
        m.put("seq", seq);
        m.put("internal", internal);
        if (batchId >= 0) m.put("batchId", batchId);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Event fromJson(Map<String, Object> m) {
        Event e = new Event();
        e.id = Json.asString(m.get("id"), "event.id");
        e.time = Json.asLong(m.get("time"), "event.time");
        e.source = Json.asString(m.get("source"), "event.source");
        e.type = Json.asString(m.get("type"), "event.type");
        Object p = m.get("payload");
        if (p != null) e.payload = new LinkedHashMap<>(Json.asMap(p, "event.payload"));
        e.seq = Json.asLong(m.get("seq"), "event.seq");
        e.internal = Boolean.TRUE.equals(m.get("internal"));
        Object b = m.get("batchId");
        e.batchId = b == null ? -1 : Json.asLong(b, "event.batchId");
        return e;
    }

    public Event copy() {
        return fromJson(toJson());
    }
}
