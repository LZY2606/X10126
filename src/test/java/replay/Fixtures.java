package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import replay.engine.Definition;

/** Shared state-machine definitions used by tests. */
public final class Fixtures {

    private Fixtures() {
    }

    public static Map<String, Object> event(String id, String event, long time,
                                            int priority, long seq, Object payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("event", event);
        m.put("time", time);
        m.put("priority", priority);
        m.put("seq", seq);
        m.put("source", "test");
        if (payload != null) {
            m.put("payload", payload);
        }
        return m;
    }

    public static Map<String, Object> action(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        return m;
    }

    public static Map<String, Object> action(String type, String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put(key, value);
        return m;
    }

    public static Map<String, Object> transition(String from, String on, String cond,
                                                 String to, List<Object> actions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("on", on);
        if (cond != null) {
            m.put("condition", cond);
        }
        m.put("to", to);
        m.put("actions", actions == null ? new ArrayList<>() : actions);
        return m;
    }

    /** Turnstile with internal event chain and a failure path. */
    public static Definition turnstile() {
        List<Object> ts = new ArrayList<>();

        List<Object> coinActions = new ArrayList<>();
        Map<String, Object> led = action("emit", "channel", "led");
        led.put("payload", "green");
        coinActions.add(led);
        Map<String, Object> internal = action("emitInternal", "event", "auto-close");
        internal.put("payload", Map.of("after", 0));
        coinActions.add(internal);
        ts.add(transition("locked", "coin", null, "open", coinActions));

        List<Object> closeActions = new ArrayList<>();
        Map<String, Object> ledRed = action("emit", "channel", "led");
        ledRed.put("payload", "red");
        closeActions.add(ledRed);
        ts.add(transition("open", "auto-close", null, "locked", closeActions));

        List<Object> pushActions = new ArrayList<>();
        Map<String, Object> alarm = action("emit", "channel", "alarm");
        alarm.put("payload", Map.of("$expr", "state"));
        pushActions.add(alarm);
        ts.add(transition("locked", "push", null, "locked", pushActions));

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("version", 1);
        def.put("name", "turnstile");
        def.put("states", List.of("locked", "open"));
        def.put("initialState", "locked");
        def.put("initialVars", Map.of("coins", 0L));
        def.put("seed", 42L);
        def.put("transitions", ts);
        return Definition.fromMap(def);
    }

    /**
     * Ordering probe: records which source handled each same-time event into
     * a trajectory output.
     */
    public static Definition orderProbe() {
        List<Object> ts = new ArrayList<>();
        for (String from : List.of("s0")) {
            Map<String, Object> out = action("emit", "channel", "order");
            out.put("payload", Map.of("$expr", "source + ':' + seq"));
            ts.add(transition(from, "tick", null, "s0", List.of(out)));
        }
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("version", 1);
        def.put("name", "order-probe");
        def.put("states", List.of("s0"));
        def.put("initialState", "s0");
        def.put("initialVars", new LinkedHashMap<>());
        def.put("seed", 7L);
        def.put("transitions", ts);
        return Definition.fromMap(def);
    }
}
