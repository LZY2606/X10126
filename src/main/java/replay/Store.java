package replay;

import java.util.LinkedHashMap;
import java.util.Map;

record Store(MachineDefinition definition, Map<String, EventRecord> events, Map<String, Checkpoint> checkpoints,
             Map<String, Branch> branches, String selectedBranchId) {
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("formatVersion", 1);
        map.put("definition", definition.toMap());
        Map<String, Object> eventsMap = new LinkedHashMap<>();
        events.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> eventsMap.put(entry.getKey(), entry.getValue().toMap()));
        map.put("events", eventsMap);
        Map<String, Object> checkpointsMap = new LinkedHashMap<>();
        checkpoints.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> checkpointsMap.put(entry.getKey(), entry.getValue().toMap()));
        map.put("checkpoints", checkpointsMap);
        Map<String, Object> branchesMap = new LinkedHashMap<>();
        branches.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> branchesMap.put(entry.getKey(), entry.getValue().toMap()));
        map.put("branches", branchesMap);
        map.put("selectedBranchId", selectedBranchId);
        return map;
    }

    static Store fromMap(Map<String, Object> map) {
        MachineDefinition definition = MachineDefinition.fromMap(Json.object(map.get("definition"), "definition"));
        Map<String, EventRecord> events = new LinkedHashMap<>();
        Json.object(map.getOrDefault("events", new LinkedHashMap<>()), "events").forEach((key, value) ->
                events.put(key, EventRecord.fromMap(Json.object(value, "event"), true)));
        Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();
        Json.object(map.getOrDefault("checkpoints", new LinkedHashMap<>()), "checkpoints").forEach((key, value) -> {
            Checkpoint checkpoint = Checkpoint.fromMap(Json.object(value, "checkpoint"));
            if (!key.equals(checkpoint.id())) {
                throw new IllegalArgumentException("checkpoint map key does not match id");
            }
            checkpoints.put(key, checkpoint);
        });
        Map<String, Branch> branches = new LinkedHashMap<>();
        Json.object(map.getOrDefault("branches", new LinkedHashMap<>()), "branches").forEach((key, value) -> {
            Branch branch = Branch.fromMap(Json.object(value, "branch"));
            if (!key.equals(branch.id())) throw new IllegalArgumentException("branch map key does not match id");
            branches.put(key, branch);
        });
        String selected = Json.string(map, "selectedBranchId", "main");
        Store store = new Store(definition, events, checkpoints, branches, selected);
        store.validate();
        return store;
    }

    private void validate() {
        for (Branch branch : branches.values()) {
            if (!definition.fingerprint().equals(branch.definitionFingerprint())) {
                continue;
            }
            if (!checkpoints.containsKey(branch.headCheckpointId())) {
                throw new IllegalArgumentException("branch " + branch.id() + " points to missing checkpoint");
            }
        }
        if (!branches.containsKey(selectedBranchId)) {
            throw new IllegalArgumentException("selected branch is missing");
        }
    }
}
