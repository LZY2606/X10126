package replay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Domain model: machine definition, events, trace entries, checkpoints, branches. */
public final class Model {
    private Model() {}

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A state-machine definition. Its fingerprint locks the exact version. */
    public static final class Definition {
        public final Map<String, Object> raw;
        public final String version;
        public final String initialState;
        public final Map<String, Object> variables;
        public final List<Map<String, Object>> transitions;
        public final String fingerprint;

        @SuppressWarnings("unchecked")
        public Definition(Map<String, Object> raw) {
            this.raw = raw;
            this.version = String.valueOf(raw.getOrDefault("version", "1"));
            this.initialState = Json.asString(raw.getOrDefault("initialState", "INIT"));
            Object vars = raw.get("variables");
            this.variables = vars == null ? new LinkedHashMap<>() : new LinkedHashMap<>(Json.asMap(vars));
            Object tr = raw.get("transitions");
            this.transitions = new ArrayList<>();
            if (tr != null) {
                for (Object o : Json.asList(tr)) this.transitions.add(Json.asMap(o));
            }
            this.fingerprint = sha256(Json.canonical(raw));
        }

        public Map<String, Object> findTransition(String state, String eventName) {
            for (Map<String, Object> t : transitions) {
                if (state.equals(String.valueOf(t.get("state"))) && eventName.equals(String.valueOf(t.get("event")))) {
                    return t;
                }
            }
            return null;
        }
    }

    /** An external or internal event instance. */
    public static final class Event {
        public final String id;
        public final long time;
        public final String source;
        public final long seq;
        public final String name;
        public final Map<String, Object> payload;
        public final boolean internal;

        public Event(String id, long time, String source, long seq, String name,
                     Map<String, Object> payload, boolean internal) {
            this.id = id;
            this.time = time;
            this.source = source;
            this.seq = seq;
            this.name = name;
            this.payload = payload;
            this.internal = internal;
        }

        public static Event fromJson(Map<String, Object> m, boolean internal) {
            String id = String.valueOf(m.getOrDefault("id", "e" + m.hashCode()));
            long time = m.containsKey("time") ? Json.asLong(m.get("time")) : 0L;
            String source = String.valueOf(m.getOrDefault("source", "external"));
            long seq = m.containsKey("seq") ? Json.asLong(m.get("seq")) : 0L;
            String name = Json.asString(m.get("name"));
            Object payload = m.get("payload");
            Map<String, Object> pl = payload == null ? new LinkedHashMap<>() : Json.asMap(payload);
            return new Event(id, time, source, seq, name, pl, internal);
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.map();
            m.put("id", id);
            m.put("time", time);
            m.put("source", source);
            m.put("seq", seq);
            m.put("name", name);
            m.put("payload", payload);
            if (internal) m.put("internal", true);
            return m;
        }

        /** Content hash used to detect "same id, different content" conflicts. */
        public String contentHash() {
            Map<String, Object> m = Json.map();
            m.put("time", time);
            m.put("source", source);
            m.put("seq", seq);
            m.put("name", name);
            m.put("payload", payload);
            return sha256(Json.canonical(m));
        }
    }

    /** One replayed step in the trace. */
    public static final class TraceEntry {
        public int index;
        public long clock;
        public Event event;
        public String status; // applied | no-transition | condition-false | failed
        public String stateBefore;
        public Map<String, Object> varsBefore;
        public String stateAfter;
        public Map<String, Object> varsAfter;
        public List<Object> outputs = new ArrayList<>();
        public List<String> emitted = new ArrayList<>();
        public String error;

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.map();
            m.put("index", (long) index);
            m.put("clock", clock);
            m.put("event", event.toJson());
            m.put("status", status);
            Map<String, Object> before = Json.map();
            before.put("state", stateBefore);
            before.put("vars", varsBefore);
            Map<String, Object> after = Json.map();
            after.put("state", stateAfter);
            after.put("vars", varsAfter);
            m.put("before", before);
            m.put("after", after);
            m.put("outputs", outputs);
            m.put("emitted", emitted);
            if (error != null) m.put("error", error);
            m.put("diff", diff());
            return m;
        }

        public Map<String, Object> diff() {
            Map<String, Object> d = Json.map();
            if (!stateBefore.equals(stateAfter)) {
                Map<String, Object> sc = Json.map();
                sc.put("from", stateBefore);
                sc.put("to", stateAfter);
                d.put("state", sc);
            }
            Map<String, Object> varDiff = Json.map();
            java.util.Set<String> keys = new java.util.TreeSet<>();
            keys.addAll(varsBefore.keySet());
            keys.addAll(varsAfter.keySet());
            for (String k : keys) {
                Object b = varsBefore.get(k);
                Object a = varsAfter.get(k);
                if (!java.util.Objects.equals(b, a)) {
                    Map<String, Object> ch = Json.map();
                    ch.put("from", b);
                    ch.put("to", a);
                    varDiff.put(k, ch);
                }
            }
            if (!varDiff.isEmpty()) d.put("vars", varDiff);
            return d;
        }
    }

    /** A checkpoint pins a step index plus the definition fingerprint it was taken under. */
    public static final class Checkpoint {
        public final String id;
        public final String name;
        public final int step;
        public final String defFingerprint;
        public final String stateHash;

        public Checkpoint(String id, String name, int step, String defFingerprint, String stateHash) {
            this.id = id;
            this.name = name;
            this.step = step;
            this.defFingerprint = defFingerprint;
            this.stateHash = stateHash;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.map();
            m.put("id", id);
            m.put("name", name);
            m.put("step", (long) step);
            m.put("defFingerprint", defFingerprint);
            m.put("stateHash", stateHash);
            return m;
        }

        public static Checkpoint fromJson(Map<String, Object> m) {
            return new Checkpoint(
                    Json.asString(m.get("id")),
                    Json.asString(m.get("name")),
                    (int) Json.asLong(m.get("step")),
                    Json.asString(m.get("defFingerprint")),
                    Json.asString(m.get("stateHash")));
        }
    }

    /** A branch: its own external event log, replay cursor and checkpoints. */
    public static final class Branch {
        public final String id;
        public String name;
        public final List<Event> events = new ArrayList<>();
        public int cursor;
        public final List<Checkpoint> checkpoints = new ArrayList<>();
        public String forkedFrom;
        public int forkStep;

        public Branch(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.map();
            m.put("id", id);
            m.put("name", name);
            List<Object> evs = Json.list();
            for (Event e : events) evs.add(e.toJson());
            m.put("events", evs);
            m.put("cursor", (long) cursor);
            List<Object> cps = Json.list();
            for (Checkpoint c : checkpoints) cps.add(c.toJson());
            m.put("checkpoints", cps);
            if (forkedFrom != null) m.put("forkedFrom", forkedFrom);
            m.put("forkStep", (long) forkStep);
            return m;
        }

        public static Branch fromJson(Map<String, Object> m) {
            Branch b = new Branch(Json.asString(m.get("id")), Json.asString(m.get("name")));
            for (Object o : Json.asList(m.get("events"))) {
                b.events.add(Event.fromJson(Json.asMap(o), false));
            }
            b.cursor = (int) Json.asLong(m.getOrDefault("cursor", 0L));
            Object cps = m.get("checkpoints");
            if (cps != null) for (Object o : Json.asList(cps)) b.checkpoints.add(Checkpoint.fromJson(Json.asMap(o)));
            Object ff = m.get("forkedFrom");
            if (ff != null) b.forkedFrom = Json.asString(ff);
            b.forkStep = (int) Json.asLong(m.getOrDefault("forkStep", 0L));
            return b;
        }
    }
}
