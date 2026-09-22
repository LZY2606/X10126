package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable(ish) domain objects built from the stored JSON representation.
 * The canonical JSON representation is the single source of truth for fingerprints.
 */
public final class Models {

    private Models() {
    }

    /** A state machine definition: states, transitions, initial state and a random seed. */
    public static final class Definition {
        public final String fingerprint;
        public final long seed;
        public final Map<String, Object> raw;

        public Definition(Map<String, Object> raw) {
            this.raw = Json.copyObj(raw);
            this.seed = Json.lng(this.raw, "seed", 0L);
            this.fingerprint = Hashes.sha256(Json.writeCanonical(this.raw));
        }

        public String initialState() {
            return Json.requireStr(raw, "initial");
        }

        public List<Map<String, Object>> transitions() {
            List<Object> list = Json.arr(raw, "transitions");
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                result.add(Json.asObj(item, "transition"));
            }
            return result;
        }

        public Map<String, Object> initialVars() {
            Object vars = raw.get("vars");
            if (vars == null) {
                return new LinkedHashMap<>();
            }
            return Json.copyObj(Json.asObj(vars, "vars"));
        }

        public List<String> stateNames() {
            List<String> names = new ArrayList<>();
            for (Object item : Json.arr(raw, "states")) {
                names.add(String.valueOf(item));
            }
            return names;
        }
    }

    /**
     * One event on the log. External events carry a logical time, source priority
     * and original sequence; internal events are produced by actions during replay
     * and keep the logical time of the event that spawned them.
     */
    public static final class Event {
        public final String id;
        public final String type;
        public final long time;
        public final long priority;
        public final long seq;
        public final Map<String, Object> payload;
        public final boolean internal;
        public final String parentId;

        public Event(String id, String type, long time, long priority, long seq,
                     Map<String, Object> payload, boolean internal, String parentId) {
            this.id = id;
            this.type = type;
            this.time = time;
            this.priority = priority;
            this.seq = seq;
            this.payload = payload;
            this.internal = internal;
            this.parentId = parentId;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("type", type);
            map.put("time", time);
            map.put("priority", priority);
            map.put("seq", seq);
            map.put("payload", payload == null ? new LinkedHashMap<>() : payload);
            map.put("internal", internal);
            if (parentId != null) {
                map.put("parentId", parentId);
            }
            return map;
        }

        public static Event fromMap(Map<String, Object> map) {
            return new Event(
                    Json.requireStr(map, "id"),
                    Json.requireStr(map, "type"),
                    Json.lng(map, "time", 0L),
                    Json.lng(map, "priority", 0L),
                    Json.lng(map, "seq", 0L),
                    map.get("payload") == null ? new LinkedHashMap<>() : Json.obj(map, "payload"),
                    Json.bool(map, "internal", false),
                    Json.str(map, "parentId"));
        }
    }
}
