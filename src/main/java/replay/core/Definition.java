package replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A versioned, immutable-ish state machine definition.
 * {@link #fingerprint} locks every transition/action and is recorded in every
 * checkpoint so checkpoints built with an old definition can never be replayed
 * under a new one.
 */
public final class Definition {
    public String name = "machine";
    public String version = "1";
    public List<String> states = new ArrayList<>();
    public String initial;
    public List<Transition> transitions = new ArrayList<>();
    public String fingerprint;

    public static final class Transition {
        public String event;
        public String from;
        public String to;
        public String condition;
        public List<Action> actions = new ArrayList<>();

        @SuppressWarnings("unchecked")
        static Transition fromJson(Map<String, Object> m) {
            Transition t = new Transition();
            t.event = Json.str(m, "event");
            t.from = Json.str(m, "from");
            t.to = Json.str(m, "to");
            t.condition = Json.str(m, "condition");
            for (Object a : Json.list(m, "actions")) {
                if (!(a instanceof Map)) throw new IllegalArgumentException("actions must be objects");
                t.actions.add(Action.fromJson((Map<String, Object>) a));
            }
            return t;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event", event);
            m.put("from", from);
            m.put("to", to);
            m.put("condition", condition == null ? "" : condition);
            List<Object> actions = new ArrayList<>();
            for (Action a : this.actions) actions.add(a.toJson());
            m.put("actions", actions);
            return m;
        }

        boolean matchesFrom(String stateName) {
            return from == null || from.isBlank() || "*".equals(from) || from.equals(stateName);
        }
    }

    public static final class Action {
        public String type;
        public String name;
        public String value;
        public String op;
        public Map<String, Object> data = new LinkedHashMap<>();
        public String eventType;
        public long delay;
        public String when;
        public String message;

        @SuppressWarnings("unchecked")
        static Action fromJson(Map<String, Object> m) {
            Action a = new Action();
            a.type = Json.str(m, "type");
            if (a.type == null || a.type.isBlank()) {
                throw new IllegalArgumentException("action requires a type");
            }
            a.name = Json.str(m, "name");
            a.value = Json.str(m, "value");
            a.op = Json.str(m, "op");
            Object data = m.get("data");
            a.data = data instanceof Map ? Util.deepCopy((Map<String, Object>) data)
                    : new LinkedHashMap<>();
            a.eventType = Json.str(m, "eventType");
            a.delay = Json.lng(m, "delay", 0);
            a.when = Json.str(m, "when");
            a.message = Json.str(m, "message");
            switch (a.type) {
                case "set" -> require(a.name != null, "set action requires name");
                case "remove" -> require(a.name != null, "remove action requires name");
                case "output" -> require(a.name != null, "output action requires name");
                case "emit" -> require(a.eventType != null, "emit action requires eventType");
                case "fail", "nop" -> { }
                default -> throw new IllegalArgumentException("unknown action type: " + a.type);
            }
            return a;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("name", name);
            m.put("value", value);
            m.put("op", op);
            m.put("data", data);
            m.put("eventType", eventType);
            m.put("delay", delay);
            m.put("when", when);
            m.put("message", message);
            return m;
        }
    }

    public static Definition fromJson(Map<String, Object> json) {
        Definition d = new Definition();
        d.name = Json.str(json, "name");
        if (d.name == null) d.name = "machine";
        d.version = Json.str(json, "version");
        if (d.version == null) d.version = "1";
        for (Object s : Json.list(json, "states")) d.states.add(String.valueOf(s));
        d.initial = Json.str(json, "initial");
        for (Object t : Json.list(json, "transitions")) {
            if (!(t instanceof Map)) throw new IllegalArgumentException("transitions must be objects");
            d.transitions.add(Transition.fromJson((Map<String, Object>) t));
        }
        d.fingerprint = Hashes.fingerprint(d.lockedShape());
        d.validate();
        return d;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("version", version);
        m.put("states", new ArrayList<>(states));
        m.put("initial", initial);
        List<Object> ts = new ArrayList<>();
        for (Transition t : transitions) ts.add(t.toJson());
        m.put("transitions", ts);
        m.put("fingerprint", fingerprint);
        return m;
    }

    private Map<String, Object> lockedShape() {
        // Everything semantically meaningful; version is documentation only -
        // structural changes always change the fingerprint.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("states", new ArrayList<>(states));
        m.put("initial", initial);
        List<Object> ts = new ArrayList<>();
        for (Transition t : transitions) ts.add(t.toJson());
        m.put("transitions", ts);
        return m;
    }

    public void validate() {
        require(states != null && !states.isEmpty(), "definition requires at least one state");
        Set<String> known = new LinkedHashSet<>(states);
        require(initial != null && known.contains(initial),
                "initial state must be one of: " + states);
        for (int i = 0; i < transitions.size(); i++) {
            Transition t = transitions.get(i);
            require(t.event != null && !t.event.isBlank(), "transition #" + i + " requires event");
            if (t.from != null && !t.from.isBlank() && !"*".equals(t.from)) {
                require(known.contains(t.from), "transition #" + i + " has unknown from-state: " + t.from);
            }
            require(t.to != null && known.contains(t.to), "transition #" + i + " has unknown to-state: " + t.to);
            if (t.condition != null && !t.condition.isBlank()) Expr.compile(t.condition);
            for (Action a : t.actions) {
                if (a.when != null && !a.when.isBlank()) Expr.compile(a.when);
                if ("set".equals(a.type) && a.value != null && !a.value.isBlank()) Expr.compile(a.value);
            }
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public static Definition demo() {
        return fromJson(Json.readObject("""
        {
          "name": "温控阀门",
          "version": "1",
          "states": ["idle", "open", "error"],
          "initial": "idle",
          "transitions": [
            {
              "event": "open_request", "from": "idle", "to": "open",
              "condition": "event.payload.force == true || vars.attempts < 3",
              "actions": [
                {"type": "set", "name": "attempts", "op": "+", "value": "1"},
                {"type": "output", "name": "valve", "value": "{open: true}"},
                {"type": "emit", "eventType": "opened", "delay": 0}
              ]
            },
            {
              "event": "opened", "from": "open", "to": "open",
              "actions": [
                {"type": "output", "name": "log", "value": "{msg: 'opened'}"}
              ]
            },
            {
              "event": "close_request", "from": "open", "to": "idle",
              "actions": [
                {"type": "output", "name": "valve", "value": "{open: false}"}
              ]
            },
            {
              "event": "fault", "from": "*", "to": "error",
              "actions": [
                {"type": "fail", "message": "fault raised at the field"}
              ]
            }
          ]
        }
        """));
    }
}
