package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Models {
    private Models() {
    }

    public record Action(String type, String when, Map<String, Object> params) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            if (when != null && !when.isBlank()) map.put("when", when);
            map.putAll(params);
            return map;
        }

        @SuppressWarnings("unchecked")
        public static Action fromMap(Object raw) {
            Map<String, Object> map = Json.object(raw);
            String type = Json.requireString(map, "type");
            String when = Json.string(map, "when");
            Map<String, Object> params = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!entry.getKey().equals("type") && !entry.getKey().equals("when")) {
                    params.put(entry.getKey(), entry.getValue());
                }
            }
            return new Action(type, when, params);
        }
    }

    public record Transition(String event, String from, String to, String condition, List<Action> actions) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("event", event);
            if (from != null && !from.isBlank() && !from.equals("*")) map.put("from", from);
            if (to != null && !to.isBlank()) map.put("to", to);
            if (condition != null && !condition.isBlank()) map.put("condition", condition);
            List<Object> actionMaps = new ArrayList<>();
            actions.forEach(action -> actionMaps.add(action.toMap()));
            map.put("actions", actionMaps);
            return map;
        }

        public static Transition fromMap(Object raw) {
            Map<String, Object> map = Json.object(raw);
            List<Action> actions = new ArrayList<>();
            for (Object action : Json.listField(map, "actions")) {
                actions.add(Action.fromMap(action));
            }
            String from = Json.string(map, "from");
            return new Transition(
                Json.requireString(map, "event"),
                from == null || from.isBlank() ? "*" : from,
                Json.string(map, "to"),
                Json.string(map, "condition"),
                actions
            );
        }
    }

    public record Definition(
        String name,
        int version,
        long seed,
        String initialState,
        Map<String, Object> initialVars,
        List<String> states,
        Map<String, Integer> priorities,
        List<Transition> transitions
    ) {
        public String fingerprint() {
            return Hashing.sha256(Json.canonical(toFingerprintMap()));
        }

        private Map<String, Object> toFingerprintMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("version", version);
            map.put("seed", seed);
            map.put("initialState", initialState);
            map.put("initialVars", initialVars);
            map.put("states", states);
            map.put("priorities", priorities);
            List<Object> transitionMaps = new ArrayList<>();
            transitions.forEach(transition -> transitionMaps.add(transition.toMap()));
            map.put("transitions", transitionMaps);
            return map;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = toFingerprintMap();
            map.put("fingerprint", fingerprint());
            return map;
        }

        public static Definition fromMap(Object raw) {
            Map<String, Object> map = Json.object(raw);
            List<String> states = new ArrayList<>();
            for (Object state : Json.listField(map, "states")) {
                states.add(String.valueOf(state));
            }
            Map<String, Integer> priorities = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : Json.objectField(map, "priorities").entrySet()) {
                if (!(entry.getValue() instanceof Number number)) {
                    throw new IllegalArgumentException("Priority must be a number");
                }
                priorities.put(entry.getKey(), number.intValue());
            }
            List<Transition> transitions = new ArrayList<>();
            for (Object transition : Json.listField(map, "transitions")) {
                transitions.add(Transition.fromMap(transition));
            }
            return new Definition(
                Json.requireString(map, "name"),
                (int) Json.integer(map, "version", 1),
                Json.integer(map, "seed", 1L),
                Json.requireString(map, "initialState"),
                Json.objectField(map, "initialVars"),
                states,
                priorities,
                transitions
            );
        }

        public void validate() {
            if (states.isEmpty()) throw new IllegalArgumentException("Definition must contain states");
            if (!states.contains(initialState)) throw new IllegalArgumentException("Initial state is not declared");
            for (Transition transition : transitions) {
                if (!transition.from().equals("*") && !states.contains(transition.from())) {
                    throw new IllegalArgumentException("Unknown from state: " + transition.from());
                }
                if (transition.to() != null && !transition.to().isBlank() && !states.contains(transition.to())) {
                    throw new IllegalArgumentException("Unknown to state: " + transition.to());
                }
                for (Action action : transition.actions()) {
                    if (action.when() != null && !action.when().isBlank()) {
                        Expression.evaluate(action.when(), Expression.context(initialVars, Map.of("type", transition.event()), seed));
                    }
                    try {
                        Actions.validate(action, Expression.context(initialVars, Map.of("type", transition.event()), seed));
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("Invalid action: " + e.getMessage(), e);
                    }
                }
                if (transition.condition() != null && !transition.condition().isBlank()) {
                    Expression.condition(transition.condition(), Expression.context(initialVars, Map.of("type", transition.event()), seed));
                }
            }
        }

        public List<Transition> matches(String currentState, String eventType) {
            List<Transition> matches = new ArrayList<>();
            for (Transition transition : transitions) {
                if (!transition.event().equals(eventType)) continue;
                if (transition.from().equals("*") || transition.from().equals(currentState)) {
                    matches.add(transition);
                }
            }
            return matches;
        }
    }

    public record EventEnvelope(
        String id,
        String type,
        long time,
        String source,
        long seq,
        Map<String, Object> payload,
        boolean internal,
        String parentEventId,
        long generation,
        int priority,
        long importOrder
    ) implements Comparable<EventEnvelope> {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("type", type);
            map.put("time", time);
            map.put("source", source);
            map.put("seq", seq);
            map.put("payload", payload);
            map.put("internal", internal);
            if (parentEventId != null) map.put("parentEventId", parentEventId);
            map.put("generation", generation);
            map.put("priority", priority);
            map.put("importOrder", importOrder);
            return map;
        }

        public static EventEnvelope fromMap(Object raw) {
            Map<String, Object> map = Json.object(raw);
            return new EventEnvelope(
                Json.requireString(map, "id"),
                Json.requireString(map, "type"),
                Json.integer(map, "time", 0L),
                Json.requireString(map, "source"),
                Json.integer(map, "seq", 0L),
                Json.objectField(map, "payload"),
                Boolean.TRUE.equals(map.get("internal")),
                Json.string(map, "parentEventId"),
                Json.integer(map, "generation", 0L),
                (int) Json.integer(map, "priority", 0L),
                Json.integer(map, "importOrder", 0L)
            );
        }

        @Override
        public int compareTo(EventEnvelope other) {
            int result = Long.compare(time, other.time);
            if (result != 0) return result;
            result = Integer.compare(priority, other.priority);
            if (result != 0) return result;
            result = Long.compare(seq, other.seq);
            if (result != 0) return result;
            result = Long.compare(importOrder, other.importOrder);
            if (result != 0) return result;
            result = Long.compare(generation, other.generation);
            if (result != 0) return result;
            return id.compareTo(other.id);
        }

    }
}
