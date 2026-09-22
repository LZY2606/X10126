package replay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** State machine definition: states, conditional transitions, actions, priorities, seed. */
public final class Definition {
    public String id;
    public String name;
    public long version;
    public long seed;
    public List<String> states = new ArrayList<>();
    public String initialState;
    public Map<String, Object> initialVars = new LinkedHashMap<>();
    public Map<String, Long> sourcePriorities = new LinkedHashMap<>();
    public List<Transition> transitions = new ArrayList<>();

    public static final class Transition {
        public String id;
        public String from = "*";
        public String on;
        public String condition;
        public String to;
        public List<Action> actions = new ArrayList<>();
    }

    public static final class Action {
        public String type; // set | emit | event | fail | choose
        public String variable;
        public Object value;
        public String message;
        public String eventType;
        public Map<String, Object> payload;
        public List<Object> options;
    }

    public static Definition fromMap(Map<String, Object> m) {
        Definition d = new Definition();
        d.id = str(m.get("id"), null);
        if (d.id == null || d.id.isBlank()) d.id = UUID.randomUUID().toString();
        d.name = str(m.get("name"), d.id);
        d.version = num(m.get("version"), 1);
        d.seed = num(m.get("seed"), 0);
        d.states = strList(m.get("states"));
        d.initialState = str(m.get("initialState"), d.states.isEmpty() ? null : d.states.get(0));
        if (d.initialState == null) throw new IllegalArgumentException("definition needs initialState");
        d.initialVars = map(m.get("initialVars"));
        d.sourcePriorities = new LinkedHashMap<>();
        Object sp = m.get("sourcePriorities");
        if (sp instanceof Map<?, ?> sm) {
            for (Map.Entry<?, ?> e : sm.entrySet()) {
                d.sourcePriorities.put(String.valueOf(e.getKey()), ((Number) e.getValue()).longValue());
            }
        }
        Object trs = m.get("transitions");
        if (trs instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map)) throw new IllegalArgumentException("transition must be object");
                d.transitions.add(transitionFromMap(map(o)));
            }
        }
        return d;
    }

    private static Transition transitionFromMap(Map<String, Object> m) {
        Transition t = new Transition();
        t.id = str(m.get("id"), "t" + Math.abs(m.hashCode()));
        t.from = str(m.get("from"), "*");
        t.on = str(m.get("on"), null);
        if (t.on == null) throw new IllegalArgumentException("transition " + t.id + " needs 'on'");
        t.condition = str(m.get("condition"), null);
        t.to = str(m.get("to"), null);
        Object acts = m.get("actions");
        if (acts instanceof List<?> list) {
            for (Object o : list) t.actions.add(actionFromMap(map(o)));
        }
        return t;
    }

    private static Action actionFromMap(Map<String, Object> m) {
        Action a = new Action();
        a.type = str(m.get("type"), null);
        if (a.type == null) throw new IllegalArgumentException("action needs 'type'");
        a.variable = str(m.get("variable"), null);
        a.value = m.get("value");
        a.message = str(m.get("message"), null);
        a.eventType = str(m.get("eventType"), null);
        a.payload = m.get("payload") instanceof Map ? map(m.get("payload")) : new LinkedHashMap<>();
        a.options = m.get("options") instanceof List ? list(m.get("options")) : new ArrayList<>();
        return a;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("version", version);
        m.put("seed", seed);
        m.put("states", new ArrayList<>(states));
        m.put("initialState", initialState);
        m.put("initialVars", new LinkedHashMap<>(initialVars));
        m.put("sourcePriorities", new LinkedHashMap<>(sourcePriorities));
        List<Object> trs = new ArrayList<>();
        for (Transition t : transitions) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("id", t.id);
            tm.put("from", t.from);
            tm.put("on", t.on);
            if (t.condition != null) tm.put("condition", t.condition);
            if (t.to != null) tm.put("to", t.to);
            List<Object> acts = new ArrayList<>();
            for (Action a : t.actions) {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("type", a.type);
                if (a.variable != null) am.put("variable", a.variable);
                if (a.value != null) am.put("value", a.value);
                if (a.message != null) am.put("message", a.message);
                if (a.eventType != null) am.put("eventType", a.eventType);
                if (a.payload != null && !a.payload.isEmpty()) am.put("payload", a.payload);
                if (a.options != null && !a.options.isEmpty()) am.put("options", a.options);
                acts.add(am);
            }
            tm.put("actions", acts);
            trs.add(tm);
        }
        m.put("transitions", trs);
        return m;
    }

    /** Stable fingerprint over the full definition content (including seed). */
    public String fingerprint() {
        return sha256(Json.canonical(toMap()));
    }

    /** Fingerprint locked for a replay: definition + initial state + events + seed. */
    public static String replayFingerprint(Definition def, List<Event> externalEvents) {
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("definition", def.toMap());
        lock.put("seed", def.seed);
        lock.put("initialState", def.initialState);
        lock.put("initialVars", def.initialVars);
        List<Object> evs = new ArrayList<>();
        for (Event e : externalEvents) evs.add(e.contentMap());
        lock.put("events", evs);
        return sha256(Json.canonical(lock));
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public long priorityOf(String source) {
        Long p = sourcePriorities.get(source);
        return p != null ? p : 1000L;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        if (o == null) return new LinkedHashMap<>();
        if (!(o instanceof Map)) throw new IllegalArgumentException("expected object, got " + o);
        return new LinkedHashMap<>((Map<String, Object>) o);
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return new ArrayList<>((List<Object>) o);
    }

    static String str(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    static long num(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }

    static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) out.add(String.valueOf(x));
        return out;
    }
}
