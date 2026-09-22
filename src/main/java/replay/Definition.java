package replay;

import java.util.List;
import java.util.Map;

/**
 * State machine definition. Transitions, conditions and actions are kept as
 * raw JSON maps so the canonical fingerprint always reflects exactly what the
 * user submitted.
 */
public final class Definition {
    public final String name;
    public final String initialState;
    public final List<Object> states;
    public final Map<String, Object> initialVars;
    public final List<Object> transitions;
    private final Map<String, Object> raw;
    private final String fingerprint;

    public Definition(Map<String, Object> raw) {
        this.raw = raw;
        this.name = raw.containsKey("name") ? Json.asString(raw.get("name"), "definition.name") : "machine";
        this.initialState = Json.asString(raw.get("initialState"), "definition.initialState");
        this.states = Json.asList(raw.get("states"), "definition.states");
        if (states.isEmpty()) {
            throw new Json.JsonException("definition.states must not be empty");
        }
        if (!states.contains(initialState)) {
            throw new Json.JsonException("definition.initialState must be one of states");
        }
        this.initialVars = raw.containsKey("variables")
                ? Json.asMap(raw.get("variables"), "definition.variables") : Json.map();
        this.transitions = raw.containsKey("transitions")
                ? Json.asList(raw.get("transitions"), "definition.transitions") : Json.list();
        for (Object t : transitions) {
            Map<String, Object> tr = Json.asMap(t, "transition");
            Json.asString(tr.get("event"), "transition.event");
            if (!tr.containsKey("from")) {
                throw new Json.JsonException("transition.from is required (state name or *)");
            }
        }
        this.fingerprint = Hash.sha256(Json.canonical(raw));
    }

    public String fingerprint() {
        return fingerprint;
    }

    public Map<String, Object> raw() {
        return raw;
    }
}
