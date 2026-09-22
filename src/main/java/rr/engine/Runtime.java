package rr.engine;

import rr.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 分支的可回放运行时：既是内存执行态，也是落盘/检查点的快照结构。 */
public final class Runtime {
    public String state;
    public Map<String, Object> data;
    public long rngState;
    /** 已派生但尚未处理的内部事件（FIFO，始终位于待处理队首）。 */
    public List<Events.Event> pendingInternals = new ArrayList<>();
    /** 该分支已知的全部外部事件（绝对顺序，稳定排序后）。 */
    public List<Events.Event> absoluteExt = new ArrayList<>();
    /** 已消费的 absoluteExt 前缀长度。 */
    public int extConsumed = 0;
    public int stepIndex = 0;
    public String traceHash;
    public long emitCounter = 0;

    public Runtime copy() {
        Runtime r = new Runtime();
        r.state = state;
        r.data = Paths.copyData(data);
        r.rngState = rngState;
        r.pendingInternals = new ArrayList<>(pendingInternals);
        r.absoluteExt = new ArrayList<>(absoluteExt);
        r.extConsumed = extConsumed;
        r.stepIndex = stepIndex;
        r.traceHash = traceHash;
        r.emitCounter = emitCounter;
        return r;
    }

    public boolean exhausted() {
        return pendingInternals.isEmpty() && extConsumed >= absoluteExt.size();
    }

    public Events.Event peek() {
        if (!pendingInternals.isEmpty()) return pendingInternals.get(0);
        if (extConsumed < absoluteExt.size()) return absoluteExt.get(extConsumed);
        return null;
    }

    public List<Events.Event> pendingView() {
        List<Events.Event> v = new ArrayList<>(pendingInternals);
        v.addAll(absoluteExt.subList(extConsumed, absoluteExt.size()));
        return v;
    }

    // ---------- 快照序列化 ----------
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("data", Paths.copyData(data));
        m.put("rngState", rngState);
        m.put("pendingInternals", eventListToMap(pendingInternals));
        m.put("absoluteExt", eventListToMap(absoluteExt));
        m.put("extConsumed", extConsumed);
        m.put("stepIndex", stepIndex);
        m.put("traceHash", traceHash);
        m.put("emitCounter", emitCounter);
        return m;
    }

    public static Runtime fromMap(Map<String, Object> m) {
        Runtime r = new Runtime();
        r.state = Json.str(m, "state");
        r.data = Json.optObj(m, "data");
        r.rngState = Json.optLng(m, "rngState", 0L);
        r.pendingInternals = eventListFromMap(Json.optArr(m, "pendingInternals"));
        r.absoluteExt = eventListFromMap(Json.optArr(m, "absoluteExt"));
        r.extConsumed = (int) Json.optLng(m, "extConsumed", 0);
        r.stepIndex = (int) Json.optLng(m, "stepIndex", 0);
        r.traceHash = Json.optStr(m, "traceHash", null);
        r.emitCounter = Json.optLng(m, "emitCounter", 0);
        return r;
    }

    static List<Object> eventListToMap(List<Events.Event> es) {
        List<Object> out = new ArrayList<>();
        for (Events.Event e : es) out.add(eventToMap(e));
        return out;
    }

    static Map<String, Object> eventToMap(Events.Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("time", e.time);
        m.put("sourcePriority", e.sourcePriority);
        m.put("seq", e.seq);
        m.put("type", e.type);
        m.put("payload", Paths.copyData(e.payload));
        m.put("internal", e.internal);
        m.put("parentId", e.parentId);
        m.put("emitOrder", e.emitOrder);
        return m;
    }

    static List<Events.Event> eventListFromMap(List<Object> arr) {
        List<Events.Event> out = new ArrayList<>();
        for (Object o : arr) out.add(eventFromMap(o));
        return out;
    }

    static Events.Event eventFromMap(Object ov) {
        Events.Event e = new Events.Event();
        e.id = Json.str(ov, "id");
        e.time = Json.lng(ov, "time");
        e.sourcePriority = Json.optLng(ov, "sourcePriority", 0);
        e.seq = Json.optLng(ov, "seq", 0);
        e.type = Json.str(ov, "type");
        e.payload = Json.optObj(ov, "payload");
        e.internal = Json.optBool(ov, "internal", false);
        e.parentId = Json.optStr(ov, "parentId", null);
        e.emitOrder = Json.optLng(ov, "emitOrder", 0);
        return e;
    }
}
