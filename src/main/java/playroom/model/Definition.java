package playroom.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import playroom.Json;

/**
 * An immutable, validated state machine definition:
 * {
 *   "version": "v1",
 *   "states": ["idle", ...],
 *   "initial": { "state": "idle", "data": { ... } },
 *   "seed": 42,
 *   "sources": { "gateway": 1, "ops": 2 },
 *   "transitions": { "idle": [ { "on": "pay", "if": "data.amount >= 100", "actions": [...] } ] }
 * }
 */
public final class Definition {
    private final Map<String, Object> raw;
    private final String version;
    private final List<String> states;
    private final String initialState;
    private final Map<String, Object> initialData;
    private final long seed;
    private final Map<String, Long> sources;
    private final Map<String, List<Map<String, Object>>> transitions;
    private final String fingerprint;

    private Definition(Map<String, Object> raw, String version, List<String> states,
                       String initialState, Map<String, Object> initialData, long seed,
                       Map<String, Long> sources,
                       Map<String, List<Map<String, Object>>> transitions,
                       String fingerprint) {
        this.raw = raw;
        this.version = version;
        this.states = states;
        this.initialState = initialState;
        this.initialData = initialData;
        this.seed = seed;
        this.sources = sources;
        this.transitions = transitions;
        this.fingerprint = fingerprint;
    }

    public static Definition of(Map<String, Object> raw) {
        List<String> problems = new ArrayList<>();

        String version = str(raw.get("version"), "version");
        if (version == null || version.isBlank()) { version = "unnamed"; }

        List<String> states = new ArrayList<>();
        Object statesRaw = raw.get("states");
        if (!(statesRaw instanceof List<?> sl) || sl.isEmpty()) {
            problems.add("states must be a non-empty array");
        } else {
            Set<String> seen = new LinkedHashSet<>();
            for (Object o : sl) {
                if (!(o instanceof String s) || s.isBlank()) {
                    problems.add("state names must be non-empty strings");
                    break;
                }
                if (!seen.add(s)) problems.add("duplicate state: " + s);
                states.add(s);
            }
        }

        String initialState = null;
        Map<String, Object> initialData = new LinkedHashMap<>();
        Object init = raw.get("initial");
        if (init instanceof Map<?, ?> im) {
            Object is = im.get("state");
            if (is instanceof String s) initialState = s;
            Object id = im.get("data");
            if (id instanceof Map<?, ?> dm) {
                for (Map.Entry<?, ?> e : dm.entrySet()) {
                    initialData.put(String.valueOf(e.getKey()), e.getValue());
                }
            } else if (id != null) {
                problems.add("initial.data must be an object");
            }
        } else {
            problems.add("initial must be an object {state, data}");
        }
        if (initialState != null && !states.isEmpty() && !states.contains(initialState)) {
            problems.add("initial.state '" + initialState + "' is not in states");
        }

        long seed = 0L;
        Object seedRaw = raw.get("seed");
        if (seedRaw instanceof Number n) seed = n.longValue();
        else if (seedRaw != null) problems.add("seed must be an integer");

        Map<String, Long> sources = new LinkedHashMap<>();
        Object srcRaw = raw.get("sources");
        if (srcRaw instanceof Map<?, ?> sm) {
            if (sm.isEmpty()) problems.add("sources must be non-empty");
            Set<Long> prios = new LinkedHashSet<>();
            for (Map.Entry<?, ?> e : sm.entrySet()) {
                String name = String.valueOf(e.getKey());
                if (!(e.getValue() instanceof Number pn)) {
                    problems.add("source priority for '" + name + "' must be a number");
                    continue;
                }
                long prio = pn.longValue();
                if (!prios.add(prio)) problems.add("duplicate source priority: " + prio + " (" + name + ")");
                sources.put(name, prio);
            }
        } else {
            problems.add("sources must be an object mapping source name to priority");
        }

        Map<String, List<Map<String, Object>>> transitions = new LinkedHashMap<>();
        Object trRaw = raw.get("transitions");
        if (trRaw instanceof Map<?, ?> tm) {
            for (Map.Entry<?, ?> e : tm.entrySet()) {
                String stateName = String.valueOf(e.getKey());
                if (!states.isEmpty() && !states.contains(stateName)) {
                    problems.add("transitions reference unknown state: " + stateName);
                }
                List<Map<String, Object>> rules = new ArrayList<>();
                if (!(e.getValue() instanceof List<?> rl)) {
                    problems.add("transitions." + stateName + " must be an array");
                    continue;
                }
                for (Object ro : rl) {
                    if (!(ro instanceof Map<?, ?> rm)) {
                        problems.add("transition rule must be an object");
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rule = (Map<String, Object>) rm;
                    Object on = rule.get("on");
                    if (!(on instanceof String) || ((String) on).isBlank()) {
                        problems.add("transition rule needs a string 'on' (state " + stateName + ")");
                    }
                    Object cond = rule.get("if");
                    if (cond != null && !(cond instanceof String)) {
                        problems.add("transition 'if' must be a string (state " + stateName + ")");
                    }
                    Object acts = rule.get("actions");
                    if (!(acts instanceof List<?> al) || al.isEmpty()) {
                        problems.add("transition rule needs a non-empty 'actions' array (state " + stateName + ")");
                    } else {
                        for (Object ao : al) {
                            if (!(ao instanceof Map<?, ?>)) {
                                problems.add("each action must be an object (state " + stateName + ")");
                            }
                        }
                    }
                    rules.add(rule);
                }
                transitions.put(stateName, rules);
            }
        } else if (trRaw != null) {
            problems.add("transitions must be an object keyed by state");
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid definition:\n - " + String.join("\n - ", problems));
        }

        String fingerprint = sha256(Json.canonical(raw));
        return new Definition(raw, version, List.copyOf(states), initialState, initialData,
                seed, Map.copyOf(sources), Map.copyOf(transitions), fingerprint);
    }

    public static String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String str(Object v, String name) {
        return v instanceof String s ? s : null;
    }

    public Map<String, Object> raw() { return raw; }
    public String version() { return version; }
    public List<String> states() { return states; }
    public String initialState() { return initialState; }
    public Map<String, Object> initialData() { return initialData; }
    public long seed() { return seed; }
    public Map<String, Long> sources() { return sources; }
    public List<Map<String, Object>> rulesFor(String state) {
        return transitions.getOrDefault(state, List.of());
    }
    public String fingerprint() { return fingerprint; }
}
