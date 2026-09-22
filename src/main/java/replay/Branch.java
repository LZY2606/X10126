package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A replay branch: an initial snapshot plus an append-only trace of applied steps. */
public final class Branch {
    public final String id;
    public String name;
    public final Snapshot initial;
    public final List<StepRecord> trace;
    public final List<Checkpoint> checkpoints;
    public final String parentBranch;
    public final long forkStep;

    public Branch(String id, String name, Snapshot initial, List<StepRecord> trace,
                  List<Checkpoint> checkpoints, String parentBranch, long forkStep) {
        this.id = id;
        this.name = name;
        this.initial = initial;
        this.trace = trace;
        this.checkpoints = checkpoints;
        this.parentBranch = parentBranch;
        this.forkStep = forkStep;
    }

    public Snapshot current() {
        return trace.isEmpty() ? initial : trace.get(trace.size() - 1).after;
    }

    public long headStep() {
        return trace.size();
    }

    public Checkpoint findCheckpoint(String checkpointId) {
        for (Checkpoint cp : checkpoints) {
            if (cp.id.equals(checkpointId)) return cp;
        }
        return null;
    }

    /** Rewind the branch to the given step index. */
    public void rewindTo(long stepIndex) {
        if (stepIndex < 0 || stepIndex > trace.size()) {
            throw new Json.JsonException("stepIndex out of range: " + stepIndex);
        }
        while (trace.size() > stepIndex) {
            trace.remove(trace.size() - 1);
        }
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = Json.map();
        m.put("id", id);
        m.put("name", name);
        m.put("initial", initial.toJson());
        List<Object> tr = Json.list();
        for (StepRecord r : trace) tr.add(r.toJson());
        m.put("trace", tr);
        List<Object> cps = Json.list();
        for (Checkpoint c : checkpoints) cps.add(c.toJson());
        m.put("checkpoints", cps);
        m.put("parentBranch", parentBranch);
        m.put("forkStep", forkStep);
        return m;
    }

    public static Branch fromJson(Map<String, Object> json) {
        List<StepRecord> trace = new ArrayList<>();
        for (Object o : Json.asList(json.get("trace"), "branch.trace")) {
            trace.add(StepRecord.fromJson(Json.asMap(o, "step")));
        }
        List<Checkpoint> cps = new ArrayList<>();
        for (Object o : Json.asList(json.get("checkpoints"), "branch.checkpoints")) {
            cps.add(Checkpoint.fromJson(Json.asMap(o, "checkpoint")));
        }
        Object parent = json.get("parentBranch");
        return new Branch(
                Json.asString(json.get("id"), "branch.id"),
                Json.asString(json.get("name"), "branch.name"),
                Snapshot.fromJson(Json.asMap(json.get("initial"), "branch.initial")),
                trace, cps,
                parent instanceof String ? (String) parent : null,
                Json.asLong(json.get("forkStep"), "branch.forkStep"));
    }
}
