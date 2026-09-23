package replay.core;

import replay.json.Json;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable state machine definition. Its fingerprint locks the exact definition version. */
public final class Definition {
    public String name = "machine";
    public String initialState;
    public final List<String> states = new ArrayList<>();
    /** source name -> priority (lower sorts earlier at equal logical time). */
    public final Map<String, Long> sources = new LinkedHashMap<>();
    public final List<Transition> transitions = new ArrayList<>();

    public static final class Transition {
        public String from = "*";
        public String event;
        public String condition; // may be null
        public String to;        // may be null (stay)
        public List<Map<String, Object>> actions = new ArrayList<>();

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", from);
            m.put("event", event);
            if (condition != null) m.put("condition", condition);
            if (to != null) m.put("to", to);
            m.put("actions", Json.deepCopy(actions));
            return m;
        }
    }

    public int sourcePriority(String source) {
        Long p = sources.get(source);
        return p == null ? 1000 : p.intValue();
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("initialState", initialState);
        m.put("states", new ArrayList<>(states));
        Map<String, Object> src = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : sources.entrySet()) src.put(e.getKey(), e.getValue());
        m.put("sources", src);
        List<Object> tr = new ArrayList<>();
        for (Transition t : transitions) tr.add(t.toJson());
        m.put("transitions", tr);
        return m;
    }

    public String fingerprint() {
        return sha256(Json.canonical(toJson()));
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Definition fromJson(Map<String, Object> m) {
        Definition d = new Definition();
        Object n = m.get("name");
        if (n != null) d.name = Json.asString(n, "definition.name");
        d.initialState = Json.asString(m.get("initialState"), "definition.initialState");
        for (Object s : Json.asList(m.get("states"), "definition.states")) {
            d.states.add(Json.asString(s, "state name"));
        }
        if (!d.states.contains(d.initialState)) {
            throw new Json.JsonException("initialState '" + d.initialState + "' is not in states");
        }
        Object src = m.get("sources");
        if (src != null) {
            for (Map.Entry<String, Object> e : Json.asMap(src, "definition.sources").entrySet()) {
                d.sources.put(e.getKey(), Json.asLong(e.getValue(), "source priority"));
            }
        }
        for (Object o : Json.asList(m.get("transitions"), "definition.transitions")) {
            Map<String, Object> tm = Json.asMap(o, "transition");
            Transition t = new Transition();
            Object from = tm.get("from");
            t.from = from == null ? "*" : Json.asString(from, "transition.from");
            t.event = Json.asString(tm.get("event"), "transition.event");
            Object cond = tm.get("condition");
            t.condition = cond == null ? null : Json.asString(cond, "transition.condition");
            Object to = tm.get("to");
            t.to = to == null ? null : Json.asString(to, "transition.to");
            if (t.to != null && !d.states.contains(t.to)) {
                throw new Json.JsonException("transition target '" + t.to + "' is not a declared state");
            }
            Object acts = tm.get("actions");
            if (acts != null) {
                for (Object a : Json.asList(acts, "transition.actions")) {
                    t.actions.add(new LinkedHashMap<>(Json.asMap(a, "action")));
                }
            }
            d.transitions.add(t);
        }
        return d;
    }
}
