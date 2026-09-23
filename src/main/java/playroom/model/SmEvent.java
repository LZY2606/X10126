package playroom.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A unit on the event queue. External events come from the imported log;
 * internal events are derived by "emit-internal" actions.
 *
 * Ordering key (ascending): logical time, source priority, original sequence,
 * then a final tie-breaker so the key is always total.
 */
public final class SmEvent {
    public final String id;
    public final long time;
    public final String source;
    public final long priority;
    public final long seq;
    public final String type;
    public final Object data;
    public final boolean internal;

    public SmEvent(String id, long time, String source, long priority, long seq,
                   String type, Object data, boolean internal) {
        this.id = id;
        this.time = time;
        this.source = source;
        this.priority = priority;
        this.seq = seq;
        this.type = type;
        this.data = data;
        this.internal = internal;
    }

    public static java.util.Comparator<SmEvent> order() {
        return java.util.Comparator
                .comparingLong((SmEvent e) -> e.time)
                .thenComparingLong(e -> e.priority)
                .thenComparingLong(e -> e.seq)
                .thenComparing(e -> e.id);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("time", time);
        m.put("source", source);
        m.put("priority", priority);
        m.put("seq", seq);
        m.put("type", type);
        m.put("data", data);
        m.put("internal", internal);
        return m;
    }

    /** Identity used when comparing branches for the same external event. */
    public Map<String, Object> externalIdentity() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("time", time);
        m.put("source", source);
        m.put("seq", seq);
        m.put("type", type);
        m.put("data", data);
        return m;
    }

    public static SmEvent external(Map<String, Object> m, long priority) {
        return new SmEvent(
                require(m, "id"),
                asLong(m.get("time"), "time"),
                require(m, "source"),
                priority,
                asLong(m.get("seq"), "seq"),
                require(m, "type"),
                m.get("data"),
                false);
    }

    @SuppressWarnings("unchecked")
    public static SmEvent fromMap(Map<String, Object> m) {
        return new SmEvent(
                (String) m.get("id"),
                ((Number) m.get("time")).longValue(),
                (String) m.get("source"),
                ((Number) m.get("priority")).longValue(),
                ((Number) m.get("seq")).longValue(),
                (String) m.get("type"),
                m.get("data"),
                Boolean.TRUE.equals(m.get("internal")));
    }

    private static String require(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("event field '" + key + "' must be a non-empty string");
        }
        return s;
    }

    private static long asLong(Object v, String key) {
        if (!(v instanceof Number n)) throw new IllegalArgumentException("event field '" + key + "' must be a number");
        return n.longValue();
    }
}
