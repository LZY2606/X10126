package replay.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A single event on the logical timeline.
 *
 * Stable total order: (logicalTime, sourcePriority, seq, kind, type, id).
 * Events at the same logical instant are ordered first by descending source
 * priority (larger wins), then by the original sequence number.
 */
public final class Event implements Comparable<Event> {
    public long time;
    public int priority;
    public long seq;
    public String type;
    public String id;
    public Map<String, Object> payload;
    /** "external" (from the imported log / API) or "internal" (emitted by an action). */
    public String kind;

    public Event() {
        this.payload = new LinkedHashMap<>();
        this.kind = "external";
    }

    public static Event external(long time, int priority, long seq, String type,
                                 String id, Map<String, Object> payload) {
        Event e = new Event();
        e.time = time;
        e.priority = priority;
        e.seq = seq;
        e.type = type == null ? "" : type;
        e.id = id;
        e.payload = payload == null ? new LinkedHashMap<>() : payload;
        e.kind = "external";
        return e;
    }

    public Event copy() {
        Event e = new Event();
        e.time = time;
        e.priority = priority;
        e.seq = seq;
        e.type = type;
        e.id = id;
        e.payload = Util.deepCopy(payload);
        e.kind = kind;
        return e;
    }

    @SuppressWarnings("unchecked")
    public static Event fromJson(Map<String, Object> m) {
        Event e = new Event();
        e.time = Json.lng(m, "time", Json.lng(m, "logicalTime", 0));
        e.priority = (int) Json.lng(m, "priority", 0);
        e.seq = Json.lng(m, "seq", Json.lng(m, "sequence", 0));
        e.type = Json.str(m, "type");
        if (e.type == null) e.type = "";
        e.id = Json.str(m, "id");
        Object payload = m.get("payload");
        e.payload = payload instanceof Map ? Util.deepCopy((Map<String, Object>) payload)
                : new LinkedHashMap<>();
        e.kind = Objects.requireNonNullElse(Json.str(m, "kind"), "external");
        return e;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", time);
        m.put("priority", priority);
        m.put("seq", seq);
        m.put("type", type);
        m.put("id", id);
        m.put("payload", payload);
        m.put("kind", kind);
        return m;
    }

    /** Identity used by branch merge (origin-independent content identity). */
    public String identity() {
        return Hashes.sha256Hex(time + "|" + type + "|" + (id == null ? "" : id)
                + "|" + Json.canonical(payload));
    }

    @Override
    public int compareTo(Event o) {
        int c = Long.compare(time, o.time);
        if (c != 0) return c;
        c = Integer.compare(o.priority, priority); // higher priority first
        if (c != 0) return c;
        c = Long.compare(seq, o.seq);
        if (c != 0) return c;
        c = kind.compareTo(o.kind);
        if (c != 0) return c;
        c = type.compareTo(o.type);
        if (c != 0) return c;
        return identity().compareTo(o.identity());
    }
}
