package replay.core;

import replay.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A versioned state machine definition. Its fingerprint locks the exact content. */
public final class Definition {

    public static final class EventDef {
        public final String condition; // may be null (= always)
        public final List<Map<String, Object>> actions;

        EventDef(String condition, List<Map<String, Object>> actions) {
            this.condition = condition;
            this.actions = actions;
        }
    }

    public final String name;
    public final long version;
    public final String initialState;
    public final List<String> states;
    public final Map<String, Object> initialVariables;
    public final Map<String, EventDef> events;
    private final Map<String, Object> raw;

    private Definition(String name, long version, String initialState, List<String> states,
                       Map<String, Object> initialVariables, Map<String, EventDef> events,
                       Map<String, Object> raw) {
        this.name = name;
        this.version = version;
        this.initialState = initialState;
        this.states = states;
        this.initialVariables = initialVariables;
        this.events = events;
        this.raw = raw;
    }

    @SuppressWarnings("unchecked")
    public static Definition fromJson(Map<String, Object> json) {
        String name = Json.asString(json.getOrDefault("name", "machine"), "definition.name");
        long version = Json.asLong(json.getOrDefault("version", 1L), "definition.version");
        String initialState = Json.asString(json.get("initialState"), "definition.initialState");
        List<String> states = new ArrayList<>();
        for (Object s : Json.asList(json.get("states"), "definition.states")) {
            states.add(Json.asString(s, "state name"));
        }
        if (!states.contains(initialState)) {
            throw new Json.JsonException("initialState '" + initialState + "' is not in states");
        }
        Map<String, Object> vars = json.containsKey("variables")
                ? Json.asMap(json.get("variables"), "definition.variables")
                : new LinkedHashMap<>();
        Map<String, EventDef> events = new LinkedHashMap<>();
        Map<String, Object> eventsJson = Json.asMap(json.get("events"), "definition.events");
        for (Map.Entry<String, Object> e : eventsJson.entrySet()) {
            Map<String, Object> ev = Json.asMap(e.getValue(), "event '" + e.getKey() + "'");
            String condition = ev.containsKey("condition") && ev.get("condition") != null
                    ? Json.asString(ev.get("condition"), "condition") : null;
            List<Map<String, Object>> actions = new ArrayList<>();
            for (Object a : Json.asList(ev.getOrDefault("actions", new ArrayList<>()), "actions")) {
                Map<String, Object> action = Json.asMap(a, "action");
                Json.asString(action.get("type"), "action.type");
                actions.add(action);
            }
            events.put(e.getKey(), new EventDef(condition, actions));
        }
        Map<String, Object> raw = (Map<String, Object>) Json.deepCopy(json);
        return new Definition(name, version, initialState, states,
                (Map<String, Object>) Json.deepCopy(vars), events, raw);
    }

    public Map<String, Object> toJson() {
        return (Map<String, Object>) Json.deepCopy(raw);
    }

    /** Stable fingerprint of the definition content alone. */
    public String fingerprint() {
        return Hashes.sha256(Json.canonical(raw));
    }

    public EventDef event(String name) {
        return events.get(name);
    }
}
