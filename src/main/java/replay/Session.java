package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** A replay session: definition + initial state + seed + event log, with checkpoints and branch ancestry. */
public final class Session {
    public final String id;
    public String name;
    public final Definition definition;
    public final String initialState;
    public final Map<String, Object> initialVars;
    public final long seed;
    public final String fingerprint; // locks definition version + initial state + events + seed at creation
    public final Engine engine;

    /** All external events ever accepted, in arrival order. */
    public final List<EventInstance> externalLog = new ArrayList<>();
    public final List<Checkpoint> checkpoints = new ArrayList<>();

    /** Branch ancestry: checkpoint (possibly in another session) this session was forked from. */
    public String parentSessionId;
    public Checkpoint ancestorCheckpoint; // null for root sessions
    public long branchPointExternalCount; // external events of the ancestor log shared with this branch

    private Session(String id, String name, Definition definition, String initialState,
                    Map<String, Object> initialVars, long seed, String fingerprint, Engine engine) {
        this.id = id;
        this.name = name;
        this.definition = definition;
        this.initialState = initialState;
        this.initialVars = initialVars;
        this.seed = seed;
        this.fingerprint = fingerprint;
        this.engine = engine;
    }

    public static Session create(String name, Definition def, String initialState,
                                 Map<String, Object> initialVars, long seed, List<EventInstance> events) {
        Engine engine = new Engine(def, initialState, initialVars, seed);
        String fingerprint = computeFingerprint(def, initialState, initialVars, seed, events);
        Session s = new Session(newId(), name, def, initialState, new LinkedHashMap<>(initialVars), seed, fingerprint, engine);
        s.externalLog.addAll(events);
        for (EventInstance e : events) engine.enqueue(e);
        return s;
    }

    private Session(String id, String name, Definition def, String initialState, Map<String, Object> initialVars,
                    long seed, Engine engine, String fingerprint) {
        this(id, name, def, initialState, initialVars, seed, fingerprint, engine);
    }

    static String computeFingerprint(Definition def, String initialState, Map<String, Object> initialVars,
                                     long seed, List<EventInstance> events) {
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("definitionVersion", def.version);
        lock.put("definition", def.raw);
        lock.put("initialState", initialState);
        lock.put("initialVars", initialVars);
        lock.put("seed", seed);
        lock.put("events", events.stream().map(EventInstance::toJson).collect(Collectors.toList()));
        return Hashing.sha256(Json.canonical(lock));
    }

    public String trajectoryHash() {
        return Hashing.sha256(Json.canonical(engine.trace().stream().map(TraceEntry::toJson).collect(Collectors.toList())));
    }

    public void addEvents(List<EventInstance> events) {
        externalLog.addAll(events);
        for (EventInstance e : events) engine.enqueue(e);
    }

    public Checkpoint checkpoint(String label) {
        String traceHash = trajectoryHash();
        Checkpoint cp = new Checkpoint("cp-" + UUID.randomUUID(), label, definition.fingerprint(),
                engine.clock(), externalLog.size(), traceHash, engine.snapshot());
        checkpoints.add(cp);
        return cp;
    }

    /** Restore a checkpoint; rejects checkpoints recorded under a different definition fingerprint. */
    public void restore(Checkpoint cp) {
        if (!cp.definitionFingerprint.equals(definition.fingerprint())) {
            throw new IllegalStateException("checkpoint definition fingerprint mismatch: checkpoint="
                    + cp.definitionFingerprint + " current=" + definition.fingerprint());
        }
        engine.restore(cp.snapshot);
        // External events accepted after the checkpoint are dropped from the log.
        while (externalLog.size() > cp.externalCount) externalLog.remove(externalLog.size() - 1);
    }

    /** Fork a new session from a checkpoint of this session. */
    public Session branch(Checkpoint cp, String branchName) {
        if (!cp.definitionFingerprint.equals(definition.fingerprint())) {
            throw new IllegalStateException("checkpoint definition fingerprint mismatch");
        }
        Engine fork = new Engine(definition, initialState, initialVars, seed);
        fork.restore(cp.snapshot);
        Session child = new Session(newId(), branchName, definition, initialState,
                new LinkedHashMap<>(initialVars), seed, fork, fingerprint);
        child.parentSessionId = this.id;
        child.ancestorCheckpoint = cp;
        child.branchPointExternalCount = cp.externalCount;
        // The branch inherits the external events accepted up to the checkpoint.
        child.externalLog.addAll(externalLog.subList(0, (int) Math.min(cp.externalCount, externalLog.size())));
        return child;
    }

