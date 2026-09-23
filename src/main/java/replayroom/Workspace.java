package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Workspace(
    String id,
    int formatVersion,
    Models.Definition definition,
    List<Branch> branches,
    List<Checkpoint> checkpoints,
    String activeBranchId,
    long createdAt,
    String exportFingerprint
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("formatVersion", formatVersion);
        map.put("definition", definition.toMap());
        List<Object> branchMaps = new ArrayList<>();
        branches.forEach(branch -> branchMaps.add(branch.toMap()));
        map.put("branches", branchMaps);
        List<Object> checkpointMaps = new ArrayList<>();
        checkpoints.forEach(checkpoint -> checkpointMaps.add(checkpoint.toMap()));
        map.put("checkpoints", checkpointMaps);
        map.put("activeBranchId", activeBranchId);
        map.put("createdAt", createdAt);
        map.put("exportFingerprint", exportFingerprint);
        return map;
    }

    public static Workspace fromMap(Object raw) {
        Map<String, Object> map = Json.object(raw);
        List<Branch> branches = new ArrayList<>();
        for (Object branch : Json.listField(map, "branches")) {
            branches.add(Branch.fromMap(branch));
        }
        List<Checkpoint> checkpoints = new ArrayList<>();
        for (Object checkpoint : Json.listField(map, "checkpoints")) {
            checkpoints.add(Checkpoint.fromMap(checkpoint));
        }
        return new Workspace(
            Json.requireString(map, "id"),
            (int) Json.integer(map, "formatVersion", 1),
            Models.Definition.fromMap(Json.objectField(map, "definition")),
            branches,
            checkpoints,
            Json.string(map, "activeBranchId"),
            Json.integer(map, "createdAt", 0L),
            Json.string(map, "exportFingerprint")
        );
    }
}
