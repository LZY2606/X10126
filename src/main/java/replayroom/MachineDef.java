package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 可版本化的状态机定义。defFp 锁定其完整内容。 */
public final class MachineDef {
    public String name = "";
    public String version = "1";
    public String initialState = "";
    public Map<String, Object> initialVars = new LinkedHashMap<>();
    public Map<String, Integer> sources = new LinkedHashMap<>();
    public List<String> states = new ArrayList<>();
    public List<Transition> transitions = new ArrayList<>();

    private String cachedFp;

    public String fingerprint() {
        if (cachedFp == null) cachedFp = Hashing.fingerprint(canonical());
        return cachedFp;
    }

    public Map<String, Object> canonical() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("version", version);
        m.put("initialState", initialState);
        m.put("initialVars", initialVars);
        Map<String, Object> src = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : sources.entrySet())
            src.put(e.getKey(), (double) e.getValue());
        m.put("sources", src);
        m.put("states", states);
        List<Object> ts = new ArrayList<>();
        for (Transition t : transitions) ts.add(t.toJson());
        m.put("transitions", ts);
        return m;
    }

    public void validate() {
        if (initialState == null || initialState.isEmpty())
            throw new IllegalArgumentException("initialState is required");
        if (!states.contains(initialState))
            throw new IllegalArgumentException("initialState must be one of states");
        for (Transition t : transitions) {
            if (t.from != null && !"*".equals(t.from) && !states.contains(t.from))
                throw new IllegalArgumentException("transition from unknown state: " + t.from);
            if (t.to != null && !states.contains(t.to))
                throw new IllegalArgumentException("transition to unknown state: " + t.to);
            for (Action a : t.actions) a.validate();
        }
    }

    @SuppressWarnings("unchecked")
    public static MachineDef fromJson(Map<String, Object> m) {
        MachineDef d = new MachineDef();
        d.name = Json.str(m, "name") == null ? "" : (String) m.get("name");
        d.version = String.valueOf(m.getOrDefault("version", "1"));
        d.initialState = (String) m.get("initialState");
        Object iv = m.get("initialVars");
        if (iv instanceof Map) d.initialVars = new LinkedHashMap<>((Map<String, Object>) iv);
        Object src = m.get("sources");
        if (src instanceof Map)
            for (Map.Entry<String, Object> e : ((Map<String, Object>) src).entrySet())
                d.sources.put(e.getKey(), ((Number) e.getValue()).intValue());
        for (Object s : Json.list(m.get("states"))) d.states.add(String.valueOf(s));
        for (Object t : Json.list(m.get("transitions")))
            d.transitions.add(Transition.fromJson(Json.obj(t)));
        d.validate();
        return d;
    }

    /** 带条件的事件转换。from 为 "*" 表示任意状态。 */
    public static final class Transition {
        public String from;
        public String eventType;
        public String guard;
        public String to;
        public List<Action> actions = new ArrayList<>();

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (from != null) m.put("from", from);
            if (eventType != null) m.put("eventType", eventType);
            if (guard != null && !guard.isEmpty()) m.put("guard", guard);
            if (to != null) m.put("to", to);
            List<Object> as = new ArrayList<>();
            for (Action a : actions) as.add(a.toJson());
            m.put("actions", as);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Transition fromJson(Map<String, Object> m) {
            Transition t = new Transition();
            t.from = (String) m.get("from");
            t.eventType = (String) m.get("eventType");
            t.guard = (String) m.get("guard");
            t.to = (String) m.get("to");
            for (Object a : Json.list(m.get("actions")))
                t.actions.add(Action.fromJson(Json.obj(a)));
            return t;
        }
    }

    /** 动作：set 改变量、emit 输出动作、raise 派生内部事件、fail 触发失败回滚。 */
    public static final class Action {
        public String op;
        public String key;
        public String expr;
        public String channel;
        public Map<String, Object> payload = new LinkedHashMap<>();
        public String type;
        public long delay;

        public void validate() {
            if (op == null) throw new IllegalArgumentException("action op is required");
            switch (op) {
                case "set":
                    if (key == null || key.isEmpty()) throw new IllegalArgumentException("set requires key");
                    break;
                case "emit":
                    if (channel == null || channel.isEmpty()) throw new IllegalArgumentException("emit requires channel");
                    break;
                case "raise":
                    if (type == null || type.isEmpty()) throw new IllegalArgumentException("raise requires type");
                    if (delay < 0) throw new IllegalArgumentException("delay must be >= 0");
                    break;
                case "fail":
                    break;
                default:
                    throw new IllegalArgumentException("unknown action op: " + op);
            }
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", op);
            if (key != null) m.put("key", key);
            if (expr != null) m.put("expr", expr);
            if (channel != null) m.put("channel", channel);
            if (!payload.isEmpty()) m.put("payload", payload);
            if (type != null) m.put("type", type);
            if (op != null && op.equals("raise")) m.put("delay", delay);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Action fromJson(Map<String, Object> m) {
            Action a = new Action();
            a.op = (String) m.get("op");
            a.key = (String) m.get("key");
            a.expr = (String) m.get("expr");
            a.channel = (String) m.get("channel");
            Object p = m.get("payload");
            if (p instanceof Map) a.payload = new LinkedHashMap<>((Map<String, Object>) p);
            a.type = (String) m.get("type");
            a.delay = ((Number) m.getOrDefault("delay", 0)).longValue();
            a.validate();
            return a;
        }
    }
}
