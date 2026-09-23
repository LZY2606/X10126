package com.replayroom.model;

import com.replayroom.expr.Expr;
import com.replayroom.json.Json;
import com.replayroom.util.Hashes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A versioned state machine definition. Its fingerprint locks the exact content. */
public final class Definition {
    public String id;
    public String name;
    public String version;
    public String initialState;
    public Map<String, Object> variables = new LinkedHashMap<>();
    public List<String> states = new ArrayList<>();
    public Map<String, Integer> sourcePriorities = new LinkedHashMap<>();
    public List<Transition> transitions = new ArrayList<>();

    public static final class Transition {
        public String from = "*";
        public String event;
        public String to;
        public String condition;
        public List<String> actions = new ArrayList<>();

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", from);
            m.put("event", event);
            m.put("to", to);
            if (condition != null) m.put("condition", condition);
            m.put("actions", new ArrayList<>(actions));
            return m;
        }

        public String describe() {
            return from + " --" + event + "--> " + to;
        }
    }

    public int priorityOf(String source) {
        Integer p = sourcePriorities.get(source);
        if (p != null) return p;
        if ("@internal".equals(source)) return 1000;
        return 100;
    }

    /** Canonical content used for the fingerprint (excludes the storage id). */
    public Map<String, Object> contentMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("version", version);
        m.put("initialState", initialState);
        m.put("variables", Json.deepCopy(variables));
        m.put("states", new ArrayList<>(states));
        m.put("sourcePriorities", new LinkedHashMap<>(sourcePriorities));
        List<Object> ts = new ArrayList<>();
        for (Transition t : transitions) ts.add(t.toMap());
        m.put("transitions", ts);
        return m;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = contentMap();
        m.put("id", id);
        return m;
    }

    public String fingerprint() {
        return Hashes.sha256Hex(Json.canonical(contentMap()));
    }

    public static Definition fromMap(Map<String, Object> m) {
        Definition d = new Definition();
        d.id = Json.optString(m, "id", null);
        d.name = Json.optString(m, "name", null);
        if (d.name == null || d.name.isBlank()) throw new IllegalArgumentException("definition requires a name");
        d.version = Json.optString(m, "version", "1");
        Object states = m.get("states");
        if (states == null) throw new IllegalArgumentException("definition requires states");
        for (Object s : Json.asList(states)) d.states.add(Json.asString(s));
        if (d.states.isEmpty()) throw new IllegalArgumentException("definition states must not be empty");
        d.initialState = Json.optString(m, "initialState", null);
        if (d.initialState == null) throw new IllegalArgumentException("definition requires initialState");
        if (!d.states.contains(d.initialState)) {
            throw new IllegalArgumentException("initialState '" + d.initialState + "' not in states");
        }
        Object vars = m.get("variables");
        if (vars != null) d.variables = new LinkedHashMap<>(Json.asMap(vars));
        Object prios = m.get("sourcePriorities");
        if (prios != null) {
            for (Map.Entry<String, Object> e : Json.asMap(prios).entrySet()) {
                d.sourcePriorities.put(e.getKey(), (int) Json.asLong(e.getValue()));
            }
        }
        Object ts = m.get("transitions");
        if (ts != null) {
            for (Object o : Json.asList(ts)) {
                Map<String, Object> tm = Json.asMap(o);
                Transition t = new Transition();
                t.from = Json.optString(tm, "from", "*");
                t.event = Json.optString(tm, "event", null);
                t.to = Json.optString(tm, "to", null);
                if (t.event == null || t.to == null) throw new IllegalArgumentException("transition requires event and to");
                if (!"*".equals(t.from) && !d.states.contains(t.from)) {
                    throw new IllegalArgumentException("transition from unknown state '" + t.from + "'");
                }
                if (!d.states.contains(t.to)) {
                    throw new IllegalArgumentException("transition to unknown state '" + t.to + "'");
                }
                Object cond = tm.get("condition");
                if (cond != null) {
                    t.condition = Json.asString(cond);
                    Expr.validate(t.condition);
                }
                Object actions = tm.get("actions");
                if (actions != null) {
                    for (Object a : Json.asList(actions)) t.actions.add(Json.asString(a));
                }
                d.transitions.add(t);
            }
        }
        return d;
    }
}
