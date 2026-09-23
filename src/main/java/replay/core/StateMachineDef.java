package replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replay.json.Json;

public final class StateMachineDef {
    public String name;
    public String version;
    public String initialState;
    public List<String> states = new ArrayList<>();
    /** Source names in priority order; earlier wins at equal logical time. */
    public List<String> sources = new ArrayList<>();
    public List<Transition> transitions = new ArrayList<>();
    public Map<String, Object> raw;

    public static final class Transition {
        public String event;
        public String from = "*";
        public String to;
        public Object condition;
        public List<Object> actions = new ArrayList<>();
        public Map<String, Object> raw;
    }

    public static StateMachineDef parse(Map<String, Object> json) {
        StateMachineDef def = new StateMachineDef();
        def.raw = Json.deepCopy(json);
        def.name = Json.str(json, "name");
        def.version = Json.str(json, "version");
        def.initialState = Json.str(json, "initialState");
        for (Object s : Json.arr(json.getOrDefault("states", List.of()), "states")) {
            def.states.add(String.valueOf(s));
        }
        if (!def.states.contains(def.initialState)) {
            throw new IllegalArgumentException("initialState not in states: " + def.initialState);
        }
        for (Object s : Json.arr(json.getOrDefault("sources", List.of()), "sources")) {
            def.sources.add(String.valueOf(s));
        }
        for (Object t : Json.arr(json.getOrDefault("transitions", List.of()), "transitions")) {
            Map<String, Object> tj = Json.obj(t, "transition");
            Transition tr = new Transition();
            tr.event = Json.str(tj, "event");
            if (tj.get("from") instanceof String f) {
                tr.from = f;
            }
            tr.to = tj.get("to") instanceof String to ? to : null;
            tr.condition = tj.get("condition");
            for (Object a : Json.arr(tj.getOrDefault("actions", List.of()), "actions")) {
                tr.actions.add(a);
            }
            tr.raw = tj;
            def.transitions.add(tr);
        }
        return def;
    }

    public String fingerprint() {
        return Fingerprint.sha256("def|" + Json.canonical(raw));
    }

    public int sourcePriority(String source) {
        int idx = sources.indexOf(source);
        return idx >= 0 ? idx : sources.size();
    }

    public Transition findTransition(String eventType, String state,
            Map<String, Object> vars, Map<String, Object> payload) {
        for (Transition t : transitions) {
            if (!t.event.equals(eventType)) {
                continue;
            }
            if (!"*".equals(t.from) && !t.from.equals(state)) {
                continue;
            }
            if (Condition.eval(t.condition, vars, payload)) {
                return t;
            }
        }
        return null;
    }
}
