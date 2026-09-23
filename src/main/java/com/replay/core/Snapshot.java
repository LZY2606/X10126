package com.replay.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.TreeMap;

/** Immutable-by-convention state snapshot: current state name + variables. */
public final class Snapshot {
    public String state;
    public final TreeMap<String, JsonElement> vars;

    public Snapshot(String state, Map<String, JsonElement> vars) {
        this.state = state;
        this.vars = new TreeMap<>(vars);
    }

    public Snapshot copy() {
        TreeMap<String, JsonElement> v = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : vars.entrySet()) v.put(e.getKey(), e.getValue().deepCopy());
        return new Snapshot(state, v);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("state", state);
        JsonObject vs = new JsonObject();
        for (Map.Entry<String, JsonElement> e : vars.entrySet()) vs.add(e.getKey(), e.getValue());
        o.add("vars", vs);
        return o;
    }

    public static Snapshot fromJson(JsonObject o) {
        TreeMap<String, JsonElement> v = new TreeMap<>();
        if (o.has("vars")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("vars").entrySet()) v.put(e.getKey(), e.getValue());
        return new Snapshot(o.get("state").getAsString(), v);
    }
}
