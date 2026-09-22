package replay;

import java.util.LinkedHashMap;
import java.util.Map;

/** A replayable event. Ordering: (logicalTime, sourcePriority, seq). */
public final class Event implements Comparable<Event> {
    public long uid;            // engine-assigned unique id within a session
    public long logicalTime;
    public String source;
    public long priority;       // resolved from definition at injection
    public long seq;            // original sequence number from the log / engine counter
    public String type;
    public Map<String, Object> payload = new LinkedHashMap<>();
    public boolean internal;
    public long originUid = -1; // for internal events: uid of the event whose action derived it

    public static Event fromMap(Map<String, Object> m, long uid, long fallbackSeq) {
        Event e = new Event();
        e.uid = uid;
        Object lt = m.get("logicalTime");
        if (lt == null) lt = m.get("time");
        e.logicalTime = lt instanceof Number n ? n.longValue() : 0L;
        e.source = Definition.str(m.get("source"), "external");
        Object sq = m.get("seq");
        e.seq = sq instanceof Number n ? n.longValue() : fallbackSeq;
        e.type = Definition.str(m.get("type"), null);
        if (e.type == null) throw new IllegalArgumentException("event needs 'type'");
        if (m.get("payload") instanceof Map) e.payload = Definition.map(m.get("payload"));
        return e;
    }

    /** Content identity used for fingerprints and merge compatibility. */
    public Map<String, Object> contentMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logicalTime", logicalTime);
        m.put("source", source);
        m.put("seq", seq);
        m.put("type", type);
        m.put("payload", payload);
        return m;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = contentMap();
        m.put("uid", uid);
        m.put("priority", priority);
        m.put("internal", internal);
        if (originUid >= 0) m.put("originUid", originUid);
        return m;
    }

    public static Event restore(Map<String, Object> m) {
        Event e = new Event();
        e.uid = Definition.num(m.get("uid"), 0);
        e.logicalTime = Definition.num(m.get("logicalTime"), 0);
        e.source = Definition.str(m.get("source"), "external");
        e.priority = Definition.num(m.get("priority"), 1000);
        e.seq = Definition.num(m.get("seq"), 0);
        e.type = Definition.str(m.get("type"), null);
        if (m.get("payload") instanceof Map) e.payload = Definition.map(m.get("payload"));
        e.internal = Boolean.TRUE.equals(m.get("internal"));
        e.originUid = Definition.num(m.get("originUid"), -1);
        return e;
    }

    public Event copy() {
        Event e = new Event();
        e.uid = uid;
        e.logicalTime = logicalTime;
        e.source = source;
        e.priority = priority;
        e.seq = seq;
        e.type = type;
        e.payload = new LinkedHashMap<>(payload);
        e.internal = internal;
        e.originUid = originUid;
        return e;
    }

    @Override
    public int compareTo(Event other) {
        int c = Long.compare(logicalTime, other.logicalTime);
        if (c != 0) return c;
        c = Long.compare(priority, other.priority);
        if (c != 0) return c;
        c = Long.compare(seq, other.seq);
        if (c != 0) return c;
        return Long.compare(uid, other.uid);
    }
}
