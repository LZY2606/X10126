package replay.model;

import replay.json.Json;

import java.util.List;
import java.util.Map;

/** 轨迹中的一步：完整记录处理前后快照，hash 为链式指纹。 */
public final class TraceStep {
    public long index;
    public String kind;
    public Map<String, Object> event;
    public boolean matched;
    public long transition = -1;
    public boolean failed;
    public String failureMessage;
    public String stateBefore;
    public String stateAfter;
    public Map<String, Object> dataBefore = Json.obj();
    public Map<String, Object> dataAfter = Json.obj();
    public List<Object> outputsAdded = Json.arr();
    public List<Object> emitted = Json.arr();
    public String hash;

    public Map<String, Object> coreMap() {
        Map<String, Object> m = Json.obj();
        m.put("index", index);
        m.put("kind", kind);
        m.put("event", event);
        m.put("matched", matched);
        m.put("transition", transition);
        m.put("failed", failed);
        m.put("failureMessage", failureMessage);
        m.put("stateBefore", stateBefore);
        m.put("stateAfter", stateAfter);
        m.put("dataBefore", dataBefore);
        m.put("dataAfter", dataAfter);
        m.put("outputsAdded", outputsAdded);
        m.put("emitted", emitted);
        return m;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = coreMap();
        m.put("hash", hash);
        return m;
    }

    public static TraceStep fromMap(Map<String, Object> m) {
        TraceStep s = new TraceStep();
        s.index = Json.optLong(m, "index", 0);
        s.kind = Json.optString(m, "kind", "external");
        s.event = Json.asMap(m.get("event"));
        s.matched = Boolean.TRUE.equals(m.get("matched"));
        s.transition = Json.optLong(m, "transition", -1);
        s.failed = Boolean.TRUE.equals(m.get("failed"));
        Object fm = m.get("failureMessage");
        s.failureMessage = fm == null ? null : String.valueOf(fm);
        s.stateBefore = Json.optString(m, "stateBefore", null);
        s.stateAfter = Json.optString(m, "stateAfter", null);
        s.dataBefore = m.get("dataBefore") == null ? Json.obj() : Json.asMap(m.get("dataBefore"));
        s.dataAfter = m.get("dataAfter") == null ? Json.obj() : Json.asMap(m.get("dataAfter"));
        s.outputsAdded = m.get("outputsAdded") == null ? Json.arr() : Json.asList(m.get("outputsAdded"));
        s.emitted = m.get("emitted") == null ? Json.arr() : Json.asList(m.get("emitted"));
        s.hash = Json.optString(m, "hash", null);
        return s;
    }
}
