package com.replayroom.session;

import com.replayroom.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One replay branch. It stores the imported external pool plus any extra
 * external events injected after forking, the current queue (which may
 * contain internal events produced by actions), the trace records and
 * provenance used to reconstruct the branch tree for merging.
 */
public class Branch {

    public String id;
    public String name;
    public String definitionFingerprint;

    /** {"branchId": "...", "stepIndex": 3} for the root branch this is null. */
    public Map<String, Object> origin;
    /** root | fork | merged */
    public String kind = "root";

    public List<Map<String, Object>> externalPool = new ArrayList<>();
    public List<Map<String, Object>> queue = new ArrayList<>();
    public List<Map<String, Object>> steps = new ArrayList<>();
    public List<String> checkpointIds = new ArrayList<>();

    public String currentState;
    public Map<String, Object> currentData = new LinkedHashMap<>();
    public long rngState;
    public String traceHash;
    public String rootHash;
    public long internalCounter;
    public long logicalClock;
    public boolean finished;

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("definitionFingerprint", definitionFingerprint);
        map.put("origin", origin);
        map.put("kind", kind);
        map.put("externalPool", externalPool);
        map.put("queue", queue);
        map.put("steps", steps);
        map.put("checkpointIds", checkpointIds);
        map.put("currentState", currentState);
        map.put("currentData", currentData);
        map.put("rngState", rngState);
        map.put("traceHash", traceHash);
        map.put("rootHash", rootHash);
        map.put("internalCounter", internalCounter);
        map.put("logicalClock", logicalClock);
        map.put("finished", finished);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Branch fromMap(Map<String, Object> map) {
        Branch branch = new Branch();
        branch.id = (String) map.get("id");
        branch.name = (String) map.get("name");
        branch.definitionFingerprint = (String) map.get("definitionFingerprint");
        branch.origin = map.get("origin") instanceof Map ? (Map<String, Object>) map.get("origin") : null;
        branch.kind = (String) map.getOrDefault("kind", "root");
        branch.externalPool = (List<Map<String, Object>>) Json.deepCopy(
                map.getOrDefault("externalPool", new ArrayList<>()));
        branch.queue = (List<Map<String, Object>>) Json.deepCopy(
                map.getOrDefault("queue", new ArrayList<>()));
        branch.steps = (List<Map<String, Object>>) Json.deepCopy(
                map.getOrDefault("steps", new ArrayList<>()));
        branch.checkpointIds = new ArrayList<>();
        for (Object id : (List<Object>) map.getOrDefault("checkpointIds", new ArrayList<>())) {
            branch.checkpointIds.add(String.valueOf(id));
        }
        branch.currentState = (String) map.get("currentState");
        Object data = map.get("currentData");
        branch.currentData = data instanceof Map ? (Map<String, Object>) Json.deepCopy(data)
                : new LinkedHashMap<>();
        branch.rngState = ((Number) map.getOrDefault("rngState", 0L)).longValue();
        branch.traceHash = (String) map.get("traceHash");
        branch.rootHash = (String) map.get("rootHash");
        branch.internalCounter = ((Number) map.getOrDefault("internalCounter", 0L)).longValue();
        branch.logicalClock = ((Number) map.getOrDefault("logicalClock", 0L)).longValue();
        branch.finished = Boolean.TRUE.equals(map.get("finished"));
        return branch;
    }
}
