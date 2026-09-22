package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Immutable state machine definition. Any change produces a new version and a
 * new fingerprint; checkpoints pin the fingerprint they were created under.
 */
public final class MachineDefinition {
    public final String id;
    public final long version;
    public final String initialState;
    public final Map<String, Object> initialVariables;
    public final Map<String, Integer> sourcePriorities;
    public final List<Map<String, Object>> transitions;
    public final Map<String, Object> raw;
    public final String fingerprint;

    private MachineDefinition(String id, long version, String initialState,
                              Map<String, Object> initialVariables,
                              Map<String, Integer> sourcePriorities,
                              List<Map<String, Object>> transitions,
                              Map<String, Object> raw) {
        this.id = id;
        this.version = version;
        this.initialState = initialState;
        this.initialVariables = initialVariables;
        this.sourcePriorities = sourcePriorities;
        this.transitions = transitions;
        this.raw = raw;
        this.fingerprint = Hashing.fingerprint(raw);
    }

    @SuppressWarnings("unchecked")
    public static MachineDefinition parse(Map<String, Object> raw) {
        String id = Json.str(raw.getOrDefault("id", "machine"));
        long version = Json.num(raw.getOrDefault("version", 1L));
        String initialState = Json.str(raw.getOrDefault("initialState", "INIT"));
        Map<String, Object> vars = raw.get("initialVariables") instanceof Map
                ? (Map<String, Object>) Json.deepCopy(raw.get("initialVariables"))
                : Json.newObj();
        Map<String, Integer> priorities = new LinkedHashMap<>();
        if (raw.get("sources") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) raw.get("sources")).entrySet()) {
                priorities.put(e.getKey(), (int) Json.num(e.getValue()));
            }
        }
        priorities.putIfAbsent("internal", 1_000_000);
        List<Map<String, Object>> transitions = new ArrayList<>();
        if (raw.get("transitions") instanceof List) {
            for (Object t : Json.arr(raw.get("transitions"))) {
                transitions.add((Map<String, Object>) Json.deepCopy(t));
            }
        }
        return new MachineDefinition(id, version, initialState, vars, priorities, transitions,
                (Map<String, Object>) Json.deepCopy(raw));
    }

    public int priorityOf(String source) {
        return sourcePriorities.getOrDefault(source, 500_000);
    }

    public ToIntFunction<String> priorityFn() {
        return this::priorityOf;
    }

    /** First transition matching the event name / from-state / condition, in declaration order. */
    public Map<String, Object> matchTransition(Event event, String state,
                                               Map<String, Object> vars) {
        Condition.SnapshotView view = Condition.view(state, vars, event.payload);
        for (Map<String, Object> t : transitions) {
            if (!Json.str(t.getOrDefault("event", "")).equals(event.name)) continue;
            Object from = t.get("from");
            if (from instanceof List) {
                boolean ok = false;
                for (Object f : Json.arr(from)) if (Json.str(f).equals(state)) { ok = true; break; }
                if (!ok) continue;
            } else if (from instanceof String && !from.equals(state)) {
                continue;
            }
            if (!Condition.eval(t.get("condition"), view)) continue;
            return t;
        }
        return null;
    }
}
