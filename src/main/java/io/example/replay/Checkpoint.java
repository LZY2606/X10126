package io.example.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public record Checkpoint(
        String id,
        String branchId,
        String definitionFingerprint,
        int stepIndex,
        ReplayState state,
        java.util.List<StoredEvent> remainingExternal,
        String traceHash,
        String label) {
    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("branchId", branchId);
        result.put("definitionFingerprint", definitionFingerprint);
        result.put("stepIndex", stepIndex);
        result.put("state", Store.stateToJson(state));
        result.put("remainingExternal", remainingExternal.stream().map(StoredEvent::toJsonMutable).toList());
        result.put("traceHash", traceHash);
        result.put("label", label);
        return result;
    }

    public static Checkpoint fromJson(Object value) {
        Map<String, Object> object = Json.object(value);
        return new Checkpoint(
                Json.string(object, "id"),
                Json.string(object, "branchId"),
                Json.string(object, "definitionFingerprint"),
                (int) Json.longValue(object, "stepIndex", 0L),
                Store.stateFromJson(object.get("state")),
                new ArrayList<>(Json.list(object.get("remainingExternal")).stream()
                        .map(StoredEvent::fromJson).toList()),
                Json.string(object, "traceHash"),
                Json.string(object, "label"));
    }
}
