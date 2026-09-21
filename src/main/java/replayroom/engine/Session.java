package replayroom.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replayroom.json.Json;
import replayroom.model.Checkpoint;
import replayroom.model.Definition;
import replayroom.model.Envelope;
import replayroom.model.OutputRec;
import replayroom.model.TraceEntry;

/**
 * A live, replayable session. All state transitions are deterministic given
 * the locked definition, initial state, external event log and RNG seed.
 */
public final class Session {

    private String id;
    private String name;
    private final String definitionFingerprint;
    private final String definitionName;
    private final String initialState;
    private final Map<String, Object> initialVars;
    private final long seed;
    private final String lockFingerprint;

    private String currentState;
    private final Map<String, Object> vars;
    private final DeterministicRng rng;

    private final List<Envelope> externalLog;
    private final List<Envelope> pending;
    private final List<String> processedExternalIds;
    private final List<TraceEntry> trace;
    private String traceHeadHash;

    private long nextEventSerial;

    private String parentCheckpointId;
    private String baseExternalLogFingerprint;
    private boolean merged;
    private long createdAt;

    private Session(String id, String name, String definitionFingerprint, String definitionName,
                    String initialState, Map<String, Object> initialVars, long seed, String lockFingerprint,
                    String currentState, Map<String, Object> vars, DeterministicRng rng,
                    List<Envelope> externalLog, List<Envelope> pending,
                    List<String> processedExternalIds, List<TraceEntry> trace, String traceHeadHash,
                    long nextEventSerial, String parentCheckpointId, String baseExternalLogFingerprint,
                    boolean merged, long createdAt) {
        this.id = id;
        this.name = name;
        this.definitionFingerprint = definitionFingerprint;
        this.definitionName = definitionName;
        this.initialState = initialState;
        this.initialVars = initialVars;
        this.seed = seed;
        this.lockFingerprint = lockFingerprint;
        this.currentState = currentState;
        this.vars = vars;
        this.rng = rng;
        this.externalLog = externalLog;
        this.pending = pending;
        this.processedExternalIds = processedExternalIds;
        this.trace = trace;
        this.traceHeadHash = traceHeadHash;
        this.nextEventSerial = nextEventSerial;
        this.parentCheckpointId = parentCheckpointId;
        this.baseExternalLogFingerprint = baseExternalLogFingerprint;
        this.merged = merged;
        this.createdAt = createdAt;
    }

