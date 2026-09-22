package replay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Core data model: definitions, events, trace entries, checkpoints, sessions. */
public final class Model {
    private Model() {}

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Transition {
        public String event;
        public String from = "*";
        public String to;
        public String condition = "true";
        public List<String> actions = new ArrayList<>();

        @SuppressWarnings("unchecked")
        static Transition fromJson(Map<String, Object> m) {
            Transition t = new Transition();
            t.event = Json.str(m.get("event"));
            if (m.get("from") != null) t.from = Json.str(m.get("from"));
            if (m.get("to") != null) t.to = Json.str(m.get("to"));
            if (m.get("condition") != null) t.condition = Json.str(m.get("condition"));
            if (m.get("actions") != null) {
                for (Object a : Json.arr(m.get("actions"))) t.actions.add(Json.str(a));
            }
            return t;
        }
    }

    public static final class Definition {
        public String id;
        public String name;
        public String version;
        public List<String> states = new ArrayList<>();
        public String initialState;
        public Map<String, Object> initialVars = new LinkedHashMap<>();
        public Map<String, Long> priorities = new LinkedHashMap<>();
        public List<Transition> transitions = new ArrayList<>();
        public String fingerprint;
        public Map<String, Object> raw;

        @SuppressWarnings("unchecked")
        public static Definition parse(Map<String, Object> m) {
            Definition d = new Definition();
            d.raw = (Map<String, Object>) Json.copy(m);
            d.name = Json.str(m.get("name"));
            d.version = m.get("version") != null ? Json.str(m.get("version")) : "0";
            for (Object s : Json.arr(m.get("states"))) d.states.add(Json.str(s));
            if (d.states.isEmpty()) throw new Json.JsonException("definition needs at least one state");
            d.initialState = Json.str(m.get("initialState"));
            if (!d.states.contains(d.initialState)) {
                throw new Json.JsonException("initialState not in states: " + d.initialState);
            }
            if (m.get("vars") != null) d.initialVars = Json.obj(Json.copy(m.get("vars")));
            if (m.get("priorities") != null) {
                for (Map.Entry<String, Object> e : Json.obj(m.get("priorities")).entrySet()) {
                    d.priorities.put(e.getKey(), Json.num(e.getValue()));
                }
            }
            if (m.get("transitions") != null) {
                for (Object t : Json.arr(m.get("transitions"))) {
                    d.transitions.add(Transition.fromJson(Json.obj(t)));
                }
            }
            d.fingerprint = sha256(Json.canonical(d.raw));
            d.id = "def-" + d.fingerprint.substring(0, 12);
            return d;
        }

        public long priorityOf(String source) {
            return priorities.getOrDefault(source, 0L);
        }
    }

    public static final class Event {
        public String id;
        public long time;
        public String source;
        public long seq;
        public long priority;
        public boolean internal;
        public String name;
        public Map<String, Object> payload = new LinkedHashMap<>();

        /** Stable ordering: logical time, then source priority, then original sequence, then id. */
        public static final Comparator<Event> ORDER = Comparator
                .comparingLong((Event e) -> e.time)
                .thenComparingLong(e -> e.priority)
                .thenComparingLong(e -> e.seq)
                .thenComparing(e -> e.id);

        public boolean sameOrderKey(Event other) {
            return time == other.time && priority == other.priority && seq == other.seq;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("time", time);
            m.put("source", source);
            m.put("seq", seq);
            m.put("priority", priority);
            m.put("internal", internal);
            m.put("name", name);
            m.put("payload", payload);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Event fromJson(Map<String, Object> m) {
            Event e = new Event();
            e.id = m.get("id") != null ? Json.str(m.get("id")) : null;
            e.time = Json.num(m.get("time"));
            e.source = m.get("source") != null ? Json.str(m.get("source")) : "external";
            e.seq = m.get("seq") != null ? Json.num(m.get("seq")) : 0;
            e.priority = m.get("priority") != null ? Json.num(m.get("priority")) : 0;
            e.internal = Boolean.TRUE.equals(m.get("internal"));
            e.name = Json.str(m.get("name"));
            if (m.get("payload") != null) e.payload = (Map<String, Object>) Json.copy(m.get("payload"));
            return e;
        }

        public Event copy() {
            return fromJson(toJson());
        }
    }

    public static final class TraceEntry {
        public int index;
        public String eventId;
        public String eventName;
        public long time;
        public String status;
        public String error;
        public Map<String, Object> before;
        public Map<String, Object> after;
        public List<Object> outputs = new ArrayList<>();

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", (long) index);
            m.put("eventId", eventId);
            m.put("eventName", eventName);
            m.put("time", time);
            m.put("status", status);
            if (error != null) m.put("error", error);
            m.put("before", before);
            m.put("after", after);
            m.put("outputs", outputs);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static TraceEntry fromJson(Map<String, Object> m) {
            TraceEntry t = new TraceEntry();
            t.index = (int) Json.num(m.get("index"));
            t.eventId = Json.str(m.get("eventId"));
            t.eventName = Json.str(m.get("eventName"));
            t.time = Json.num(m.get("time"));
            t.status = Json.str(m.get("status"));
            t.error = m.get("error") != null ? Json.str(m.get("error")) : null;
            t.before = Json.obj(Json.copy(m.get("before")));
            t.after = Json.obj(Json.copy(m.get("after")));
            for (Object o : Json.arr(m.get("outputs"))) t.outputs.add(Json.copy(o));
            return t;
        }
    }

    public static Map<String, Object> snapshot(String state, Map<String, Object> vars) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("vars", Json.copy(vars));
        return m;
    }

