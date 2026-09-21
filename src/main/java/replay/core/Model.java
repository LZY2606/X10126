package replay.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Definition, event records and ordering rules for the replay engine. */
public final class Model {
    private Model() {}

    /** A state-machine definition. Everything is derived from plain JSON-compatible maps. */
    public static final class Definition {
        public final List<String> states;
        public final String initialState;
        public final Map<String, Object> variables;
        public final Map<String, Integer> sourcePriority;
        public final Map<String, EventDef> events;
        public final Map<String, Object> raw;

        @SuppressWarnings("unchecked")
        public Definition(Map<String, Object> json) {
            this.raw = json;
            this.states = strList(json.get("states"));
            if (states.isEmpty()) throw new IllegalArgumentException("definition.states must not be empty");
            this.initialState = str(json.get("initialState"), states.get(0));
            if (!states.contains(initialState))
                throw new IllegalArgumentException("initialState '" + initialState + "' is not in states");
            Object vars = json.get("variables");
            this.variables = vars instanceof Map ? deepCopyMap((Map<String, Object>) vars) : new LinkedHashMap<>();
            Object prio = json.get("sourcePriority");
            this.sourcePriority = new LinkedHashMap<>();
            if (prio instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) prio).entrySet())
                    sourcePriority.put(e.getKey(), ((Number) e.getValue()).intValue());
            }
            Object evs = json.get("events");
            this.events = new LinkedHashMap<>();
            if (evs instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) evs).entrySet()) {
                    events.put(e.getKey(), new EventDef((Map<String, Object>) e.getValue()));
                }
            }
        }

        public int priorityOf(String source) {
            return sourcePriority.getOrDefault(source, 0);
        }

        public Map<String, Object> initialVars() {
            return deepCopyMap(variables);
        }

        public String fingerprint() {
            return Hash.sha256(Json.canonical(raw));
        }
    }

    public static final class EventDef {
        public final String condition; // may be null
        public final List<Map<String, Object>> actions;

        @SuppressWarnings("unchecked")
        public EventDef(Map<String, Object> json) {
            Object c = json.get("condition");
            this.condition = c == null ? null : String.valueOf(c);
            Object a = json.get("actions");
            this.actions = new ArrayList<>();
            if (a instanceof List) {
                for (Object o : (List<Object>) a) {
                    if (!(o instanceof Map)) throw new IllegalArgumentException("action must be an object");
                    this.actions.add(deepCopyMap((Map<String, Object>) o));
                }
            }
        }
    }

    /** One event in a log. External events come from imports; internal events are emitted by actions. */
    public static final class EventRecord {
        public String id;
        public long time;
        public String source;
        public long seq;
        public String name;
        public Map<String, Object> payload;
        public boolean internal;

        @SuppressWarnings("unchecked")
        public static EventRecord fromJson(Map<String, Object> json, int fallbackSeq) {
            EventRecord e = new EventRecord();
            e.id = str(json.get("id"), null);
            e.time = json.get("time") instanceof Number n ? n.longValue() : 0L;
            e.source = str(json.get("source"), "default");
            e.seq = json.get("seq") instanceof Number n ? n.longValue() : fallbackSeq;
            e.name = str(json.get("name"), null);
            if (e.name == null) throw new IllegalArgumentException("event is missing 'name'");
            Object p = json.get("payload");
            e.payload = p instanceof Map ? deepCopyMap((Map<String, Object>) p) : new LinkedHashMap<>();
            e.internal = Boolean.TRUE.equals(json.get("internal"));
            if (e.id == null) e.id = "ev-" + e.time + "-" + e.source + "-" + e.seq + "-" + e.name;
            return e;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("time", time);
            m.put("source", source);
            m.put("seq", seq);
            m.put("name", name);
            m.put("payload", deepCopyMap(payload));
            if (internal) m.put("internal", true);
            return m;
        }

        public EventRecord copy() {
            return fromJson(toJson(), 0);
        }
    }

    /** Stable ordering: logical time, then source priority, then original sequence, then id. */
    public static Comparator<EventRecord> ordering(Definition def) {
        return Comparator
                .comparingLong((EventRecord e) -> e.time)
                .thenComparingInt(e -> def.priorityOf(e.source))
                .thenComparingLong(e -> e.seq)
                .thenComparing(e -> e.id);
    }

    public static String sortKey(EventRecord e, Definition def) {
        return e.time + "/" + def.priorityOf(e.source) + "/" + e.seq + "/" + e.id;
    }

    // ---- helpers ----

    public static String str(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    public static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) for (Object v : (List<Object>) o) out.add(String.valueOf(v));
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopyMap(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) out.put(e.getKey(), deepCopy(e.getValue()));
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object v) {
        if (v instanceof Map) return deepCopyMap((Map<String, Object>) v);
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<Object>) v) out.add(deepCopy(o));
            return out;
        }
        return v;
    }
}
