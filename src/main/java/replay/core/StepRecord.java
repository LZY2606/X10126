package replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StepRecord {
    public long index;
    public long eventId;
    public String eventKind;
    public String eventType;
    public long time;
    public String source;
    public boolean matched;
    public String beforeState;
    public String afterState;
    public Map<String, Object> beforeVars;
    public Map<String, Object> afterVars;
    public List<Object> outputs = new ArrayList<>();
    public List<Map<String, Object>> spawned = new ArrayList<>();
    public String failure;
    public String hash;

    /** JSON without the rolling hash; this is exactly what gets hashed. */
    public Map<String, Object> toJsonCore() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("index", index);
        map.put("eventId", eventId);
        map.put("eventKind", eventKind);
        map.put("eventType", eventType);
        map.put("time", time);
        map.put("source", source);
        map.put("matched", matched);
        map.put("beforeState", beforeState);
        map.put("afterState", afterState);
        map.put("beforeVars", beforeVars);
        map.put("afterVars", afterVars);
        map.put("outputs", outputs);
        map.put("spawned", spawned);
        map.put("failure", failure);
        return map;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = toJsonCore();
        map.put("hash", hash);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static StepRecord fromJson(Map<String, Object> map) {
        StepRecord r = new StepRecord();
        r.index = ((Number) map.get("index")).longValue();
        r.eventId = ((Number) map.get("eventId")).longValue();
        r.eventKind = (String) map.get("eventKind");
        r.eventType = (String) map.get("eventType");
        r.time = ((Number) map.get("time")).longValue();
        r.source = (String) map.get("source");
        r.matched = Boolean.TRUE.equals(map.get("matched"));
        r.beforeState = (String) map.get("beforeState");
        r.afterState = (String) map.get("afterState");
        r.beforeVars = (Map<String, Object>) map.get("beforeVars");
        r.afterVars = (Map<String, Object>) map.get("afterVars");
        r.outputs = (List<Object>) map.get("outputs");
        r.spawned = (List<Map<String, Object>>) map.get("spawned");
        r.failure = (String) map.get("failure");
        r.hash = (String) map.get("hash");
        return r;
    }
}
