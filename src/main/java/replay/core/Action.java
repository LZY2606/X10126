package replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replay.json.Json;

/**
 * Actions mutate the working context of one step. If any action throws
 * ActionFailure, the engine rolls the whole step back.
 *
 * Supported forms:
 *   {"type":"set","var":"x","value":...}            value may contain {"ref":"var.y"|"event.z"}
 *   {"type":"emit","output":...}
 *   {"type":"spawn","event":"TYPE","payload":{...}} queued after the current event
 *   {"type":"randomInt","var":"x","min":0,"max":9}  deterministic, seeded per session
 *   {"type":"fail","message":"..."}                 always fails (for chaos drills)
 */
public final class Action {
    private Action() {
    }

    public static final class Context {
        public final Map<String, Object> vars;
        public final Map<String, Object> payload;
        public final List<Object> emitted = new ArrayList<>();
        public final List<Event> spawned = new ArrayList<>();
        public long rngState;

        public Context(Map<String, Object> vars, Map<String, Object> payload, long rngState) {
            this.vars = vars;
            this.payload = payload;
            this.rngState = rngState;
        }
    }

    public static void execute(Object actionJson, Context ctx) {
        Map<String, Object> action = Json.obj(actionJson, "action");
        String type = Json.str(action, "type");
        switch (type) {
            case "set" -> {
                String var = Json.str(action, "var");
                ctx.vars.put(var, resolve(action.get("value"), ctx));
            }
            case "emit" -> ctx.emitted.add(resolve(action.get("output"), ctx));
            case "spawn" -> {
                String eventType = Json.str(action, "event");
                Map<String, Object> payload = new LinkedHashMap<>();
                if (action.get("payload") instanceof Map<?, ?> p) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> raw = (Map<String, Object>) p;
                    for (Map.Entry<String, Object> e : raw.entrySet()) {
                        payload.put(e.getKey(), resolve(e.getValue(), ctx));
                    }
                }
                ctx.spawned.add(new Event(-1, Event.INTERNAL, 0, "<internal>", -1, eventType, payload));
            }
            case "randomInt" -> {
                String var = Json.str(action, "var");
                long min = Json.lng(action, "min", 0);
                long max = Json.lng(action, "max", 100);
                if (max < min) {
                    throw new ActionFailure("randomInt range inverted: " + min + ".." + max);
                }
                ctx.rngState = nextRandom(ctx.rngState);
                long value = min + Math.floorMod(ctx.rngState, max - min + 1);
                ctx.vars.put(var, value);
            }
            case "fail" -> throw new ActionFailure(
                    action.get("message") instanceof String m ? m : "action failed");
            default -> throw new ActionFailure("unknown action type: " + type);
        }
    }

    /** SplitMix64 step: deterministic and trivially serializable. */
    private static long nextRandom(long state) {
        long z = state + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @SuppressWarnings("unchecked")
    private static Object resolve(Object value, Context ctx) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            if (m.size() == 1 && m.containsKey("ref")) {
                return resolveRef(String.valueOf(m.get("ref")), ctx);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                out.put(e.getKey(), resolve(e.getValue(), ctx));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(resolve(item, ctx));
            }
            return out;
        }
        return value;
    }

    private static Object resolveRef(String path, Context ctx) {
        String[] parts = path.split("\\.", 2);
        Map<String, Object> scope;
        if ("var".equals(parts[0])) {
            scope = ctx.vars;
        } else if ("event".equals(parts[0])) {
            scope = ctx.payload;
        } else {
            throw new ActionFailure("unknown ref scope: " + path);
        }
        if (parts.length == 1) {
            throw new ActionFailure("ref needs a field: " + path);
        }
        if (!scope.containsKey(parts[1])) {
            throw new ActionFailure("ref not found: " + path);
        }
        return scope.get(parts[1]);
    }
}
