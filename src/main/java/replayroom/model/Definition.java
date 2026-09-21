package replayroom.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replayroom.engine.Hashes;
import replayroom.json.Json;

/** An immutable state machine definition with a content fingerprint. */
public final class Definition {
    private final String name;
    private final String initialState;
    private final Map<String, Object> initialVars;
    private final List<Transition> transitions;
    private final String fingerprint;

    public Definition(String name, String initialState, Map<String, Object> initialVars, List<Transition> transitions) {
        this.name = name == null ? "" : name;
        if (initialState == null || initialState.isBlank()) {
            throw new IllegalArgumentException("initialState is required");
        }
        this.initialState = initialState;
        this.initialVars = initialVars == null ? new LinkedHashMap<>() : Json.deepCopyMap(initialVars);
        this.transitions = List.copyOf(transitions);
        validate();
        this.fingerprint = Hashes.sha256Hex(Json.canonical(fingerprintBody()));
    }

    public static Definition fromMap(Map<String, Object> map) {
        String initialState = Json.optString(map, "initialState", null);
        if (initialState == null) {
            throw new IllegalArgumentException("definition.initialState is required");
        }
        Map<String, Object> initialVars = Json.optObject(map, "initialVars");
        List<Transition> transitions = new ArrayList<>();
        int index = 0;
        for (Object value : Json.optList(map, "transitions")) {
            transitions.add(Transition.fromMap(Json.object(value, "transitions[" + index + "]"), index));
            index++;
        }
        return new Definition(Json.optString(map, "name", ""), initialState, initialVars, transitions);
    }

    private void validate() {
        for (Transition transition : transitions) {
            if (transition.from() != null && !transition.from().isBlank()
                    && !transition.from().equals(initialState)
                    && transitions.stream().noneMatch(other ->
                            transition != other && transition.from() != null
                                    && (other.to() != null && other.to().equals(transition.from())))) {
                // from-state may legitimately be declared without an incoming transition; no hard error.
            }
        }
    }

    private Map<String, Object> fingerprintBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "sm-definition/v1");
        body.put("name", name);
        body.put("initialState", initialState);
        body.put("initialVars", initialVars);
        List<Object> transitionMaps = new ArrayList<>();
        for (Transition transition : transitions) transitionMaps.add(transition.toMap());
        body.put("transitions", transitionMaps);
        return body;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("initialState", initialState);
        map.put("initialVars", initialVars);
        List<Object> transitionMaps = new ArrayList<>();
        for (Transition transition : transitions) transitionMaps.add(transition.toMap());
        map.put("transitions", transitionMaps);
        map.put("fingerprint", fingerprint);
        return map;
    }

    public String name() { return name; }
    public String initialState() { return initialState; }
    public Map<String, Object> initialVars() { return Json.deepCopyMap(initialVars); }
    public List<Transition> transitions() { return transitions; }
    public String fingerprint() { return fingerprint; }
}
