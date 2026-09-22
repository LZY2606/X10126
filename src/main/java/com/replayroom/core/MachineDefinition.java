package com.replayroom.core;

import com.replayroom.json.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsed, validated state machine definition. Its fingerprint locks the definition version. */
public final class MachineDefinition {
    public final Map<String, Object> raw;
    public final String version;
    public final String initialState;
    public final List<String> states;
    public final Map<String, Object> initialVars;
    public final Map<String, Long> sourcePriorities;
    public final List<Rule> rules;
    public final String fingerprint;

    private MachineDefinition(Map<String, Object> raw, String version, String initialState,
                              List<String> states, Map<String, Object> initialVars,
                              Map<String, Long> sourcePriorities, List<Rule> rules) {
        this.raw = raw;
        this.version = version;
        this.initialState = initialState;
        this.states = states;
        this.initialVars = initialVars;
        this.sourcePriorities = sourcePriorities;
        this.rules = rules;
        this.fingerprint = sha256(Json.canonical(raw));
    }

    public long sourcePriority(String source) {
        Long p = sourcePriorities.get(source);
        return p != null ? p : Long.MAX_VALUE;
    }

    @SuppressWarnings("unchecked")
    public static MachineDefinition parse(Map<String, Object> raw) {
        String version = String.valueOf(raw.getOrDefault("version", "1"));
        Object statesObj = raw.get("states");
        if (!(statesObj instanceof List) || ((List<?>) statesObj).isEmpty()) {
            throw new IllegalArgumentException("definition must list at least one state");
        }
        List<String> states = new ArrayList<>();
        for (Object s : (List<Object>) statesObj) states.add(String.valueOf(s));

        String initialState = raw.get("initialState") != null ? String.valueOf(raw.get("initialState")) : null;
        if (initialState == null || !states.contains(initialState)) {
            throw new IllegalArgumentException("initialState must be one of states");
        }

        Map<String, Object> vars = new LinkedHashMap<>();
        Object varsObj = raw.get("variables");
        if (varsObj instanceof Map) vars.putAll((Map<String, Object>) varsObj);

        Map<String, Long> priorities = new LinkedHashMap<>();
        Object srcObj = raw.get("sources");
        if (srcObj instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) srcObj).entrySet()) {
                if (!(e.getValue() instanceof Number)) {
                    throw new IllegalArgumentException("source priority must be a number: " + e.getKey());
                }
                priorities.put(e.getKey(), ((Number) e.getValue()).longValue());
            }
        }

        List<Rule> rules = new ArrayList<>();
        Object rulesObj = raw.get("rules");
        if (rulesObj instanceof List) {
            for (Object r : (List<Object>) rulesObj) {
                if (!(r instanceof Map)) throw new IllegalArgumentException("rule must be an object");
                rules.add(Rule.parse((Map<String, Object>) r, states));
            }
        }
        return new MachineDefinition(new LinkedHashMap<>(raw), version, initialState, states,
                vars, priorities, rules);
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

    /** A transition rule: on event type, optionally from a state, guarded by a condition. */
    public static final class Rule {
        public final String on;
        public final String from;
        public final String condition;
        public final List<Map<String, Object>> actions;

        Rule(String on, String from, String condition, List<Map<String, Object>> actions) {
            this.on = on;
            this.from = from;
            this.condition = condition;
            this.actions = actions;
        }

        @SuppressWarnings("unchecked")
        static Rule parse(Map<String, Object> m, List<String> states) {
            Object on = m.get("on");
            if (on == null) throw new IllegalArgumentException("rule missing 'on'");
            String from = m.get("from") != null ? String.valueOf(m.get("from")) : null;
            if (from != null && !states.contains(from)) {
                throw new IllegalArgumentException("rule 'from' is not a known state: " + from);
            }
            String cond = m.get("if") != null ? String.valueOf(m.get("if")) : null;
            if (cond != null) Expr.parse(cond);
            List<Map<String, Object>> actions = new ArrayList<>();
            Object acts = m.get("actions");
            if (acts instanceof List) {
                for (Object a : (List<Object>) acts) {
                    if (!(a instanceof Map)) throw new IllegalArgumentException("action must be an object");
                    Map<String, Object> action = (Map<String, Object>) a;
                    validateAction(action, states);
                    actions.add(action);
                }
            }
            return new Rule(String.valueOf(on), from, cond, actions);
        }

        private static void validateAction(Map<String, Object> action, List<String> states) {
            if (action.containsKey("set") && !action.containsKey("to")) {
                throw new IllegalArgumentException("set action requires 'to'");
            }
            Object go = action.get("goto");
            if (go != null && !states.contains(String.valueOf(go))) {
                throw new IllegalArgumentException("goto target is not a known state: " + go);
            }
            if (!action.containsKey("set") && go == null && !action.containsKey("emit")
                    && !action.containsKey("out") && !action.containsKey("fail")) {
                throw new IllegalArgumentException("unknown action: " + Json.canonical(action));
            }
        }
    }
}
