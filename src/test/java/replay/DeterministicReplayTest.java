package replay;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic replay scenarios driven through the public store API.
 * Nothing here sleeps; the logical clock is purely a field on each event.
 */
class DeterministicReplayTest extends AbstractStoreTest {

    private Map<String, Object> simpleDefinition() {
        return def("7", "s0", List.of(
                transition("go", "s0", "go", "s1", "counter < 10", List.of(
                        inc("counter"),
                        log("=\"handled #\" + counter"))),
                transition("chain", "s1", "go", "s0", "payload.chain == true", List.of(
                        emit("inner", Map.of("via", "=payload.src")))),
                transition("stay", "s1", "go", "s1", "payload.chain != true", List.of()),
                transition("inner0", "s0", "inner", "s2", null, List.of(
                        set("via", "=payload.via"))),
                transition("inner1", "s1", "inner", "s2", null, List.of(
                        set("via", "=payload.via"))),
                transition("boom", "s0", "boom", "dead", null, List.of(
                        inc("counter"),
                        emit("never", Map.of()),
                        fail("=\"forced failure at t=\" + $eventTime")))));
    }

    private static String eventId(Map<String, Object> stepResponse) {
        return String.valueOf(eventField(stepResponse).get("id"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> eventField(Map<String, Object> stepResponse) {
        return (Map<String, Object>) ((Map<?, ?>) stepResponse.get("node")).get("event");
    }

    private static String stateAfter(Map<String, Object> stepResponse) {
        return String.valueOf(((Map<?, ?>) stepResponse.get("node")).get("stateAfter"));
    }

    private static String eventIdOf(Map<String, Object> node) {
        return String.valueOf(((Map<?, ?>) node.get("event")).get("id"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> traceNodes(ProjectStore store, String branch) {
        return (List<Map<String, Object>>) store.trace(branch).get("nodes");
    }

    @Test
    void sameLogicalTimeIsOrderedByPriorityThenSeq() {
        ProjectStore store = newStore();
        store.updateDefinition(simpleDefinition());
        // Both at time 5: lower priority value wins, insertion order ignored;
        // within one priority seq decides.
        store.importEvents(List.of(
                event("late", "go", 5, 1, 9),
                event("first", "go", 5, 0, 2),
                event("second", "go", 5, 0, 1)), "replace");

        assertEquals("second", eventId(store.step("main")));
        assertEquals("first", eventId(store.step("main")));
        assertEquals("late", eventId(store.step("main")));
    }

    @Test
    void internalEventsQueueAfterCurrentEventBeforeNextExternal() {
        ProjectStore store = newStore();
        store.updateDefinition(simpleDefinition());
        store.importEvents(List.of(
                event("g1", "go", 1, 0, 1),
                // The internal "inner" spawned while processing g2 must run
                // before this later external event can matter.
                event("g2", "go", 2, 0, 2, Map.of("src", "unit-9", "chain", true)),
                event("boom", "boom", 3, 0, 3)), "replace");

        store.step("main"); // g1: s0 -> s1
        assertEquals("g2", eventId(store.step("main"))); // g2: s1 -> s0, emits inner
        Map<String, Object> internalStep = store.step("main"); // queued inner: s0 -> s2
        Map<?, ?> node = (Map<?, ?>) internalStep.get("node");
        assertEquals(Boolean.TRUE, node.get("internal"));
        assertEquals("s2", node.get("stateAfter"));
        assertEquals(0, ((Number) branch(store, "main").get("internalQueue")).intValue());
        assertEquals("unit-9", ((Map<?, ?>) branch(store, "main").get("vars")).get("via"));

        List<Map<String, Object>> nodes = traceNodes(store, "main");
        assertEquals("in:g2:0", eventIdOf(nodes.get(nodes.size() - 1)));
    }

    @Test
    void failedActionRollsBackStateAndSpawnedEventsButLeavesTrace() {
        ProjectStore store = newStore();
        store.updateDefinition(simpleDefinition());
        store.importEvents(List.of(
                event("boom", "boom", 1, 0, 1),
                event("g1", "go", 2, 0, 2)), "replace");
        store.run("main", 100);

        Map<String, Object> status = branch(store, "main");
        // The increment inside the failed transition is rolled back; g1 later
        // produces the only successful counter bump.
        assertEquals(1, ((Number) ((Map<?, ?>) status.get("vars")).get("counter")).intValue());
        assertEquals(Boolean.TRUE, status.get("complete"));
        assertEquals("s1", status.get("stateName"));

        Map<String, Object> failureNode = traceNodes(store, "main").stream()
                .filter(n -> Boolean.TRUE.equals(n.get("rolledBack")))
                .findFirst().orElseThrow(() -> new AssertionError("expected failure node"));
        assertNotNull(failureNode.get("failure"));
        assertEquals(0, ((List<?>) failureNode.get("spawned")).size());
        assertEquals(0, ((List<?>) failureNode.get("diff")).size());
    }

    @Test
    void checkpointRejectsOldDefinition() {
        ProjectStore store = newStore();
        store.updateDefinition(simpleDefinition());
        store.importEvents(List.of(event("g1", "go", 1, 0, 1)), "replace");
        store.step("main");
        Map<String, Object> checkpoint = store.createCheckpoint("main", "cp1");
        assertEquals(Boolean.TRUE, checkpoint.get("compatible"));

        // Same shape, different seed => new definition fingerprint.
        Map<String, Object> changed = simpleDefinition();
        changed.put("seed", 999);
        store.updateDefinition(changed);

        ProjectStore.DefinitionMismatchException error = assertThrows(
                ProjectStore.DefinitionMismatchException.class,
                () -> store.fork("main", "from-old-checkpoint", "cp1"));
        assertTrue(error.getMessage().contains("old checkpoint"));

        // Forking main at its current (new-definition) head still works.
        store.fork("main", "fresh", null);
        assertNotNull(branch(store, "fresh"));
    }

    @Test
    void divergentSameTimeEventsConflictAndPointAtFirstGroup() {
        ProjectStore store = newStore();
        store.updateDefinition(simpleDefinition());
        store.importEvents(List.of(
                event("g1", "go", 1, 0, 1),
                event("a", "go", 2, 0, 2),
                event("b", "go", 2, 1, 3)), "replace");
        store.step("main"); // common ancestor after g1
        store.fork("main", "branch-a", null);
        store.fork("main", "branch-b", null);

        store.step("branch-a");
        store.drop("branch-a", "b");
        store.drop("branch-b", "a");
        store.step("branch-b");

        ProjectStore.ConflictException conflict = assertThrows(
                ProjectStore.ConflictException.class,
                () -> store.merge("branch-a", "branch-b", "merged-bad"));
        assertEquals(2L, conflict.detail.get("logicalTime"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groupA =
                (List<Map<String, Object>>) conflict.detail.get("eventsA");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groupB =
                (List<Map<String, Object>>) conflict.detail.get("eventsB");
        assertEquals(1, groupA.size());
        assertEquals(1, groupB.size());
        assertTrue(conflict.detail.get("reason").toString().contains("ambiguous"));
    }

    @Test
    void identicalBranchesMergeAndMatchStateHash() {
        ProjectStore store = newStore();
        store.updateDefinition(simpleDefinition());
        store.importEvents(List.of(
                event("g1", "go", 1, 0, 1),
                event("a", "go", 2, 0, 2),
                event("b", "go", 2, 1, 3)), "replace");
        store.step("main");
        store.fork("main", "branch-a", null);
        store.fork("main", "branch-c", null);
        store.step("branch-a");
        store.drop("branch-a", "b");
        store.step("branch-c");
        store.drop("branch-c", "b");

        Map<String, Object> merged = store.merge("branch-a", "branch-c", "merged-ok");
        assertEquals(Boolean.TRUE, merged.get("merged"));
        @SuppressWarnings("unchecked")
        Map<String, Object> mergedBranch = (Map<String, Object>) merged.get("branch");
        assertEquals(branch(store, "branch-a").get("stateHash"),
                mergedBranch.get("stateHash"));
    }

    @Test
    void oneSidedLaterEventsMergeUnambiguously() {
        ProjectStore store = newStore();
        store.updateDefinition(simpleDefinition());
        store.importEvents(List.of(
                event("g1", "go", 1, 0, 1),
                event("a", "go", 2, 0, 2),
                event("b", "go", 3, 0, 3)), "replace");
        store.step("main"); // ancestor after g1
        store.fork("main", "ba", null);
        store.fork("main", "bb", null);
        store.step("ba");
        store.step("bb");
        store.step("bb"); // b only on bb, later logical time -> unambiguous

        Map<String, Object> merged = store.merge("ba", "bb", "joined");
        assertEquals(Boolean.TRUE, merged.get("merged"));
        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) merged.get("externalOrder");
        assertEquals(List.of("a", "b"), order);
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) merged.get("branch");
        assertEquals(Boolean.TRUE, status.get("complete"));
    }

    @Test
    void hashesSurviveRestartAndExportImport() {
        ProjectStore store = newStore();
        store.updateDefinition(simpleDefinition());
        store.importEvents(List.of(
                event("g1", "go", 1, 0, 1),
                event("g2", "go", 2, 0, 2),
                event("boom", "boom", 3, 0, 3)), "replace");
        store.run("main", 50);
        String stateHash = String.valueOf(branch(store, "main").get("stateHash"));
        String traceHash = String.valueOf(branch(store, "main").get("traceHash"));

        // Restart: re-read the same file from disk.
        ProjectStore restarted = newStore();
        assertEquals(stateHash, branch(restarted, "main").get("stateHash"));
        assertEquals(traceHash, branch(restarted, "main").get("traceHash"));

        // Export -> import into a fresh database yields identical hashes.
        Map<String, Object> exported = store.exportSession();
        ProjectStore imported = new ProjectStore(tempDir.resolve("other.json"));
        imported.importSession(exported);
        assertEquals(stateHash, branch(imported, "main").get("stateHash"));
        assertEquals(traceHash, branch(imported, "main").get("traceHash"));
        assertEquals(traceHash, imported.trace("main").get("traceHash"));
    }

    @Test
    void seedLocksRandomDrawsAndFingersChangeWhenSeedChanges() {
        Map<String, Object> definition = def("123", "s0", List.of(
                transition("draw", "s0", "draw", "s1", null, List.of(
                        set("pick", "=random(1000000)")))));
        ProjectStore store = newStore();
        store.updateDefinition(definition);
        store.importEvents(List.of(event("d", "draw", 1, 0, 1)), "replace");
        store.step("main");
        Object firstPick = ((Map<?, ?>) branch(store, "main").get("vars")).get("pick");

        ProjectStore again = new ProjectStore(tempDir.resolve("again.json"));
        again.updateDefinition(definition);
        again.importEvents(List.of(event("d", "draw", 1, 0, 1)), "replace");
        again.step("main");
        Object secondPick = ((Map<?, ?>) branch(again, "main").get("vars")).get("pick");
        assertEquals(firstPick, secondPick);

        Map<String, Object> otherSeed = new LinkedHashMap<>(definition);
        otherSeed.put("seed", 124);
        again.updateDefinition(otherSeed);
        again.importEvents(List.of(event("d", "draw", 1, 0, 1)), "replace");
        again.step("main");
        Object thirdPick = ((Map<?, ?>) branch(again, "main").get("vars")).get("pick");
        assertNotEquals(firstPick, thirdPick);
    }
}
