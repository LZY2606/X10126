package com.replayroom.engine;

import com.replayroom.json.Json;
import com.replayroom.model.ModelAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validated machine definition plus its content fingerprint. The fingerprint
 * covers the exact canonical definition document, so any edit (states,
 * events, conditions, actions or seed) invalidates checkpoints created
 * under the previous version.
 */
public final class Definition {

    private final Map<String, Object> raw;
    private final String fingerprint;
    private final String name;
    private final long version;
    private final long seed;
    private final String initialState;
    private final Map<String, Object> initialData;
    private final Map<String, Object> sources;
    private final List<Map<String, Object>> transitions;

    private Definition(Map<String, Object> raw,
                       String fingerprint,
                       String name,
                       long version,
                       long seed,
                       String initialState,
                       Map<String, Object> initialData,
                       Map<String, Object> sources,
                       List<Map<String, Object>> transitions) {
        this.raw = raw;
        this.fingerprint = fingerprint;
        this.name = name;
        this.version = version;
        this.seed = seed;
        this.initialState = initialState;
        this.initialData = initialData;
        this.sources = sources;
        this.transitions = transitions;
    }

    public static Definition parse(Map<String, Object> document) {
        List<String> errors = new ArrayList<>();

        String name = document.get("name") instanceof String text ? text : "machine";
        long version = document.get("version") instanceof Number number ? number.longValue() : 1L;
        long seed = document.get("seed") instanceof Number number ? number.longValue() : 0L;

        Object statesObject = document.get("states");
        if (!(statesObject instanceof List<?> stateList) || stateList.isEmpty()) {
            errors.add("states must be a non-empty array");
        }
        Set<String> states = new LinkedHashSet<>();
        if (statesObject instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof String state) || state.isBlank()) {
                    errors.add("every state must be a non-empty string");
                    continue;
                }
                if (!states.add(state)) {
                    errors.add("duplicate state '" + state + "'");
                }
            }
        }

        Object initialObject = document.get("initial");
        String initialState = null;
        Map<String, Object> initialData = new LinkedHashMap<>();
        if (initialObject instanceof Map<?, ?> initialMap) {
            Object stateValue = initialMap.get("state");
            initialState = stateValue == null ? null : String.valueOf(stateValue);
            initialData = ModelAccess.objectField(ModelAccess.asStringObjectMap(initialMap), "data");
        } else if (initialObject instanceof String initialText) {
            initialState = initialText;
        } else {
            errors.add("initial must be a string or an object with state/data");
        }
        if (initialState != null && !states.contains(initialState)) {
            errors.add("initial state '" + initialState + "' is not declared in states");
        }

        Map<String, Object> sources = new LinkedHashMap<>();
        if (document.get("sources") instanceof Map<?, ?> sourceMap) {
            sources = ModelAccess.asStringObjectMap(sourceMap);
            for (Map.Entry<String, Object> entry : sources.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?>)) {
                    errors.add("source '" + entry.getKey() + "' must be an object");
                }
            }
        }

        List<Map<String, Object>> transitions = new ArrayList<>();
        Object transitionObject = document.get("transitions");
        if (!(transitionObject instanceof List<?>)) {
            errors.add("transitions must be an array");
        } else {
            int index = 0;
            for (Object item : (List<?>) transitionObject) {
                Map<String, Object> transition = ModelAccess.asObject(item);
                String from = stringOrNull(transition.get("from"));
                String event = stringOrNull(transition.get("event"));
                if (from == null) {
                    errors.add("transitions[" + index + "].from is required");
                } else if (!"*".equals(from) && !states.contains(from)) {
                    errors.add("transitions[" + index + "].from references unknown state '" + from + "'");
                }
                if (event == null || event.isBlank()) {
                    errors.add("transitions[" + index + "].event is required");
                }
                if (!transition.containsKey("to") || !(transition.get("to") instanceof String to)
                        || (!"*".equals(to) && !states.contains(to))) {
                    errors.add("transitions[" + index + "].to must be a declared state");
                }
                Object when = transition.get("when");
                if (when != null && !(when instanceof String)) {
                    errors.add("transitions[" + index + "].when must be an expression string");
                }
                Object actions = transition.get("actions");
                if (actions != null) {
                    if (!(actions instanceof List<?> actionList)) {
                        errors.add("transitions[" + index + "].actions must be an array");
                    } else {
                        int actionIndex = 0;
                        for (Object actionItem : actionList) {
                            validateAction(ModelAccess.asObject(actionItem), index, actionIndex, errors);
                            actionIndex++;
                        }
                    }
                }
                transitions.add(transition);
                index++;
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }

        String fingerprint = Json.fingerprint(document);
        return new Definition(
                document,
                fingerprint,
                name,
                version,
                seed,
                initialState,
                initialData,
                sources,
                List.copyOf(transitions)
        );
    }

    private static void validateAction(Map<String, Object> action, int transitionIndex,
                                       int actionIndex, List<String> errors) {
        String prefix = "transitions[" + transitionIndex + "].actions[" + actionIndex + "]";
        Object typeObject = action.get("type");
        if (!(typeObject instanceof String type)) {
            errors.add(prefix + ".type is required");
            return;
        }
        switch (type) {
            case "set" -> {
                if (!(action.get("path") instanceof String path) || path.isBlank()) {
                    errors.add(prefix + ".path is required for set actions");
                }
                if (!action.containsKey("value")) {
                    errors.add(prefix + ".value is required for set actions");
                }
            }
            case "output" -> {
                if (!(action.get("name") instanceof String name) || name.isBlank()) {
                    errors.add(prefix + ".name is required for output actions");
                }
            }
            case "emit" -> {
                if (!(action.get("event") instanceof String event) || event.isBlank()) {
                    errors.add(prefix + ".event is required for emit actions");
                }
            }
            case "fail" -> {
                if (action.containsKey("message") && !(action.get("message") instanceof String)) {
                    errors.add(prefix + ".message must be a string");
                }
            }
            default -> errors.add(prefix + ".type '" + type + "' is unsupported");
        }
    }

    private static String stringOrNull(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    public Map<String, Object> raw() {
        return raw;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public String name() {
        return name;
    }

    public long version() {
        return version;
    }

    public long seed() {
        return seed;
    }

    public String initialState() {
        return initialState;
    }

    public Map<String, Object> initialData() {
        return initialData;
    }

    public Map<String, Object> sources() {
        return sources;
    }

    public List<Map<String, Object>> transitions() {
        return transitions;
    }
}
