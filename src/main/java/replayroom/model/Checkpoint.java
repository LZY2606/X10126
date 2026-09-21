package replayroom.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replayroom.json.Json;

/** Immutable branch point captured from a session. */
public final class Checkpoint implements replayroom.engine.BranchMerger.CheckpointRecord {
    private final String id;
    private final String sessionId;
    private final int step;
    private final String definitionFingerprint;
    private final String initialState;
    private final Map<String, Object> stateVars;
    private final String currentState;
    private final long rngState;
    private final List<Envelope> pending;
    private final List<String> processedExternalIds;
    private final String lockFingerprint;
    private final String traceHeadHash;
    private final String baseExternalLogFingerprint;
    private final long nextEventSerial;

    public Checkpoint(String id, String sessionId, int step, String definitionFingerprint, String initialState,
                      Map<String, Object> stateVars, String currentState, long rngState,
                      List<Envelope> pending, List<String> processedExternalIds,
                      String lockFingerprint, String traceHeadHash,
                      String baseExternalLogFingerprint, long nextEventSerial) {
        this.id = id;
        this.sessionId = sessionId;
        this.step = step;
        this.definitionFingerprint = definitionFingerprint;
        this.initialState = initialState;
        this.stateVars = Json.deepCopyMap(stateVars);
        this.currentState = currentState;
        this.rngState = rngState;
        this.pending = List.copyOf(pending);
        this.processedExternalIds = List.copyOf(processedExternalIds);
        this.lockFingerprint = lockFingerprint;
        this.traceHeadHash = traceHeadHash;
        this.baseExternalLogFingerprint = baseExternalLogFingerprint;
        this.nextEventSerial = nextEventSerial;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", "sm-checkpoint/v1");
        map.put("id", id);
        map.put("sessionId", sessionId);
        map.put("step", step);
        map.put("definitionFingerprint", definitionFingerprint);
        map.put("initialState", initialState);
        map.put("stateVars", stateVars);
        map.put("currentState", currentState);
        map.put("rngState", rngState);
        List<Object> pendingMaps = new ArrayList<>();
        for (Envelope envelope : pending) pendingMaps.add(envelope.toMap());
        map.put("pending", pendingMaps);
        map.put("processedExternalIds", new ArrayList<>(processedExternalIds));
        map.put("lockFingerprint", lockFingerprint);
        map.put("traceHeadHash", traceHeadHash);
        map.put("baseExternalLogFingerprint", baseExternalLogFingerprint);
        map.put("nextEventSerial", nextEventSerial);
        return map;
    }

    public static Checkpoint fromMap(Map<String, Object> map) {
        List<Envelope> pending = new ArrayList<>();
        for (Object value : Json.optList(map, "pending")) {
            pending.add(Envelope.fromMap(Json.object(value, "pending")));
        }
        List<String> processed = new ArrayList<>();
        for (Object value : Json.optList(map, "processedExternalIds")) {
            processed.add(Json.string(value, "processedExternalIds[]"));
        }
        return new Checkpoint(
                Json.string(map.get("id"), "checkpoint.id"),
                Json.optString(map, "sessionId", null),
                Json.intValue(map.get("step"), "checkpoint.step"),
                Json.string(map.get("definitionFingerprint"), "checkpoint.definitionFingerprint"),
                Json.optString(map, "initialState", null),
                Json.optObject(map, "stateVars"),
                Json.string(map.get("currentState"), "checkpoint.currentState"),
                Json.optLong(map, "rngState", 1L),
                pending,
                processed,
                Json.string(map.get("lockFingerprint"), "checkpoint.lockFingerprint"),
                Json.optString(map, "traceHeadHash", null),
                Json.optString(map, "baseExternalLogFingerprint", null),
                Json.optLong(map, "nextEventSerial", 0L));
    }

    public String id() { return id; }
    public String sessionId() { return sessionId; }
    public int step() { return step; }
    public String definitionFingerprint() { return definitionFingerprint; }
    public String initialState() { return initialState; }
    public Map<String, Object> stateVars() { return stateVars; }
    public String currentState() { return currentState; }
    public long rngState() { return rngState; }
    public List<Envelope> pending() { return pending; }
    public List<String> processedExternalIds() { return processedExternalIds; }
    public String lockFingerprint() { return lockFingerprint; }
    public String traceHeadHash() { return traceHeadHash; }
    public String baseExternalLogFingerprint() { return baseExternalLogFingerprint; }
    public long nextEventSerial() { return nextEventSerial; }
}
