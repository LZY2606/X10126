package replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replay.json.Json;

/**
 * Immutable snapshot of a branch. Carries the definition fingerprint so a
 * checkpoint taken under an old definition can never be attached to a new one.
 */
public final class Checkpoint {
    public String id;
    public String name;
    public String defFingerprint;
    public String branchId;
    public long stepIndex;
    public String state;
    public Map<String, Object> vars;
    public List<Object> outputs;
    public List<Event> pendingExternals;
    public List<Event> internalQueue;
    public List<StepRecord> tracePrefix; // full trace up to this point
    public long rngState;
    public long clock;
    public String traceHash;

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("defFingerprint", defFingerprint);
        map.put("branchId", branchId);
        map.put("stepIndex", stepIndex);
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
        for (StepRecord s : tracePrefix) {
            st.add(s.toJson());
        }
        map.put("tracePrefix", st);
        map.put("rngState", rngState);
        map.put("clock", clock);
        map.put("traceHash", traceHash);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Checkpoint fromJson(Map<String, Object> map) {
        Checkpoint c = new Checkpoint();
        c.id = (String) map.get("id");
        c.name = (String) map.get("name");
        c.defFingerprint = (String) map.get("defFingerprint");
        c.branchId = (String) map.get("branchId");
        c.stepIndex = ((Number) map.get("stepIndex")).longValue();
        c.state = (String) map.get("state");
        c.vars = (Map<String, Object>) map.get("vars");
        c.outputs = new ArrayList<>((List<Object>) map.get("outputs"));
        c.pendingExternals = new ArrayList<>();
        for (Object e : (List<Object>) map.get("pendingExternals")) {
            c.pendingExternals.add(Event.fromJson(Json.obj(e, "event")));
        }
        c.internalQueue = new ArrayList<>();
        for (Object e : (List<Object>) map.get("internalQueue")) {
            c.internalQueue.add(Event.fromJson(Json.obj(e, "event")));
        }
        c.tracePrefix = new ArrayList<>();
        for (Object s : (List<Object>) map.get("tracePrefix")) {
            c.tracePrefix.add(StepRecord.fromJson(Json.obj(s, "step")));
        }
        c.rngState = ((Number) map.get("rngState")).longValue();
        c.clock = ((Number) map.get("clock")).longValue();
        c.traceHash = (String) map.get("traceHash");
        return c;
    }
}
