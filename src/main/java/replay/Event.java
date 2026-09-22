package replay;

import java.util.Map;

/**
 * A replayable event. Ordering is fully determined by
 * (logicalTime, sourcePriority, sequence, insertionId) — never by wall clock.
 */
public final class Event implements Comparable<Event> {
    public final String id;
    public final long time;
    public final String source;
    public final long seq;
    public final String name;
    public final Map<String, Object> payload;
    public final boolean internal;
    /** Final tie-breaker so the queue order is always total. */
    public final long insertionId;

    public Event(String id, long time, String source, long seq, String name,
                 Map<String, Object> payload, boolean internal, long insertionId) {
        this.id = id;
        this.time = time;
        this.source = source;
        this.seq = seq;
        this.name = name;
        this.payload = payload;
        this.internal = internal;
        this.insertionId = insertionId;
    }

    public int compareTo(Event other, java.util.function.ToIntFunction<String> priority) {
        if (time != other.time) return Long.compare(time, other.time);
        int p = Integer.compare(priority.applyAsInt(source), priority.applyAsInt(other.source));
        if (p != 0) return p;
        if (seq != other.seq) return Long.compare(seq, other.seq);
        return Long.compare(insertionId, other.insertionId);
    }

    @Override
    public int compareTo(Event other) {
        return compareTo(other, s -> 0);
    }

    /** Ordering key without insertionId — used to detect ambiguous merge ordering. */
    public String orderKey(java.util.function.ToIntFunction<String> priority) {
        return time + "/" + priority.applyAsInt(source) + "/" + seq;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = Json.newObj();
        m.put("id", id);
        m.put("time", time);
        m.put("source", source);
        m.put("seq", seq);
        m.put("name", name);
        m.put("payload", payload == null ? Json.newObj() : payload);
        if (internal) m.put("internal", true);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Event fromJson(Map<String, Object> m, boolean internal, long insertionId) {
        Object p = m.get("payload");
        return new Event(
                Json.str(m.get("id")),
                Json.num(m.get("time")),
                Json.str(m.getOrDefault("source", "external")),
                Json.num(m.getOrDefault("seq", 0L)),
                Json.str(m.get("name")),
                p == null ? Json.newObj() : (Map<String, Object>) Json.deepCopy(p),
                internal,
                insertionId);
    }
}
