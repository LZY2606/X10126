package com.replayroom.model;

import com.replayroom.json.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Immutable snapshot of machine state + variables, captured before/after each event. */
public final class StateSnap {
    public final String state;
    public final Map<String, Object> variables;

    public StateSnap(String state, Map<String, Object> variables) {
        this.state = state;
        this.variables = new TreeMap<>(variables);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("variables", new TreeMap<>(variables));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static StateSnap fromMap(Map<String, Object> m) {
        return new StateSnap(
                Json.asString(m.get("state")),
                (Map<String, Object>) Json.deepCopy(Json.asMap(m.get("variables"))));
    }
}
