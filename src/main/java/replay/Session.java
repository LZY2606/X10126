package replay;

import replay.Model.Action;
import replay.Model.Definition;
import replay.Model.Event;
import replay.Model.Snapshot;
import replay.Model.Transition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * One deterministic replay session. All time is logical (carried by events);
 * the engine never reads a wall clock.
 */
public final class Session {
    String id;
    String name;
    Definition def;
    long seed;
    Map<String, Object> initialVars;
    List<Map<String, Object>> initialEvents;
    Snapshot snapshot;
    long rngState;
    long internalSeq;
    final PriorityQueue<Event> pending;
    final List<Map<String, Object>> trace;
    final List<Map<String, Object>> checkpoints;
    /** External events applied since own branch point: {traceIndex, event}. */
    final List<Map<String, Object>> externalApplied;
    Map<String, Object> branchPoint; // {sessionId, stepIndex} or null for roots
    String traceHash;

    private static final String EMPTY_HASH = Util.sha256("");

    public Session(Definition def, long seed, String name) {
        this.id = "s-" + UUID.randomUUID().toString().substring(0, 8);
        this.name = name == null ? this.id : name;
        this.def = def;
        this.seed = seed;
        this.initialVars = new LinkedHashMap<>();
        this.initialEvents = new ArrayList<>();
        this.snapshot = new Snapshot(def.initialState, new LinkedHashMap<>(initialVars));
        this.rngState = seed;
        this.internalSeq = 1;
        this.pending = new PriorityQueue<>(eventComparator(def));
        this.trace = new ArrayList<>();
        this.checkpoints = new ArrayList<>();
        this.externalApplied = new ArrayList<>();
        this.traceHash = EMPTY_HASH;
    }

    static Comparator<Event> eventComparator(Definition def) {
        return Comparator.comparingLong((Event e) -> e.time)
                .thenComparingInt(e -> def.priorityOf(e.source))
                .thenComparingLong(e -> e.seq)
                .thenComparing(e -> e.name);
    }

    // ---------- setup ----------

    public void addEvents(List<Event> events) {
        for (Event e : events) {
            if (e.internal) throw new IllegalArgumentException("cannot inject internal events");
            pending.add(e);
            if (trace.isEmpty() && branchPoint == null) initialEvents.add(e.toMap());
        }
    }

    // ---------- fingerprints ----------

    public String definitionFingerprint() {
        return Util.sha256(Json.canonical(def.toMap()));
    }

