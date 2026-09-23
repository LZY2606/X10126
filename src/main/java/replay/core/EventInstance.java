package replay.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** A queued event occurrence. Ordering: (time, sourcePriority, seq, insertion). */
public final class EventInstance implements Comparable<EventInstance> {
    public String id;
    public String type;
    public long time;
    public String source;
    public int sourcePriority;
    public long seq;
    public boolean internal;
    public long insertion;

    public EventInstance copy() {
        EventInstance e = new EventInstance();
        e.id = id; e.type = type; e.time = time; e.source = source;
        e.sourcePriority = sourcePriority; e.seq = seq; e.internal = internal; e.insertion = insertion;
        return e;
    }

    @Override
    public int compareTo(EventInstance o) {
        int c = Long.compare(time, o.time);
        if (c != 0) return c;
        c = Integer.compare(sourcePriority, o.sourcePriority);
        if (c != 0) return c;
        c = Long.compare(seq, o.seq);
        if (c != 0) return c;
        return Long.compare(insertion, o.insertion);
    }

    /** Ordering key shared by external events; used by merge ambiguity checks. */
    public String orderKey() {
        return time + ":" + sourcePriority + ":" + seq;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("time", time);
        m.put("source", source);
        m.put("sourcePriority", sourcePriority);
        m.put("seq", seq);
        m.put("internal", internal);
        m.put("insertion", insertion);
        return m;
    }

    public static EventInstance fromMap(Map<String, Object> m) {
        EventInstance e = new EventInstance();
        e.id = str(m.get("id"));
        e.type = str(m.get("type"));
        e.time = num(m.get("time"));
        e.source = str(m.get("source"));
        e.sourcePriority = (int) num(m.get("sourcePriority"));
        e.seq = num(m.get("seq"));
        e.internal = Boolean.TRUE.equals(m.get("internal"));
        e.insertion = num(m.get("insertion"));
        return e;
    }

    static String str(Object o) { return o == null ? null : o.toString(); }
    static long num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }
}
