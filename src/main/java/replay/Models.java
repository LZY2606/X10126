package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record MachineDefinition(String name, String version, String initialState, long randomSeed,
                         Map<String, Object> initialData, List<Transition> transitions) {
    String fingerprint() {
        return Hashes.sha256(toMap());
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("version", version);
        map.put("initialState", initialState);
        map.put("randomSeed", randomSeed);
        map.put("initialData", initialData);
        map.put("transitions", transitions.stream().map(Transition::toMap).toList());
        return map;
    }

    static MachineDefinition fromMap(Map<String, Object> map) {
        String name = Json.string(map, "name", "未命名状态机");
        String version = Json.requireString(map, "version");
        String initialState = Json.requireString(map, "initialState");
        long seed = Json.optionalLong(map, "randomSeed", 0L);
        Map<String, Object> data = map.containsKey("initialData")
                ? Json.object(Json.copy(map.get("initialData")), "initialData")
                : new LinkedHashMap<>();
        List<Transition> transitions = new ArrayList<>();
        if (map.containsKey("transitions")) {
            for (Object item : Json.list(map.get("transitions"), "transitions")) {
                transitions.add(Transition.fromMap(Json.object(item, "transition")));
            }
        }
        MachineDefinition definition = new MachineDefinition(name, version, initialState, seed, data, transitions);
        definition.validate();
        return definition;
    }

    private void validate() {
        if (initialState == null || initialState.isBlank()) {
            throw new IllegalArgumentException("initialState is required");
        }
        if (initialState.equals("*")) {
            throw new IllegalArgumentException("initialState cannot be '*'");
        }
        if (transitions == null) {
            throw new IllegalArgumentException("transitions are required");
        }
        for (Transition transition : transitions) {
            if (transition.from().equals("*") && transition.to().equals("*")) {
                throw new IllegalArgumentException("transition '*' to '*' is not explicit enough");
            }
        }
    }
}

record Transition(String from, String event, String to, String condition, List<Action> actions) {
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("from", from);
        map.put("event", event);
        map.put("to", to);
        map.put("condition", condition == null || condition.isBlank() ? "true" : condition);
        map.put("actions", actions.stream().map(Action::toMap).toList());
        return map;
    }

    static Transition fromMap(Map<String, Object> map) {
        String from = Json.string(map, "from", "*");
        String event = Json.requireString(map, "event");
        String to = Json.string(map, "to", from);
        if ("*".equals(from) && "*".equals(to)) {
            throw new IllegalArgumentException("transition '*' to '*' requires an explicit target");
        }
        String condition = Json.string(map, "condition", "true");
        List<Action> actions = new ArrayList<>();
        if (map.containsKey("actions")) {
            for (Object item : Json.list(map.get("actions"), "actions")) {
                actions.add(Action.fromMap(Json.object(item, "action")));
            }
        }
        Transition transition = new Transition(from, event, to, condition, actions);
        ExpressionParser.compile(transition.condition());
        return transition;
    }
}

record Action(String type, String path, Object value, String eventName, String output, int priority,
              long delayTicks, Map<String, Object> payload) {
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        if (path != null) map.put("path", path);
        if (value != null) map.put("value", value);
        if (eventName != null) map.put("event", eventName);
        if (output != null) map.put("output", output);
        if (priority != 10) map.put("priority", priority);
        if (delayTicks != 0L) map.put("delayTicks", delayTicks);
        if (payload != null && !payload.isEmpty()) map.put("payload", payload);
        return map;
    }

    static Action fromMap(Map<String, Object> map) {
        String type = Json.requireString(map, "type");
        List<String> allowed = List.of("set", "output", "emit", "fail", "random");
        if (!allowed.contains(type)) {
            throw new IllegalArgumentException("unsupported action type: " + type);
        }
        String path = map.containsKey("path") ? String.valueOf(map.get("path")) : null;
        Object value = map.containsKey("value") ? Json.copy(map.get("value")) : null;
        String eventName = map.containsKey("event") ? String.valueOf(map.get("event")) : null;
        String output = map.containsKey("output") ? String.valueOf(map.get("output")) : null;
        int priority = (int) Json.optionalLong(map, "priority", 10L);
        long delay = Json.optionalLong(map, "delayTicks", 0L);
        Map<String, Object> payload = map.containsKey("payload")
                ? Json.object(Json.copy(map.get("payload")), "payload")
                : new LinkedHashMap<>();
        Action action = new Action(type, path, value, eventName, output, priority, delay, payload);
        action.validate();
        return action;
    }

    private void validate() {
        if (("set".equals(type) || "random".equals(type)) && (path == null || path.isBlank())) {
            throw new IllegalArgumentException(type + " action requires path");
        }
        if ("set".equals(type) && value == null) {
            throw new IllegalArgumentException("set action requires value");
        }
        if ("emit".equals(type) && (eventName == null || eventName.isBlank())) {
            throw new IllegalArgumentException("emit action requires event");
        }
        if ("output".equals(type) && (output == null || output.isBlank())) {
            throw new IllegalArgumentException("output action requires output");
        }
        if ("fail".equals(type) && (output == null || output.isBlank())) {
            throw new IllegalArgumentException("fail action requires output describing the failure");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("priority cannot be negative");
        }
        if (delayTicks < 0L) {
            throw new IllegalArgumentException("delayTicks cannot be negative");
        }
    }
}

