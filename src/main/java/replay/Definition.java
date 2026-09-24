package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A versioned state machine definition: states, events with conditions and actions, source priorities. */
public final class Definition {
    public final String version;
    public final List<String> states;
    public final List<String> sources; // index = priority (lower wins)
    public final Map<String, EventDef> events;
    public final Map<String, Object> raw;
    private final String fingerprint;

    private Definition(String version, List<String> states, List<String> sources,
                       Map<String, EventDef> events, Map<String, Object> raw) {
        this.version = version;
        this.states = states;
        this.sources = sources;
        this.events = events;
        this.raw = raw;
        this.fingerprint = Hashing.sha256(Json.canonical(raw));
    }

    public String fingerprint() { return fingerprint; }

    public int sourcePriority(String source) {
        int idx = sources.indexOf(source);
        return idx >= 0 ? idx : sources.size() + 1000;
    }

    @SuppressWarnings("unchecked")
    public static Definition fromJson(Map<String, Object> json) {
        String version = str(json.getOrDefault("version", "1"));
        List<String> states = strList(json.get("states"));
        if (states.isEmpty()) throw new IllegalArgumentException("definition needs at least one state");
        List<String> sources = json.containsKey("sources") ? strList(json.get("sources")) : List.of("default");
        Map<String, EventDef> events = new LinkedHashMap<>();
        Object ev = json.get("events");
        if (!(ev instanceof Map)) throw new IllegalArgumentException("definition needs an events object");
        for (Map.Entry<String, Object> e : ((Map<String, Object>) ev).entrySet()) {
            events.put(e.getKey(), EventDef.fromJson(e.getKey(), e.getValue()));
        }
        return new Definition(version, states, sources, events, json);
    }

    public static final class EventDef {
        public final String name;
        public final Expr condition; // may be null => always
        public final String conditionSource;
        public final List<Action> actions;

        EventDef(String name, Expr condition, String conditionSource, List<Action> actions) {
            this.name = name;
            this.condition = condition;
            this.conditionSource = conditionSource;
            this.actions = actions;
        }

        @SuppressWarnings("unchecked")
        static EventDef fromJson(String name, Object json) {
            if (!(json instanceof Map)) throw new IllegalArgumentException("event " + name + " must be an object");
            Map<String, Object> m = (Map<String, Object>) json;
            Object cond = m.get("condition");
            Expr condition = cond == null ? null : Expr.parse(String.valueOf(cond));
            List<Action> actions = new ArrayList<>();
            Object acts = m.get("actions");
            if (acts instanceof List) {
                for (Object a : (List<Object>) acts) actions.add(Action.fromJson(a));
            }
            return new EventDef(name, condition, cond == null ? null : String.valueOf(cond), actions);
        }
    }

    public sealed interface Action {
        static Action fromJson(Object json) {
            if (!(json instanceof Map)) throw new IllegalArgumentException("action must be an object");
            Map<?, ?> m = (Map<?, ?>) json;
            String type = String.valueOf(m.get("type"));
            return switch (type) {
                case "transition" -> new Transition(String.valueOf(m.get("state")));
                case "set" -> new Set(String.valueOf(m.get("var")), Expr.parse(String.valueOf(m.get("expr"))),
                        String.valueOf(m.get("expr")));
                case "emit" -> new Emit(String.valueOf(m.get("event")), payloadMap(m.get("payload")));
                case "output" -> new Output(Expr.parse(String.valueOf(m.get("expr"))), String.valueOf(m.get("expr")));
                default -> throw new IllegalArgumentException("unknown action type: " + type);
            };
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Expr> payloadMap(Object payload) {
            Map<String, Expr> out = new LinkedHashMap<>();
            if (payload instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) payload).entrySet()) {
                    out.put(e.getKey(), Expr.parse(String.valueOf(e.getValue())));
                }
            }
            return out;
        }

        record Transition(String state) implements Action {}
        record Set(String var, Expr expr, String exprSource) implements Action {}
        record Emit(String event, Map<String, Expr> payload) implements Action {}
        record Output(Expr expr, String exprSource) implements Action {}
    }

    static String str(Object o) { return String.valueOf(o); }

    @SuppressWarnings("unchecked")
    static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) for (Object v : (List<Object>) o) out.add(String.valueOf(v));
        return out;
    }
}
