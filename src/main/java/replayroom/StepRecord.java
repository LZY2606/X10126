package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一步事件处理的完整轨迹：前快照、后快照、输出、派生内部事件、失败信息、哈希链。 */
public final class StepRecord {
    public int index;
    public String stepHash;
    public String prevHash;

    public Event event;
    public String stateBefore;
    public Map<String, Object> varsBefore = new LinkedHashMap<>();
    public long rngBefore;

    public String stateAfter;
    public Map<String, Object> varsAfter = new LinkedHashMap<>();
    public long rngAfter;
    public long birthAfter;

    public boolean matched = true;
    public Integer transitionIndex;
    public boolean success = true;
    public String failure;

    public List<Output> outputs = new ArrayList<>();
    public List<Event> raised = new ArrayList<>();
    public List<Event> pendingAfter = new ArrayList<>();

    public static final class Output {
        public String channel;
        public Map<String, Object> payload = new LinkedHashMap<>();

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", channel);
            m.put("payload", payload);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Output fromJson(Map<String, Object> m) {
            Output o = new Output();
            o.channel = (String) m.get("channel");
            if (m.get("payload") instanceof Map)
                o.payload = new LinkedHashMap<>((Map<String, Object>) m.get("payload"));
            return o;
        }
    }

    /** 参与哈希链/重放的逻辑内容（不含派生标识，保证跨会话导入相同）。 */
    public Map<String, Object> canonical() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("prevHash", prevHash);
        m.put("event", eventCanonical(event));
        m.put("stateBefore", stateBefore);
        m.put("varsBefore", varsBefore);
        m.put("rngBefore", rngBefore);
        m.put("stateAfter", stateAfter);
        m.put("varsAfter", varsAfter);
        m.put("rngAfter", rngAfter);
        m.put("birthAfter", birthAfter);
        m.put("matched", matched);
        m.put("transitionIndex", transitionIndex);
        m.put("success", success);
        m.put("failure", failure);
        List<Object> outs = new ArrayList<>();
        for (Output o : outputs) outs.add(o.toJson());
        m.put("outputs", outs);
        List<Object> raisedL = new ArrayList<>();
        for (Event e : raised) raisedL.add(e.toJson());
        m.put("raised", raisedL);
        return m;
    }

    /** 事件的逻辑内容：不含 API 分配的 id；内部事件用 parentBirth 关联父事件。 */
    public static Map<String, Object> eventCanonical(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("birth", e.birth);
        m.put("kind", e.kind);
        m.put("time", e.time);
        m.put("source", e.source);
        m.put("seq", e.seq);
        m.put("type", e.type);
        m.put("payload", e.payload);
        if (e.isInternal()) m.put("parentBirth", e.parentBirth);
        return m;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = canonical();
        m.put("stepHash", stepHash);
        List<Object> pend = new ArrayList<>();
        for (Event e : pendingAfter) pend.add(e.toJson());
        m.put("pendingAfter", pend);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static StepRecord fromJson(Map<String, Object> m) {
        StepRecord s = new StepRecord();
        s.index = ((Number) m.get("index")).intValue();
        s.stepHash = (String) m.get("stepHash");
        s.prevHash = (String) m.get("prevHash");
        s.event = Event.fromJson(Json.obj(m.get("event")));
        s.stateBefore = (String) m.get("stateBefore");
        s.varsBefore = new LinkedHashMap<>(Json.obj(m.get("varsBefore")));
        s.rngBefore = ((Number) m.getOrDefault("rngBefore", 0)).longValue();
        s.stateAfter = (String) m.get("stateAfter");
        s.varsAfter = new LinkedHashMap<>(Json.obj(m.get("varsAfter")));
        s.rngAfter = ((Number) m.getOrDefault("rngAfter", 0)).longValue();
        s.birthAfter = ((Number) m.getOrDefault("birthAfter", 0)).longValue();
        s.matched = Boolean.TRUE.equals(m.get("matched"));
        if (m.get("transitionIndex") instanceof Number)
            s.transitionIndex = ((Number) m.get("transitionIndex")).intValue();
        s.success = Boolean.TRUE.equals(m.get("success"));
        s.failure = (String) m.get("failure");
        for (Object o : Json.list(m.get("outputs")))
            s.outputs.add(Output.fromJson(Json.obj(o)));
        for (Object e : Json.list(m.get("raised")))
            s.raised.add(Event.fromJson(Json.obj(e)));
        for (Object e : Json.list(m.get("pendingAfter")))
            s.pendingAfter.add(Event.fromJson(Json.obj(e)));
        return s;
    }
}