record EventRecord(String id, String event, long tick, int sourcePriority, long sourceSequence,
                   boolean internal, Map<String, Object> payload) {
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("event", event);
        map.put("tick", tick);
        map.put("sourcePriority", sourcePriority);
        map.put("sourceSequence", sourceSequence);
        map.put("internal", internal);
        map.put("payload", payload);
        return map;
    }

    static EventRecord externalFromMap(Map<String, Object> map) {
        EventRecord event = fromMap(map, false);
        if (event.internal()) {
            throw new IllegalArgumentException("imported events cannot be marked internal");
        }
        return event;
    }

    static EventRecord fromMap(Map<String, Object> map, boolean allowInternal) {
        String id = Json.string(map, "id", "");
        String event = Json.requireString(map, "event");
        long tick = Json.requireLong(map, "tick");
        int priority = (int) Json.optionalLong(map, "sourcePriority", 10L);
        long sequence = Json.requireLong(map, "sourceSequence");
        boolean internal = Json.optionalBoolean(map, "internal", false);
        if (internal && !allowInternal) {
            throw new IllegalArgumentException("internal events are derived and cannot be imported");
        }
        if (tick < 0L || priority < 0 || sequence < 0L) {
            throw new IllegalArgumentException("tick, sourcePriority and sourceSequence cannot be negative");
        }
        Map<String, Object> payload = map.containsKey("payload")
                ? Json.object(Json.copy(map.get("payload")), "payload")
                : new LinkedHashMap<>();
        if (id == null || id.isBlank()) {
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("event", event);
            identity.put("tick", tick);
            identity.put("sourcePriority", priority);
            identity.put("sourceSequence", sequence);
            identity.put("payload", payload);
            id = "ev_" + Hashes.shortHash(identity);
        }
        return new EventRecord(id, event, tick, priority, sequence, internal, payload);
    }
}

record OutputRecord(String name, long tick, String eventId, String traceId, Map<String, Object> value) {
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("tick", tick);
        map.put("eventId", eventId);
        map.put("traceId", traceId);
        map.put("value", value);
        return map;
    }

    static OutputRecord fromMap(Map<String, Object> map) {
        return new OutputRecord(Json.requireString(map, "name"),
                Json.requireLong(map, "tick"),
                Json.string(map, "eventId", ""),
                Json.string(map, "traceId", ""),
                map.containsKey("value") ? Json.object(Json.copy(map.get("value")), "value") : new LinkedHashMap<>());
    }
}

record TraceRecord(String traceId, String eventId, String event, long tick, boolean internal,
                   String fromState, String toState, String transitionFrom, String transitionTo,
                   boolean matched, boolean failed, String failure, Map<String, Object> beforeData,
                   Map<String, Object> afterData, Map<String, Object> eventPayload) {
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("traceId", traceId);
        map.put("eventId", eventId);
        map.put("event", event);
        map.put("tick", tick);
        map.put("internal", internal);
        map.put("fromState", fromState);
        map.put("toState", toState);
        map.put("transitionFrom", transitionFrom);
        map.put("transitionTo", transitionTo);
        map.put("matched", matched);
        map.put("failed", failed);
        map.put("failure", failure);
        map.put("beforeData", beforeData);
        map.put("afterData", afterData);
        map.put("eventPayload", eventPayload);
        return map;
    }

    static TraceRecord fromMap(Map<String, Object> map) {
        return new TraceRecord(Json.requireString(map, "traceId"),
                Json.string(map, "eventId", ""),
                Json.requireString(map, "event"),
                Json.requireLong(map, "tick"),
                Json.optionalBoolean(map, "internal", false),
                Json.string(map, "fromState", ""),
                Json.string(map, "toState", ""),
                Json.string(map, "transitionFrom", ""),
                Json.string(map, "transitionTo", ""),
                Json.optionalBoolean(map, "matched", false),
                Json.optionalBoolean(map, "failed", false),
                Json.string(map, "failure", ""),
                data(map, "beforeData"), data(map, "afterData"), data(map, "eventPayload"));
    }

    private static Map<String, Object> data(Map<String, Object> map, String key) {
        return map.containsKey(key) ? Json.object(Json.copy(map.get(key)), key) : new LinkedHashMap<>();
    }
}

