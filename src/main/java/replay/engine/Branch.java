package replay.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import replay.Json;

/**
 * A replay branch: ordered external events (anchored to a common log),
 * accumulated steps, and checkpoints.
 */
public final class Branch {

    public String id;
    public String name;
    public String parentBranchId;
    public String baseCheckpointId;
    public long ancestorStepIndex;

    /** External events actually belonging to this branch replay, in stable order. */
    public final List<Map<String, Object>> externalEvents = new ArrayList<>();
    public final List<Map<String, Object>> steps = new ArrayList<>();
    public final List<Checkpoint> checkpoints = new ArrayList<>();

    public transient ReplayEngine engine;

    public ReplayEngine engine() {
        return engine;
    }

    public static final class Checkpoint {
        public String id;
        public String label;
        public long stepIndex;
        public String traceHash;
        public String definitionFingerprint;
        public String definitionVersion;
        public String parentCheckpointId;
        public ReplayEngine.Snapshot snapshot;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", label);
            m.put("stepIndex", stepIndex);
            m.put("traceHash", traceHash);
            m.put("definitionFingerprint", definitionFingerprint);
            m.put("definitionVersion", definitionVersion);
            m.put("parentCheckpointId", parentCheckpointId);
            m.put("state", snapshot.state);
            m.put("vars", snapshot.vars);
            return m;
        }
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("parentBranchId", parentBranchId);
        m.put("baseCheckpointId", baseCheckpointId);
        m.put("externalEventCount", externalEvents.size());
        m.put("stepCount", steps.size());
        m.put("finished", engine != null && !engine.hasNext());
        m.put("currentState", engine == null ? null : engine.getState());
        m.put("currentVars", engine == null ? new LinkedHashMap<>() : engine.getVars());
        m.put("traceHash", engine == null ? null : engine.getTraceHash());
        m.put("nextExternalIndex", engine == null ? -1 : engine.getNextExternalIndex());
        List<Object> cps = new ArrayList<>();
        for (Checkpoint cp : checkpoints) {
            cps.add(cp.toMap());
        }
        m.put("checkpoints", cps);
        return m;
    }

    /**
     * External events this branch has applied after the given checkpoint.
     */
    public List<Map<String, Object>> externalSince(Checkpoint cp) {
        long uptoStep = cp == null ? 0 : cp.stepIndex;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            if (Json.lng(step, "index", 0L) < uptoStep) {
                continue;
            }
            if (!"external".equals(Json.str(step, "kind"))) {
                continue;
            }
            String eid = Json.str(step, "eventId");
            for (Map<String, Object> e : externalEvents) {
                if (Json.str(e, "id").equals(eid)) {
                    out.add(e);
                    break;
                }
            }
        }
        return out;
    }
}
