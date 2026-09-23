package com.replayroom.model;

import com.replayroom.json.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/** One event occurrence with a logical timestamp, a source and a stable sequence number. */
public final class EventInstance {
    public String id;
    public String name;
    public Map<String, Object> payload = new LinkedHashMap<>();
    public long time;
    public String source = "default";
    public long seq;
    public boolean internal;

    public EventInstance() {}

    public EventInstance(String id, String name, Map<String, Object> payload, long time, String source, long seq, boolean internal) {
        this.id = id;
        this.name = name;
        this.payload = payload == null ? new LinkedHashMap<>() : payload;
        this.time = time;
        this.source = source;
        this.seq = seq;
        this.internal = internal;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("payload", Json.deepCopy(payload));
        m.put("time", time);
        m.put("source", source);
        m.put("seq", seq);
        m.put("internal", internal);
        return m;
    }

    public static EventInstance fromMap(Map<String, Object> m) {
        EventInstance e = new EventInstance();
        e.id = Json.optString(m, "id", null);
        e.name = Json.optString(m, "name", null);
        if (e.name == null) throw new IllegalArgumentException("event requires a name");
        Object p = m.get("payload");
        if (p != null) e.payload = new LinkedHashMap<>(Json.asMap(p));
        e.time = Json.optLong(m, "time", 0);
        e.source = Json.optString(m, "source", "default");
        e.seq = Json.optLong(m, "seq", 0);
        Object internal = m.get("internal");
        e.internal = internal != null && Json.asBool(internal);
        return e;
    }

    /** Canonical identity of event content, used for merge compatibility and fingerprints. */
    public String contentKey() {
        return Json.canonical(toMap());
    }
}
