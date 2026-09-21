package replayroom.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import replayroom.engine.BranchMerger;
import replayroom.engine.CompiledDefinition;
import replayroom.engine.MergeConflict;
import replayroom.engine.MergeResult;
import replayroom.engine.Session;
import replayroom.engine.Store;
import replayroom.json.Json;
import replayroom.model.Checkpoint;
import replayroom.model.Definition;
import replayroom.model.Envelope;
import replayroom.model.TraceEntry;

/** Stateless service layer used by the HTTP handlers. */
public final class ApiService {

    private final Store store;

    public ApiService(Store store) {
        this.store = store;
    }

    public Map<String, Object> createDefinition(Map<String, Object> body) {
        Definition definition = Definition.fromMap(body);
        CompiledDefinition.compile(definition);
        store.saveDefinition(definition);
        return definition.toMap();
    }

    public Map<String, Object> listDefinitions() {
        List<Object> items = new ArrayList<>();
        for (Definition definition : store.definitions()) items.add(definition.toMap());
        return Json.obj("definitions", items);
    }

    public Map<String, Object> getDefinition(String fingerprint) {
        Definition definition = store.definition(fingerprint);
        if (definition == null) throw new NotFoundException("definition", fingerprint);
        return definition.toMap();
    }

    public Map<String, Object> createSession(Map<String, Object> body) {
        String fingerprint = Json.string(body.get("definitionFingerprint"), "definitionFingerprint");
        CompiledDefinition compiled = store.compiled(fingerprint);
        long seed = Json.optLong(body, "seed", 0L);
        String name = Json.optString(body, "name", "session");
        List<Envelope> events = parseEvents(body, "events");
        Session session = Session.create(newId("sess"), name, compiled, seed, events);
        store.saveSession(session);
        return sessionView(session, false);
    }

    public Map<String, Object> getSession(String id) {
        Session session = requireSession(id);
        return sessionView(session, true);
    }

    public Map<String, Object> listSessions() {
        List<Object> items = new ArrayList<>();
        for (Session session : store.sessions()) items.add(sessionSummary(session));
        return Json.obj("sessions", items);
    }

    public Map<String, Object> step(String id, Map<String, Object> body) {
        Session session = requireSession(id);
        int count = body == null ? 1 : (int) Json.optLong(body, "count", 1L);
        if (count <= 0) throw new BadRequestException("count must be positive");
        CompiledDefinition compiled = store.compiled(session.definitionFingerprint());
        TraceEntry last = null;
        int executed = 0;
        for (int i = 0; i < count && session.hasPending(); i++) {
            last = session.step(compiled);
            executed++;
        }
        store.saveSession(session);
        Map<String, Object> result = sessionView(session, true);
        result.put("executedSteps", executed);
        if (last != null) result.put("lastStep", last.toMap());
        return result;
    }

    public Map<String, Object> appendEvents(String id, Map<String, Object> body) {
        Session session = requireSession(id);
        List<Envelope> events = parseEvents(body, "events");
        session.appendExternalEvents(events);
        store.saveSession(session);
        return sessionView(session, true);
    }

    public Map<String, Object> createCheckpoint(String sessionId, Map<String, Object> body) {
        Session session = requireSession(sessionId);
        String checkpointId = newId("cp");
        Checkpoint checkpoint = session.checkpoint(checkpointId);
        store.saveCheckpoint(checkpoint);
        return checkpoint.toMap();
    }

