package replay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON-native domain model: definition, transitions, actions, events, snapshots. */
public final class Model {
    private Model() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o, String what) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException(what + " must be a JSON object");
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o, String what) {
        if (o instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException(what + " must be a JSON array");
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        return String.valueOf(v);
    }

    public static final class Definition {
        public final String name;
        public final int version;
        public final List<String> states;
        public final String initialState;
        public final Map<String, Integer> sourcePriorities;
        public final List<Transition> transitions;

        public Definition(String name, int version, List<String> states, String initialState,
                          Map<String, Integer> sourcePriorities, List<Transition> transitions) {
            this.name = name;
            this.version = version;
            this.states = states;
            this.initialState = initialState;
            this.sourcePriorities = sourcePriorities;
            this.transitions = transitions;
        }

        static Definition fromMap(Map<String, Object> m) {
            List<String> states = (asList(m.get("states"), "states")).stream()
                    .map(String::valueOf).toList();
            String initial = str(m, "initialState");
            if (initial == null || !states.contains(initial)) {
                throw new IllegalArgumentException("initialState must be one of states");
            }
            Map<String, Integer> prio = new LinkedHashMap<>();
            Object p = m.get("sourcePriorities");
            if (p instanceof Map<?, ?> pm) {
                pm.forEach((k, v) -> prio.put(String.valueOf(k),
                        ((Number) v).intValue()));
            }
            List<Transition> ts = new java.util.ArrayList<>();
            for (Object t : asList(m.getOrDefault("transitions", List.of()), "transitions")) {
                ts.add(Transition.fromMap(asMap(t, "transition")));
            }
            return new Definition(str(m, "name"),
                    m.get("version") == null ? 1 : ((Number) m.get("version")).intValue(),
                    states, initial, prio, ts);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("version", version);
            m.put("states", states);
            m.put("initialState", initialState);
            m.put("sourcePriorities", sourcePriorities);
            List<Object> tl = new java.util.ArrayList<>();
            for (Transition t : transitions) tl.add(t.toMap());
            m.put("transitions", tl);
            return m;
        }

        public int priorityOf(String source) {
            Integer p = sourcePriorities.get(source);
            if (p != null) return p;
            if ("internal".equals(source)) return Integer.MAX_VALUE;
            return 1000;
        }
    }

    public static final class Transition {
        public final String event;
        public final String from;
        public final String condition;
        public final String to;
        public final List<Action> actions;

        public Transition(String event, String from, String condition, String to, List<Action> actions) {
            this.event = event;
            this.from = from;
            this.to = to;
            this.condition = condition;
            this.actions = actions;
        }

        static Transition fromMap(Map<String, Object> m) {
            String ev = str(m, "event");
            if (ev == null || ev.isBlank()) throw new IllegalArgumentException("transition requires event");
            List<Action> acts = new java.util.ArrayList<>();
            for (Object a : asList(m.getOrDefault("actions", List.of()), "actions")) {
                acts.add(Action.fromMap(asMap(a, "action")));
            }
            return new Transition(ev, str(m, "from"), str(m, "condition"), str(m, "to"), acts);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event", event);
            if (from != null) m.put("from", from);
            if (condition != null) m.put("condition", condition);
            if (to != null) m.put("to", to);
            List<Object> al = new java.util.ArrayList<>();
            for (Action a : actions) al.add(a.toMap());
            m.put("actions", al);
            return m;
        }
    }

    public static final class Action {
        public final String type;
        public final String var;
        public final Object value;
        public final String expr;
        public final String eventName;
        public final Map<String, Object> payload;
        public final String message;
        public final int bound;

        private Action(String type, String var, Object value, String expr, String eventName,
                       Map<String, Object> payload, String message, int bound) {
            this.type = type;
            this.var = var;
            this.value = value;
            this.expr = expr;
            this.eventName = eventName;
            this.payload = payload;
            this.message = message;
            this.bound = bound;
        }

        static Action fromMap(Map<String, Object> m) {
            String type = str(m, "type");
            if (type == null) throw new IllegalArgumentException("action requires type");
            return new Action(type, str(m, "var"), m.get("value"), str(m, "expr"),
                    str(m, "event"), asMap(m.getOrDefault("payload", new LinkedHashMap<>()), "payload"),
                    str(m, "message"),
                    m.get("bound") == null ? 100 : ((Number) m.get("bound")).intValue());
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            if (var != null) m.put("var", var);
            if (value != null) m.put("value", value);
            if (expr != null) m.put("expr", expr);
            if (eventName != null) m.put("event", eventName);
            if (payload != null && !payload.isEmpty()) m.put("payload", payload);
            if (message != null) m.put("message", message);
            if (bound != 100) m.put("bound", bound);
            return m;
        }
    }

    public static final class Event {
        public final String name;
        public final long time;
        public final String source;
        public final long seq;
        public final Map<String, Object> payload;
        public final boolean internal;

        public Event(String name, long time, String source, long seq,
                     Map<String, Object> payload, boolean internal) {
            this.name = name;
            this.time = time;
            this.source = source;
            this.seq = seq;
            this.payload = payload == null ? new LinkedHashMap<>() : payload;
            this.internal = internal;
        }

        @SuppressWarnings("unchecked")
        static Event fromMap(Map<String, Object> m) {
            return new Event(str(m, "name"),
                    ((Number) m.getOrDefault("time", 0L)).longValue(),
                    str(m.getOrDefault("source", "external") == null ? "external" : m.get("source")),
                    ((Number) m.getOrDefault("seq", 0L)).longValue(),
                    m.get("payload") instanceof Map<?, ?> p ? new LinkedHashMap<>((Map<String, Object>) p)
                            : new LinkedHashMap<>(),
                    Boolean.TRUE.equals(m.get("internal")));
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("time", time);
            m.put("source", source);
            m.put("seq", seq);
            if (!payload.isEmpty()) m.put("payload", payload);
            if (internal) m.put("internal", true);
            return m;
        }

        /** Stable identity: logical time, source priority slot, original sequence. */
        public String identityKey(Definition def) {
            return time + "|" + def.priorityOf(source) + "|" + source + "|" + seq;
        }

        String sortKey(Definition def) {
            return String.format("%020d|%010d|%020d|%s", time, def.priorityOf(source), seq, name);
        }

        boolean sameContent(Event other) {
            return name.equals(other.name) && payload.equals(other.payload);
        }
    }

    public static final class Snapshot {
        public final String state;
        public final Map<String, Object> vars;

        public Snapshot(String state, Map<String, Object> vars) {
            this.state = state;
            this.vars = vars;
        }

        Snapshot copy() {
            return new Snapshot(state, new LinkedHashMap<>(vars));
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", state);
            m.put("vars", vars);
            return m;
        }

        @SuppressWarnings("unchecked")
        static Snapshot fromMap(Map<String, Object> m) {
            return new Snapshot(str(m, "state"),
                    new LinkedHashMap<>(asMap(m.get("vars"), "vars")));
        }
    }
}
