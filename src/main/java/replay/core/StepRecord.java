package replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One entry in the replay trace: what happened when a single event was processed. */
public final class StepRecord {
    public long index;
    public String eventId;
    public String eventType;
    public long time;
    public String source;
    public int sourcePriority;
    public long seq;
    public boolean internal;
    public String beforeState;
    public String afterState;
    public LinkedHashMap<String, Object> beforeVars;
    public LinkedHashMap<String, Object> afterVars;
    public List<String> outputs = new ArrayList<>();
    public List<Map<String, Object>> emitted = new ArrayList<>();
    public String failure; // non-null when the action batch was rolled back
    public int matchedRules;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("eventId", eventId);
        m.put("eventType", eventType);
        m.put("time", time);
        m.put("source", source);
        m.put("sourcePriority", sourcePriority);
        m.put("seq", seq);
        m.put("internal", internal);
        m.put("beforeState", beforeState);
        m.put("afterState", afterState);
        m.put("beforeVars", new LinkedHashMap<>(beforeVars));
        m.put("afterVars", new LinkedHashMap<>(afterVars));
        m.put("outputs", new ArrayList<>(outputs));
        m.put("emitted", new ArrayList<>(emitted));
        m.put("failure", failure);
        m.put("matchedRules", matchedRules);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static StepRecord fromMap(Map<String, Object> m) {
        StepRecord r = new StepRecord();
        r.index = EventInstance.num(m.get("index"));
        r.eventId = EventInstance.str(m.get("eventId"));
        r.eventType = EventInstance.str(m.get("eventType"));
        r.time = EventInstance.num(m.get("time"));
        r.source = EventInstance.str(m.get("source"));
        r.sourcePriority = (int) EventInstance.num(m.get("sourcePriority"));
        r.seq = EventInstance.num(m.get("seq"));
        r.internal = Boolean.TRUE.equals(m.get("internal"));
        r.beforeState = EventInstance.str(m.get("beforeState"));
        r.afterState = EventInstance.str(m.get("afterState"));
        r.beforeVars = new LinkedHashMap<>((Map<String, Object>) m.get("beforeVars"));
        r.afterVars = new LinkedHashMap<>((Map<String, Object>) m.get("afterVars"));
        for (Object o : (List<Object>) m.get("outputs")) r.outputs.add(o.toString());
        for (Object o : (List<Object>) m.get("emitted")) r.emitted.add((Map<String, Object>) o);
        r.failure = EventInstance.str(m.get("failure"));
        r.matchedRules = (int) EventInstance.num(m.get("matchedRules"));
        return r;
    }
}
