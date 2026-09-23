package replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replay.json.Json;

/** A replay line: its own queues, variables, RNG state, clock and step trace. */
public final class Branch {
    public String id;
    public String name;
    public String forkCheckpoint; // nullable
    public String state;
    public Map<String, Object> vars = new LinkedHashMap<>();
    public List<Object> outputs = new ArrayList<>();
    public List<Event> pendingExternals = new ArrayList<>();
    public List<Event> internalQueue = new ArrayList<>();
    public List<StepRecord> steps = new ArrayList<>();
    public long rngState;
    public long clock;
    public String traceHash;

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("forkCheckpoint", forkCheckpoint);
        map.put("state", state);
        map.put("vars", vars);
        map.put("outputs", outputs);
        List<Object> pe = new ArrayList<>();
        for (Event e : pendingExternals) {
            pe.add(e.toJson());
        }
        map.put("pendingExternals", pe);
        List<Object> iq = new ArrayList<>();
        for (Event e : internalQueue) {
            iq.add(e.toJson());
        }
        map.put("internalQueue", iq);
        List<Object> st = new ArrayList<>();
        for (StepRecord s : steps) {
            st.add(s.toJson());
        }
        map.put("steps", st);
        map.put("rngState", rngState);
        map.put("clock", clock);
        map.put("traceHash", traceHash);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Branch fromJson(Map<String, Object> map) {
        Branch b = new Branch();
        b.id = (String) map.get("id");
        b.name = (String) map.get("name");
        b.forkCheckpoint = (String) map.get("forkCheckpoint");
        b.state = (String) map.get("state");
        b.vars = (Map<String, Object>) map.get("vars");
        b.outputs = new ArrayList<>((List<Object>) map.get("outputs"));
        for (Object e : (List<Object>) map.get("pendingExternals")) {
            b.pendingExternals.add(Event.fromJson(Json.obj(e, "event")));
        }
        for (Object e : (List<Object>) map.get("internalQueue")) {
            b.internalQueue.add(Event.fromJson(Json.obj(e, "event")));
        }
        for (Object s : (List<Object>) map.get("steps")) {
            b.steps.add(StepRecord.fromJson(Json.obj(s, "step")));
        }
        b.rngState = ((Number) map.get("rngState")).longValue();
        b.clock = ((Number) map.get("clock")).longValue();
        b.traceHash = (String) map.get("traceHash");
        return b;
    }
}
