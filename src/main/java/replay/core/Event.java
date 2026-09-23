package replay.core;

import replay.json.Json;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件。外部事件带逻辑时间 ts、来源 source、原始序号 seq（会话内唯一）。
 * 排序规则：ts 升序 → 来源优先级（数值小者优先）→ seq 升序，完全确定。
 * 内部事件由动作派生，不进入外部排序，只在当前事件之后按 FIFO 处理。
 */
public final class Event {
    public final long seq;
    public final long ts;
    public final String source;
    public final String type;
    public final Map<String, Object> data;
    public final boolean internal;

    public Event(long seq, long ts, String source, String type, Map<String, Object> data, boolean internal) {
        this.seq = seq;
        this.ts = ts;
        this.source = source;
        this.type = type;
        this.data = data;
        this.internal = internal;
    }

    public static Comparator<Event> order(Definition def) {
        return Comparator
                .comparingLong((Event e) -> e.ts)
                .thenComparingLong(e -> def.priorityOf(e.source))
                .thenComparingLong(e -> e.seq);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("ts", ts);
        m.put("source", source);
        m.put("type", type);
        m.put("data", data);
        m.put("internal", internal);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Event fromJson(Map<String, Object> m) {
        Map<String, Object> data = m.containsKey("data")
                ? (Map<String, Object>) Json.asMap(m.get("data"), "event.data")
                : new LinkedHashMap<>();
        return new Event(
                Json.asLong(m.get("seq"), "event.seq"),
                Json.asLong(m.get("ts"), "event.ts"),
                Json.asString(m.get("source"), "event.source"),
                Json.asString(m.get("type"), "event.type"),
                data,
                Boolean.TRUE.equals(m.get("internal")));
    }

    /** 从用户导入的原始 JSON（无 seq）构造外部事件。 */
    @SuppressWarnings("unchecked")
    public static Event fromImport(Map<String, Object> m, long seq) {
        Map<String, Object> data = m.containsKey("data")
                ? (Map<String, Object>) Json.asMap(m.get("data"), "event.data")
                : new LinkedHashMap<>();
        String source = m.containsKey("source") ? Json.asString(m.get("source"), "event.source") : "default";
        return new Event(seq, Json.asLong(m.get("ts"), "event.ts"), source,
                Json.asString(m.get("type"), "event.type"), data, false);
    }
}
