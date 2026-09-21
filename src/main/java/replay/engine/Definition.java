package replay.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import replay.Json;

/**
 * A versioned state-machine definition.
 *
 * <pre>
 * {
 *   "version": 3, "name": "turnstile",
 *   "states": ["locked", "open"],
 *   "initialState": "locked",
 *   "initialVars": { ... },
 *   "seed": 42,
 *   "transitions": [
 *     { "from": "locked", "on": "coin",
 *       "condition": "$state == 'locked'",
 *       "to": "open",
 *       "actions": [ { "type": "emit", "channel": "led", "payload": "green" } ] }
 *   ]
 * }
 * </pre>
 *
 * Action types:
 *  - emit:    {type:"emit", channel, payload ($expr supported inside payload)}
 *  - emitInternal: {type:"emitInternal", event, priority?, payload}
 *  - set:     {type:"set", name, value ($expr supported)}
 *  - fail:    {type:"fail", message ($expr supported)}
 */
public final class Definition {

    public int version;
    public String name = "machine";
    public final List<String> states = new ArrayList<>();
    public String initialState;
    public final Map<String, Object> initialVars = new LinkedHashMap<>();
    public long seed = 0L;
    public final List<Transition> transitions = new ArrayList<>();

    private String fingerprint;

    public static Definition fromMap(Map<String, Object> map) {
        Definition d = new Definition();
        d.version = Json.integer(map, "version", 1);
        if (Json.str(map, "name") != null) {
            d.name = Json.str(map, "name");
        }
        for (Object s : Json.list(map, "states")) {
            d.states.add(String.valueOf(s));
        }
        d.initialState = Json.str(map, "initialState");
        Map<String, Object> vars = Json.obj(map, "initialVars");
        if (vars != null) {
            d.initialVars.putAll(vars);
        }
        d.seed = Json.lng(map, "seed", 0L);
        for (Object t : Json.list(map, "transitions")) {
            if (!(t instanceof Map)) {
                throw new DefinitionException("transition must be an object");
            }
            d.transitions.add(Transition.fromMap((Map<String, Object>) t));
        }
        d.validate();
        return d;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", version);
        map.put("name", name);
        map.put("states", new ArrayList<>(states));
        map.put("initialState", initialState);
        map.put("initialVars", new LinkedHashMap<>(initialVars));
        map.put("seed", seed);
        List<Object> ts = new ArrayList<>();
        for (Transition t : transitions) {
            ts.add(t.toMap());
        }
        map.put("transitions", ts);
        return map;
    }

    /** Fingerprint excludes mutable runtime data; binds version and full semantics. */
    public String fingerprint() {
        if (fingerprint == null) {
            fingerprint = Hashes.fingerprint(toMap());
        }
        return fingerprint;
    }

    public List<Transition> matching(String state, String event) {
        List<Transition> out = new ArrayList<>();
        for (Transition t : transitions) {
            if (t.matches(state, event)) {
                out.add(t);
            }
        }
        return out;
    }

    public void validate() {
        if (states.isEmpty()) {
            throw new DefinitionException("definition needs at least one state");
        }
        if (initialState == null || !states.contains(initialState)) {
            throw new DefinitionException("initialState must be one of declared states");
        }
        for (Transition t : transitions) {
            t.validate(states);
        }
    }

    public static final class DefinitionException extends RuntimeException {
        public DefinitionException(String message) {
            super(message);
        }
    }

    public static final class Transition {
        public String from; // "*" means any state
        public String on;
        public String condition; // optional expression
        public String to;
        public final List<Action> actions = new ArrayList<>();

        static Transition fromMap(Map<String, Object> map) {
            Transition t = new Transition();
            t.from = requiredString(map, "from");
            t.on = requiredString(map, "on");
            t.condition = Json.str(map, "condition");
            t.to = requiredString(map, "to");
            for (Object a : Json.list(map, "actions")) {
                if (!(a instanceof Map)) {
                    throw new DefinitionException("action must be an object");
                }
                t.actions.add(Action.fromMap((Map<String, Object>) a));
            }
            return t;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("from", from);
            map.put("on", on);
            if (condition != null) {
                map.put("condition", condition);
            }
            map.put("to", to);
            List<Object> as = new ArrayList<>();
            for (Action a : actions) {
                as.add(a.toMap());
            }
            map.put("actions", as);
            return map;
        }

        boolean matches(String state, String event) {
            return ("*".equals(from) || from.equals(state)) && on.equals(event);
        }

        void validate(List<String> allowedStates) {
            if (from == null || on == null || to == null) {
                throw new DefinitionException("transition requires from/on/to");
            }
            if (!"*".equals(from) && !allowedStates.contains(from)) {
                throw new DefinitionException("unknown from-state: " + from);
            }
            if (!allowedStates.contains(to)) {
                throw new DefinitionException("unknown to-state: " + to);
            }
            for (Action a : actions) {
                a.validate();
            }
        }

        private static String requiredString(Map<String, Object> map, String key) {
            String v = Json.str(map, key);
            if (v == null) {
                throw new DefinitionException("transition requires '" + key + "'");
            }
            return v;
        }
    }

    public static final class Action {
        public String type;
        public String channel;
        public Object payload;
        public String event;
        public int priority;
        public String name;
        public Object value;
        public Object message;

        static Action fromMap(Map<String, Object> map) {
            Action a = new Action();
            a.type = Json.str(map, "type");
            a.channel = Json.str(map, "channel");
            a.payload = map.get("payload");
            a.event = Json.str(map, "event");
            a.priority = Json.integer(map, "priority", 0);
            a.name = Json.str(map, "name");
            a.value = map.get("value");
            a.message = Json.str(map, "message", "action failed");
            return a;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            if (channel != null) {
                map.put("channel", channel);
            }
            if (payload != null) {
                map.put("payload", payload);
            }
            if (event != null) {
                map.put("event", event);
            }
            if (priority != 0) {
                map.put("priority", priority);
            }
            if (name != null) {
                map.put("name", name);
            }
            if (value != null) {
                map.put("value", value);
            }
            if (message != null && !"action failed".equals(message)) {
                map.put("message", message);
            }
            return map;
        }

        void validate() {
            if (type == null) {
                throw new DefinitionException("action requires type");
            }
            switch (type) {
                case "emit" -> {
                    if (channel == null) {
                        throw new DefinitionException("emit action requires channel");
                    }
                }
                case "emitInternal" -> {
                    if (event == null) {
                        throw new DefinitionException("emitInternal action requires event");
                    }
                }
                case "set" -> {
                    if (name == null) {
                        throw new DefinitionException("set action requires name");
                    }
                }
                case "fail" -> {
                }
                default -> throw new DefinitionException("unknown action type: " + type);
            }
        }
    }
}
