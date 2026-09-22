package replay;

import java.util.Comparator;
import java.util.Map;

/** An external or internal event with a logical timestamp. */
public final class Event {
    public static final String INTERNAL_SOURCE = "$internal";

    public final String id;
    public final long time;
    public final String source;
    public final long seq;
    /** Import order, final tiebreaker so ordering is total and deterministic. */
    public final long order;
    public final String type;
    public final Map<String, Object> fields;
    public final boolean internal;

    public Event(String id, long time, String source, long seq, long order,
                 String type, Map<String, Object> fields, boolean internal) {
        this.id = id;
        this.time = time;
        this.source = source;
        this.seq = seq;
        this.order = order;
        this.type = type;
        this.fields = fields;
        this.internal = internal;
    }

    public static Event fromJson(Map<String, Object> json, long order) {
        String id = json.containsKey("id") ? Json.asString(json.get("id"), "event.id") : "e" + order;
        long time = json.containsKey("time") ? Json.asLong(json.get("time"), "event.time") : 0;
        String source = json.containsKey("source") ? Json.asString(json.get("source"), "event.source") : "default";
        long seq = json.containsKey("seq") ? Json.asLong(json.get("seq"), "event.seq") : order;
        String type = Json.asString(json.get("type"), "event.type");
        Map<String, Object> fields = json.containsKey("fields")
                ? Json.asMap(json.get("fields"), "event.fields") : Json.map();
        return new Event(id, time, source, seq, order, type, fields, false);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = Json.map();
        m.put("id", id);
        m.put("time", time);
        m.put("source", source);
        m.put("seq", seq);
        m.put("order", order);
        m.put("type", type);
        m.put("fields", fields);
        m.put("internal", internal);
        return m;
    }

    public static Event fromStoredJson(Map<String, Object> json) {
        return new Event(
                Json.asString(json.get("id"), "event.id"),
                Json.asLong(json.get("time"), "event.time"),
                Json.asString(json.get("source"), "event.source"),
                Json.asLong(json.get("seq"), "event.seq"),
                Json.asLong(json.get("order"), "event.order"),
                Json.asString(json.get("type"), "event.type"),
                Json.asMap(json.get("fields"), "event.fields"),
                Boolean.TRUE.equals(json.get("internal")));
    }

    /** Stable ordering: logical time, then source priority, then original seq, then import order. */
    public static Comparator<Event> comparator(Map<String, Long> sourcePriorities) {
        return Comparator
                .comparingLong((Event e) -> e.time)
                .thenComparingLong(e -> priorityOf(e.source, sourcePriorities))
                .thenComparingLong(e -> e.seq)
                .thenComparingLong(e -> e.order);
    }

    public static long priorityOf(String source, Map<String, Long> sourcePriorities) {
        if (INTERNAL_SOURCE.equals(source)) return -1;
        Long p = sourcePriorities.get(source);
        return p != null ? p : 1000L;
    }
}
