package com.replay.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** State machine definition: states, transitions with conditions and actions, source priorities. */
public final class Definition {
    public final JsonObject raw;
    public final String name;
    public final String version;
    public final String initialState;
    public final List<Transition> transitions;
    public final Map<String, Integer> sourcePriorities;

    public Definition(JsonObject raw) {
        this.raw = raw;
        this.name = raw.has("name") ? raw.get("name").getAsString() : "machine";
        this.version = raw.has("version") ? raw.get("version").getAsString() : "1";
        if (!raw.has("initialState")) throw new IllegalArgumentException("definition requires initialState");
        this.initialState = raw.get("initialState").getAsString();
        this.transitions = new ArrayList<>();
        if (raw.has("transitions")) {
            for (JsonElement t : raw.getAsJsonArray("transitions")) transitions.add(new Transition(t.getAsJsonObject()));
        }
        this.sourcePriorities = new HashMap<>();
        if (raw.has("sourcePriorities")) {
            for (Map.Entry<String, JsonElement> e : raw.getAsJsonObject("sourcePriorities").entrySet()) {
                sourcePriorities.put(e.getKey(), e.getValue().getAsInt());
            }
        }
    }

    public int priorityOf(String source) {
        Integer p = sourcePriorities.get(source);
        if (p != null) return p;
        if (Event.INTERNAL_SOURCE.equals(source)) return 1000;
        return 100;
    }

    public String fingerprint() {
        return Canonical.fingerprint(raw);
    }

    public static final class Transition {
        public final String event;
        public final String from; // "*" matches any
        public final String to;   // null = stay
        public final String condition; // null/empty = always
        public final List<JsonObject> actions;

        public Transition(JsonObject o) {
            if (!o.has("event")) throw new IllegalArgumentException("transition requires event");
            this.event = o.get("event").getAsString();
            this.from = o.has("from") ? o.get("from").getAsString() : "*";
            this.to = o.has("to") && !o.get("to").isJsonNull() ? o.get("to").getAsString() : null;
            this.condition = o.has("condition") && !o.get("condition").isJsonNull() ? o.get("condition").getAsString() : null;
            this.actions = new ArrayList<>();
            if (o.has("actions")) {
                JsonArray a = o.getAsJsonArray("actions");
                for (JsonElement el : a) actions.add(el.getAsJsonObject());
            }
        }

        public JsonObject summary() {
            JsonObject o = new JsonObject();
            o.addProperty("event", event);
            o.addProperty("from", from);
            if (to != null) o.addProperty("to", to);
            return o;
        }
    }
}
