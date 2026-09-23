package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Branch(
    String id,
    String name,
    String definitionFingerprint,
    String baseCheckpointId,
    String forkedFromBranchId,
    String parentCheckpointId,
    String currentState,
    Map<String, Object> vars,
    long rngState,
    List<Models.EventEnvelope> externalEvents,
    List<Models.EventEnvelope> pendingInternal,
    int nextExternal,
    List<Step> steps,
    List<String> processedExternalIds,
    String lastTraceHash,
    boolean merged
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("definitionFingerprint", definitionFingerprint);
        if (baseCheckpointId != null) map.put("baseCheckpointId", baseCheckpointId);
        if (forkedFromBranchId != null) map.put("forkedFromBranchId", forkedFromBranchId);
        if (parentCheckpointId != null) map.put("parentCheckpointId", parentCheckpointId);
        map.put("currentState", currentState);
        map.put("vars", vars);
        map.put("rngState", rngState);
        map.put("externalEvents", maps(externalEvents));
        map.put("pendingInternal", maps(pendingInternal));
        map.put("nextExternal", nextExternal);
        List<Object> stepMaps = new ArrayList<>();
        steps.forEach(step -> stepMaps.add(step.toMap()));
        map.put("steps", stepMaps);
        map.put("processedExternalIds", processedExternalIds);
        map.put("lastTraceHash", lastTraceHash);
        map.put("merged", merged);
        map.put("done", pendingInternal.isEmpty() && nextExternal >= externalEvents.size());
        return map;
    }

    private static List<Object> maps(List<Models.EventEnvelope> events) {
        List<Object> result = new ArrayList<>();
        events.forEach(event -> result.add(event.toMap()));
        return result;
    }

    public static Branch fromMap(Object raw) {
        Map<String, Object> map = Json.object(raw);
        List<Models.EventEnvelope> external = new ArrayList<>();
        for (Object event : Json.listField(map, "externalEvents")) {
            external.add(Models.EventEnvelope.fromMap(event));
        }
        List<Models.EventEnvelope> pending = new ArrayList<>();
        for (Object event : Json.listField(map, "pendingInternal")) {
            pending.add(Models.EventEnvelope.fromMap(event));
        }
        List<Step> steps = new ArrayList<>();
        for (Object step : Json.listField(map, "steps")) {
            steps.add(Step.fromMap(step));
        }
        List<String> processed = new ArrayList<>();
        for (Object id : Json.listField(map, "processedExternalIds")) {
            processed.add(String.valueOf(id));
        }
        return new Branch(
            Json.requireString(map, "id"),
            Json.string(map, "name"),
            Json.requireString(map, "definitionFingerprint"),
            Json.string(map, "baseCheckpointId"),
            Json.string(map, "forkedFromBranchId"),
            Json.string(map, "parentCheckpointId"),
            Json.requireString(map, "currentState"),
            Json.objectField(map, "vars"),
            Json.integer(map, "rngState", 0L),
            external,
            pending,
            (int) Json.integer(map, "nextExternal", 0L),
            steps,
            processed,
            Json.requireString(map, "lastTraceHash"),
            Boolean.TRUE.equals(map.get("merged"))
        );
    }
}
