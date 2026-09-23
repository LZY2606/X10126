package replay.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replay.json.Json;

/**
 * One replay session. Locks definition fingerprint, initial state, event
 * contents and seed together; every replay of the same session produces the
 * same trace hash.
 */
public final class Engine {
    public String id;
    public StateMachineDef def;
    public String defFingerprint;
    public long seed;
    public String initialState;
    public Map<String, Object> initialVars = new LinkedHashMap<>();
    public List<Event> baseEvents = new ArrayList<>();
    public long eventSeqCounter;
    public long eventIdCounter;
    public String sessionFingerprint;
    public Map<String, Branch> branches = new LinkedHashMap<>();
    public Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();
    public Map<Long, Event> eventIndex = new LinkedHashMap<>();
    private long branchCounter;
    private long checkpointCounter;

    // ---------- creation ----------

    public static Engine create(String id, Map<String, Object> defJson, List<Object> eventJsons,
            long seed, Map<String, Object> initialVars) {
        Engine e = new Engine();
        e.id = id;
        e.def = StateMachineDef.parse(defJson);
        e.defFingerprint = e.def.fingerprint();
        e.seed = seed;
        e.initialState = e.def.initialState;
        if (initialVars != null) {
            e.initialVars = Json.deepCopy(initialVars);
        }
        long seq = 0;
        for (Object ej : eventJsons) {
            Map<String, Object> em = Json.obj(ej, "event");
            Event ev = new Event(e.eventIdCounter++, Event.EXTERNAL,
                    Json.lng(em, "time", 0),
                    em.get("source") instanceof String s ? s : "unknown",
                    em.containsKey("seq") ? Json.lng(em, "seq", seq) : seq,
                    Json.str(em, "type"),
                    em.get("payload") instanceof Map<?, ?> p ? Json.deepCopy(p) : null);
            e.baseEvents.add(ev);
            e.eventIndex.put(ev.id, ev);
            seq++;
        }
        e.eventSeqCounter = seq;
        e.sortEvents(e.baseEvents);
        e.sessionFingerprint = e.computeSessionFingerprint();

        Branch main = e.freshBranch("main", "main");
        e.branches.put(main.id, main);
        return e;
    }

    private Branch freshBranch(String id, String name) {
        Branch b = new Branch();
        b.id = id;
        b.name = name;
        b.state = initialState;
        b.vars = Json.deepCopy(initialVars);
        b.rngState = seed;
        b.clock = 0;
        b.traceHash = sessionFingerprint;
        for (Event ev : baseEvents) {
            b.pendingExternals.add(ev.copy());
        }
        return b;
    }

    private void sortEvents(List<Event> events) {
        events.sort(eventOrder());
    }

    private Comparator<Event> eventOrder() {
        return Comparator.comparingLong((Event e) -> e.time)
                .thenComparingInt(e -> def.sourcePriority(e.source))
                .thenComparing(e -> e.source)
                .thenComparingLong(e -> e.seq);
    }

    private String computeSessionFingerprint() {
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("defFingerprint", defFingerprint);
        lock.put("seed", seed);
        lock.put("initialState", initialState);
        lock.put("initialVars", initialVars);
        List<Object> evs = new ArrayList<>();
        for (Event ev : baseEvents) {
            evs.add(ev.toJson());
        }
        lock.put("events", evs);
        return Fingerprint.sha256("session|" + Json.canonical(lock));
    }

    // ---------- stepping ----------

