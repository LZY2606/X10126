package rr.engine;

import rr.json.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 外部/内部事件及稳定排序。 */
public final class Events {
    private Events() {}

    /**
     * 外部事件形态：
     * {"id":"可选稳定ID","time":10,"sourcePriority":5,"seq":2,
     *  "type":"pay","payload":{...}}
     * sourcePriority 越大越先处理；同刻同优先级按 seq 升序。
     */
    public static final class Event {
        public String id;          // 外部提供或导入时生成 e#
        public long time;
        public long sourcePriority;
        public long seq;           // 原始序号
        public String type;
        public Map<String, Object> payload;
        public boolean internal;   // 内部事件标记
        public String parentId;    // 派生自哪个事件
        public long emitOrder;     // 同事件内派生顺序

        public Map<String, Object> toExprMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", id);
            m.put("time", time);
            m.put("sourcePriority", sourcePriority);
            m.put("seq", seq);
            m.put("type", type);
            m.put("payload", payload);
            m.put("internal", internal);
            return m;
        }
    }

    public static final Comparator<Event> ORDER = (a, b) -> {
        int c = Long.compare(a.time, b.time);
        if (c != 0) return c;
        // 内部事件总是紧跟父事件之后，排在同刻外部事件之前
        if (a.internal != b.internal) return a.internal ? -1 : 1;
        if (a.internal && b.internal) {
            int p = Long.compare(a.parentId == null ? 0 : 1, b.parentId == null ? 0 : 1);
            int byParent = (a.parentId == null || b.parentId == null) ? 0
                    : compareParent(a.parentId, b.parentId);
            if (byParent != 0) return byParent;
            return Long.compare(a.emitOrder, b.emitOrder);
        }
        c = Long.compare(b.sourcePriority, a.sourcePriority);
        if (c != 0) return c;
        c = Long.compare(a.seq, b.seq);
        if (c != 0) return c;
        return a.id.compareTo(b.id);
    };

    private static int compareParent(String a, String b) {
        // 父事件 id 以其外部排序键的字符串表示；派生内部事件只会在运行时入队，
        // 入队顺序由队列结构保证，这里仅作兜底。
        return a.compareTo(b);
    }

    public static Event parseExternal(Object ev, int index) {
        if (!(ev instanceof Map)) throw new BadDefinition("第 " + index + " 个事件必须是 object");
        Event e = new Event();
        e.time = Json.lng(ev, "time");
        e.sourcePriority = Json.optLng(ev, "sourcePriority", 0);
        e.seq = Json.optLng(ev, "seq", index);
        e.type = Json.str(ev, "type");
        e.payload = Json.optObj(ev, "payload");
        e.id = Json.optStr(ev, "id", "e" + index + "#" + e.time + "#" + e.sourcePriority + "#" + e.seq + "#" + e.type);
        e.internal = false;
        return e;
    }

    public static List<Event> normalizeExternal(List<Object> raw) {
        List<Event> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) out.add(parseExternal(raw.get(i), i));
        out.sort(ORDER);
        return out;
    }
}
