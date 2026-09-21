package replayroom.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replayroom.model.Action;
import replayroom.model.Definition;
import replayroom.model.Envelope;
import replayroom.model.Transition;

final class TestFixtures {

    private TestFixtures() {}

    static Map<String, Object> action(String type, Map<String, Object> fields) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.putAll(fields);
        return map;
    }

    static Map<String, Object> transition(String event, String from, String to, String condition,
                                          List<Map<String, Object>> actions) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("event", event);
        if (from != null) map.put("from", from);
        if (to != null) map.put("to", to);
        if (condition != null) map.put("condition", condition);
        map.put("actions", actions);
        return map;
    }

    static Definition definition(String name, String initialState, Map<String, Object> initialVars,
                                 List<Map<String, Object>> transitions) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("initialState", initialState);
        map.put("initialVars", initialVars);
        map.put("transitions", transitions);
        return Definition.fromMap(map);
    }

    static Envelope ext(String id, String name, String source, long time, long seq, int priority,
                        Map<String, Object> data) {
        return Envelope.external(id, name, source, time, seq, priority, data);
    }

    static Envelope ext(String id, String name, String source, long time, long seq, int priority) {
        return Envelope.external(id, name, source, time, seq, priority, new LinkedHashMap<>());
    }

    static Session freshSession(Definition definition, long seed, Envelope... events) {
        return Session.create("sess-test", "test", CompiledDefinition.compile(definition),
                seed, new ArrayList<>(List.of(events)));
    }
}
