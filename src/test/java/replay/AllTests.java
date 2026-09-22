package replay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import replay.engine.Definition;
import replay.engine.ReplayEngine;
import replay.json.Json;
import replay.json.JsonUtil;
import replay.store.ProjectStore;

public final class AllTests {
    private static int passed;

    public static void main(String[] args) throws Exception {
        sameLogicalTimeIsStable();
        internalEventsQueueAfterCurrentEvent();
        actionFailureRollsBackStateAndDerivedEvents();
        checkpointRejectsDifferentDefinitionFingerprint();
        branchMergeRejectsFirstAmbiguousConflict();
        persistenceAndExportedSessionReplaysSameHash();
        System.out.println("All deterministic replay tests passed: " + passed);
    }

    private static void sameLogicalTimeIsStable() {
        ReplayEngine engine = new ReplayEngine(Definition.sample());
        engine.enqueueExternal(List.of(
                event("low-1", 1, "sensor", 1, "start"),
                event("high-1", 1, "operator", 1, "start")));
        var first = engine.step();
        assertEqual("high-1", JsonUtil.object(first.record().get("event"), "event").get("id"),
                "higher source priority wins at the same logical time");
        engine.step();
        ReplayEngine repeated = new ReplayEngine(Definition.sample());
        repeated.enqueueExternal(List.of(
                event("high-1", 1, "operator", 1, "start"),
                event("low-1", 1, "sensor", 1, "start")));
        repeated.step();
        repeated.step();
        assertEqual(engine.trajectoryHash(), repeated.trajectoryHash(), "import order must not change trajectory");
        passed++;
    }

    private static void internalEventsQueueAfterCurrentEvent() {
        Map<String, Object> definition = definition("""
                {
                  "definitionVersion": "internal-v1",
                  "initialState": "on",
                  "initialData": {"score": 0},
                  "randomSeed": 7,
                  "sourcePriorities": {"external": 10, "internal": 1},
                  "states": ["on"],
                  "transitions": [
                    {
                      "from": "on", "event": "tick", "to": "on",
                      "actions": [
                        {"type": "increment", "path": "data.score", "amount": 1},
                        {"type": "emit", "event": "alpha", "source": "internal", "data": {"n": 1}}
                      ]
                    },
                    {"from": "on", "event": "alpha", "to": "on",
                      "actions": [{"type": "output", "name": "alpha-output"}]}
                  ]
                }
                """);
        ReplayEngine engine = new ReplayEngine(definition);
        engine.enqueueExternal(List.of(
                event("tick-1", 1, "external", 1, "tick"),
                event("tick-2", 1, "external", 2, "tick")));
        var first = engine.step();
        assertEqual("external", kind(first, 0), "first event kind");
        Map<String, Object> firstAfter = JsonUtil.object(first.record().get("after"), "after");
        Map<String, Object> firstData = JsonUtil.object(firstAfter.get("data"), "after data");
        assertEqual(1L, JsonUtil.integer(firstData.get("score"), "score"), "current event completes before internal event");
        var second = engine.step();
        assertEqual("internal", kind(second, 0), "derived internal event runs before later external event");
        assertEqual("tick-1.i1", JsonUtil.object(second.record().get("event"), "event").get("id"), "internal event parent");
        passed++;
    }

    private static void actionFailureRollsBackStateAndDerivedEvents() {
        Map<String, Object> definition = Definition.sample();
        ReplayEngine engine = new ReplayEngine(definition);
        engine.enqueueExternal(List.of(
                event("operator-start", 1, "operator", 1, "start"),
                event("panic", 2, "timer", 1, "panic", Map.of("hard", true)),
                event("later-tick", 3, "timer", 2, "tick")));
        engine.step();
        var failed = engine.step();
        assertEqual(true, failed.failure() != null, "failure must be recorded");
        Map<String, Object> after = JsonUtil.object(failed.record().get("after"), "after");
        assertEqual("running", after.get("state"), "state rolls back");
        assertEqual(true, JsonUtil.list(failed.record().get("derivedInternalEvents"), "internal").isEmpty(),
                "derived internal events roll back");
        assertEqual(true, JsonUtil.list(failed.record().get("outputs"), "outputs").isEmpty(),
                "outputs from failed transaction roll back");
        assertEqual(true, failed.failure().contains("transactional"), "failure remains in trace");
        passed++;
    }

    private static void checkpointRejectsDifferentDefinitionFingerprint() {
        Map<String, Object> definition = Definition.sample();
        ReplayEngine engine = new ReplayEngine(definition);
        engine.enqueueExternal(List.of(event("start", 1, "operator", 1, "start")));
        engine.step();
        Map<String, Object> checkpoint = engine.checkpoint("cp", "main");
        Map<String, Object> changed = JsonUtil.object(JsonUtil.deepCopy(definition), "changed definition");
        changed.put("randomSeed", 20260924L);
        boolean rejected = false;
        try {
            ReplayEngine.fromCheckpoint(changed, checkpoint);
        } catch (ReplayEngine.DefinitionMismatchException expected) {
            rejected = true;
        }
        assertEqual(true, rejected, "old checkpoint cannot attach to new definition");
        passed++;
    }