    public Map<String, Object> fork(String checkpointId, Map<String, Object> body) {
        Checkpoint checkpoint = store.getCheckpoint(checkpointId);
        if (checkpoint == null) throw new NotFoundException("checkpoint", checkpointId);
        String fingerprint = Json.string(body.get("definitionFingerprint"), "definitionFingerprint");
        CompiledDefinition compiled = store.compiled(fingerprint);
        Session forked;
        try {
            forked = Session.fromCheckpoint(newId("sess"),
                    Json.optString(body, "name", "branch"), checkpoint, compiled,
                    Json.optLong(body, "seed", 0L));
        } catch (Session.DefinitionMismatchException e) {
            throw new DefinitionMismatchHttpException(e);
        }
        if (body.containsKey("events")) {
            forked.appendExternalEvents(parseEvents(body, "events"));
        }
        store.saveSession(forked);
        return sessionView(forked, true);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> mergeSessions(Map<String, Object> body) {
        String aId = Json.string(body.get("a"), "a");
        String bId = Json.string(body.get("b"), "b");
        Session a = requireSession(aId);
        Session b = requireSession(bId);
        BranchMerger merger = new BranchMerger(store);
        Object result = merger.merge(newId("sess"), Json.optString(body, "name", "merged"), a, b);
        if (result instanceof MergeConflict conflict) {
            Map<String, Object> payload = Json.obj("ok", false, "conflict", conflict.toMap());
            throw new ConflictException(payload);
        }
        MergeResult mergeResult = (MergeResult) result;
        store.saveSession(mergeResult.session());
        return sessionView(mergeResult.session(), true);
    }

    public Map<String, Object> compare(String aId, String bId) {
        Session a = requireSession(aId);
        Session b = requireSession(bId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("a", sessionSummary(a));
        result.put("b", sessionSummary(b));
        result.put("stateDiff", diff(a.vars(), b.vars()));
        result.put("currentState", Json.obj("a", a.currentState(), "b", b.currentState()));
        result.put("outputs", Json.obj(
                "a", collectOutputs(a),
                "b", collectOutputs(b)));
        result.put("traceHeadHash", Json.obj("a", nullSafe(a.traceHeadHash()), "b", nullSafe(b.traceHeadHash())));
        return result;
    }

    public Map<String, Object> exportSession(String id) {
        Session session = requireSession(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("format", "sm-session-export/v1");
        payload.put("session", session.toMap(false));
        Definition definition = store.definition(session.definitionFingerprint());
        payload.put("definition", definition == null ? null : stripFingerprint(definition.toMap()));
        return payload;
    }

    public Map<String, Object> importSession(Map<String, Object> body) {
        String format = Json.optString(body, "format", "");
        if (!"sm-session-export/v1".equals(format)) {
            throw new BadRequestException("Unsupported export format: " + format);
        }
        Map<String, Object> sessionMap = Json.object(body.get("session"), "session");
        if (body.get("definition") instanceof Map<?, ?> definitionMap) {
            Definition definition = Definition.fromMap(Json.object(definitionMap, "definition"));
            if (!definition.fingerprint().equals(Json.string(sessionMap.get("definitionFingerprint"),
                    "session.definitionFingerprint"))) {
                throw new BadRequestException("Exported definition does not match locked session fingerprint");
            }
            if (store.definition(definition.fingerprint()) == null) {
                store.saveDefinition(definition);
            }
        }
        Session session = Session.fromMap(sessionMap);
        String claimedLock = session.lockFingerprint();
        CompiledDefinition compiled = store.compiled(session.definitionFingerprint());
        String recomputedLock = Session.lockFingerprint(
                session.definitionFingerprint(),
                session.initialState(),
                session.initialVars(),
                session.externalLog().stream().map(Envelope::toMap).toList(),
                session.seed());
        if (!claimedLock.equals(recomputedLock)) {
            throw new BadRequestException("Session lock fingerprint is not reproducible from the import payload");
        }
        verifyTraceChain(session, compiled);
        String newId = newId("sess");
        session.id(newId);
        session.name(session.name() + " (imported)");
        store.saveSession(session);
        return sessionView(session, true);
    }

    private void verifyTraceChain(Session session, CompiledDefinition compiled) {
        String parent = null;
        for (TraceEntry entry : session.trace()) {
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("version", "sm-trace/v1");
            chain.put("lock", session.lockFingerprint());
            chain.put("parent", parent);
            chain.put("entry", entry.hashBody());
            String expected = replayroom.engine.Hashes.sha256Hex(Json.canonical(chain));
            if (!expected.equals(entry.hash())) {
                throw new BadRequestException("Imported trace hash mismatch at step " + entry.step());
            }
            parent = entry.hash();
        }
        if (session.traceHeadHash() != null && !session.traceHeadHash().equals(parent)) {
            throw new BadRequestException("Imported trace head hash does not match the hash chain");
        }
    }

    private static Map<String, Object> stripFingerprint(Map<String, Object> map) {
        Map<String, Object> copy = new LinkedHashMap<>(map);
        copy.remove("fingerprint");
        return copy;
    }

    private static List<Object> collectOutputs(Session session) {
        List<Object> outputs = new ArrayList<>();
        for (TraceEntry entry : session.trace()) {
            outputs.addAll(entry.toMap().get("outputs") instanceof List<?> list ? list : List.of());
        }
        return outputs;
    }

    private List<Envelope> parseEvents(Map<String, Object> body, String key) {
        List<Object> raw = Json.optList(body, key);
        List<Envelope> events = new ArrayList<>();
        long autoSeq = 0;
        for (Object value : raw) {
            Map<String, Object> map = Json.object(value, key + "[]");
            String name = Json.string(map.get("name"), "event.name");
            String source = Json.optString(map, "source", "external");
            long time = Json.optLong(map, "time", 0L);
            long seq = map.containsKey("seq") ? Json.longValue(map.get("seq"), "event.seq") : autoSeq;
            int priority = (int) Json.optLong(map, "priority", 0L);
            Map<String, Object> data = Json.optObject(map, "data");
            String providedId = Json.optString(map, "id", null);
            String id = providedId != null ? providedId : "ext-" + time + "-" + source + "-" + seq;
            events.add(Envelope.external(id, name, source, time, seq, priority, data));
            autoSeq++;
        }
        return events;
    }

    private Session requireSession(String id) {
        Session session = store.session(id);
        if (session == null) throw new NotFoundException("session", id);
        return session;
    }

    private static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> sessionSummary(Session session) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", session.id());
        map.put("name", session.name());
        map.put("definitionFingerprint", session.definitionFingerprint());
        map.put("lockFingerprint", session.lockFingerprint());
        map.put("currentState", session.currentState());
        map.put("steps", session.trace().size());
        map.put("pending", session.pendingCount());
        map.put("traceHeadHash", nullSafe(session.traceHeadHash()));
        map.put("parentCheckpointId", session.parentCheckpointId());
        map.put("merged", session.merged());
        return map;
    }

    private Map<String, Object> sessionView(Session session, boolean detail) {
        Map<String, Object> map = sessionSummary(session);
        map.put("seed", session.seed());
        map.put("vars", session.vars());
        map.put("state", session.currentState());
        map.put("definitionName", session.definitionName());
        map.put("hasPending", session.hasPending());
        if (detail) {
            List<Object> trace = new ArrayList<>();
            for (TraceEntry entry : session.trace()) trace.add(entry.toMap());
            map.put("trace", trace);
            List<Object> pending = new ArrayList<>();
            for (Envelope envelope : session.pending()) pending.add(envelope.toMap());
            map.put("pendingEvents", pending);
        }
        return map;
    }

    private static Map<String, Object> diff(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> added = new LinkedHashMap<>();
        Map<String, Object> removed = new LinkedHashMap<>();
        Map<String, Object> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : b.entrySet()) {
            if (!a.containsKey(entry.getKey())) {
                added.put(entry.getKey(), entry.getValue());
            } else if (!JsonEquals.values(a.get(entry.getKey()), entry.getValue())) {
                changed.put(entry.getKey(), Json.obj("a", a.get(entry.getKey()), "b", entry.getValue()));
            }
        }
        for (Map.Entry<String, Object> entry : a.entrySet()) {
            if (!b.containsKey(entry.getKey())) removed.put(entry.getKey(), entry.getValue());
        }
        return Json.obj("added", added, "removed", removed, "changed", changed);
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String kind, String id) {
            super(kind + " not found: " + id);
        }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) { super(message); }
    }

    public static class DefinitionMismatchHttpException extends RuntimeException {
        final Session.DefinitionMismatchException cause;
        public DefinitionMismatchHttpException(Session.DefinitionMismatchException cause) {
            super(cause.getMessage());
            this.cause = cause;
        }
    }

    public static class ConflictException extends RuntimeException {
        final Map<String, Object> payload;
        public ConflictException(Map<String, Object> payload) {
            super("merge conflict");
            this.payload = payload;
        }
    }
}
