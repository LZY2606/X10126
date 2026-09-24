package replay;

import java.util.Map;

/** A replayable event. External events come from the imported log; internal events are emitted by actions. */
public final class Event {
    public static final String INTERNAL_SOURCE = "@internal";

    public String id;
    public long time;
    public String source;
    public long seq;
    public String name;
    public Map<String, Object> payload;
    public boolean internal;

    public Map<String, Object> toJson() {
        Map<String, Object> m = Json.map();
        m.put("id", id);
        m.put("time", time);
        m.put("source", source);
        m.put("seq", seq);
        m.put("name", name);
        m.put("payload", payload == null ? Json.map() : payload);
        m.put("internal", internal);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Event fromJson(Map<String, Object> m) {
        Event e = new Event();
        e.id = Json.str(m.get("id"));
        e.time = Json.num(m.get("time"));
        e.source = Json.str(m.get("source"));
        e.seq = Json.num(m.get("seq"));
        e.name = Json.str(m.get("name"));
        Object p = m.get("payload");
        e.payload = p == null ? Json.map() : (Map<String, Object>) Json.deepCopy(p);
        Object in = m.get("internal");
        e.internal = in instanceof Boolean && (Boolean) in;
        return e;
    }

    public Event copy() {
        return fromJson(toJson());
    }
}
