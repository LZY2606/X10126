package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** State machine definition: states, variables, source priorities and event handlers with conditions/actions. */
public final class Definition {
    public String version = "1.0";
    public List<String> states = new ArrayList<>();
    public String initialState;
    public Map<String, Object> initialVariables = Json.map();
    public Map<String, Integer> sourcePriorities = Json.map();
    public Map<String, EventDef> events = new java.util.LinkedHashMap<>();

    public static final int UNKNOWN_SOURCE_PRIORITY = 1000;
    public static final int INTERNAL_SOURCE_PRIORITY = 1_000_000;

    public int priorityOf(String source) {
        if (Event.INTERNAL_SOURCE.equals(source)) return INTERNAL_SOURCE_PRIORITY;
        Integer p = sourcePriorities.get(source);
        return p != null ? p : UNKNOWN_SOURCE_PRIORITY;
    }

    public String fingerprint() {
        return Hash.sha256(Json.canonical(toJson()));
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = Json.map();
        m.put("version", version);
        m.put("states", new ArrayList<>(states));
        m.put("initialState", initialState);
        m.put("initialVariables", Json.deepCopy(initialVariables));
        m.put("sourcePriorities", Json.deepCopy(sourcePriorities));
        List<Object> evs = Json.list();
        for (EventDef e : events.values()) evs.add(e.toJson());
        m.put("events", evs);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Definition fromJson(Map<String, Object> m) {
        Definition d = new Definition();
        Object v = m.get("version");
        d.version = v == null ? "1.0" : String.valueOf(v);
        Object states = m.get("states");
        if (states != null) for (Object s : Json.arr(states)) d.states.add(Json.str(s));
        d.initialState = m.get("initialState") == null ? (d.states.isEmpty() ? "init" : Json.str(m.getOrDefault("initialState", d.states.get(0)))) : Json.str(m.get("initialState"));
        if (d.initialState == null) d.initialState = d.states.isEmpty() ? "init" : d.states.get(0);
        Object iv = m.get("initialVariables");
        if (iv != null) d.initialVariables = (Map<String, Object>) Json.deepCopy(Json.obj(iv));
        Object sp = m.get("sourcePriorities");
        if (sp != null) {
            for (Map.Entry<String, Object> e : Json.obj(sp).entrySet()) {
                d.sourcePriorities.put(e.getKey(), (int) Json.num(e.getValue()));
            }
        }
        Object evs = m.get("events");
        if (evs != null) {
            for (Object eo : Json.arr(evs)) {
                EventDef ed = EventDef.fromJson(Json.obj(eo));
                d.events.put(ed.name, ed);
            }
        }
        return d;
    }

    public static final class EventDef {
        public String name;
        public String condition;
        public List<ActionDef> actions = new ArrayList<>();

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.map();
            m.put("name", name);
            if (condition != null) m.put("when", condition);
            List<Object> as = Json.list();
            for (ActionDef a : actions) as.add(a.toJson());
            m.put("actions", as);
            return m;
        }

        public static EventDef fromJson(Map<String, Object> m) {
            EventDef e = new EventDef();
            e.name = Json.str(m.get("name"));
            Object w = m.get("when");
            e.condition = w == null ? null : Json.str(w);
            Object as = m.get("actions");
            if (as != null) for (Object ao : Json.arr(as)) e.actions.add(ActionDef.fromJson(Json.obj(ao)));
            return e;
        }
    }

    /** Action types: set / emit / output / require / fail. Optional "if" guard on every action. */
    public static final class ActionDef {
        public String type;
        public String target;       // set: variable name; emit: event name
        public String expr;         // set: value expr; output: value expr; require: condition expr; fail: message
        public String guard;        // optional "if" expression
        public Map<String, Object> payload; // emit: literal payload

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.map();
            switch (type) {
                case "set":
                    m.put("set", target);
                    m.put("to", expr);
                    break;
                case "emit":
                    m.put("emit", target);
                    if (payload != null && !payload.isEmpty()) m.put("payload", Json.deepCopy(payload));
                    break;
                case "output":
                    m.put("output", expr);
                    break;
                case "require":
                    m.put("require", expr);
                    break;
                case "fail":
                    m.put("fail", expr);
                    break;
                default:
                    m.put("type", type);
            }
            if (guard != null) m.put("if", guard);
            return m;
        }

        public static ActionDef fromJson(Map<String, Object> m) {
            ActionDef a = new ActionDef();
            if (m.containsKey("set")) {
                a.type = "set";
                a.target = Json.str(m.get("set"));
                a.expr = Json.str(m.get("to"));
            } else if (m.containsKey("emit")) {
                a.type = "emit";
                a.target = Json.str(m.get("emit"));
                Object p = m.get("payload");
                if (p != null) a.payload = Json.obj(Json.deepCopy(p));
            } else if (m.containsKey("output")) {
                a.type = "output";
                a.expr = Json.str(m.get("output"));
            } else if (m.containsKey("require")) {
                a.type = "require";
                a.expr = Json.str(m.get("require"));
            } else if (m.containsKey("fail")) {
                a.type = "fail";
                a.expr = m.get("fail") == null ? "动作失败" : Json.str(m.get("fail"));
            } else {
                throw new IllegalArgumentException("未知动作定义: " + Json.write(m));
            }
            Object g = m.get("if");
            a.guard = g == null ? null : Json.str(g);
            return a;
        }
    }
}