    /** Locks definition version, initial state, event content and seed together. */
    public String fingerprint() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definition", def.toMap());
        m.put("seed", seed);
        m.put("initialVars", initialVars);
        m.put("initialState", def.initialState);
        m.put("events", initialEvents);
        return Util.sha256(Json.canonical(m));
    }

    public String traceHash() { return traceHash; }

    // ---------- replay ----------

    public boolean hasNext() { return !pending.isEmpty(); }

    public Event peek() { return pending.peek(); }

    public int pendingCount() { return pending.size(); }

    /** Advance exactly one event. Returns the step record, or null when drained. */
    public Map<String, Object> step() {
        Event ev = pending.poll();
        if (ev == null) return null;
        Snapshot before = snapshot.copy();
        long rngBefore = rngState;
        long seqBefore = internalSeq;

        Transition matched = null;
        boolean guardRejected = false;
        for (Transition t : def.transitions) {
            if (!t.event.equals(ev.name)) continue;
            if (t.from != null && !"*".equals(t.from) && !t.from.equals(before.state)) continue;
            if (t.condition != null && !t.condition.isBlank()
                    && !Expr.isTrue(t.condition, before.vars, before.state, ev.payload)) {
                guardRejected = true;
                continue;
            }
            matched = t;
            break;
        }

        Snapshot work = before.copy();
        List<String> outs = new ArrayList<>();
        List<Event> emitted = new ArrayList<>();
        String error = null;
        if (matched != null) {
            if (matched.to != null) work = new Snapshot(matched.to, work.vars);
            try {
                for (Action a : matched.actions) applyAction(a, work, ev, outs, emitted);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }

        if (error != null) {
            // Roll back state changes and derived internal events together;
            // the failure itself is still recorded in the trace.
            snapshot = before;
            rngState = rngBefore;
            internalSeq = seqBefore;
        } else {
            snapshot = work;
            pending.addAll(emitted);
        }

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("index", trace.size());
        step.put("event", ev.toMap());
        step.put("before", before.toMap());
        step.put("after", snapshot.toMap());
        step.put("transition", matched == null ? null : matched.toMap());
        step.put("outputs", outs);
        List<Object> emittedMaps = new ArrayList<>();
        for (Event e : emitted) emittedMaps.add(e.toMap());
        step.put("emitted", error == null ? emittedMaps : List.of());
        step.put("failed", error != null);
        if (error != null) step.put("error", error);
        step.put("guardRejected", matched == null && guardRejected);
        trace.add(step);
        traceHash = Util.sha256(traceHash + "|" + Json.canonical(step));
        if (!ev.internal) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("traceIndex", step.get("index"));
            rec.put("event", ev.toMap());
            externalApplied.add(rec);
        }
        return step;
    }

    public int runAll() {
        int n = 0;
        while (step() != null) n++;
        return n;
    }

    private void applyAction(Action a, Snapshot work, Event ev,
                             List<String> outs, List<Event> emitted) {
        switch (a.type) {
            case "set" -> {
                Object v = a.expr != null
                        ? Expr.eval(a.expr, work.vars, work.state, ev.payload)
                        : a.value;
                work.vars.put(a.var, v);
            }
            case "emit" -> emitted.add(new Event(a.eventName, ev.time, "internal",
                    internalSeq++, a.payload, true));
            case "output" -> outs.add(Expr.interpolate(a.message, work.vars, work.state, ev.payload));
            case "fail" -> throw new IllegalStateException(
                    a.message == null ? "action failed" : a.message);
            case "random" -> work.vars.put(a.var, nextRandom(a.bound));
            default -> throw new IllegalArgumentException("unknown action type: " + a.type);
        }
    }

    private long nextRandom(int bound) {
        if (bound <= 0) throw new IllegalStateException("random bound must be > 0");
        rngState = rngState * 6364136223846793005L + 1442695040888963407L;
        long v = (rngState >>> 33);
        return Math.floorMod(v, bound);
    }

    // ---------- checkpoints ----------

    public Map<String, Object> checkpoint(String name) {
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("id", "cp-" + checkpoints.size());
        cp.put("name", name == null ? "checkpoint-" + checkpoints.size() : name);
        cp.put("stepIndex", trace.size());
        cp.put("snapshot", snapshot.toMap());
        cp.put("rngState", rngState);
        cp.put("internalSeq", internalSeq);
        List<Object> pend = new ArrayList<>();
        for (Event e : pending) pend.add(e.toMap());
        cp.put("pending", pend);
        cp.put("traceHash", traceHash);
        cp.put("definitionFingerprint", definitionFingerprint());
        checkpoints.add(cp);
        return cp;
    }

    /** Attach a checkpoint document coming from another session/export. */
    public Map<String, Object> attachCheckpoint(Map<String, Object> cp) {
        Object fp = cp.get("definitionFingerprint");
        if (!definitionFingerprint().equals(fp)) {
            throw new IllegalStateException("checkpoint definition fingerprint mismatch: checkpoint="
                    + fp + " current=" + definitionFingerprint());
        }
        Map<String, Object> copy = new LinkedHashMap<>(cp);
        copy.put("id", "cp-" + checkpoints.size());
        checkpoints.add(copy);
        return copy;
    }

    Map<String, Object> findCheckpoint(String checkpointId) {
        for (Map<String, Object> cp : checkpoints) {
            if (cp.get("id").equals(checkpointId)) return cp;
        }
        throw new IllegalArgumentException("unknown checkpoint: " + checkpointId);
    }

    /** Fork a new session from one of this session's checkpoints. */
    @SuppressWarnings("unchecked")
    public Session branch(String checkpointId, String branchName) {
        Map<String, Object> cp = findCheckpoint(checkpointId);
        String cpFingerprint = (String) cp.get("definitionFingerprint");
        if (!cpFingerprint.equals(definitionFingerprint())) {
            throw new IllegalStateException("checkpoint definition fingerprint mismatch: checkpoint="
                    + cpFingerprint + " current=" + definitionFingerprint());
        }
        int stepIndex = ((Number) cp.get("stepIndex")).intValue();
        Session s = new Session(def, seed, branchName);
        s.initialVars = new LinkedHashMap<>(initialVars);
        s.initialEvents = new ArrayList<>(initialEvents);
        s.snapshot = Snapshot.fromMap(Model.asMap(cp.get("snapshot"), "snapshot"));
        s.rngState = ((Number) cp.get("rngState")).longValue();
        s.internalSeq = ((Number) cp.get("internalSeq")).longValue();
        for (Object o : (List<Object>) cp.get("pending")) {
            s.pending.add(Event.fromMap(Model.asMap(o, "event")));
        }
        s.trace.addAll(trace.subList(0, stepIndex));
        s.traceHash = (String) cp.get("traceHash");
        for (Map<String, Object> parentCp : checkpoints) {
            if (((Number) parentCp.get("stepIndex")).intValue() <= stepIndex) {
                s.checkpoints.add(new LinkedHashMap<>(parentCp));
            }
        }
        Map<String, Object> bp = new LinkedHashMap<>();
        bp.put("sessionId", id);
        bp.put("stepIndex", stepIndex);
        s.branchPoint = bp;
        return s;
    }

    // ---------- outputs / views ----------

    public List<String> outputs() {
        List<String> all = new ArrayList<>();
        for (Map<String, Object> step : trace) {
            for (Object o : (List<?>) step.get("outputs")) all.add(String.valueOf(o));
        }
        return all;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("definitionFingerprint", definitionFingerprint());
        m.put("fingerprint", fingerprint());
        m.put("traceHash", traceHash);
        m.put("stepCount", trace.size());
        m.put("pendingCount", pending.size());
        m.put("snapshot", snapshot.toMap());
        m.put("seed", seed);
        m.put("branchPoint", branchPoint);
        Event next = pending.peek();
        m.put("nextEvent", next == null ? null : next.toMap());
        return m;
    }

    // ---------- persistence ----------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("definition", def.toMap());
        m.put("seed", seed);
        m.put("initialVars", initialVars);
        m.put("initialEvents", initialEvents);
        m.put("snapshot", snapshot.toMap());
        m.put("rngState", rngState);
        m.put("internalSeq", internalSeq);
        List<Object> pend = new ArrayList<>();
        List<Event> sorted = new ArrayList<>(pending);
        sorted.sort(eventComparator(def));
        for (Event e : sorted) pend.add(e.toMap());
        m.put("pending", pend);
        m.put("trace", trace);
        m.put("checkpoints", checkpoints);
        m.put("externalApplied", externalApplied);
        m.put("branchPoint", branchPoint);
        m.put("traceHash", traceHash);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Session fromMap(Map<String, Object> m) {
        Definition def = Definition.fromMap(Model.asMap(m.get("definition"), "definition"));
        Session s = new Session(def, ((Number) m.get("seed")).longValue(), Model.str(m, "name"));
        s.id = Model.str(m, "id");
        s.initialVars = new LinkedHashMap<>(Model.asMap(m.get("initialVars"), "initialVars"));
        s.initialEvents = new ArrayList<>((List<Map<String, Object>>) m.get("initialEvents"));
        s.snapshot = Snapshot.fromMap(Model.asMap(m.get("snapshot"), "snapshot"));
        s.rngState = ((Number) m.get("rngState")).longValue();
        s.internalSeq = ((Number) m.get("internalSeq")).longValue();
        for (Object o : (List<Object>) m.get("pending")) {
            s.pending.add(Event.fromMap(Model.asMap(o, "event")));
        }
        s.trace.addAll((List<Map<String, Object>>) m.get("trace"));
        for (Object o : (List<Object>) m.get("checkpoints")) {
            s.checkpoints.add(new LinkedHashMap<>(Model.asMap(o, "checkpoint")));
        }
        for (Object o : (List<Object>) m.get("externalApplied")) {
            s.externalApplied.add(new LinkedHashMap<>(Model.asMap(o, "externalApplied")));
        }
        s.branchPoint = m.get("branchPoint") == null ? null
                : new LinkedHashMap<>(Model.asMap(m.get("branchPoint"), "branchPoint"));
        s.traceHash = Model.str(m, "traceHash");
        return s;
    }

    /** Recompute the rolling trace hash; used to verify imported sessions. */
    public String recomputeTraceHash() {
        String h = EMPTY_HASH;
        for (Map<String, Object> step : trace) {
            h = Util.sha256(h + "|" + Json.canonical(step));
        }
        return h;
    }
}