    /** Processes exactly one event on the branch; returns null when drained. */
    public StepRecord step(String branchId) {
        Branch b = requireBranch(branchId);
        Event ev;
        if (!b.internalQueue.isEmpty()) {
            ev = b.internalQueue.remove(0);
        } else if (!b.pendingExternals.isEmpty()) {
            ev = b.pendingExternals.remove(0);
        } else {
            return null;
        }

        String beforeState = b.state;
        Map<String, Object> beforeVars = Json.deepCopy(b.vars);
        long rngBefore = b.rngState;

        Action.Context ctx = new Action.Context(b.vars, ev.payload, b.rngState);
        boolean matched = false;
        String failure = null;
        try {
            StateMachineDef.Transition t = def.findTransition(ev.type, b.state, b.vars, ev.payload);
            if (t != null) {
                matched = true;
                for (Object action : t.actions) {
                    Action.execute(action, ctx);
                }
                if (t.to != null) {
                    b.state = t.to;
                }
            }
        } catch (ActionFailure ex) {
            // Roll back state, variables, RNG and anything this event derived.
            failure = ex.getMessage();
            b.state = beforeState;
            b.vars = beforeVars;
            ctx.emitted.clear();
            ctx.spawned.clear();
            ctx.rngState = rngBefore;
        }
        b.rngState = ctx.rngState;

        for (Event spawned : ctx.spawned) {
            spawned.id = eventIdCounter++;
            spawned.seq = eventSeqCounter++;
            spawned.time = ev.time;
            b.internalQueue.add(spawned);
            eventIndex.put(spawned.id, spawned);
        }
        if (ev.time > b.clock) {
            b.clock = ev.time;
        }
        b.outputs.addAll(ctx.emitted);

        StepRecord rec = new StepRecord();
        rec.index = b.steps.size();
        rec.eventId = ev.id;
        rec.eventKind = ev.kind;
        rec.eventType = ev.type;
        rec.time = ev.time;
        rec.source = ev.source;
        rec.matched = matched;
        rec.beforeState = beforeState;
        rec.afterState = b.state;
        rec.beforeVars = beforeVars;
        rec.afterVars = Json.deepCopy(b.vars);
        rec.outputs.addAll(ctx.emitted);
        for (Event s : ctx.spawned) {
            rec.spawned.add(s.toJson());
        }
        rec.failure = failure;
        rec.hash = Fingerprint.sha256(b.traceHash + "|" + Json.canonical(rec.toJsonCore()));
        b.traceHash = rec.hash;
        b.steps.add(rec);
        return rec;
    }