record Checkpoint(String id, String branchId, String parentId, String definitionFingerprint,
                  String reason, boolean manual, String state, long randomState,
                  Map<String, Object> data, List<EventRecord> pending, List<OutputRecord> outputs,
                  List<TraceRecord> traces, List<String> externalIds, long stepCount, long emissionCount) {
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("branchId", branchId);
        map.put("parentId", parentId);
        map.put("definitionFingerprint", definitionFingerprint);
        map.put("reason", reason);
        map.put("manual", manual);
        map.put("state", state);
        map.put("randomState", randomState);
        map.put("data", data);
        map.put("pending", pending.stream().map(EventRecord::toMap).toList());
        map.put("outputs", outputs.stream().map(OutputRecord::toMap).toList());
        map.put("traces", traces.stream().map(TraceRecord::toMap).toList());
        map.put("externalIds", externalIds);
        map.put("stepCount", stepCount);
        map.put("emissionCount", emissionCount);
        return map;
    }

    static Checkpoint fromMap(Map<String, Object> map) {
        return new Checkpoint(Json.requireString(map, "id"),
                Json.string(map, "branchId", ""),
                Json.string(map, "parentId", ""),
                Json.requireString(map, "definitionFingerprint"),
                Json.string(map, "reason", "step"),
                Json.optionalBoolean(map, "manual", false),
                Json.requireString(map, "state"),
                Json.optionalLong(map, "randomState", 0L),
                map.containsKey("data") ? Json.object(Json.copy(map.get("data")), "data") : new LinkedHashMap<>(),
                readEvents(map), readOutputs(map), readTraces(map),
                map.containsKey("externalIds") ? Json.list(Json.copy(map.get("externalIds")), "externalIds")
                        .stream().map(String::valueOf).toList() : List.of(),
                Json.optionalLong(map, "stepCount", 0L),
                Json.optionalLong(map, "emissionCount", 0L));
    }

    private static List<EventRecord> readEvents(Map<String, Object> map) {
        List<EventRecord> events = new ArrayList<>();
        if (map.containsKey("pending")) {
            for (Object item : Json.list(map.get("pending"), "pending")) {
                events.add(EventRecord.fromMap(Json.object(item, "event"), true));
            }
        }
        return events;
    }

    private static List<OutputRecord> readOutputs(Map<String, Object> map) {
        List<OutputRecord> outputs = new ArrayList<>();
        if (map.containsKey("outputs")) {
            for (Object item : Json.list(map.get("outputs"), "outputs")) {
                outputs.add(OutputRecord.fromMap(Json.object(item, "output")));
            }
        }
        return outputs;
    }

    private static List<TraceRecord> readTraces(Map<String, Object> map) {
        List<TraceRecord> traces = new ArrayList<>();
        if (map.containsKey("traces")) {
            for (Object item : Json.list(map.get("traces"), "traces")) {
                traces.add(TraceRecord.fromMap(Json.object(item, "trace")));
            }
        }
        return traces;
    }
}

record Branch(String id, String name, String headCheckpointId, String definitionFingerprint,
              String parentBranchId, String startCheckpointId) {
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("headCheckpointId", headCheckpointId);
        map.put("definitionFingerprint", definitionFingerprint);
        map.put("parentBranchId", parentBranchId);
        map.put("startCheckpointId", startCheckpointId);
        return map;
    }

    static Branch fromMap(Map<String, Object> map) {
        return new Branch(Json.requireString(map, "id"),
                Json.string(map, "name", "branch"),
                Json.requireString(map, "headCheckpointId"),
                Json.requireString(map, "definitionFingerprint"),
                Json.string(map, "parentBranchId", ""),
                Json.string(map, "startCheckpointId", ""));
    }
}
