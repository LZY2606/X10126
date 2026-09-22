package com.replayroom.core;

import com.replayroom.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A replayable event. External events arrive in the imported log; internal events
 * are emitted by actions and queued behind the event that produced them.
 * Ordering key: (logical time, source priority, original sequence number).
 */
public final class Event {
    public final String id;
    public final long time;
    public final String source;
    public final long seq;
    public final String type;
    public final Map<String, Object> payload;
    public final boolean internal;

    public Event(String id, long time, String source, long seq, String type,
                 Map<String, Object> payload, boolean internal) {
        this.id = id;
        this.time = time;
        this.source = source;
        this.seq = seq;
        this.type = type;
        this.payload = payload == null ? Map.of() : new LinkedHashMap<>(payload);
        this.internal = internal;
    }

    /** Canonical ordering key string, used for merge ambiguity checks. */
    public String orderKey(MachineDefinition def) {
        return time + "|" + def.sourcePriority(source) + "|" + seq;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("time", time);
        m.put("source", source);
        m.put("seq", seq);
        m.put("type", type);
        m.put("payload", payload);
        m.put("internal", internal);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Event fromMap(Map<String, Object> m) {
        Object payload = m.get("payload");
        return new Event(
                str(m, "id", null),
                num(m, "time", 0),
                str(m, "source", "external"),
                num(m, "seq", 0),
                str(m, "type", null),
                payload instanceof Map ? (Map<String, Object>) payload : Map.of(),
                Boolean.TRUE.equals(m.get("internal")));
    }

    static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        if (v == null) {
            if (def == null) throw new IllegalArgumentException("event missing '" + key + "': " + Json.canonical(m));
            return def;
        }
        return String.valueOf(v);
    }

    static long num(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("event field '" + key + "' must be a number");
    }
}