    public static Session create(String id, String name, CompiledDefinition compiled, long seed,
                                 List<Envelope> externalEvents) {
        Definition def = compiled.definition();
        List<Envelope> sorted = new ArrayList<>(externalEvents);
        sorted.sort(Envelope::compareExternal);
        String lock = lockFingerprint(compiled.fingerprint(), def.initialState(), def.initialVars(),
                envelopeListForFingerprint(sorted), seed);
        return new Session(
                id,
                name,
                compiled.fingerprint(),
                def.name(),
                def.initialState(),
                def.initialVars(),
                seed,
                lock,
                def.initialState(),
                def.initialVars(),
                new DeterministicRng(seed),
                sorted,
                new ArrayList<>(sorted),
                new ArrayList<>(),
                new ArrayList<>(),
                null,
                0L,
                null,
                null,
                false,
                System.currentTimeMillis());
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    public int pendingCount() {
        return pending.size();
    }

    public TraceEntry step(CompiledDefinition compiled) {
        if (compiled.fingerprint().equals(definitionFingerprint) == false) {
            throw new IllegalStateException("Definition fingerprint mismatch for session " + id);
        }
        if (pending.isEmpty()) {
            throw new IllegalStateException("No pending events in session " + id);
        }
        Envelope event = pending.remove(0);
        return processEvent(compiled, event);
    }

    public TraceEntry runToEnd(CompiledDefinition compiled) {
        TraceEntry last = null;
        while (!pending.isEmpty()) {
            last = step(compiled);
        }
        return last;
    }

    public TraceEntry runSteps(CompiledDefinition compiled, int count) {
        TraceEntry last = null;
        for (int i = 0; i < count && !pending.isEmpty(); i++) {
            last = step(compiled);
        }
        return last;
    }

    private TraceEntry processEvent(CompiledDefinition compiled, Envelope event) {
        StateSnapshot before = snapshotState();
        int stepIndex = trace.size() + 1;

        CompiledDefinition.CompiledTransition selected = null;
        int selectedIndex = -1;
        Map<String, Object> context = buildContext(event, before.vars());
        for (int i = 0; i < compiled.transitions().size(); i++) {
            CompiledDefinition.CompiledTransition candidate = compiled.transitions().get(i);
            if (!candidate.transition().event().equals(event.name())) continue;
            String from = candidate.transition().from();
            if (from != null && !from.isBlank() && !from.equals(currentState)) continue;
            boolean conditionOk = true;
            if (candidate.condition() != null) {
                try {
                    conditionOk = candidate.condition().evalBoolean(context, rng);
                } catch (Expr.EvalException e) {
                    conditionOk = false;
                }
            }
            if (conditionOk) {
                selected = candidate;
                selectedIndex = i;
                break;
            }
        }

        if (selected == null) {
            TraceEntry entry = new TraceEntry(stepIndex, event, false, null, "ignored",
                    before.vars(), snapshotState().vars(), List.of(), null, List.of());
            commitTraceEntry(event, entry);
            return entry;
        }

        String targetState = selected.transition().to();
        if (targetState != null && !targetState.isBlank()) {
            currentState = targetState;
        }

        Map<String, Object> workVars = Json.deepCopyMap(vars);
        long rngBefore = rng.state();
        List<OutputRec> outputs = new ArrayList<>();
        List<Envelope> emitted = new ArrayList<>();
        String failure = null;

        actionLoop:
        for (CompiledDefinition.CompiledAction compiledAction : selected.actions()) {
            replayroom.model.Action action = compiledAction.action();
            try {
                Map<String, Object> actionContext = buildContext(event, workVars);
                switch (action.type()) {
                    case "set" -> {
                        Object value = compiledAction.value().eval(actionContext, rng);
                        applySet(workVars, compiledAction.target().sourceText(), value);
                    }
                    case "output" -> {
                        Object data = compiledAction.data() == null
                                ? new LinkedHashMap<String, Object>()
                                : compiledAction.data().eval(actionContext, rng);
                        outputs.add(new OutputRec(action.name(), asDataMap(data, action.name())));
                    }
                    case "emit" -> {
                        Object data = compiledAction.data() == null
                                ? new LinkedHashMap<String, Object>()
                                : compiledAction.data().eval(actionContext, rng);
                        Envelope internal = Envelope.internal(
                                id + "-e" + nextEventSerial++,
                                action.event(),
                                action.source(),
                                event.time() + (action.timeDelta() == null ? 0L : action.timeDelta()),
                                event.seq(),
                                event.priority(),
                                asDataMap(data, action.event()),
                                event.id());
                        emitted.add(internal);
                    }
                    case "fail" -> {
                        Object message = compiledAction.message().eval(actionContext, rng);
                        Double chance = action.chance();
                        boolean triggered = chance == null || rng.nextUnit() < chance;
                        if (triggered) {
                            failure = String.valueOf(message);
                            break actionLoop;
                        }
                    }
                    default -> throw new Expr.EvalException("Unknown action type " + action.type());
                }
            } catch (Expr.EvalException e) {
                failure = e.getMessage();
                break actionLoop;
            }
        }

        TraceEntry entry;
        if (failure != null) {
            vars.clear();
            vars.putAll(before.vars());
            currentState = before.state();
            rng.restore(rngBefore);
            entry = new TraceEntry(stepIndex, event, true, selectedIndex, "failed",
                    before.vars(), snapshotState().vars(), List.of(), failure, List.of());
            commitTraceEntry(event, entry);
            return entry;
        }

        vars.clear();
        vars.putAll(workVars);
        // Newly emitted internal events form a depth-first chain: the whole
        // batch is inserted at the front in emission order, so it runs after
        // the current event but before previously queued siblings and before
        // any external events (regardless of their logical time/priority).
        int insertAt = 0;
        for (Envelope internalEvent : emitted) {
            pending.add(insertAt++, internalEvent);
        }
        entry = new TraceEntry(stepIndex, event, true, selectedIndex, "applied",
                before.vars(), snapshotState().vars(), outputs, null, emitted);
        commitTraceEntry(event, entry);
        return entry;
    }

    private record StateSnapshot(String state, Map<String, Object> vars) {}

    private StateSnapshot snapshotState() {
        return new StateSnapshot(currentState, Json.deepCopyMap(vars));
    }

    private Map<String, Object> buildContext(Envelope event, Map<String, Object> stateVars) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.putAll(stateVars);
        Map<String, Object> eventView = new LinkedHashMap<>();
        eventView.put("name", event.name());
        eventView.put("source", event.source());
        eventView.put("time", event.time());
        eventView.put("seq", event.seq());
        eventView.put("priority", event.priority());
        eventView.put("data", Json.deepCopyMap(event.data()));
        eventView.put("internal", event.internal());
        context.put("e", eventView);
        context.put("event", eventView);
        context.put("t", event.time());
        context.put("time", event.time());
        return context;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asDataMap(Object value, String owner) {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(String.valueOf(e.getKey()), e.getValue());
            }
            return result;
        }
        throw new Expr.EvalException("data expression for '" + owner + "' must produce an object");
    }

    @SuppressWarnings("unchecked")
    private static void applySet(Map<String, Object> target, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = target;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                cursor.put(parts[i], next);
            }
            cursor = (Map<String, Object>) next;
        }
        cursor.put(parts[parts.length - 1], value);
    }

    private void commitTraceEntry(Envelope event, TraceEntry entry) {
        if (!event.internal()) {
            processedExternalIds.add(event.id());
        }
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("version", "sm-trace/v1");
        chain.put("lock", lockFingerprint);
        chain.put("parent", traceHeadHash);
        chain.put("entry", entry.hashBody());
        String hash = Hashes.sha256Hex(Json.canonical(chain));
        entry.hash(hash);
        traceHeadHash = hash;
        trace.add(entry);
    }

    public static String lockFingerprint(String definitionFingerprint, String initialState,
                                         Map<String, Object> initialVars,
                                         List<Map<String, Object>> externalEventMaps, long seed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", "sm-session-lock/v1");
        body.put("definition", definitionFingerprint);
        body.put("initialState", initialState);
        body.put("initialVars", initialVars);
        body.put("events", externalEventMaps);
        body.put("seed", seed);
        return Hashes.sha256Hex(Json.canonical(body));
    }

    private static List<Map<String, Object>> envelopeListForFingerprint(List<Envelope> envelopes) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Envelope envelope : envelopes) maps.add(envelope.toMap());
        return maps;
    }

    public Checkpoint checkpoint(String checkpointId) {
        String baseLog = baseExternalLogFingerprint;
        if (baseLog == null) {
            baseLog = Hashes.sha256Hex(Json.canonical(envelopeListForFingerprint(externalLog)));
        }
        return new Checkpoint(
                checkpointId,
                id,
                trace.size(),
                definitionFingerprint,
                initialState,
                Json.deepCopyMap(vars),
                currentState,
                rng.state(),
                new ArrayList<>(pending),
                new ArrayList<>(processedExternalIds),
                lockFingerprint,
                traceHeadHash,
                baseLog,
                nextEventSerial);
    }

    public static Session fromCheckpoint(String newSessionId, String name, Checkpoint checkpoint,
                                         CompiledDefinition compiled, long seed) {
        if (!checkpoint.definitionFingerprint().equals(compiled.fingerprint())) {
            throw new DefinitionMismatchException(checkpoint.definitionFingerprint(), compiled.fingerprint());
        }
        List<Envelope> externalLog = checkpoint.pending().stream()
                .filter(e -> !e.internal())
                .collect(java.util.stream.Collectors.toList());
        return new Session(
                newSessionId,
                name,
                checkpoint.definitionFingerprint(),
                compiled.definition().name(),
                checkpoint.initialState(),
                compiled.definition().initialVars(),
                seed,
                checkpoint.lockFingerprint(),
                checkpoint.currentState(),
                Json.deepCopyMap(checkpoint.stateVars()),
                DeterministicRng.fromState(checkpoint.rngState()),
                new ArrayList<>(externalLog),
                new ArrayList<>(checkpoint.pending()),
                new ArrayList<>(checkpoint.processedExternalIds()),
                new ArrayList<>(),
                checkpoint.traceHeadHash(),
                checkpoint.nextEventSerial(),
                checkpoint.id(),
                checkpoint.baseExternalLogFingerprint(),
                false,
                System.currentTimeMillis());
    }

    public static final class DefinitionMismatchException extends RuntimeException {
        private final String checkpointFingerprint;
        private final String actualFingerprint;

        public DefinitionMismatchException(String checkpointFingerprint, String actualFingerprint) {
            super("Checkpoint definition " + checkpointFingerprint + " cannot be replayed under " + actualFingerprint);
            this.checkpointFingerprint = checkpointFingerprint;
            this.actualFingerprint = actualFingerprint;
        }

        public String checkpointFingerprint() { return checkpointFingerprint; }
        public String actualFingerprint() { return actualFingerprint; }
    }

    public void appendExternalEvents(List<Envelope> events) {
        List<Envelope> additions = new ArrayList<>(events);
        for (Envelope event : additions) {
            if (event.internal()) {
                throw new IllegalArgumentException("Only external events can be appended");
            }
            if (externalLog.stream().anyMatch(existing -> existing.id().equals(event.id()))
                    || processedExternalIds.contains(event.id())
                    || pending.stream().anyMatch(existing -> existing.id().equals(event.id()))) {
                throw new IllegalArgumentException("Duplicate event id: " + event.id());
            }
        }
        additions.sort(Envelope::compareExternal);
        externalLog.addAll(additions);
        externalLog.sort(Envelope::compareExternal);
        int insertAt = pending.size();
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).internal()) {
                insertAt = i;
                break;
            }
        }
        pending.addAll(insertAt, additions);
        sortExternalPrefix();
    }

    private void sortExternalPrefix() {
        int internalStart = pending.size();
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).internal()) {
                internalStart = i;
                break;
            }
        }
        List<Envelope> externalPrefix = new ArrayList<>(pending.subList(0, internalStart));
        externalPrefix.sort(Envelope::compareExternal);
        for (int i = 0; i < internalStart; i++) pending.set(i, externalPrefix.get(i));
    }

    public Map<String, Object> toMap(boolean includeDefinition) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", "sm-session/v1");
        map.put("id", id);
        map.put("name", name);
        map.put("definitionFingerprint", definitionFingerprint);
        map.put("definitionName", definitionName);
        map.put("initialState", initialState);
        map.put("initialVars", initialVars);
        map.put("seed", seed);
        map.put("lockFingerprint", lockFingerprint);
        map.put("currentState", currentState);
        map.put("vars", vars);
        map.put("rngState", rng.state());
        map.put("nextEventSerial", nextEventSerial);
        map.put("externalLog", envelopeListForFingerprint(externalLog));
        map.put("pending", envelopeListForFingerprint(pending));
        map.put("processedExternalIds", new ArrayList<>(processedExternalIds));
        List<Object> traceMaps = new ArrayList<>();
        for (TraceEntry entry : trace) traceMaps.add(entry.toMap());
        map.put("trace", traceMaps);
        map.put("traceHeadHash", traceHeadHash);
        map.put("parentCheckpointId", parentCheckpointId);
        map.put("baseExternalLogFingerprint", baseExternalLogFingerprint);
        map.put("merged", merged);
        map.put("createdAt", createdAt);
        if (includeDefinition) {
            map.put("definitionRef", definitionFingerprint);
        }
        return map;
    }

    public static Session fromMap(Map<String, Object> map) {
        List<Envelope> externalLog = readEnvelopeList(map, "externalLog");
        List<Envelope> pending = readEnvelopeList(map, "pending");
        List<String> processed = new ArrayList<>();
        for (Object value : Json.optList(map, "processedExternalIds")) {
            processed.add(Json.string(value, "processedExternalIds[]"));
        }
        List<TraceEntry> trace = new ArrayList<>();
        for (Object value : Json.optList(map, "trace")) {
            trace.add(TraceEntry.fromMap(Json.object(value, "trace[]")));
        }
        return new Session(
                Json.string(map.get("id"), "session.id"),
                Json.optString(map, "name", ""),
                Json.string(map.get("definitionFingerprint"), "session.definitionFingerprint"),
                Json.optString(map, "definitionName", ""),
                Json.string(map.get("initialState"), "session.initialState"),
                Json.optObject(map, "initialVars"),
                Json.optLong(map, "seed", 0L),
                Json.string(map.get("lockFingerprint"), "session.lockFingerprint"),
                Json.string(map.get("currentState"), "session.currentState"),
                Json.optObject(map, "vars"),
                DeterministicRng.fromState(Json.optLong(map, "rngState", 1L)),
                externalLog,
                pending,
                processed,
                trace,
                Json.optString(map, "traceHeadHash", null),
                Json.optLong(map, "nextEventSerial", 0L),
                Json.optString(map, "parentCheckpointId", null),
                Json.optString(map, "baseExternalLogFingerprint", null),
                Boolean.TRUE.equals(map.get("merged")),
                Json.optLong(map, "createdAt", 0L));
    }

    private static List<Envelope> readEnvelopeList(Map<String, Object> map, String key) {
        List<Envelope> result = new ArrayList<>();
        for (Object value : Json.optList(map, key)) {
            result.add(Envelope.fromMap(Json.object(value, key + "[]")));
        }
        return result;
    }

    public String id() { return id; }
    public void id(String value) { this.id = value; }
    public String name() { return name; }
    public void name(String value) { this.name = value == null ? "" : value; }
    public String definitionFingerprint() { return definitionFingerprint; }
    public String definitionName() { return definitionName; }
    public String initialState() { return initialState; }
    public Map<String, Object> initialVars() { return Json.deepCopyMap(initialVars); }
    public long seed() { return seed; }
    public String lockFingerprint() { return lockFingerprint; }
    public String currentState() { return currentState; }
    public Map<String, Object> vars() { return Json.deepCopyMap(vars); }
    public long rngState() { return rng.state(); }
    public List<Envelope> externalLog() { return List.copyOf(externalLog); }
    public List<Envelope> pending() { return List.copyOf(pending); }
    public List<String> processedExternalIds() { return List.copyOf(processedExternalIds); }
    public List<TraceEntry> trace() { return List.copyOf(trace); }
    public String traceHeadHash() { return traceHeadHash; }
    public long nextEventSerial() { return nextEventSerial; }
    public String parentCheckpointId() { return parentCheckpointId; }
    public String baseExternalLogFingerprint() { return baseExternalLogFingerprint; }
    public boolean merged() { return merged; }
    public long createdAt() { return createdAt; }
    public void createdAt(long value) { this.createdAt = value; }

    public void markMerged(String ancestorCheckpointId) {
        this.merged = true;
        this.parentCheckpointId = ancestorCheckpointId;
    }

    void replaceForMergedReplay(List<Envelope> fullExternalLog) {
        this.externalLog.clear();
        this.externalLog.addAll(fullExternalLog);
    }
}
