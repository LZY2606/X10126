package io.example.replay;

import java.util.LinkedHashMap;
import java.util.Map;

public record TraceStep(
        int index,
        String eventId,
        String eventType,
        long logicalTime,
        String source,
        boolean internal,
        String status,
        String transitionId,
        String error,
        Object outputs,
        Map<String, Object> beforeSnapshot,
        Map<String, Object> afterSnapshot,
        String stepHash,
        String traceHash) {
    public TraceStep copy() {
        return new TraceStep(index, eventId, eventType, logicalTime, source, internal, status,
                transitionId, error, Json.deepCopy(outputs),
                Json.object(Json.deepCopy(beforeSnapshot)),
                Json.object(Json.deepCopy(afterSnapshot)), stepHash, traceHash);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("eventId", eventId);
        result.put("eventType", eventType);
        result.put("logicalTime", logicalTime);
        result.put("source", source);
        result.put("internal", internal);
        result.put("status", status);
        result.put("transitionId", transitionId);
        result.put("error", error);
        result.put("outputs", Json.deepCopy(outputs));
        result.put("beforeSnapshot", Json.deepCopy(beforeSnapshot));
        result.put("afterSnapshot", Json.deepCopy(afterSnapshot));
        result.put("stepHash", stepHash);
        result.put("traceHash", traceHash);
        return result;
    }

    public static TraceStep fromJson(Object value) {
        Map<String, Object> object = Json.object(value);
        return new TraceStep(
                (int) Json.longValue(object, "index", 0L),
                Json.string(object, "eventId"),
                Json.string(object, "eventType"),
                Json.longValue(object, "logicalTime", 0L),
                Json.string(object, "source"),
                Boolean.TRUE.equals(object.get("internal")),
                Json.string(object, "status"),
                Json.string(object, "transitionId"),
                Json.string(object, "error"),
                Json.deepCopy(object.get("outputs")),
                Json.object(Json.deepCopy(object.get("beforeSnapshot"))),
                Json.object(Json.deepCopy(object.get("afterSnapshot"))),
                Json.string(object, "stepHash"),
                Json.string(object, "traceHash"));
    }
}
