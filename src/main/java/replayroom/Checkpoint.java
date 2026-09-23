package replayroom;

import java.util.LinkedHashMap;
import java.util.Map;

public record Checkpoint(
    String id,
    String name,
    String branchId,
    long stepIndex,
    String definitionFingerprint,
    String traceHash,
    Map<String, Object> stateSnapshot,
    String currentState,
    Map<String, Object> vars,
    long rngState,
    int nextExternal,
    List<Models.EventEnvelope> pendingInternal,
    List<String> processedExternalIds,
    String parentCheckpointId
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("branchId", branchId);
        map.put("stepIndex", stepIndex);
        map.put("definitionFingerprint", definitionFingerprint);
        map.put("traceHash", traceHash);
        map.put("stateSnapshot", stateSnapshot);
        map.put("currentState", currentState);
        map.put("vars", vars);
        map.put("rngState", rngState);
        map.put("nextExternal", nextExternal);
        List<Object> pending = new java.util.ArrayList<>();
        pendingInternal.forEach(event -> pending.add(event.toMap()));
        map.put("pendingInternal", pending);
        map.put("processedExternalIds", processedExternalIds);
        if (parentCheckpointId != null) map.put("parentCheckpointId", parentCheckpointId);
        return map;
    }

    public static Checkpoint fromMap(Object raw) {
        Map<String, Object> map = Json.object(raw);
        return new Checkpoint(
            Json.requireString(map, "id"),
            Json.string(map, "name"),
            Json.requireString(map, "branchId"),
            Json.integer(map, "stepIndex", 0L),
            Json.requireString(map, "definitionFingerprint"),
            Json.requireString(map, "traceHash"),
            Json.objectField(map, "stateSnapshot"),
            Json.requireString(map, "currentState"),
            Json.objectField(map, "vars"),
            Json.integer(map, "rngState", 0L),
            (int) Json.integer(map, "nextExternal", 0L),
            events(Json.listField(map, "pendingInternal")),
            ids(Json.listField(map, "processedExternalIds")),
            Json.string(map, "parentCheckpointId")
        );
    }

    private static List<Models.EventEnvelope> events(List<Object> values) {
        List<Models.EventEnvelope> result = new java.util.ArrayList<>();
        for (Object value : values) result.add(Models.EventEnvelope.fromMap(value));
        return result;
    }

    private static List<String> ids(List<Object> values) {
        List<String> result = new java.util.ArrayList<>();
        for (Object value : values) result.add(String.valueOf(value));
        return result;
    }
}
