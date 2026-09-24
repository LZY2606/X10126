package replay.core;

import replay.json.Json;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;

/**
 * 事件：kind=external 为导入的外部事件，kind=internal 为动作派生的内部事件。
 *
 * 稳定顺序（同逻辑时刻）：
 *   time 升序 -> internal 先于 external -> 来源 priority 升序 -> seq 升序 -> id 升序
 * 内部事件 priority=0，普通外部来源默认 priority=100。
 */
public final class Event {

    public static final String EXTERNAL = "external";
    public static final String INTERNAL = "internal";
    public static final String INTERNAL_SOURCE = "__internal";
    public static final int INTERNAL_PRIORITY = 0;
    public static final int DEFAULT_PRIORITY = 100;

    public final String id;
    public final String kind;
    public final long time;
    public final String source;
    public final long seq;
    public final String type;
    public final Map<String, Object> payload;

    public Event(String id, String kind, long time, String source, long seq,
                 String type, Map<String, Object> payload) {
        this.id = id;
        this.kind = kind;
        this.time = time;
        this.source = source;
        this.seq = seq;
        this.type = type;
        this.payload = payload;
    }

    public static Comparator<Event> comparator() {
        return (a, b) -> {
            int cmp = Long.compare(a.time, b.time);
            if (cmp != 0) {
                return cmp;
            }
            cmp = kindRank(a.kind) - kindRank(b.kind);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(priorityOf(a), priorityOf(b));
            if (cmp != 0) {
                return cmp;
            }
            cmp = Long.compare(a.seq, b.seq);
            if (cmp != 0) {
                return cmp;
            }
            return a.id.compareTo(b.id);
        };
    }

    private static int kindRank(String kind) {
        return INTERNAL.equals(kind) ? 0 : 1;
    }

    private static int priorityOf(Event e) {
        return INTERNAL.equals(e.kind) ? INTERNAL_PRIORITY : DEFAULT_PRIORITY;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = Json.newObj();
        m.put(Keys.EV_ID, id);
        m.put(Keys.EV_KIND, kind);
        m.put(Keys.EV_TIME, BigDecimal.valueOf(time));
        m.put(Keys.EV_SOURCE, source);
        m.put(Keys.EV_SEQ, BigDecimal.valueOf(seq));
        m.put(Keys.EV_TYPE, type);
        m.put(Keys.EV_PAYLOAD, payload);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Event fromJson(Object value) {
        Map<String, Object> m = Json.obj(value);
        return new Event(
                Json.getString(m, Keys.EV_ID),
                Json.getString(m, Keys.EV_KIND),
                ((Number) m.get(Keys.EV_TIME)).longValue(),
                Json.getString(m, Keys.EV_SOURCE),
                ((Number) m.get(Keys.EV_SEQ)).longValue(),
                Json.getString(m, Keys.EV_TYPE),
                m.get(Keys.EV_PAYLOAD) instanceof Map
                        ? (Map<String, Object>) m.get(Keys.EV_PAYLOAD)
                        : Json.newObj());
    }
}
