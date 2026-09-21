package io.example.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayRoomTest {
    @TempDir
    Path tempDir;

    @Test
    void sameLogicalTimeUsesSourcePriorityThenOriginalSequence() {
        MachineDefinition definition = MachineDefinition.parse(oneStateMachine());
        ReplayEngine engine = new ReplayEngine(definition, 7);
        engine.reset(List.of(
                event("b", "go", 5, "z", 10, 2),
                event("a", "go", 5, "a", 10, 1),
                event("c", "go", 5, "a", 1, 1)
        ));

        assertEquals("c", engine.step().event().id());
        assertEquals("a", engine.step().event().id());
        assertEquals("b", engine.step().event().id());
    }

    @Test
    void internalEventsQueueAfterCurrentEventAtSameLogicalTime() {
        MachineDefinition definition = MachineDefinition.parse(Map.of(
                "name", "internal",
                "version", "v1",
                "initialState", "idle",
                "states", List.of("idle", "busy"),
                "initialData", Map.of("seen", List.of()),
                "transitions", List.of(
                        transition("trigger", "idle", "busy", List.of(
                                action("emit", Map.of("eventType", "child"))
                        )),
                        transition("child", "*", "busy", List.of(
                                action("set", Map.of("path", "data.child", "value", true))
                        )),
                        transition("externalLater", "*", "busy", List.of())
                )
        ));
        ReplayEngine engine = new ReplayEngine(definition, 1);
        engine.reset(List.of(
                event("external-child", "child", 5, "api", 1, 3),
                event("trigger", "trigger", 5, "api", 1, 2),
                event("later", "externalLater", 6, "api", 1, 1)
        ));

        assertEquals("trigger", engine.step().event().id());
        ReplayEngine.StepResult second = engine.step();
        assertTrue(second.event().internal());
        assertEquals("__internal_1", second.event().id());
        assertEquals("external-child", engine.step().event().id());
        assertEquals("later", engine.step().event().id());
    }

    @Test
    void failedActionRollsBackStateChangesAndDerivedInternalEventsButKeepsFailureTrace() {
        MachineDefinition definition = MachineDefinition.parse(Map.of(
                "name", "rollback",
                "version", "v1",
                "initialState", "idle",
                "states", List.of("idle", "busy", "failed"),
                "initialData", Map.of("count", 1),
                "transitions", List.of(
                        transition("bad", "idle", "failed", List.of(
                                action("set", Map.of("path", "data.count", "value", 99)),
                                action("emit", Map.of("eventType", "child")),
                                action("fail", Map.of("message", "boom"))
                        )),
                        transition("child", "*", "busy", List.of())
                )
        ));
        ReplayEngine engine = new ReplayEngine(definition, 1);
        engine.reset(List.of(event("bad", "bad", 1, "api", 1, 1)));

        ReplayEngine.StepResult result = engine.step();

        assertEquals("failed", result.step().status());
        assertEquals("boom", result.step().error());
        assertEquals("idle", engine.replayState().currentState);
        assertEquals(1L, ((Number) engine.replayState().data.get("count")).longValue());
        assertTrue(engine.replayState().internalQueue.isEmpty());
        assertFalse(engine.canStep());
    }

    @Test
    void stepResultIncludesBeforeAfterDifferences() {
        MachineDefinition definition = MachineDefinition.parse(Map.of(
                "name", "diff",
                "version", "v1",
                "initialState", "idle",
                "states", List.of("idle", "busy"),
                "initialData", Map.of("count", 0),
                "transitions", List.of(
                        transition("go", "idle", "busy", List.of(
                                action("set", Map.of("path", "data.count",
                                        "expression", "data.count + 1"))
                        ))
                )
        ));
        ReplayEngine engine = new ReplayEngine(definition, 1);
        engine.reset(List.of(event("go", "go", 1, "api", 1, 1)));

        ReplayEngine.StepResult result = engine.step();

        assertEquals(2, result.diff().size());
        assertEquals("$.state", result.diff().get(0).path());
        assertEquals("$.data.count", result.diff().get(1).path());
        assertEquals(1L, result.after().get("data") instanceof Map<?, ?> data ? data.get("count") : null);
    }

    @Test
    void checkpointRejectsNewDefinitionFingerprint() {
        ReplayService service = service();
        Session session = service.createSession(createRequest());
        service.checkpoint(session.id, "main", "base");

        Map<String, Object> changed = new LinkedHashMap<>(Json.object(SampleData.definition()));
        changed.put("version", "demo-v2");
        changed.put("initialData", Map.of("count", 1, "accepted", 0, "log", List.of("changed")));
        Session reloaded = service.session(session.id);
        reloaded.definition = MachineDefinition.parse(changed);

        assertThrows(ReplayService.DefinitionMismatchException.class,
                () -> service.restoreCheckpoint(session.id, "main",
                        service.session(session.id).branches.get("main").checkpoints.keySet().iterator().next()));
    }

    @Test
    void mergeRejectsFirstConflictingEventGroup() {
        ReplayService service = service();
        Session session = service.createSession(createRequest());
        service.step(session.id, "main");
        Checkpoint checkpoint = service.checkpoint(session.id, "main", "before-split");
        service.restoreCheckpoint(session.id, "main", checkpoint.id());

        service.fork(session.id, Map.of(
                "sourceBranchId", "main",
                "checkpointId", checkpoint.id(),
                "branchId", "left",
                "events", List.of(eventMap("x", "start", 100, "api", 1, 1, Map.of("priority", 1)))
        ));
        service.fork(session.id, Map.of(
                "sourceBranchId", "main",
                "checkpointId", checkpoint.id(),
                "branchId", "right",
                "events", List.of(eventMap("y", "start", 100, "api", 1, 1, Map.of("priority", 2)))
        ));
        MergeResult result = service.merge(session.id, Map.of(
                "leftBranchId", "left",
                "rightBranchId", "right",
                "mergedBranchId", "should-not-exist"
        ));

        assertFalse(result.allowed());
        assertEquals(checkpoint.id(), result.commonAncestorCheckpointId());
        assertEquals("x", Json.object(result.firstConflictGroup().get(0)).get("left") == null ? null
                : Json.string(Json.object(Json.object(result.firstConflictGroup().get(0)).get("left")), "id"));
    }

    @Test
    void compatibleBranchesMergeAndExportedImportReplaysSameTraceHash() {
        ReplayService service = service();
        Session session = service.createSession(createRequest());
        service.step(session.id, "main");
        Checkpoint checkpoint = service.checkpoint(session.id, "main", "before-split");
        service.restoreCheckpoint(session.id, "main", checkpoint.id());
        service.fork(session.id, Map.of(
                "sourceBranchId", "main",
                "checkpointId", checkpoint.id(),
                "branchId", "left",
                "events", List.of(eventMap("x1", "finish", 100, "api", 1, 1, Map.of()))
        ));
        service.fork(session.id, Map.of(
                "sourceBranchId", "main",
                "checkpointId", checkpoint.id(),
                "branchId", "right",
                "events", List.of(eventMap("x2", "finish", 101, "api", 1, 1, Map.of()))
        ));
        MergeResult merge = service.merge(session.id, Map.of(
                "leftBranchId", "left",
                "rightBranchId", "right",
                "mergedBranchId", "merged"
        ));
        assertTrue(merge.allowed());

        String exported = service.exportSession(session.id);
        Session imported = service.importSession(exported);
        assertEquals(traceHash(session.id, "merged", service), traceHash(imported.id, "merged", service));
        assertNotNull(service.compare(session.id, "left", "right"));
    }

    @Test
    void persistedSessionCanContinueAfterServiceRestart() {
        Store sameStore = new Store(tempDir.resolve("restart-data"));
        ReplayService first = new ReplayService(sameStore);
        Session session = first.createSession(createRequest());
        first.step(session.id, "main");
        String hash = first.session(session.id).branches.get("main").state.trace.get(0).traceHash();

        ReplayService restarted = new ReplayService(sameStore);
        ReplayEngine.StepResult next = restarted.step(session.id, "main");

        assertEquals(1, next.step().index());
        assertEquals(hash, restarted.session(session.id).branches.get("main").state.trace.get(0).traceHash());
    }

    private ReplayService service() {
        return new ReplayService(new Store(tempDir.resolve(UUID.randomUUID().toString())));
    }

    private Map<String, Object> createRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", "session-" + UUID.randomUUID());
        request.put("seed", 42L);
        request.put("events", SampleData.events());
        request.put("definition", SampleData.definition());
        return request;
    }

    private Map<String, Object> oneStateMachine() {
        return Map.of(
                "name", "one",
                "version", "v1",
                "initialState", "idle",
                "states", List.of("idle", "busy"),
                "initialData", Map.of(),
                "transitions", List.of(transition("go", "*", "idle", List.of()))
        );
    }

    private Map<String, Object> transition(String event, String from, String to, List<Object> actions) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", event + "-" + from);
        result.put("event", event);
        result.put("from", from);
        result.put("to", to);
        result.put("actions", actions);
        return result;
    }

    private Map<String, Object> action(String type, Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.putAll(values);
        return result;
    }

    private StoredEvent event(String id, String type, long time, String source, int priority, long seq) {
        return StoredEvent.external(eventMap(id, type, time, source, priority, seq, Map.of()), seq);
    }

    private Map<String, Object> eventMap(String id, String type, long time, String source,
                                         int priority, long seq, Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("type", type);
        result.put("time", time);
        result.put("source", source);
        result.put("priority", priority);
        result.put("originalSeq", seq);
        result.put("payload", payload);
        return result;
    }

    private String traceHash(String sessionId, String branchId, ReplayService service) {
        return Json.object(service.sessionView(service.session(sessionId))).get("branches") instanceof List<?> list ? list.stream()
                .map(Json::object)
                .filter(branch -> branchId.equals(branch.get("id")))
                .map(branch -> Json.string(branch, "traceHash"))
                .findFirst().orElseThrow() : null;
    }
}