    public static final class Checkpoint {
        public String id;
        public String sessionId;
        public String definitionId;
        public String defFingerprint;
        public String state;
        public Map<String, Object> vars;
        public List<Event> pending = new ArrayList<>();
        public List<TraceEntry> trace = new ArrayList<>();
        public String traceHash;
        public long rngState;
        public long internalSeq;
        public List<Event> externalApplied = new ArrayList<>();
        public List<Event> eventLog = new ArrayList<>();

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("sessionId", sessionId);
            m.put("definitionId", definitionId);
            m.put("defFingerprint", defFingerprint);
            m.put("state", state);
            m.put("vars", vars);
            List<Object> p = new ArrayList<>();
            for (Event e : pending) p.add(e.toJson());
            m.put("pending", p);
            List<Object> t = new ArrayList<>();
            for (TraceEntry e : trace) t.add(e.toJson());
            m.put("trace", t);
            m.put("traceHash", traceHash);
            m.put("rngState", rngState);
            m.put("internalSeq", internalSeq);
            List<Object> applied = new ArrayList<>();
            for (Event e : externalApplied) applied.add(e.toJson());
            m.put("externalApplied", applied);
            List<Object> log = new ArrayList<>();
            for (Event e : eventLog) log.add(e.toJson());
            m.put("eventLog", log);
            return m;
        }

        public static Checkpoint fromJson(Map<String, Object> m) {
            Checkpoint c = new Checkpoint();
            c.id = Json.str(m.get("id"));
            c.sessionId = Json.str(m.get("sessionId"));
            c.definitionId = Json.str(m.get("definitionId"));
            c.defFingerprint = Json.str(m.get("defFingerprint"));
            c.state = Json.str(m.get("state"));
            c.vars = Json.obj(Json.copy(m.get("vars")));
            for (Object o : Json.arr(m.get("pending"))) c.pending.add(Event.fromJson(Json.obj(o)));
            for (Object o : Json.arr(m.get("trace"))) c.trace.add(TraceEntry.fromJson(Json.obj(o)));
            c.traceHash = Json.str(m.get("traceHash"));
            c.rngState = Json.num(m.get("rngState"));
            c.internalSeq = Json.num(m.get("internalSeq"));
            for (Object o : Json.arr(m.get("externalApplied"))) c.externalApplied.add(Event.fromJson(Json.obj(o)));
            for (Object o : Json.arr(m.get("eventLog"))) c.eventLog.add(Event.fromJson(Json.obj(o)));
            return c;
        }
    }

    public static final class Session {
        public String id;
        public String definitionId;
        public String defFingerprint;
        public long seed;
        public String state;
        public Map<String, Object> vars = new LinkedHashMap<>();
        public List<Event> pending = new ArrayList<>();
        public List<TraceEntry> trace = new ArrayList<>();
        public String traceHash;
        public long rngState;
        public long internalSeq;
        public String ancestorCheckpoint;
        public List<Event> externalApplied = new ArrayList<>();
        public List<Event> eventLog = new ArrayList<>();
        public List<String> checkpoints = new ArrayList<>();

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("definitionId", definitionId);
            m.put("defFingerprint", defFingerprint);
            m.put("seed", seed);
            m.put("state", state);
            m.put("vars", vars);
            List<Object> p = new ArrayList<>();
            for (Event e : pending) p.add(e.toJson());
            m.put("pending", p);
            List<Object> t = new ArrayList<>();
            for (TraceEntry e : trace) t.add(e.toJson());
            m.put("trace", t);
            m.put("traceHash", traceHash);
            m.put("rngState", rngState);
            m.put("internalSeq", internalSeq);
            m.put("ancestorCheckpoint", ancestorCheckpoint);
            List<Object> applied = new ArrayList<>();
            for (Event e : externalApplied) applied.add(e.toJson());
            m.put("externalApplied", applied);
            List<Object> log = new ArrayList<>();
            for (Event e : eventLog) log.add(e.toJson());
            m.put("eventLog", log);
            m.put("checkpoints", new ArrayList<>(checkpoints));
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Session fromJson(Map<String, Object> m) {
            Session s = new Session();
            s.id = Json.str(m.get("id"));
            s.definitionId = Json.str(m.get("definitionId"));
            s.defFingerprint = Json.str(m.get("defFingerprint"));
            s.seed = Json.num(m.get("seed"));
            s.state = Json.str(m.get("state"));
            s.vars = Json.obj(Json.copy(m.get("vars")));
            for (Object o : Json.arr(m.get("pending"))) s.pending.add(Event.fromJson(Json.obj(o)));
            for (Object o : Json.arr(m.get("trace"))) s.trace.add(TraceEntry.fromJson(Json.obj(o)));
            s.traceHash = Json.str(m.get("traceHash"));
            s.rngState = Json.num(m.get("rngState"));
            s.internalSeq = Json.num(m.get("internalSeq"));
            s.ancestorCheckpoint = m.get("ancestorCheckpoint") != null
                    ? Json.str(m.get("ancestorCheckpoint")) : null;
            for (Object o : Json.arr(m.get("externalApplied"))) s.externalApplied.add(Event.fromJson(Json.obj(o)));
            for (Object o : Json.arr(m.get("eventLog"))) s.eventLog.add(Event.fromJson(Json.obj(o)));
            if (m.get("checkpoints") != null) {
                for (Object o : Json.arr(m.get("checkpoints"))) s.checkpoints.add(Json.str(o));
            }
            return s;
        }
    }
}