    public List<StepRecord> step(String branchId, int count) {
        List<StepRecord> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StepRecord rec = step(branchId);
            if (rec == null) {
                break;
            }
            out.add(rec);
        }
        return out;
    }

    // ---------- checkpoints & branches ----------

    public Checkpoint checkpoint(String branchId, String name) {
        Branch b = requireBranch(branchId);
        Checkpoint c = new Checkpoint();
        c.id = "cp-" + (++checkpointCounter);
        c.name = name == null || name.isBlank() ? c.id : name;
        c.defFingerprint = defFingerprint;
        c.branchId = b.id;
        c.stepIndex = b.steps.size();
        c.state = b.state;
        c.vars = Json.deepCopy(b.vars);
        c.outputs = new ArrayList<>(b.outputs);
        c.pendingExternals = copyEvents(b.pendingExternals);
        c.internalQueue = copyEvents(b.internalQueue);
        c.tracePrefix = new ArrayList<>(fullTrace(b.id));
        c.rngState = b.rngState;
        c.clock = b.clock;
        c.traceHash = b.traceHash;
        checkpoints.put(c.id, c);
        return c;
    }

    public Branch fork(String checkpointId, String name) {
        Checkpoint c = checkpoints.get(checkpointId);
        if (c == null) {
            throw new IllegalArgumentException("unknown checkpoint: " + checkpointId);
        }
        if (!c.defFingerprint.equals(defFingerprint)) {
            throw new IllegalStateException(
                    "checkpoint definition fingerprint mismatch: checkpoint=" + c.defFingerprint
                            + " session=" + defFingerprint);
        }
        Branch b = new Branch();
        b.id = "br-" + (++branchCounter);
        b.name = name == null || name.isBlank() ? b.id : name;
        b.forkCheckpoint = c.id;
        b.state = c.state;
        b.vars = Json.deepCopy(c.vars);
        b.outputs = new ArrayList<>(c.outputs);
        b.pendingExternals = copyEvents(c.pendingExternals);
        b.internalQueue = copyEvents(c.internalQueue);
        b.rngState = c.rngState;
        b.clock = c.clock;
        b.traceHash = c.traceHash;
        branches.put(b.id, b);
        return b;
    }

    /** Injects extra external events into one branch's pending queue. */
    public List<Event> inject(String branchId, List<Object> eventJsons) {
        Branch b = requireBranch(branchId);
        List<Event> added = new ArrayList<>();
        for (Object ej : eventJsons) {
            Map<String, Object> em = Json.obj(ej, "event");
            Event ev = new Event(eventIdCounter++, Event.EXTERNAL,
                    Json.lng(em, "time", 0),
                    em.get("source") instanceof String s ? s : "unknown",
                    eventSeqCounter++,
                    Json.str(em, "type"),
                    em.get("payload") instanceof Map<?, ?> p ? Json.deepCopy(p) : null);
            b.pendingExternals.add(ev);
            eventIndex.put(ev.id, ev);
            added.add(ev);
        }
        sortEvents(b.pendingExternals);
        return added;
    }

    // ---------- merge ----------

    public static final class MergeConflict extends RuntimeException {
        public final int position;
        public final Event a;
        public final Event b;

        MergeConflict(int position, Event a, Event b) {
            super("external event conflict at position " + position + ": "
                    + a.summary() + " vs " + b.summary());
            this.position = position;
            this.a = a;
            this.b = b;
        }
    }

    /**
     * Merges {@code fromId} into a new branch. Allowed only when the external
     * events both sides consumed since their common ancestor are prefix
     * compatible; otherwise the first conflicting pair is reported.
     */
    public Branch merge(String fromId, String toId, String name) {
        List<StepRecord> traceA = fullTrace(fromId);
        List<StepRecord> traceB = fullTrace(toId);
        int lcp = 0;
        while (lcp < traceA.size() && lcp < traceB.size()
                && traceA.get(lcp).hash.equals(traceB.get(lcp).hash)) {
            lcp++;
        }
        List<Long> extA = externalIds(traceA, lcp);
        List<Long> extB = externalIds(traceB, lcp);
        int i = 0;
        while (i < extA.size() && i < extB.size() && extA.get(i).equals(extB.get(i))) {
            i++;
        }
        if (i < extA.size() && i < extB.size()) {
            throw new MergeConflict(i, eventIndex.get(extA.get(i)), eventIndex.get(extB.get(i)));
        }
        Branch longer = extB.size() > extA.size() ? requireBranch(toId) : requireBranch(fromId);
        Branch merged = new Branch();
        merged.id = "br-" + (++branchCounter);
        merged.name = name == null || name.isBlank() ? "merge-" + longer.name : name;
        merged.forkCheckpoint = null;
        merged.state = longer.state;
        merged.vars = Json.deepCopy(longer.vars);
        merged.outputs = new ArrayList<>(longer.outputs);
        merged.pendingExternals = copyEvents(longer.pendingExternals);
        merged.internalQueue = copyEvents(longer.internalQueue);
        merged.steps = new ArrayList<>(fullTrace(longer.id));
        merged.rngState = longer.rngState;
        merged.clock = longer.clock;
        merged.traceHash = longer.traceHash;
        branches.put(merged.id, merged);
        return merged;
    }

    private static List<Long> externalIds(List<StepRecord> trace, int from) {
        List<Long> ids = new ArrayList<>();
        for (int i = from; i < trace.size(); i++) {
            StepRecord r = trace.get(i);
            if (Event.EXTERNAL.equals(r.eventKind)) {
                ids.add(r.eventId);
            }
        }
        return ids;
    }

    /** Full trace of a branch: ancestor prefix (via fork checkpoint) + own steps. */
    public List<StepRecord> fullTrace(String branchId) {
        Branch b = requireBranch(branchId);
        List<StepRecord> trace = new ArrayList<>();
        if (b.forkCheckpoint != null) {
            Checkpoint c = checkpoints.get(b.forkCheckpoint);
            if (c == null) {
                throw new IllegalStateException("dangling fork checkpoint: " + b.forkCheckpoint);
            }
            trace.addAll(c.tracePrefix);
        }
        trace.addAll(b.steps);
        return trace;
    }

    // ---------- serialization ----------

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("format", "replay-session/1");
        map.put("id", id);
        map.put("def", def.raw);
        map.put("defFingerprint", defFingerprint);
        map.put("seed", seed);
        map.put("initialState", initialState);
        map.put("initialVars", initialVars);
        List<Object> evs = new ArrayList<>();
        for (Event ev : baseEvents) {
            evs.add(ev.toJson());
        }
        map.put("events", evs);
        map.put("eventSeqCounter", eventSeqCounter);
        map.put("eventIdCounter", eventIdCounter);
        map.put("sessionFingerprint", sessionFingerprint);
        map.put("branchCounter", branchCounter);
        map.put("checkpointCounter", checkpointCounter);
        List<Object> brs = new ArrayList<>();
        for (Branch b : branches.values()) {
            brs.add(b.toJson());
        }
        map.put("branches", brs);
        List<Object> cps = new ArrayList<>();
        for (Checkpoint c : checkpoints.values()) {
            cps.add(c.toJson());
        }
        map.put("checkpoints", cps);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Engine fromJson(Map<String, Object> map) {
        Engine e = new Engine();
        e.id = Json.str(map, "id");
        e.def = StateMachineDef.parse(Json.obj(map.get("def"), "def"));
        e.defFingerprint = (String) map.get("defFingerprint");
        e.seed = Json.lng(map, "seed", 0);
        e.initialState = (String) map.get("initialState");
        e.initialVars = map.get("initialVars") instanceof Map<?, ?> v
                ? Json.deepCopy(v) : new LinkedHashMap<>();
        for (Object ej : Json.arr(map.get("events"), "events")) {
            Event ev = Event.fromJson(Json.obj(ej, "event"));
            e.baseEvents.add(ev);
            e.eventIndex.put(ev.id, ev);
        }
        e.eventSeqCounter = Json.lng(map, "eventSeqCounter", 0);
        e.eventIdCounter = Json.lng(map, "eventIdCounter", 0);
        e.sessionFingerprint = (String) map.get("sessionFingerprint");
        e.branchCounter = Json.lng(map, "branchCounter", 0);
        e.checkpointCounter = Json.lng(map, "checkpointCounter", 0);
        for (Object bj : Json.arr(map.get("branches"), "branches")) {
            Branch b = Branch.fromJson(Json.obj(bj, "branch"));
            e.branches.put(b.id, b);
        }
        for (Object cj : Json.arr(map.get("checkpoints"), "checkpoints")) {
            Checkpoint c = Checkpoint.fromJson(Json.obj(cj, "checkpoint"));
            e.checkpoints.put(c.id, c);
        }
        for (Branch b : e.branches.values()) {
            for (Event ev : b.pendingExternals) {
                e.eventIndex.putIfAbsent(ev.id, ev);
            }
            for (Event ev : b.internalQueue) {
                e.eventIndex.putIfAbsent(ev.id, ev);
            }
        }
        e.verify();
        return e;
    }

    /** Recomputes every fingerprint; throws if the file was tampered with. */
    public void verify() {
        String defFp = def.fingerprint();
        if (!defFp.equals(defFingerprint)) {
            throw new IllegalStateException("definition fingerprint mismatch");
        }
        String sessionFp = computeSessionFingerprint();
        if (!sessionFp.equals(sessionFingerprint)) {
            throw new IllegalStateException("session fingerprint mismatch");
        }
        for (Branch b : branches.values()) {
            String expected = recomputeTraceHash(b.id);
            if (!expected.equals(b.traceHash)) {
                throw new IllegalStateException("trace hash mismatch on branch " + b.id);
            }
        }
        for (Checkpoint c : checkpoints.values()) {
            if (!c.defFingerprint.equals(defFingerprint)) {
                throw new IllegalStateException(
                        "checkpoint " + c.id + " belongs to a different definition");
            }
        }
    }

    public String recomputeTraceHash(String branchId) {
        String hash = sessionFingerprint;
        for (StepRecord rec : fullTrace(branchId)) {
            hash = Fingerprint.sha256(hash + "|" + Json.canonical(rec.toJsonCore()));
        }
        return hash;
    }

    // ---------- helpers ----------

    public Branch requireBranch(String branchId) {
        Branch b = branches.get(branchId);
        if (b == null) {
            throw new IllegalArgumentException("unknown branch: " + branchId);
        }
        return b;
    }

    public String mainBranchId() {
        return branches.keySet().iterator().next();
    }

    private static List<Event> copyEvents(List<Event> events) {
        List<Event> copy = new ArrayList<>(events.size());
        for (Event e : events) {
            copy.add(e.copy());
        }
        return copy;
    }
}
