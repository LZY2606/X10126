package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TraceEntry {
    public final int index;
    public final EventInstance event;
    public final Map<String, Object> before;
    private Map<String, Object> after;
    private List<Object> outputs = List.of();
    private String status; // applied | skipped | failed
    private String error;

    TraceEntry(int index, EventInstance event, Map<String, Object> before) {
        this.index = index;
        this.event = event;
        this.before = before;
    }

    void finishApplied(Map<String, Object> after, List<Object> outputs) {
        this.after = after; this.outputs = new ArrayList<>(outputs); this.status = "applied";
    }

    void finishSkipped(Map<String, Object> after, List<Object> outputs) {
        this.after = after; this.outputs = new ArrayList<>(outputs); this.status = "skipped";
    }

    void finishFailed(Map<String, Object> after, List<Object> outputs, String error) {
        this.after = after; this.outputs = new ArrayList<>(outputs); this.status = "failed"; this.error = error;
    }

    public String status() { return status; }
    public String error() { return error; }
    public Map<String, Object> after() { return after; }
    public List<Object> outputs() { return outputs; }

    /** Stable canonical form used for trajectory hashing. */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", (long) index);
        m.put("event", event.toJson());
        m.put("before", before);
        m.put("after", after);
        m.put("outputs", outputs);
        m.put("status", status);
        if (error != null) m.put("error", error);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static TraceEntry fromJson(Map<String, Object> m) {
        TraceEntry e = new TraceEntry(((Number) m.get("index")).intValue(),
                EventInstance.fromJson((Map<String, Object>) m.get("event")),
                (Map<String, Object>) m.get("before"));
        e.after = (Map<String, Object>) m.get("after");
        e.outputs = m.get("outputs") instanceof List ? new ArrayList<>((List<Object>) m.get("outputs")) : List.of();
        e.status = String.valueOf(m.get("status"));
        e.error = m.get("error") == null ? null : String.valueOf(m.get("error"));
        return e;
    }
}
