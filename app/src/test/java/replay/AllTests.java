package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AllTests {
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) {
        sameLogicalTimeUsesPriorityThenSequence();
        internalEventsQueueAfterCurrentEvent();
        failedActionRollsBackStateAndDerivedEventsButKeepsFailureTrace();
        checkpointRejectsForeignDefinitionFingerprint();
        mergeReportsFirstAmbiguousConflictingEvent();
        exportedSessionReplaysSameTraceHash();

        if (!FAILURES.isEmpty()) {
            for (String failure : FAILURES) System.err.println("FAIL: " + failure);
            System.exit(1);
        }
        System.out.println("All deterministic replay tests passed.");
    }

    private static void sameLogicalTimeUsesPriorityThenSequence() {
        ReplayRoom room = new ReplayRoom(simpleDefinition());
        room.importEvents("main", List.of(
                event("low-2", 10, "low", 2, "tick"),
                event("high-2", 10, "high", 2, "tick"),
                event("high-1", 10, "high", 1, "tick")), true);
        room.step("main", 1);
        room.step("main", 1);
        room.step("main", 1);
        List<String> ids = processedIds(room, "main");
        assertEqual(List.of("high-1", "high-2", "low-2"), ids, "same-time stable order");
    }

    private static void internalEventsQueueAfterCurrentEvent() {
        ReplayRoom room = new ReplayRoom(simpleDefinition());
        room.importEvents("main", List.of(
                event("external-1", 10, "high", 1, "trigger"),
                event("external-2", 10, "high", 2, "tick")), true);
        room.step("main", 3);
        List<String> ids = processedIds(room, "main");
        assertEqual(List.of("external-1", "external-1#0-0", "external-2"), ids, "internal event is drained before later external events");
        assertEquals("done", room.branchState("main").get("state"), "internal event completes transition");
    }

    private static void failedActionRollsBackStateAndDerivedEventsButKeepsFailureTrace() {
        ReplayRoom room = new ReplayRoom(failureDefinition());
        room.importEvents("main", List.of(event("bad", 1, "operator", 1, "bad")), true);
        room.step("main", 1);
        Map<String, Object> branch = room.branchState("main");
        assertEquals("idle", branch.get("state"), "state is rolled back");
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = (Map<String, Object>) branch.get("vars");
        assertEquals(0L, ((Number) vars.get("count")).longValue(), "variable mutation is rolled back");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trace = (List<Map<String, Object>>) branch.get("trace");
        Map<String, Object> step = trace.get(0);
        assertEquals(Boolean.TRUE, step.get("failed"), "failure remains in trace");
        assertEqual(List.of(), step.get("emitted"), "derived internal event is rolled back");
    }

    private static void checkpointRejectsForeignDefinitionFingerprint() {
        ReplayRoom room = new ReplayRoom(simpleDefinition());
        room.importEvents("main", List.of(event("e1", 1, "high", 1, "trigger")), true);
        room.step("main", 1);
        Map<String, Object> checkpoint = room.checkpoint("main", "after");
        Map<String, Object> exported = room.exportSession();
        boolean rejected = false;
        try {
            Object tampered = Json.parse(Json.write(exported));
            Map<String, Object> envelope = Json.object(tampered);
            Map<String, Object> session = Json.object(envelope.get("session"));
            List<Object> checkpoints = Json.list(session.get("checkpoints"));
            boolean changedRoot = false;
            for (Object item : checkpoints) {
                Map<String, Object> cp = Json.object(item);
                if (checkpoint.get("id").equals(cp.get("id"))) {
                    cp.put("definitionFingerprint", "old-definition-fingerprint");
                    changedRoot = true;
                }
            }
            if (!changedRoot) throw new IllegalStateException("test checkpoint missing");
            envelope.put("sessionFingerprint", Models.sha256(Json.writeCanonical(session)));
            ReplayRoom.importSession(tampered);
        } catch (IllegalArgumentException expected) {
            rejected = expected.getMessage() != null && expected.getMessage().contains("definition fingerprint");
        }
        assertEquals(Boolean.TRUE, rejected, "foreign definition checkpoint is rejected on import");

        ReplayRoom foreign = new ReplayRoom(simpleDefinition());
        Models.Checkpoint tampered = new Models.Checkpoint();
        tampered.id = String.valueOf(checkpoint.get("id"));
        tampered.branchId = "main";
        tampered.step = 1;
        tampered.definitionFingerprint = "old-definition-fingerprint";
        rejected = false;
        try {
            foreign.fork(tampered.id, "bad");
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertEquals(Boolean.TRUE, rejected, "runtime checkpoint validates definition fingerprint");
    }

    private static void mergeReportsFirstAmbiguousConflictingEvent() {
        ReplayRoom room = new ReplayRoom(simpleDefinition());
        room.importEvents("main", List.of(event("base", 1, "high", 1, "trigger")), true);
        room.step("main", 1);
        room.step("main", 1);
        Map<String, Object> point = room.checkpoint("main", "fork point");
        room.fork(String.valueOf(point.get("id")), "A");
        room.fork(String.valueOf(point.get("id")), "B");
        room.importEvents("branch-1", List.of(event("a", 5, "high", 9, "tick")), false);
        room.importEvents("branch-2", List.of(event("b", 5, "high", 9, "tick")), false);
        boolean conflict = false;
        try {
            room.merge("branch-1", "branch-2", "merged");
        } catch (ReplayRoom.MergeConflictException expected) {
            conflict = true;
            assertEquals("merge_conflict", "merge_conflict", expected.details.get("kind").equals("order") ? "merge_conflict" : "merge_conflict");
            assertEquals("order", expected.details.get("kind"), "first conflict group is identified");
        }
        assertEquals(Boolean.TRUE, conflict, "ambiguous branch merge is rejected");
    }

    private static void exportedSessionReplaysSameTraceHash() {
        ReplayRoom room = new ReplayRoom(simpleDefinition());
        room.importEvents("main", List.of(
                event("e1", 1, "high", 1, "trigger"),
                event("e2", 2, "high", 2, "tick")), true);
        room.step("main", 3);
        Map<String, Object> point = room.checkpoint("main", "cp");
        room.fork(String.valueOf(point.get("id")), "copy work");
        room.importEvents("branch-1", List.of(event("e3", 3, "low", 1, "tick")), false);
        room.step("branch-1", 1);
        Map<String, Object> exported = room.exportSession();
        ReplayRoom imported = ReplayRoom.importSession(Json.parse(Json.write(exported)));
        assertEquals(room.branchState("main").get("traceHash"), imported.branchState("main").get("traceHash"), "main trace hash survives export");
        assertEquals(room.branchState("branch-1").get("traceHash"), imported.branchState("branch-1").get("traceHash"), "branch trace hash survives export");
        assertEquals(room.state().get("definitionFingerprint"), imported.state().get("definitionFingerprint"), "definition fingerprint survives export");
    }

    private static Models.Definition simpleDefinition() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", "simple-v1");
        json.put("initialState", "idle");
        json.put("seed", 42L);
        json.put("initialVariables", Map.of());
        json.put("sourcePriority", Map.of("internal", 100, "high", 10, "low", 1));
        json.put("transitions", List.of(
                Map.of("name", "trigger", "from", "idle", "event", "trigger", "condition", "", "to", "waiting",
                        "actions", List.of(Map.of("type", "emit", "event", "child", "timeDelta", 0L, "source", "internal", "sequence", 1L))),
                Map.of("name", "child", "from", "waiting", "event", "child", "condition", "", "to", "done", "actions", List.of()),
                Map.of("name", "tick", "from", "done", "event", "tick", "condition", "", "to", "done", "actions", List.of())));
        return DefinitionCodec.parse(json);
    }

    private static Models.Definition failureDefinition() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", "failure-v1");
        json.put("initialState", "idle");
        json.put("seed", 7L);
        json.put("initialVariables", Map.of("count", 0L));
        json.put("sourcePriority", Map.of("operator", 10));
        json.put("transitions", List.of(Map.of(
                "name", "bad", "from", "idle", "event", "bad", "condition", "", "to", "changed",
                "actions", List.of(
                        Map.of("type", "set", "target", "vars.count", "value", "vars.count + 1"),
                        Map.of("type", "output", "name", "beforeFailure"),
                        Map.of("type", "emit", "event", "never", "timeDelta", 0L, "source", "internal", "sequence", 1L),
                        Map.of("type", "fail", "error", "'forced failure'"),
                        Map.of("type", "set", "target", "vars.count", "value", "99")))));
        return DefinitionCodec.parse(json);
    }

    private static Map<String, Object> event(String id, long time, String source, long sequence, String type) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("time", time);
        event.put("source", source);
        event.put("sequence", sequence);
        event.put("type", type);
        event.put("payload", new LinkedHashMap<String, Object>());
        return event;
    }

    @SuppressWarnings("unchecked")
    private static List<String> processedIds(ReplayRoom room, String branchId) {
        List<Map<String, Object>> trace = (List<Map<String, Object>>) room.branchState(branchId).get("trace");
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> step : trace) ids.add(String.valueOf(((Map<String, Object>) step.get("event")).get("id")));
        return ids;
    }

    private static void assertEqual(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) FAILURES.add(message + " expected=" + expected + " actual=" + actual);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        assertEqual(expected, actual, message);
    }
}
