package com.replayroom.session;

import com.replayroom.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** On-disk session record. The embedded definition makes exports self-contained. */
public final class Session {

    public String id;
    public String name;
    public String formatVersion = "1";
    public Map<String, Object> definition = new LinkedHashMap<>();
    public String definitionFingerprint;
    public long lockedSeed;
    public List<Branch> branches = new ArrayList<>();
    public Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();
    public String createdAt;
    public String updatedAt;
    public String exportHash;

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("formatVersion", formatVersion);
        map.put("id", id);
        map.put("name", name);
        map.put("definition", definition);
        map.put("definitionFingerprint", definitionFingerprint);
        map.put("lockedSeed", lockedSeed);
        List<Object> branchList = new ArrayList<>();
        for (Branch branch : branches) {
            branchList.add(branch.toMap());
        }
        map.put("branches", branchList);
        Map<String, Object> checkpointMap = new LinkedHashMap<>();
        for (Map.Entry<String, Checkpoint> entry : checkpoints.entrySet()) {
            checkpointMap.put(entry.getKey(), entry.getValue().toMap());
        }
        map.put("checkpoints", checkpointMap);
        map.put("createdAt", createdAt);
        map.put("updatedAt", updatedAt);
        if (exportHash != null) {
            map.put("exportHash", exportHash);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Session fromMap(Map<String, Object> map) {
        Session session = new Session();
        session.formatVersion = String.valueOf(map.getOrDefault("formatVersion", "1"));
        session.id = (String) map.get("id");
        session.name = (String) map.get("name");
        Object definition = map.get("definition");
        session.definition = definition instanceof Map
                ? (Map<String, Object>) Json.deepCopy(definition)
                : new LinkedHashMap<>();
        session.definitionFingerprint = (String) map.get("definitionFingerprint");
        session.lockedSeed = ((Number) map.getOrDefault("lockedSeed", 0L)).longValue();
        for (Object item : (List<Object>) map.getOrDefault("branches", new ArrayList<>())) {
            session.branches.add(Branch.fromMap((Map<String, Object>) item));
        }
        Object checkpoints = map.get("checkpoints");
        if (checkpoints instanceof Map<?, ?> rawCheckpoints) {
            for (Map.Entry<?, ?> entry : rawCheckpoints.entrySet()) {
                session.checkpoints.put(String.valueOf(entry.getKey()),
                        Checkpoint.fromMap((Map<String, Object>) entry.getValue()));
            }
        }
        session.createdAt = (String) map.get("createdAt");
        session.updatedAt = (String) map.get("updatedAt");
        session.exportHash = (String) map.get("exportHash");
        return session;
    }
}