    /** External events applied after the branch point (for merge compatibility checks). */
    public List<EventInstance> eventsSinceBranchPoint() {
        long from = ancestorCheckpoint != null ? branchPointExternalCount : 0;
        if (ancestorCheckpoint == null && parentSessionId == null) return List.of();
        return new ArrayList<>(externalLog.subList((int) from, externalLog.size()));
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("fingerprint", fingerprint);
        m.put("definitionFingerprint", definition.fingerprint());
        m.put("definitionVersion", definition.version);
        m.put("seed", seed);
        m.put("initialState", initialState);
        m.put("initialVars", initialVars);
        m.put("state", engine.state());
        m.put("vars", engine.vars());
        m.put("clock", engine.clock());
        m.put("pending", engine.pending());
        m.put("traceLength", engine.trace().size());
        m.put("trajectoryHash", trajectoryHash());
        m.put("externalCount", (long) externalLog.size());
        m.put("parentSessionId", parentSessionId);
        m.put("ancestorCheckpointId", ancestorCheckpoint == null ? null : ancestorCheckpoint.id);
        return m;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = summary();
        m.put("definition", definition.raw);
        m.put("externalLog", externalLog.stream().map(EventInstance::toJson).collect(Collectors.toList()));
        m.put("trace", engine.trace().stream().map(TraceEntry::toJson).collect(Collectors.toList()));
        m.put("checkpoints", checkpoints.stream().map(Checkpoint::toJson).collect(Collectors.toList()));
        m.put("queue", engine.queuedEvents().stream().filter(e -> !e.internal).map(EventInstance::toJson).collect(Collectors.toList()));
        m.put("internalQueue", engine.internalQueueSnapshot().stream().map(EventInstance::toJson).collect(Collectors.toList()));
        m.put("rngState", engineRngState());
        m.put("internalSeq", engineInternalSeq());
        m.put("branchPointExternalCount", branchPointExternalCount);
        if (ancestorCheckpoint != null) m.put("ancestorCheckpoint", ancestorCheckpoint.toJson());
        return m;
    }

    private long engineRngState() { return engine.snapshot().rngState; }
    private long engineInternalSeq() { return engine.snapshot().internalSeq; }

    @SuppressWarnings("unchecked")
    public static Session fromJson(Map<String, Object> m) {
        Definition def = Definition.fromJson((Map<String, Object>) m.get("definition"));
        String initialState = String.valueOf(m.get("initialState"));
        Map<String, Object> initialVars = (Map<String, Object>) m.getOrDefault("initialVars", Map.of());
        long seed = ((Number) m.getOrDefault("seed", 0L)).longValue();
        Engine engine = new Engine(def, initialState, initialVars, seed);
        Session s = new Session(String.valueOf(m.get("id")), String.valueOf(m.getOrDefault("name", "session")),
                def, initialState, new LinkedHashMap<>(initialVars), seed, engine,
                String.valueOf(m.get("fingerprint")));
        for (Object o : (List<Object>) m.getOrDefault("externalLog", List.of()))
            s.externalLog.add(EventInstance.fromJson((Map<String, Object>) o));
        List<TraceEntry> trace = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("trace", List.of()))
            trace.add(TraceEntry.fromJson((Map<String, Object>) o));
        List<EventInstance> queue = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("queue", List.of()))
            queue.add(EventInstance.fromJson((Map<String, Object>) o));
        List<EventInstance> internal = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("internalQueue", List.of()))
            internal.add(EventInstance.fromJson((Map<String, Object>) o));
        long rngState = Long.parseLong(String.valueOf(m.getOrDefault("rngState", "0")));
        long internalSeq = ((Number) m.getOrDefault("internalSeq", 0L)).longValue();
        long clock = ((Number) m.getOrDefault("clock", Long.MIN_VALUE)).longValue();
        engine.loadPersisted(initialStateOf(m, def), varsOf(m), queue, internal, clock, rngState, internalSeq, trace);
        for (Object o : (List<Object>) m.getOrDefault("checkpoints", List.of()))
            s.checkpoints.add(Checkpoint.fromJson((Map<String, Object>) o));
        s.parentSessionId = m.get("parentSessionId") == null ? null : String.valueOf(m.get("parentSessionId"));
        s.branchPointExternalCount = ((Number) m.getOrDefault("branchPointExternalCount", 0L)).longValue();
        if (m.get("ancestorCheckpoint") instanceof Map)
            s.ancestorCheckpoint = Checkpoint.fromJson((Map<String, Object>) m.get("ancestorCheckpoint"));
        return s;
    }

    @SuppressWarnings("unchecked")
    private static String initialStateOf(Map<String, Object> m, Definition def) {
        return String.valueOf(m.getOrDefault("state", m.get("initialState")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> varsOf(Map<String, Object> m) {
        Object v = m.get("vars");
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    static String newId() { return "s-" + UUID.randomUUID().toString().substring(0, 8); }
}
