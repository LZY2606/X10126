package replay.core;

import replay.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单步轨迹条目：事件、前后快照、输出、失败信息、滚动哈希。 */
final class TraceEntry {
    int step;
    String kind;                 // external | internal
    Event event;
    String stateBefore;
    Map<String, Object> varsBefore;
    String stateAfter;
    Map<String, Object> varsAfter;
    List<String> outputs = new ArrayList<>();
    String failure;              // null 表示成功
    String transition;           // 命中的迁移描述，未命中为 null
    String hash;                 // 滚动哈希

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", (long) step);
        m.put("kind", kind);
        m.put("event", event.toJson());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("state", stateBefore);
        before.put("vars", varsBefore);
        m.put("before", before);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("state", stateAfter);
        after.put("vars", varsAfter);
        m.put("after", after);
        m.put("outputs", new ArrayList<>(outputs));
        m.put("failure", failure);
        m.put("transition", transition);
        m.put("hash", hash);
        return m;
    }

    /** 用于滚动哈希的规范化内容（不含 hash 字段本身）。 */
    String canonicalBody() {
        Map<String, Object> m = toJson();
        m.remove("hash");
        return Json.writeCanonical(m);
    }

    @SuppressWarnings("unchecked")
    static TraceEntry fromJson(Map<String, Object> m) {
        TraceEntry e = new TraceEntry();
        e.step = (int) Json.asLong(m.get("step"), "entry.step");
        e.kind = Json.asString(m.get("kind"), "entry.kind");
        e.event = Event.fromJson(Json.asMap(m.get("event"), "entry.event"));
        Map<String, Object> before = Json.asMap(m.get("before"), "entry.before");
        e.stateBefore = Json.asString(before.get("state"), "before.state");
        e.varsBefore = new LinkedHashMap<>((Map<String, Object>) Json.asMap(before.get("vars"), "before.vars"));
        Map<String, Object> after = Json.asMap(m.get("after"), "entry.after");
        e.stateAfter = Json.asString(after.get("state"), "after.state");
        e.varsAfter = new LinkedHashMap<>((Map<String, Object>) Json.asMap(after.get("vars"), "after.vars"));
        for (Object o : Json.asList(m.get("outputs"), "entry.outputs")) e.outputs.add(Json.asString(o, "output"));
        Object f = m.get("failure");
        e.failure = f == null ? null : Json.asString(f, "entry.failure");
        Object t = m.get("transition");
        e.transition = t == null ? null : Json.asString(t, "entry.transition");
        e.hash = Json.asString(m.get("hash"), "entry.hash");
        return e;
    }
}
