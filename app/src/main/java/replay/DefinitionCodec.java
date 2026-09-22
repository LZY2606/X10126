package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DefinitionCodec {
    private DefinitionCodec() {}

    static Models.Definition parse(Map<String, Object> json) {
        Models.Definition definition = new Models.Definition();
        definition.version = Json.optionalString(json, "version", "1");
        definition.initialState = Json.optionalString(json, "initialState", "idle");
        definition.seed = Json.optionalLong(json, "seed", 1L);
        Object initialVariables = json.getOrDefault("initialVariables", new LinkedHashMap<String, Object>());
        if (!(initialVariables instanceof Map<?, ?>)) throw new IllegalArgumentException("initialVariables must be an object");
        definition.initialVariables = Json.object(initialVariables);
        Object sourcePriority = json.getOrDefault("sourcePriority", new LinkedHashMap<String, Object>());
        if (!(sourcePriority instanceof Map<?, ?> map)) throw new IllegalArgumentException("sourcePriority must be an object");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof Number number)) throw new IllegalArgumentException("sourcePriority values must be numbers");
            definition.sourcePriority.put(String.valueOf(entry.getKey()), number.intValue());
        }
        definition.transitions = parseTransitions(Json.list(Json.required(json, "transitions")));
        validate(definition);
        return definition;
    }

    static Map<String, Object> write(Models.Definition definition) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", definition.version);
        json.put("initialState", definition.initialState);
        json.put("seed", definition.seed);
        json.put("initialVariables", definition.initialVariables);
        json.put("sourcePriority", definition.sourcePriority);
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (Models.Transition transition : definition.transitions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", transition.name);
            item.put("from", transition.from);
            item.put("event", transition.event);
            item.put("condition", transition.condition);
            item.put("to", transition.to);
            List<Map<String, Object>> actions = new ArrayList<>();
            for (Models.Action action : transition.actions) actions.add(action.toJson());
            item.put("actions", actions);
            transitions.add(item);
        }
        json.put("transitions", transitions);
        return json;
    }

    static String fingerprint(Models.Definition definition) {
        return Models.sha256(Json.writeCanonical(write(definition)));
    }

    private static List<Models.Transition> parseTransitions(List<Object> values) {
        List<Models.Transition> transitions = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> item = Json.object(value);
            Models.Transition transition = new Models.Transition();
            transition.name = Json.optionalString(item, "name", "transition-" + (transitions.size() + 1));
            transition.from = Json.optionalString(item, "from", "*");
            transition.event = Json.optionalString(item, "event", "*");
            transition.condition = Json.optionalString(item, "condition", "");
            transition.to = Json.optionalString(item, "to", "");
            transition.actions = parseActions(Json.list(Json.required(item, "actions")));
            transitions.add(transition);
        }
        return transitions;
    }

    private static List<Models.Action> parseActions(List<Object> values) {
        List<Models.Action> actions = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> item = Json.object(value);
            String type = Json.string(item, "type");
            actions.add(switch (type) {
                case "set" -> new Models.SetAction(Json.string(item, "target"), Json.optionalString(item, "value", ""));
                case "output" -> new Models.OutputAction(Json.string(item, "name"), item.containsKey("payload") ? String.valueOf(item.get("payload")) : null);
                case "emit" -> new Models.EmitAction(
                        Json.string(item, "event"),
                        Json.optionalLong(item, "timeDelta", 0L),
                        Json.optionalString(item, "source", "internal"),
                        Json.optionalLong(item, "sequence", 0L),
                        item.containsKey("payload") ? String.valueOf(item.get("payload")) : null);
                case "randomInt" -> new Models.RandomIntAction(
                        Json.string(item, "target"),
                        Json.optionalString(item, "min", "0"),
                        Json.optionalString(item, "max", "1"));
                case "fail" -> new Models.FailAction(
                        Json.string(item, "error"),
                        item.containsKey("payload") ? String.valueOf(item.get("payload")) : null);
                default -> throw new IllegalArgumentException("Unknown action type: " + type);
            });
        }
        return actions;
    }

    private static void validate(Models.Definition definition) {
        if (definition.version.isBlank()) throw new IllegalArgumentException("version must not be blank");
        if (definition.initialState.isBlank()) throw new IllegalArgumentException("initialState must not be blank");
        if (definition.transitions.isEmpty()) return;
        for (Models.Transition transition : definition.transitions) {
            if (transition.from.isBlank() && !"*".equals(transition.from)) throw new IllegalArgumentException("transition from must not be blank");
            if (transition.event.isBlank() && !"*".equals(transition.event)) throw new IllegalArgumentException("transition event must not be blank");
            if (transition.to == null || transition.to.isBlank()) transition.to = transition.from;
            for (Models.Action action : transition.actions) {
                if (action instanceof Models.SetAction set && set.target().isBlank()) throw new IllegalArgumentException("set target must not be blank");
                if (action instanceof Models.OutputAction output && output.name().isBlank()) throw new IllegalArgumentException("output name must not be blank");
                if (action instanceof Models.EmitAction emit && emit.event().isBlank()) throw new IllegalArgumentException("emit event must not be blank");
            }
        }
    }
}
