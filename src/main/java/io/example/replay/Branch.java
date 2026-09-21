package io.example.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Branch {
    public String id;
    public String name;
    public String parentBranchId;
    public String parentCheckpointId;
    public String rootCheckpointId;
    public ReplayState state;
    public List<StoredEvent> externalHistory = new ArrayList<>();
    public List<StoredEvent> pendingExternal = new ArrayList<>();
    public Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();

    public Branch copy() {
        Branch result = new Branch();
        result.id = id;
        result.name = name;
        result.parentBranchId = parentBranchId;
        result.parentCheckpointId = parentCheckpointId;
        result.rootCheckpointId = rootCheckpointId;
        result.state = state.copy();
        result.externalHistory = new ArrayList<>(externalHistory.stream().map(StoredEvent::copy).toList());
        result.pendingExternal = new ArrayList<>(pendingExternal.stream().map(StoredEvent::copy).toList());
        for (Checkpoint checkpoint : checkpoints.values()) {
            result.checkpoints.put(checkpoint.id(), checkpoint);
        }
        return result;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        result.put("parentBranchId", parentBranchId);
        result.put("parentCheckpointId", parentCheckpointId);
        result.put("rootCheckpointId", rootCheckpointId);
        result.put("state", Store.stateToJson(state));
        result.put("externalHistory", externalHistory.stream().map(StoredEvent::toJsonMutable).toList());
        result.put("pendingExternal", pendingExternal.stream().map(StoredEvent::toJsonMutable).toList());
        result.put("checkpoints", checkpoints.values().stream().map(Checkpoint::toJson).toList());
        return result;
    }

    public static Branch fromJson(Object value) {
        Map<String, Object> object = Json.object(value);
        Branch result = new Branch();
        result.id = Json.string(object, "id");
        result.name = Json.string(object, "name");
        result.parentBranchId = Json.string(object, "parentBranchId");
        result.parentCheckpointId = Json.string(object, "parentCheckpointId");
        result.rootCheckpointId = Json.string(object, "rootCheckpointId");
        result.state = Store.stateFromJson(object.get("state"));
        result.externalHistory = new ArrayList<>(Json.list(object.get("externalHistory")).stream()
                .map(StoredEvent::fromJson).toList());
        result.pendingExternal = new ArrayList<>(Json.list(object.get("pendingExternal")).stream()
                .map(StoredEvent::fromJson).toList());
        for (Object checkpoint : Json.list(object.get("checkpoints"))) {
            Checkpoint parsed = Checkpoint.fromJson(checkpoint);
            result.checkpoints.put(parsed.id(), parsed);
        }
        return result;
    }
}
