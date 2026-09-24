package replayroom;

import java.util.LinkedHashMap;
import java.util.Map;

/** 外部导入或动作派生的事件。同一逻辑时刻按稳定全序排列。 */
public final class Event {
    public static final String INTERNAL_SOURCE = "__internal__";
    public static final int INTERNAL_RANK = Integer.MAX_VALUE;
    public static final int DEFAULT_RANK = 1000;

    public String id;
    public long birth;
    public String kind;      // external | internal
    public long time;
    public String source;
    public long seq;
    public String type;
    public Map<String, Object> payload = new LinkedHashMap<>();
    public String parentEventId;
    public long parentBirth;

    public Event() {}

    public boolean isInternal() { return "internal".equals(kind); }

    public int rank(MachineDef machine) {
        if (isInternal()) return INTERNAL_RANK;
        Integer r = machine.sources.get(source);
        return r == null ? DEFAULT_RANK : r;
    }

    public String identity() {
        return time + "|" + source + "|" + seq;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("birth", birth);
        m.put("kind", kind);
        m.put("time", time);
        m.put("source", source);
        m.put("seq", seq);
        m.put("type", type);
        m.put("payload", payload);
        if (isInternal()) m.put("parentBirth", parentBirth);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Event fromJson(Map<String, Object> m) {
        Event e = new Event();
        e.id = (String) m.get("id");
        e.birth = ((Number) m.getOrDefault("birth", 0)).longValue();
        e.kind = (String) m.getOrDefault("kind", "external");
        e.time = ((Number) m.getOrDefault("time", 0)).longValue();
        e.source = (String) m.get("source");
        e.seq = ((Number) m.getOrDefault("seq", 0)).longValue();
        e.type = (String) m.get("type");
        Object p = m.get("payload");
        if (p instanceof Map) e.payload = new LinkedHashMap<>((Map<String, Object>) p);
        e.parentEventId = (String) m.get("parentEventId");
        e.parentBirth = ((Number) m.getOrDefault("parentBirth", 0)).longValue();
        return e;
    }
}
