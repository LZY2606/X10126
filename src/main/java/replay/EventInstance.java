package replay;

import java.util.LinkedHashMap;
import java.util.Map;

/** One event occurrence. Ordering: (logicalTime, source priority, seq). */
public final class EventInstance implements Comparable<EventInstance> {
    public final long time;
    public final String source;
    public final long seq; // original sequence number within its source / arrival order
    public final String name;
    public final Map<String, Object> payload;
    public final boolean internal;

    public EventInstance(long time, String source, long seq, String name, Map<String, Object> payload, boolean internal) {
        this.time = time;
        this.source = source;
        this.seq = seq;
        this.name = name;
        this.payload = payload == null ? Map.of() : payload;
        this.internal = internal;
    }

    public int compareToWith(EventInstance other, java.util.function.ToIntFunction<String> priority) {
        if (time != other.time) return Long.compare(time, other.time);
        int p = Integer.compare(priority.applyAsInt(source), priority.applyAsInt(other.source));
        if (p != 0) return p;
        return Long.compare(seq, other.seq);
    }

    @Override
    public int compareTo(EventInstance other) {
        return compareToWith(other, s -> 0);
    }

    /** Identity key used for branch merge conflict detection. */
    public String key() { return time + "|" + source + "|" + seq + "|" + name; }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", time);
        m.put("source", source);
        m.put("seq", seq);
        m.put("name", name);
        m.put("payload", payload);
        if (internal) m.put("internal", true);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static EventInstance fromJson(Map<String, Object> m) {
        long time = ((Number) m.getOrDefault("time", 0L)).longValue();
        String source = String.valueOf(m.getOrDefault("source", "default"));
        long seq = ((Number) m.getOrDefault("seq", 0L)).longValue();
        String name = String.valueOf(m.get("name"));
        Object payload = m.get("payload");
        boolean internal = Boolean.TRUE.equals(m.get("internal"));
        return new EventInstance(time, source, seq, name,
                payload instanceof Map ? (Map<String, Object>) payload : Map.of(), internal);
    }

    @Override
    public String toString() { return Json.canonical(toJson()); }
}
