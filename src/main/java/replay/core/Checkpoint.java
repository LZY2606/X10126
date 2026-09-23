package replay.core;

import replay.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 检查点：完整可恢复快照，并绑定定义指纹，跨定义版本不可复用。 */
public final class Checkpoint {
    String name;
    int step;                       // 已应用到第几步（轨迹条目数）
    String defFingerprint;
    String state;
    Map<String, Object> vars;
    List<Event> pending = new ArrayList<>();        // 尚未消费的外部事件
    List<Event> internalQueue = new ArrayList<>();  // 排队中的内部事件
    long rngState;
    String rollingHash;

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("step", (long) step);
        m.put("defFingerprint", defFingerprint);
        m.put("state", state);
        m.put("vars", vars);
        List<Object> p = new ArrayList<>();
        for (Event e : pending) p.add(e.toJson());
        m.put("pending", p);
        List<Object> q = new ArrayList<>();
        for (Event e : internalQueue) q.add(e.toJson());
        m.put("internalQueue", q);
        m.put("rngState", Long.toUnsignedString(rngState));
        m.put("rollingHash", rollingHash);
        return m;
    }

    static Checkpoint fromJson(Map<String, Object> m) {
        Checkpoint c = new Checkpoint();
        c.name = Json.asString(m.get("name"), "checkpoint.name");
        c.step = (int) Json.asLong(m.get("step"), "checkpoint.step");
        c.defFingerprint = Json.asString(m.get("defFingerprint"), "checkpoint.defFingerprint");
        c.state = Json.asString(m.get("state"), "checkpoint.state");
        c.vars = new LinkedHashMap<>(Json.asMap(m.get("vars"), "checkpoint.vars"));
        for (Object e : Json.asList(m.get("pending"), "checkpoint.pending")) {
            c.pending.add(Event.fromJson(Json.asMap(e, "event")));
        }
        if (m.containsKey("internalQueue")) {
            for (Object e : Json.asList(m.get("internalQueue"), "checkpoint.internalQueue")) {
                c.internalQueue.add(Event.fromJson(Json.asMap(e, "event")));
            }
        }
        c.rngState = Long.parseUnsignedLong(Json.asString(m.get("rngState"), "checkpoint.rngState"));
        c.rollingHash = Json.asString(m.get("rollingHash"), "checkpoint.rollingHash");
        return c;
    }
}