    private static void branchMergeRejectsFirstAmbiguousConflict() throws Exception {
        Path directory = Files.createTempDirectory("replay-merge-");
        ProjectStore store = new ProjectStore(directory);
        Map<String, Object> definition = Definition.sample();
        store.createProject("merge", definition, List.of(event("start", 1, "operator", 1, "start")), "log");
        store.step("merge", "main", 1);
        store.checkpoint("merge", "main", "base");
        store.fork("merge", "main", currentCheckpoint(store, "merge", "main"), "a", "a");
        store.fork("merge", "main", currentCheckpoint(store, "merge", "main"), "b", "b");
        store.appendEvents("merge", "a", List.of(event("a-tick", 5, "sensor", 9, "tick")));
        store.appendEvents("merge", "b", List.of(event("b-tick", 5, "sensor", 9, "tick")));
        store.step("merge", "a", 1);
        store.step("merge", "b", 1);
        Map<String, Object> merged = store.merge("merge", "a", "b", null, null, "merged", "merged");
        assertEqual(false, merged.get("ok"), "merge must reject ambiguous event order");
        List<?> conflictEvents = JsonUtil.list(JsonUtil.object(merged.get("conflict"), "conflict").get("firstConflictEvents"),
                "firstConflictEvents");
        assertEqual(2, conflictEvents.size(), "conflict event group size");
        passed++;
    }

    private static void persistenceAndExportedSessionReplaysSameHash() throws Exception {
        Path directory = Files.createTempDirectory("replay-persist-");
        ProjectStore first = new ProjectStore(directory);
        first.createProject("persist", Definition.sample(), List.of(
                event("sensor-1", 1, "sensor", 1, "tick"),
                event("operator-1", 1, "operator", 1, "start"),
                event("sensor-2", 2, "sensor", 1, "tick"),
                event("panic", 3, "timer", 1, "panic", Map.of("hard", true))), "log");
        first.step("persist", "main", 4);
        String originalHash = JsonUtil.object(first.project("persist", "main").get("currentBranch"), "branch")
                .get("state") != null ? hash(first.project("persist", "main")) : null;
        first.checkpoint("persist", "main", "after-four");

        ProjectStore restarted = new ProjectStore(directory);
        assertEqual(originalHash, hash(restarted.project("persist", "main")), "restart resumes same trajectory");

        Path importDirectory = Files.createTempDirectory("replay-import-");
        Map<String, Object> session = first.exportSession("persist");
        ProjectStore importedStore = new ProjectStore(importDirectory);
        Map<String, Object> imported = importedStore.importSession(session, "persist-copy");
        assertEqual(originalHash, hash(imported), "export/import replay hash");
        Map<String, Object> reexported = importedStore.exportSession("persist-copy");
        assertEqual(session.get("sessionFingerprint"), reexported.get("sessionFingerprint"), "session fingerprint");
        passed++;
    }

    private static Map<String, Object> event(String id, long time, String source, long seq, String event) {
        return event(id, time, source, seq, event, Map.of());
    }

    private static Map<String, Object> event(String id, long time, String source, long seq, String event,
                                             Map<String, Object> data) {
        return JsonUtil.object(Json.parse("""
                {
                  "id": "%s",
                  "logicalTime": %d,
                  "source": "%s",
                  "originalSeq": %d,
                  "event": "%s",
                  "data": %s
                }
                """.formatted(id, time, source, seq, event, Json.write(data))), "event");
    }

    private static Map<String, Object> definition(String json) {
        return JsonUtil.object(Json.parse(json), "definition");
    }

    private static String kind(ReplayEngine.StepResult step, int ignored) {
        return String.valueOf(JsonUtil.object(step.record().get("event"), "event").get("kind"));
    }

    private static String currentCheckpoint(ProjectStore store, String projectId, String branchId) {
        Map<String, Object> project = store.project(projectId, branchId);
        Map<String, Object> branch = JsonUtil.object(project.get("currentBranch"), "branch");
        return String.valueOf(branch.get("currentCheckpointId"));
    }

    private static String hash(Map<String, Object> projectView) {
        Map<String, Object> branch = JsonUtil.object(projectView.get("currentBranch"), "currentBranch");
        Map<String, Object> state = JsonUtil.object(branch.get("state"), "state");
        return String.valueOf(state.get("trajectoryHash"));
    }

    private static void assertEqual(Object expected, Object actual, String message) {
        boolean equal = expected instanceof Number && actual instanceof Number
                ? ((Number) expected).doubleValue() == ((Number) actual).doubleValue()
                : expected.equals(actual);
        if (!equal) {
            throw new AssertionError(message + "\nexpected: " + expected + "\nactual:   " + actual);
        }
    }
}
