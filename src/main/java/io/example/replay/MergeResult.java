package io.example.replay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MergeResult(boolean allowed, String mergedBranchId, String commonAncestorCheckpointId,
                          String reason, List<Map<String, Object>> firstConflictGroup) {
    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", allowed);
        result.put("mergedBranchId", mergedBranchId);
        result.put("commonAncestorCheckpointId", commonAncestorCheckpointId);
        result.put("reason", reason);
        result.put("firstConflictGroup", firstConflictGroup);
        return result;
    }

    public static MergeResult conflict(String checkpointId, String reason,
                                       StoredEvent left, StoredEvent right) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("kind", "conflict");
        group.put("reason", reason);
        if (left != null) {
            group.put("left", left.toJsonMutable());
        }
        if (right != null) {
            group.put("right", right.toJsonMutable());
        }
        return new MergeResult(false, null, checkpointId, reason, List.of(group));
    }
}
