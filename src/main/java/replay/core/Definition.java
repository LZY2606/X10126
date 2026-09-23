package replay.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replay.json.Json;

/** Immutable state machine definition. Its fingerprint locks the definition version. */
public final class Definition {
    public final String name;
    public final long version;
    public final List<String> states;
    public final String initial;
    public final LinkedHashMap<String, Object> variables;
    public final List<Rule> rules;

    public Definition(String name, long version, List<String> states, String initial,
                      LinkedHashMap<String, Object> variables, List<Rule> rules) {
        this.name = name;
        this.version = version;
        this.states = List.copyOf(states);
        this.initial = initial;
        this.variables = variables;
        this.rules = List.copyOf(rules);
    }

    public static final class Rule {
        public final String event;
        public final String from;
        public final String condition;
        public final List<String> actionSources;
        public final List<Action> actions;

        Rule(String event, String from, String condition, List<String> actionSources, List<Action> actions) {
            this.event = event;
            this.from = from;
            this.condition = condition;
            this.actionSources = List.copyOf(actionSources);
            this.actions = List.copyOf(actions);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event", event);
            m.put("from", from);
            m.put("condition", condition);
            m.put("actions", new ArrayList<>(actionSources));
            return m;
        }
    }

    @SuppressWarnings("unchecked")
    public static Definition fromMap(Map<String, Object> m) {
        String name = m.getOrDefault("name", "machine").toString();
        long version = m.get("version") instanceof Number n ? n.longValue() : 1;
        Object statesObj = m.get("states");
        if (!(statesObj instanceof List) || ((List<?>) statesObj).isEmpty())
            throw new IllegalArgumentException("definition needs a non-empty 'states' list");
        List<String> states = new ArrayList<>();
        for (Object o : (List<Object>) statesObj) states.add(o.toString());
        String initial = m.get("initial") == null ? states.get(0) : m.get("initial").toString();
        if (!states.contains(initial))
            throw new IllegalArgumentException("initial state '" + initial + "' not in states");
        LinkedHashMap<String, Object> variables = new LinkedHashMap<>();
        if (m.get("variables") instanceof Map) {
            ((Map<String, Object>) m.get("variables")).forEach(variables::put);
        }
        List<Rule> rules = new ArrayList<>();
        if (m.get("rules") instanceof List) {
            for (Object ro : (List<Object>) m.get("rules")) {
                Map<String, Object> rm = (Map<String, Object>) ro;
                String event = String.valueOf(rm.get("event"));
                if (event.isEmpty() || event.equals("null"))
                    throw new IllegalArgumentException("rule missing 'event'");
                String from = rm.get("from") == null ? "*" : rm.get("from").toString();
                if (!from.equals("*") && !states.contains(from))
                    throw new IllegalArgumentException("rule 'from' state '" + from + "' not in states");
                String condition = rm.get("condition") == null ? "true" : rm.get("condition").toString();
                List<String> actionSrcs = new ArrayList<>();
                if (rm.get("actions") instanceof List) {
                    for (Object a : (List<Object>) rm.get("actions")) actionSrcs.add(a.toString());
                }
                List<Action> actions = new ArrayList<>();
                for (String src : actionSrcs) actions.add(Action.parse(src, states));
                rules.add(new Rule(event, from, condition, actionSrcs, actions));
            }
        }
        return new Definition(name, version, states, initial, variables, rules);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("version", version);
        m.put("states", new ArrayList<>(states));
        m.put("initial", initial);
        m.put("variables", new LinkedHashMap<>(variables));
        List<Object> ruleList = new ArrayList<>();
        for (Rule r : rules) ruleList.add(r.toMap());
        m.put("rules", ruleList);
        return m;
    }

    public String fingerprint() {
        return sha256(Json.canonical(toMap()));
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
