package com.replayroom.model;

import com.replayroom.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One processed event: what was seen, what matched, what changed, or why it failed. */
public final class TraceEntry {
    public int index;
    public long tick;
    public EventInstance event;
    public String transition;      // human description of matched transition, or null
    public String condition;       // evaluated condition text, or null
    public Boolean conditionResult;
    public List<String> outputs = new ArrayList<>();
    public String status;          // APPLIED | NO_MATCH | FAILED
    public String error;           // failure reason, or null
    public StateSnap before;
    public StateSnap after;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", (long) index);
        m.put("tick", tick);
        m.put("event", event.toMap());
        m.put("transition", transition);
        m.put("condition", condition);
        m.put("conditionResult", conditionResult);
        m.put("outputs", new ArrayList<>(outputs));
        m.put("status", status);
        m.put("error", error);
        m.put("before", before.toMap());
        m.put("after", after.toMap());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static TraceEntry fromMap(Map<String, Object> m) {
        TraceEntry t = new TraceEntry();
        t.index = (int) Json.asLong(m.get("index"));
        t.tick = Json.asLong(m.get("tick"));
        t.event = EventInstance.fromMap(Json.asMap(m.get("event")));
        Object tr = m.get("transition");
        t.transition = tr == null ? null : Json.asString(tr);
        Object c = m.get("condition");
        t.condition = c == null ? null : Json.asString(c);
        Object cr = m.get("conditionResult");
        t.conditionResult = cr == null ? null : Json.asBool(cr);
        for (Object o : Json.asList(m.get("outputs"))) t.outputs.add(Json.asString(o));
        t.status = Json.asString(m.get("status"));
        Object err = m.get("error");
        t.error = err == null ? null : Json.asString(err);
        t.before = StateSnap.fromMap(Json.asMap(m.get("before")));
        t.after = StateSnap.fromMap(Json.asMap(m.get("after")));
        return t;
    }
}
