package com.replayroom.core;

import com.replayroom.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** Restorable machine state: current state name, variables and RNG state. */
public class StateSnapshot {
    public String state;
    public Map<String, Object> variables = new LinkedHashMap<>();
    public long rngState;

    public StateSnapshot copy() {
        StateSnapshot c = new StateSnapshot();
        c.state = state;
        c.variables = Json.convert(variables, LinkedHashMap.class);
        c.rngState = rngState;
        return c;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("state", state);
        map.put("variables", variables);
        map.put("rngState", rngState);
        return map;
    }
}
