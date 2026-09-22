package replay.model;

import replay.json.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 分支：一条可独立回放的时间线，含工作态、轨迹与检查点。 */
public final class Branch {
    public String id;
    public String name;
    public String parentBranchId;
    public String forkCheckpointId;
    public String baseTraceHash;
    public Map<String, Object> baseSnapshot;
    public boolean paused;
    public long insertionCounter;

    public String state;
    public Map<String, Object> data = Json.obj();
    public List<Object> outputs = Json.arr();
    public List<Object> failures = Json.arr();
    public Deque<Map<String, Object>> internalQueue = new ArrayDeque<>();
    public Random rng = new Random(0);

    public List<ExternalEvent> remaining = new ArrayList<>();
    public List<ExternalEvent> processed = new ArrayList<>();
    public List<TraceStep> trace = new ArrayList<>();
    public List<Checkpoint> checkpoints = new ArrayList<>();

    public String traceHash() {
        return trace.isEmpty() ? baseTraceHash : trace.get(trace.size() - 1).hash;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = Json.obj();
        m.put("id", id);
        m.put("name", name);
        m.put("parentBranchId",	parentBranchId);
        m.put("forkCheckpointId", forkCheckpointId);
        m.put("baseTraceHash", baseTraceHash);
        m.put("baseSnapshot", baseSnapshot);
        m.put("paused", paused);
        m.put("insertionCounter", insertionCounter);
        m.put("state", state);
        m.put("data", data);
        m.put("outputs", outputs);
        m.put("failures", failures);
        m.put("internalQueue", new ArrayList<>(internalQueue));
        m.put("rng", Rngs.toBase64(rng));
        List<Object> pe = Json.arr();
        for (ExternalEvent e : processed) pe.add(e.toMap());
        m.put("processedEvents", pe);
        List<Object> re = Json.arr();
        for (ExternalEvent e : remaining) re.add(e.toMap());
        m.put("remainingEvents", re);
        List<Object> tr = Json.arr();
        for (TraceStep s : trace) tr.add(s.toMap());
        m.put("trace", tr);
        List<Object> cps = Json.arr();
        for (Checkpoint c : checkpoints) cps.add(c.toMap());
        m.put("checkpoints", cps);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Branch fromMap(Map<String, Object> m) {
        Branch b = new Branch();
        b.id = Json.optString(m, "id", null);
        b.name = Json.optString(m, "name", b.id);
        b.parentBranchId = Json.optString(m, "parentBranchId", null);
        b.forkCheckpointId = Json.optString(m, "forkCheckpointId", null);
        b.baseTraceHash = Json.optString(m, "baseTraceHash", null);
        b.baseSnapshot = m.get("baseSnapshot") == null ? null : Json.asMap(m.get("baseSnapshot"));
        b.paused = Boolean.TRUE.equals(m.get("paused"));
        b.insertionCounter = Json.optLong(m, "insertionCounter", 0);
        b.state = Json.optString(m, "state", null);
        b.data = m.get("data") == null ? Json.obj() : Json.asMap(m.get("data"));
        b.outputs = m.get("outputs") == null ? Json.arr() : Json.asList(m.get("outputs"));
        b.failures = m.get("failures") == null ? Json.arr() : Json.asList(m.get("failures"));
        b.internalQueue = new ArrayDeque<>();
        if (m.get("internalQueue") != null) {
            for (Object o : Json.asList(m.get("internalQueue"))) b.internalQueue.addLast(Json.asMap(o));
        }
        b.rng = m.get("rng") == null ? new Random(0) : Rngs.fromBase64(Json.asString(m.get("rng")));
        if (m.get("processedEvents") != null) {
            for (Object o : Json.asList(m.get("processedEvents"))) b.processed.add(ExternalEvent.fromMap(Json.asMap(o)));
        }
        if (m.get("remainingEvents") != null) {
            for (Object o : Json.asList(m.get("remainingEvents"))) b.remaining.add(ExternalEvent.fromMap(Json.asMap(o)));
        }
        if (m.get("trace") != null) {
            for (Object o : Json.asList(m.get("trace"))) b.trace.add(TraceStep.fromMap(Json.asMap(o)));
        }
        if (m.get("checkpoints") != null) {
            for (Object o : Json.asList(m.get("checkpoints"))) b.checkpoints.add(Checkpoint.fromMap(Json.asMap(o)));
        }
        return b;
    }
}
