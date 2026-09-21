package com.replayroom.session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable replay checkpoint. A checkpoint is only valid under the exact
 * definition fingerprint it was created with; restoring it against another
 * definition version is rejected by the session layer.
 */
public final class Checkpoint {

    public String id;
    public String name;
    public String branchId;
    public int stepIndex;
    public String definitionFingerprint;
    public String frameHash;
    public String state;
    public Map<String, Object> data = new LinkedHashMap<>();
    public long rngState;
    public String traceHash;
    public List<Map<String, Object>> pending = List.of();
    public String createdAt;

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("branchId", branchId);
        map.put("stepIndex", stepIndex);
        map.put("definitionFingerprint", definitionFingerprint);
        map.put("frameHash", frameHash);
        map.put("state", state);
        map.put("data", data);
        map.put("rngState", rngState);
        map.put("traceHash", traceHash);
        map.put("pending", pending);
        map.put("createdAt", createdAt);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Checkpoint fromMap(Map<String, Object> map) {
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.id = (String) map.get("id");
        checkpoint.name = (String) map.get("name");
        checkpoint.branchId = (String) map.get("branchId");
        checkpoint.stepIndex = ((Number) map.getOrDefault("stepIndex", 0)).intValue();
        checkpoint.definitionFingerprint = (String) map.get("definitionFingerprint");
        checkpoint.frameHash = (String) map.get("frameHash");
        checkpoint.state = (String) map.get("state");
        Object data = map.get("data");
        checkpoint.data = data instanceof Map ? (Map<String, Object>) data : new LinkedHashMap<>();
        checkpoint.rngState = ((Number) map.getOrDefault("rngState", 0L)).longValue();
        checkpoint.traceHash = (String) map.get("traceHash");
        Object pending = map.get("pending");
        checkpoint.pending = pending instanceof List ? (List<Map<String, Object>>) pending : List.of();
        checkpoint.createdAt = (String) map.get("createdAt");
        return checkpoint;
    }
}
