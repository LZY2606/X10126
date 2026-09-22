package replay.model;

import replay.json.Json;

import java.util.Map;

/** 外部事件：逻辑时间 + 来源 + 原始序号。insertion 为分支内到达次序，作为最终决胜键。 */
public final class ExternalEvent {
    public final String id;
    public final String type;
    public final String source;
    public final long time;
    public final long seq;
    public final long insertion;
    public final Map<String, Object> payload;

    public ExternalEvent(String id, String type, String source, long time, long seq, long insertion,
                         Map<String, Object> payload) {
        this.id = id;
        this.type = type;
        this.source = source;
        this.time = time;
        this.seq = seq;
        this.insertion = insertion;
        this.payload = payload;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = Json.obj();
        m.put("kind", "external");
        m.put("id", id);
        m.put("type", type);
        m.put("source", source);
        m.put("time", time);
        m.put("seq", seq);
        m.put("insertion", insertion);
        m.put("payload", payload);
        return m;
    }

    public static ExternalEvent fromMap(Map<String, Object> m) {
        return new ExternalEvent(
                Json.optString(m, "id", null),
                Json.asString(m.get("type")),
                Json.optString(m, "source", ""),
                Json.optLong(m, "time", 0),
                Json.optLong(m, "seq", 0),
                Json.optLong(m, "insertion", 0),
                m.get("payload") == null ? Json.obj() : Json.asMap(m.get("payload")));
    }

    /** 内容身份：不含分支内到达次序，用于合并时的内容一致性判定。 */
    public String contentKey() {
        Map<String, Object> m = toMap();
        m.remove("insertion");
        return Json.canonical(m);
    }
}
