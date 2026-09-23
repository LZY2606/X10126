package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Session {
    String id;
    String name;
    String rootSessionId;
    String parentSessionId;
    String forkPointId;
    int forkPointStep;
    String definitionFingerprint;
    Models.Definition definition;
    String state;
    Map<String, Object> data;
    SplitMix64 random;
    long clock;
    long nextExternalSeq;
    long nextInternalSeq;
    int internalGeneration;
    List<Models.Event> pendingExternal = new ArrayList<>();
    List<Models.Event> internalQueue = new ArrayList<>();
    List<TraceEntry> trace = new ArrayList<>();
    List<Models.Checkpoint> checkpoints = new ArrayList<>();
    String traceHash = Hashes.EMPTY_HASH;

    record Output(String name, Map<String, Object> payload) {
        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("payload", payload);
            return map;
        }

        @SuppressWarnings("unchecked")
        static Output fromMap(Map<String, Object> map) {
            return new Output(
                    Strings.require(map.get("name"), "output name is required"),
                    map.get("payload") instanceof Map<?, ?> payload
                            ? (Map<String, Object>) Models.deepCopy(payload) : new LinkedHashMap<>()
            );
        }
    }

    record FailedAction(int index, String type, String message, String when, Map<String, Object> attemptedOutputs) {
        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("index", index);
            map.put("type", type);
            map.put("message", message);
            if (when != null) {
                map.put("when", when);
            }
            map.put("attemptedOutputs", attemptedOutputs);
            return map;
        }

        @SuppressWarnings("unchecked")
        static FailedAction fromMap(Map<String, Object> map) {
            return new FailedAction(
                    (int) Numbers.longValue(map.get("index"), 0L),
                    Strings.stringOrDefault(map.get("type"), ""),
                    Strings.stringOrDefault(map.get("message"), "action failed"),
                    Strings.stringOrDefault(map.get("when"), null),
                    map.get("attemptedOutputs") instanceof Map<?, ?> outputs
                            ? (Map<String, Object>) Models.deepCopy(outputs) : new LinkedHashMap<>()
            );
        }
    }

    record TraceEntry(
            int step,
            String hash,
            String eventId,
            String eventType,
            long logicalTime,
            boolean internal,
            String source,
            int priority,
            long seq,
            Map<String, Object> eventPayload,
            String beforeState,
            Map<String, Object> beforeData,
            String afterState,
            Map<String, Object> afterData,
            String transitionId,
            List<Output> outputs,
            boolean success,
            FailedAction failure,
            List<Models.Event> emittedEvents,
            long rngStateAfter
    ) {
        Map<String, Object> eventMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("id", eventId);
            map.put("type", eventType);
            map.put("time", logicalTime);
            map.put("internal", internal);
            map.put("source", source);
            map.put("priority", priority);
            map.put("seq", seq);
            map.put("payload", eventPayload);
            return map;
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("step", step);
            map.put("hash", hash);
            map.put("event", eventMap());
            map.put("beforeState", beforeState);
            map.put("beforeData", beforeData);
            map.put("afterState", afterState);
            map.put("afterData", afterData);
            map.put("transitionId", transitionId);
            map.put("outputs", outputs.stream().map(Output::toMap).toList());
            map.put("success", success);
            if (failure != null) {
                map.put("failure", failure.toMap());
            }
            map.put("emittedEvents", emittedEvents.stream().map(Models.Event::toMap).toList());
            map.put("rngStateAfter", rngStateAfter);
            return map;
        }

        @SuppressWarnings("unchecked")
        static TraceEntry fromMap(Map<String, Object> map) {
            Map<String, Object> event = map.get("event") instanceof Map<?, ?> eventMap
                    ? (Map<String, Object>) eventMap : new LinkedHashMap<>();
            List<Output> outputs = new ArrayList<>();
            if (map.get("outputs") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> outputMap) {
                        outputs.add(Output.fromMap((Map<String, Object>) outputMap));
                    }
                }
            }
            List<Models.Event> emitted = Models.eventList(map.get("emittedEvents"));
            FailedAction failure = map.get("failure") instanceof Map<?, ?> failureMap
                    ? FailedAction.fromMap((Map<String, Object>) failureMap) : null;
            return new TraceEntry(
                    (int) Numbers.longValue(map.get("step"), 0L),
                    Strings.stringOrDefault(map.get("hash"), ""),
                    Strings.stringOrDefault(event.get("id"), ""),
                    Strings.stringOrDefault(event.get("type"), ""),
                    Numbers.longValue(event.get("time"), 0L),
                    Boolean.TRUE.equals(event.get("internal")),
                    Strings.stringOrDefault(event.get("source"), "external"),
                    (int) Numbers.longValue(event.getOrDefault("priority", 100), 100),
                    Numbers.longValue(event.get("seq"), 0L),
                    event.get("payload") instanceof Map<?, ?> payload
                            ? (Map<String, Object>) Models.deepCopy(payload) : new LinkedHashMap<>(),
                    Strings.stringOrDefault(map.get("beforeState"), ""),
                    objectMap(map.get("beforeData")),
                    Strings.stringOrDefault(map.get("afterState"), ""),
                    objectMap(map.get("afterData")),
                    Strings.stringOrDefault(map.get("transitionId"), null),
                    outputs,
                    Boolean.TRUE.equals(map.get("success")),
                    failure,
                    emitted,
                    Numbers.longValue(map.get("rngStateAfter"), SplitMix64.INITIAL_STATE)
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map
                ? (Map<String, Object>) Models.deepCopy(map) : new LinkedHashMap<>();
    }
}
