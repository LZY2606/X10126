package io.example.replay;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record MachineDefinition(String name, String version, String fingerprint, Object raw) {
    public static MachineDefinition parse(Object json) {
        Map<String, Object> object = Json.object(Json.deepCopy(json));
        object.remove("fingerprint");
        String name = Json.string(object, "name");
        if (name == null || name.isBlank()) {
            name = "machine";
            object.put("name", name);
        }
        String version = Json.string(object, "version");
        if (version == null || version.isBlank()) {
            version = "unversioned";
            object.put("version", version);
        }
        if (object.get("initialState") == null) {
            throw new IllegalArgumentException("Definition requires initialState");
        }
        String initialState = String.valueOf(object.get("initialState"));
        Set<String> states = new HashSet<>();
        for (Object state : Json.list(object.getOrDefault("states", List.of()))) {
            states.add(String.valueOf(state));
        }
        if (!states.contains(initialState)) {
            throw new IllegalArgumentException("initialState must be declared in states");
        }
        List<Object> transitions = Json.list(object.getOrDefault("transitions", List.of()));
        for (int i = 0; i < transitions.size(); i++) {
            Map<String, Object> transition = Json.object(transitions.get(i));
            String id = Json.string(transition, "id");
            if (id == null || id.isBlank()) {
                transition.put("id", "t" + i);
            }
            String from = Json.string(transition, "from");
            String to = Json.string(transition, "to");
            if (from == null || to == null || Json.string(transition, "event") == null) {
                throw new IllegalArgumentException("Transition requires from, to and event");
            }
            if (!"*".equals(from) && !states.contains(from)) {
                throw new IllegalArgumentException("Unknown transition from state: " + from);
            }
            if (!states.contains(to)) {
                throw new IllegalArgumentException("Unknown transition to state: " + to);
            }
            Object actions = transition.getOrDefault("actions", List.of());
            if (!(actions instanceof List<?>)) {
                throw new IllegalArgumentException("Transition actions must be an array");
            }
        }
        String fingerprint = Hashing.sha256(Json.canonical(object));
        return new MachineDefinition(name, version, fingerprint, object);
    }

    public String initialState() {
        return String.valueOf(Json.object(raw).get("initialState"));
    }

    public Map<String, Object> initialData() {
        Object data = Json.object(raw).get("initialData");
        return data == null ? new LinkedHashMap<>() : Json.object(Json.deepCopy(data));
    }

    public List<Map<String, Object>> transitions() {
        return Json.list(Json.object(raw).get("transitions")).stream()
                .map(Json::object)
                .toList();
    }

    public Map<String, Object> toJson() {
        Map<String, Object> result = Json.object(Json.deepCopy(raw));
        result.put("fingerprint", fingerprint);
        return result;
    }
}
