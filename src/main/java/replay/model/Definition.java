package replay.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable-ish state machine definition:
 * {
 *   "name": "...",
 *   "version": 3,
 *   "initial": {"state": "S0", "data": {...}},
 *   "transitions": [
 *     {"from": "S0"|"*", "event": "evt"|"*", "condition": "expr", "to": "S1",
 *      "actions": [{"type":"set","key":"...","value":"expr"},
 *                  {"type":"emit","event":"...","payload":"expr"},
 *                  {"type":"check","condition":"expr"}]}
 *   ]
 * }
 * Transitions are evaluated in order; the first matching one (event type and
 * condition evaluated against the pre-event snapshot) fires.
 */
public final class Definition {

    public String name = "machine";
    public int version = 1;
    public String initialState;
    public Map<String, Object> initialData = new LinkedHashMap<>();
    public List<Map<String, Object>> transitions = new ArrayList<>();

    public Definition() {}

    public static Definition fromMap(Map<String, Object> map) {
        Definition d = new Definition();
        d.name = str(map.get("name"), "machine");
        d.version = (int) asLong(map.get("version"), 1L);
        Object initial = map.get("initial");
        if (initial instanceof Map) {
            Map<?, ?> im = (Map<?, ?>) initial;
            d.initialState = im.get("state") == null ? null : String.valueOf(im.get("state"));
            Object data = im.get("data");
            d.initialData = data instanceof Map ? copyStringKeyed((Map<?, ?>) data) : new LinkedHashMap<>();
        }
        Object trans = map.get("transitions");
        if (trans instanceof List) {
            for (Object item : (List<?>) trans) {
                if (!(item instanceof Map)) {
                    throw new Json.JsonException("Each transition must be an object");
                }
                d.transitions.add(copyStringKeyed((Map<?, ?>) item));
            }
        }
        d.validate();
        return d;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("version", version);
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("state", initialState);
        initial.put("data", initialData);
        map.put("initial", initial);
        map.put("transitions", transitions);
        return map;
    }

    /**
     * Fingerprint covers the complete definition, including the declared
     * version number, so any edit changes the hash.
     */
    public String fingerprint() {
        return Hashes.fingerprint(toMap());
    }

    public void validate() {
        if (initialState == null || initialState.isEmpty()) {
            throw new IllegalArgumentException("definition.initial.state is required");
        }
        if (transitions.isEmpty()) {
            throw new IllegalArgumentException("at least one transition is required");
        }
        for (int i = 0; i < transitions.size(); i++) {
            Map<String, Object> t = transitions.get(i);
            String from = String.valueOf(t.getOrDefault("from", "*"));
            String event = String.valueOf(t.getOrDefault("event", "*"));
            Object to = t.get("to");
            if (to == null || String.valueOf(to).isEmpty()) {
                throw new IllegalArgumentException("transitions[" + i + "].to is required");
            }
            Object actions = t.get("actions");
            if (actions != null && !(actions instanceof List)) {
                throw new IllegalArgumentException("transitions[" + i + "].actions must be a list");
            }
            if (actions instanceof List) {
                for (Object raw : (List<?>) actions) {
                    if (!(raw instanceof Map)) {
                        throw new IllegalArgumentException("transitions[" + i + "] action must be an object");
                    }
                    Map<?, ?> action = (Map<?, ?>) raw;
                    String type = String.valueOf(action.get("type"));
                    switch (type) {
                        case "set":
                            requireField(action, "key", "set", i);
                            requireField(action, "value", "set", i);
                            break;
                        case "emit":
                            requireField(action, "event", "emit", i);
                            break;
                        case "check":
                            requireField(action, "condition", "check", i);
                            break;
                        default:
                            throw new IllegalArgumentException(
                                    "transitions[" + i + "] unknown action type: " + type);
                    }
                }
            }
            if (from.isEmpty() || event.isEmpty()) {
                throw new IllegalArgumentException("transitions[" + i + "] from/event must not be empty");
            }
        }
    }

    private static void requireField(Map<?, ?> action, String field, String type, int index) {
        Object value = action.get(field);
        if (value == null || (value instanceof String && ((String) value).isEmpty())) {
            throw new IllegalArgumentException(
                    "transitions[" + index + "] " + type + " action requires '" + field + "'");
        }
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    static long asLong(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return fallback;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException e) { return fallback; }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> copyStringKeyed(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, Object>) value).entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) copy.add(deepCopy(item));
            return copy;
        }
        return value;
    }
}
