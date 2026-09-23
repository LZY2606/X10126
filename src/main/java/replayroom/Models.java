package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Models {
    private Models() {
    }

    record Definition(
            String name,
            String version,
            List<String> states,
            List<String> events,
            String initialState,
            Map<String, Object> initialData,
            long seed,
            List<Transition> transitions
    ) {
        @SuppressWarnings("unchecked")
        static Definition fromMap(Map<String, Object> map) {
            List<String> states = stringList(map.get("states"));
            List<String> events = stringList(map.get("events"));
            String initialState = Strings.stringOrDefault(map.get("initialState"),
                    states.isEmpty() ? "idle" : states.get(0));
            Map<String, Object> initialData = map.get("initialData") instanceof Map<?, ?> data
                    ? (Map<String, Object>) deepCopy(data)
                    : new LinkedHashMap<>();
            if (!states.contains(initialState)) {
                throw new IllegalArgumentException("initialState is not declared: " + initialState);
            }
            List<Transition> transitions = new ArrayList<>();
            Object rawTransitions = map.getOrDefault("transitions", List.of());
            if (!(rawTransitions instanceof List<?> list)) {
                throw new IllegalArgumentException("transitions must be a list");
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> transitionMap)) {
                    throw new IllegalArgumentException("transition must be an object");
                }
                transitions.add(Transition.fromMap((Map<String, Object>) transitionMap));
            }
            Definition definition = new Definition(
                    Strings.stringOrDefault(map.get("name"), "未命名状态机"),
                    Strings.stringOrDefault(map.get("version"), "1"),
                    states,
                    events,
                    initialState,
                    initialData,
                    Numbers.longValue(map.getOrDefault("seed", 1L), 1L),
                    transitions
            );
            definition.validate();
            return definition;
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("version", version);
            map.put("states", states);
            map.put("events", events);
            map.put("initialState", initialState);
            map.put("initialData", initialData);
            map.put("seed", seed);
            map.put("transitions", transitions.stream().map(Transition::toMap).toList());
            return map;
        }

        void validate() {
            if (states.isEmpty()) {
                throw new IllegalArgumentException("states must not be empty");
            }
            if (states.stream().distinct().count() != states.size()) {
                throw new IllegalArgumentException("state names must be unique");
            }
            if (events.stream().distinct().count() != events.size()) {
                throw new IllegalArgumentException("event names must be unique");
            }
            for (Transition transition : transitions) {
                transition.validate(this);
            }
        }
    }

    record Transition(
            String id,
            List<String> from,
            String event,
            String to,
            String when,
            List<Action> actions
    ) {
        @SuppressWarnings("unchecked")
        static Transition fromMap(Map<String, Object> map) {
            List<String> from = new ArrayList<>();
            Object rawFrom = map.get("from");
            if (rawFrom == null || "*".equals(rawFrom)) {
                from.add("*");
            } else if (rawFrom instanceof List<?> list) {
                list.forEach(item -> from.add(String.valueOf(item)));
            } else {
                from.add(String.valueOf(rawFrom));
            }
            List<Action> actions = new ArrayList<>();
            Object rawActions = map.getOrDefault("actions", List.of());
            if (!(rawActions instanceof List<?> list)) {
                throw new IllegalArgumentException("actions must be a list");
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> actionMap)) {
                    throw new IllegalArgumentException("action must be an object");
                }
                actions.add(Action.fromMap((Map<String, Object>) actionMap));
            }
            return new Transition(
                    Strings.stringOrDefault(map.get("id"), "t" + System.identityHashCode(map)),
                    from,
                    Strings.require(map.get("event"), "transition event is required"),
                    Strings.stringOrDefault(map.get("to"), null),
                    Strings.stringOrDefault(map.get("when"), null),
                    actions
            );
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("from", from.size() == 1 && "*".equals(from.get(0)) ? "*" : from);
            map.put("event", event);
            if (to != null) {
                map.put("to", to);
            }
            if (when != null && !when.isBlank()) {
                map.put("when", when);
            }
            map.put("actions", actions.stream().map(Action::toMap).toList());
            return map;
        }

        void validate(Definition definition) {
            if (!definition.events.contains(event)) {
                throw new IllegalArgumentException("transition uses undeclared event: " + event);
            }
            for (String source : from) {
                if (!"*".equals(source) && !definition.states.contains(source)) {
                    throw new IllegalArgumentException("transition uses undeclared state: " + source);
                }
            }
            if (to != null && !definition.states.contains(to)) {
                throw new IllegalArgumentException("transition targets undeclared state: " + to);
            }
        }
    }

    record Action(
            String type,
            String path,
            String name,
            String event,
            String message,
            String when,
            Object value,
            Map<String, Object> payload,
            long bound
    ) {
        @SuppressWarnings("unchecked")
        static Action fromMap(Map<String, Object> map) {
            String type = Strings.require(map.get("type"), "action type is required");
            return switch (type) {
                case "set", "setExpr", "random", "output", "emit", "fail" -> new Action(
                        type,
                        Strings.stringOrDefault(map.get("path"), null),
                        Strings.stringOrDefault(map.get("name"), null),
                        Strings.stringOrDefault(map.get("event"), null),
                        Strings.stringOrDefault(map.get("message"), "action failed"),
                        Strings.stringOrDefault(map.get("when"), null),
                        map.get("value"),
                        map.get("payload") instanceof Map<?, ?> payload
                                ? (Map<String, Object>) deepCopy(payload) : new LinkedHashMap<>(),
                        Numbers.longValue(map.getOrDefault("bound", 1_000_000L), 1_000_000L)
                );
                default -> throw new IllegalArgumentException("unsupported action type: " + type);
            };
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            switch (type) {
                case "set", "setExpr" -> {
                    map.put("path", path);
                    map.put("value", value);
                }
                case "random" -> {
                    map.put("path", path);
                    map.put("bound", bound);
                }
                case "output" -> {
                    map.put("name", name);
                    map.put("payload", payload);
                }
                case "emit" -> {
                    map.put("event", event);
                    map.put("payload", payload);
                }
                case "fail" -> {
                    map.put("message", message);
                    if (when != null) {
                        map.put("when", when);
                    }
                }
                default -> throw new IllegalStateException(type);
            }
            return map;
        }
    }

    record Event(
            String id,
            String type,
            long time,
            int priority,
            long seq,
            String source,
            String originSessionId,
            boolean internal,
            Map<String, Object> payload,
            String derivedFrom,
            int generation
    ) implements Comparable<Event> {
        @SuppressWarnings("unchecked")
        static Event fromMap(Map<String, Object> map) {
            return new Event(
                    Strings.require(map.get("id"), "event id is required"),
                    Strings.require(map.get("type"), "event type is required"),
                    Numbers.longValue(map.get("time"), 0L),
                    (int) Numbers.longValue(map.getOrDefault("priority", 100), 100),
                    Numbers.longValue(map.get("seq"), 0L),
                    Strings.stringOrDefault(map.get("source"), "external"),
                    Strings.stringOrDefault(map.get("originSessionId"), ""),
                    Boolean.TRUE.equals(map.get("internal")),
                    map.get("payload") instanceof Map<?, ?> payload
                            ? (Map<String, Object>) deepCopy(payload) : new LinkedHashMap<>(),
                    Strings.stringOrDefault(map.get("derivedFrom"), ""),
                    (int) Numbers.longValue(map.getOrDefault("generation", 0), 0)
            );
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("type", type);
            map.put("time", time);
            map.put("priority", priority);
            map.put("seq", seq);
            map.put("source", source);
            if (originSessionId != null && !originSessionId.isEmpty()) {
                map.put("originSessionId", originSessionId);
            }
            map.put("internal", internal);
            map.put("payload", payload);
            if (derivedFrom != null && !derivedFrom.isEmpty()) {
                map.put("derivedFrom", derivedFrom);
            }
            map.put("generation", generation);
            return map;
        }

        @Override
        public int compareTo(Event other) {
            int result = Long.compare(time, other.time);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(priority, other.priority);
            if (result != 0) {
                return result;
            }
            result = Long.compare(seq, other.seq);
            if (result != 0) {
                return result;
            }
            result = source.compareTo(other.source);
            if (result != 0) {
                return result;
            }
            result = originSessionId.compareTo(other.originSessionId);
            if (result != 0) {
                return result;
            }
            return id.compareTo(other.id);
        }

        String orderGroup() {
            return time + "|" + priority + "|" + seq;
        }
    }

    record Checkpoint(
            String id,
            String label,
            int step,
            String definitionFingerprint,
            String traceHash,
            String state,
            Map<String, Object> data,
            long rngState,
            long clock,
            List<Event> pendingExternal,
            List<Event> internalQueue,
            String rootSessionId,
            long nextExternalSeq,
            long nextInternalSeq,
            int internalGeneration
    ) {
        @SuppressWarnings("unchecked")
        static Checkpoint fromMap(Map<String, Object> map) {
            return new Checkpoint(
                    Strings.require(map.get("id"), "checkpoint id is required"),
                    Strings.stringOrDefault(map.get("label"), ""),
                    (int) Numbers.longValue(map.get("step"), 0L),
                    Strings.require(map.get("definitionFingerprint"), "definitionFingerprint is required"),
                    Strings.stringOrDefault(map.get("traceHash"), Hashes.EMPTY_HASH),
                    Strings.require(map.get("state"), "state is required"),
                    map.get("data") instanceof Map<?, ?> data
                            ? (Map<String, Object>) deepCopy(data) : new LinkedHashMap<>(),
                    Numbers.longValue(map.get("rngState"), SplitMix64.INITIAL_STATE),
                    Numbers.longValue(map.getOrDefault("clock", 0L), 0L),
                    eventList(map.get("pendingExternal")),
                    eventList(map.get("internalQueue")),
                    Strings.stringOrDefault(map.get("rootSessionId"), ""),
                    Numbers.longValue(map.getOrDefault("nextExternalSeq", 1L), 1L),
                    Numbers.longValue(map.getOrDefault("nextInternalSeq", 1L), 1L),
                    (int) Numbers.longValue(map.getOrDefault("internalGeneration", 0), 0)
            );
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("label", label);
            map.put("step", step);
            map.put("definitionFingerprint", definitionFingerprint);
            map.put("traceHash", traceHash);
            map.put("state", state);
            map.put("data", data);
            map.put("rngState", rngState);
            map.put("clock", clock);
            map.put("pendingExternal", pendingExternal.stream().map(Event::toMap).toList());
            map.put("internalQueue", internalQueue.stream().map(Event::toMap).toList());
            map.put("rootSessionId", rootSessionId);
            map.put("nextExternalSeq", nextExternalSeq);
            map.put("nextInternalSeq", nextInternalSeq);
            map.put("internalGeneration", internalGeneration);
            return map;
        }
    }

    static List<Event> eventList(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Event> events = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typedMap = (Map<String, Object>) map;
                events.add(Event.fromMap(typedMap));
            }
        }
        return events;
    }

    static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }
}
