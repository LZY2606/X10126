package replay.model;

import replay.json.Json;

import java.util.List;
import java.util.Map;

/** 检查点：绑定定义指纹的完整可恢复快照。 */
public final class Checkpoint {
    public String id;
    public String name;
    public String branchId;
    public String definitionHash;
    public long stepIndex;
    public long processedExternalCount;
    public long insertionCounter;
    public String state;
    public Map<String, Object> data = Json.obj();
    public List<Object> outputs = Json.arr();
    public List<Object> failures = Json.arr();
    public List<Object> internalQueue = Json.arr();
    public String rng;
    public List<Object> remainingExternal = Json.arr();
    public String traceHash;

    public Map<String, Object> snapshotMap() {
        Map<String, Object> snap = Json.obj();
        snap.put("state", state);
        snap.put("data", data);
        snap.put("outputs", outputs);
        snap.put("failures", failures);
        snap.put("internalQueue", internalQueue);
        snap.put("rng", rng);
        return snap;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = Json.obj();
        m.put("kind", "checkpoint");
        m.put("id", id);
        m.put("name", name);
        m.put("branchId", branchId);
        m.put("definitionHash", definitionHash);
        m.put("stepIndex", stepIndex);
        m.put("processedExternalCount", processedExternalCount);
        m.put("insertionCounter", insertionCounter);
        m.put("traceHash", traceHash);
        m.put("snapshot", snapshotMap());
        m.put("remainingExternal", remainingExternal);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Checkpoint fromMap(Map<String, Object> m) {
        Checkpoint c = new Checkpoint();
        c.id = Json.optString(m, "id", null);
        c.name = Json.optString(m, "name", c.id);
        c.branchId = Json.optString(m, "branchId", null);
        c.definitionHash = Json.optString(m, "definitionHash", null);
        c.stepIndex = Json.optLong(m, "stepIndex", 0);
        c.processedExternalCount = Json.optLong(m, "processedExternalCount", 0);
        c.insertionCounter = Json.optLong(m, "insertionCounter", 0);
        c.traceHash = Json.optString(m, "traceHash", null);
        Map<String, Object> snap = m.get("snapshot") == null ? Json.obj() : Json.asMap(m.get("snapshot"));
        c.state = Json.optString(snap, "state", null);
        c.data = snap.get("data") == null ? Json.obj() : Json.asMap(snap.get("data"));
        c.outputs = snap.get("outputs") == null ? Json.arr() : Json.asList(snap.get("outputs"));
        c.failures = snap.get("failures") == null ? Json.arr() : Json.asList(snap.get("failures"));
        c.internalQueue = snap.get("internalQueue") == null ? Json.arr() : Json.asList(snap.get("internalQueue"));
        c.rng = Json.optString(snap, "rng", null);
        c.remainingExternal = m.get("remainingExternal") == null ? Json.arr()
                : Json.asList(m.get("remainingExternal"));
        return c;
    }
}
