package replayroom;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MachineValidator {
    private MachineValidator() {}

    public static void validate(Map<String, Object> machine, String initialState) {
        List<Object> states = Json.list(machine.get("states"), "machine.states");
        if (states.isEmpty()) throw new IllegalArgumentException("machine.states must not be empty");
        Set<String> stateNames = new HashSet<>();
        for (Object value : states) {
            String state = Json.string(value, "machine.states[]");
            if (!stateNames.add(state)) throw new IllegalArgumentException("Duplicate state: " + state);
        }
        if (!stateNames.contains(initialState)) {
            throw new IllegalArgumentException("Initial state is not declared: " + initialState);
        }
        Object sources = machine.get("sources");
        if (sources != null) {
            Json.object(sources, "machine.sources");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) sources).entrySet()) {
                Json.integer(entry.getValue(), "machine.sources." + entry.getKey());
            }
        }
        List<Object> transitions = Json.list(machine.getOrDefault("transitions", List.of()), "machine.transitions");
        Set<String> transitionIds = new HashSet<>();
        for (int i = 0; i < transitions.size(); i++) {
            Map<String, Object> transition = Json.object(transitions.get(i), "machine.transitions[" + i + "]");
            String id = Json.optionalString(transition, "id", "transition-" + (i + 1));
            if (!transitionIds.add(id)) throw new IllegalArgumentException("Duplicate transition id: " + id);
            validateFrom(transition.get("from"), stateNames);
            Json.optionalString(transition, "event", "");
            Object condition = transition.get("condition");
            if (condition != null) Json.string(condition, "transition.condition");
            List<Object> actions = Json.list(transition.getOrDefault("actions", List.of()), "transition.actions");
            for (int j = 0; j < actions.size(); j++) {
                Json.object(actions.get(j), "transition.actions[" + j + "]");
            }
        }
    }

    private static void validateFrom(Object from, Set<String> states) {
        if (from instanceof List<?> list) {
            if (list.isEmpty()) throw new IllegalArgumentException("transition.from must not be empty");
            for (Object value : list) {
                String state = Json.string(value, "transition.from[]");
                if (!state.equals("*") && !states.contains(state)) {
                    throw new IllegalArgumentException("Transition references unknown state: " + state);
                }
            }
            return;
        }
        String state = from == null ? "*" : Json.string(from, "transition.from");
        if (!state.equals("*") && !states.contains(state)) {
            throw new IllegalArgumentException("Transition references unknown state: " + state);
        }
    }
}
