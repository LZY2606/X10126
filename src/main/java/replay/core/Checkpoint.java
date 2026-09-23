package replay.core;

import replay.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable snapshot of a branch at a given trace step, bound to a definition fingerprint. */
public final class Checkpoint {
    public String id;
    public String name;
    public String branchId;
    public int stepCount;
    public String state;
    public Map<String, Object> vars = new LinkedHashMap<>();
    public List<String> outputs = new ArrayList<>();
    public long rngState;
    public long internalSeq;
    public String traceHash;
    public String defFingerprint;
    public List<Event> queue = new ArrayList<>();
    public List<Event> externalPrefix = new ArrayList<>();
    public List<Map<String, Object>> trace = new ArrayList<>();

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("branchId", branchId);
        m.put("stepCount", stepCount);
        m.put("state", state);
        m.put("vars", Json.deepCopy(vars));
        m.put("outputs", new ArrayList<>(outputs));
        m.put("rngState", rngState);
        m.put("internalSeq", internalSeq);
        m.put("traceHash", traceHash);
        m.put("defFingerprint", defFingerprint);
        List<Object> q = new ArrayList<>();
        for (Event e : queue) q.add(e.toJson());
        m.put("queue", q);
        List<Object> ext = new ArrayList<>();
        for (Event e : externalPrefix) ext.add(e.toJson());
        m.put("externalPrefix", ext);
        m.put("trace", Json.deepCopy(trace));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Checkpoint fromJson(Map<String, Object> m) {
        Checkpoint cp = new Checkpoint();
        cp.id = Json.asString(m.get("id"), "checkpoint.id");
        cp.name = Json.asString(m.get("name"), "checkpoint.name");
        cp.branchId = Json.asString(m.get("branchId"), "checkpoint.branchId");
        cp.stepCount = (int) Json.asLong(m.get("stepCount"), "checkpoint.stepCount");
        cp.state = Json.asString(m.get("state"), "checkpoint.state");
        cp.vars = new LinkedHashMap<>(Json.asMap(m.get("vars"), "checkpoint.vars"));
        for (Object o : Json.asList(m.get("outputs"), "checkpoint.outputs")) {
            cp.outputs.add(Json.asString(o, "output"));
        }
        cp.rngState = Json.asLong(m.get("rngState"), "checkpoint.rngState");
        cp.internalSeq = Json.asLong(m.get("internalSeq"), "checkpoint.internalSeq");
        cp.traceHash = Json.asString(m.get("traceHash"), "checkpoint.traceHash");
        cp.defFingerprint = Json.asString(m.get("defFingerprint"), "checkpoint.defFingerprint");
        for (Object o : Json.asList(m.get("queue"), "checkpoint.queue")) {
            cp.queue.add(Event.fromJson(Json.asMap(o, "event")));
        }
        for (Object o : Json.asList(m.get("externalPrefix"), "checkpoint.externalPrefix")) {
            cp.externalPrefix.add(Event.fromJson(Json.asMap(o, "event")));
        }
        for (Object o : Json.asList(m.get("trace"), "checkpoint.trace")) {
            cp.trace.add((Map<String, Object>) Json.deepCopy(Json.asMap(o, "trace entry")));
        }
        return cp;
    }
}
