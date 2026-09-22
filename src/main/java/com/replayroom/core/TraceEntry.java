package com.replayroom.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One replayed step: which event, before/after snapshots, outputs, status. */
public final class TraceEntry {
    public enum Status { OK, SKIPPED, UNHANDLED, FAILED }

    public final int index;
    public final Event event;
    public final String beforeState;
    public final Map<String, Object> beforeVars;
    public String afterState;
    public Map<String, Object> afterVars;
    public Status status = Status.OK;
    public final List<String> outputs = new ArrayList<>();
    public String error;

    public TraceEntry(int index, Event event, String beforeState, Map<String, Object> beforeVars) {
        this.index = index;
        this.event = event;
        this.beforeState = beforeState;
        this.beforeVars = beforeVars;
    }

    public void finish(String afterState, Map<String, Object> afterVars) {
        this.afterState = afterState;
        this.afterVars = afterVars;
    }

    /** Canonical map used for hashing: wall-clock free, fully deterministic. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("event", event.toMap());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("state", beforeState);
        before.put("vars", beforeVars);
        m.put("before", before);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("state", afterState);
        after.put("vars", afterVars);
        m.put("after", after);
        m.put("status", status.name());
        m.put("outputs", outputs);
        if (error != null) m.put("error", error);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static TraceEntry fromMap(Map<String, Object> m) {
        Map<String, Object> before = (Map<String, Object>) m.get("before");
        Map<String, Object> after = (Map<String, Object>) m.get("after");
        TraceEntry e = new TraceEntry(
                ((Number) m.get("index")).intValue(),
                Event.fromMap((Map<String, Object>) m.get("event")),
                (String) before.get("state"),
                (Map<String, Object>) before.get("vars"));
        e.finish((String) after.get("state"), (Map<String, Object>) after.get("vars"));
        e.status = Status.valueOf(String.valueOf(m.get("status")));
        Object outs = m.get("outputs");
        if (outs instanceof List) {
            for (Object o : (List<Object>) outs) e.outputs.add(String.valueOf(o));
        }
        Object err = m.get("error");
        if (err != null) e.error = String.valueOf(err);
        return e;
    }
}
