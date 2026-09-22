package replay;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class AbstractStoreTest {

    @TempDir
    Path tempDir;

    ProjectStore newStore() {
        return new ProjectStore(tempDir.resolve("session.json"));
    }

    static Map<String, Object> def(String seed, String initial, List<Object> transitions) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", "test-machine");
        definition.put("seed", Long.parseLong(seed));
        definition.put("initial", initial);
        definition.put("states", List.of("s0", "s1", "s2", "dead"));
        definition.put("vars", new LinkedHashMap<>(Map.of("counter", 0L)));
        definition.put("transitions", transitions);
        return definition;
    }

    static Map<String, Object> transition(String name, String from, String on, String to,
                                          String when, List<Object> actions) {
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("name", name);
        transition.put("from", from);
        transition.put("on", on);
        transition.put("to", to);
        if (when != null) {
            transition.put("when", when);
        }
        transition.put("actions", actions);
        return transition;
    }

    static Map<String, Object> set(String name, String value) {
        return new LinkedHashMap<>(Map.of("type", "set", "name", name, "value", value));
    }

    static Map<String, Object> inc(String name) {
        return new LinkedHashMap<>(Map.of("type", "inc", "name", name));
    }

    static Map<String, Object> log(String message) {
        return new LinkedHashMap<>(Map.of("type", "log", "message", message));
    }

    static Map<String, Object> emit(String type, Map<String, Object> payload) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "emit");
        action.put("event", type);
        action.put("payload", payload);
        return action;
    }

    static Map<String, Object> fail(String message) {
        return new LinkedHashMap<>(Map.of("type", "fail", "message", message));
    }

    static Map<String, Object> event(String id, String type, long time,
                                     long priority, long seq) {
        return event(id, type, time, priority, seq, Map.of());
    }

    static Map<String, Object> event(String id, String type, long time,
                                     long priority, long seq, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("type", type);
        event.put("time", time);
        event.put("priority", priority);
        event.put("seq", seq);
        event.put("payload", payload);
        return event;
    }

    static List<Object> events(Map<String, Object>... events) {
        return new ArrayList<>(List.of(events));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> branch(ProjectStore store, String name) {
        for (Object item : (List<Object>) store.overview().get("branches")) {
            Map<String, Object> branch = (Map<String, Object>) item;
            if (name.equals(branch.get("name"))) {
                return branch;
            }
        }
        throw new IllegalStateException("branch not found: " + name);
    }
}
