package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import replay.engine.Session;

/**
 * Branching: fork from checkpoints, append external events, compare branches,
 * and merge only when sets are compatible with unambiguous ordering.
 */
public class BranchMergeConflictTest {

    private List<Object> threeCoins() {
        List<Object> events = new ArrayList<>();
        for (long i = 0; i < 3; i++) {
            events.add(Fixtures.event("coin-" + i, "coin", i, 0, i, null));
        }
        return events;
    }

    @Test
    public void cleanForksMergeAndReplayDeterministically() {
        Session s = Session.create("t5", "merge", Fixtures.turnstile(), threeCoins());
        s.step("br-main", 1); // coin-0
        var cp = s.checkpoint("br-main", "after first coin");

        var left = s.fork("br-main", cp.id, "left",
                List.of(Fixtures.event("L1", "push", 5L, 0, 100L, null)));
        var right = s.fork("br-main", cp.id, "right",
                List.of(Fixtures.event("R1", "push", 6L, 0, 101L, null)));
        s.runToEnd(left.id);
        s.runToEnd(right.id);

        Map<String, Object> compare = s.compare(left.id, right.id);
        Asserts.assertTrue(compare.containsKey("varsDiff"), "comparison includes var diff");

        Map<String, Object> merged = s.merge(left.id, right.id, "clean-merge", true);
        Asserts.assertEquals("merged", merged.get("status"), "compatible merge executes");
        String mergedBranchId = String.valueOf(((Map<?, ?>) merged.get("mergedBranch")).get("id"));
        Asserts.assertTrue(String.valueOf(merged.get("traceHash")).length() == 64,
                "merged branch has a trajectory hash");

        // Replay the same merge fresh: must produce the same merged trace hash.
        Session again = Session.create("t5b", "merge", Fixtures.turnstile(), threeCoins());
        again.step("br-main", 1);
        var cp2 = again.checkpoint("br-main", "after first coin");
        var l2 = again.fork("br-main", cp2.id, "left",
                List.of(Fixtures.event("L1", "push", 5L, 0, 100L, null)));
        var r2 = again.fork("br-main", cp2.id, "right",
                List.of(Fixtures.event("R1", "push", 6L, 0, 101L, null)));
        again.runToEnd(l2.id);
        again.runToEnd(r2.id);
        Map<String, Object> merged2 = again.merge(l2.id, r2.id, "clean-merge", true);
        Asserts.assertEquals(merged.get("traceHash"), merged2.get("traceHash"),
                "merge result is deterministic");
    }

    @Test
    public void conflictingOrderingIsRejectedWithFirstConflict() {
        Session s = Session.create("t5c", "conflict", Fixtures.turnstile(), threeCoins());
        s.step("br-main", 1);
        var cp = s.checkpoint("br-main", "ancestor");

        // Both branches introduce two brand-new events at the same logical time,
        // same priority and indistinguishable stable ordering at the first
        // merge position -> ambiguous, must be rejected.
        var left = s.fork("br-main", cp.id, "L",
                List.of(Fixtures.event("LX", "push", 10L, 0, 0L, null)));
        var right = s.fork("br-main", cp.id, "R",
                List.of(Fixtures.event("RX", "push", 10L, 0, 0L, null)));
        s.runToEnd(left.id);
        s.runToEnd(right.id);

        Map<String, Object> merged = s.merge(left.id, right.id, "bad", true);
        Asserts.assertEquals("rejected", merged.get("status"), "merge rejected");
        Asserts.assertEquals(false, merged.get("compatible"), "flagged incompatible");
        @SuppressWarnings("unchecked")
        Map<String, Object> conflict = (Map<String, Object>) merged.get("firstConflict");
        Asserts.assertTrue(conflict != null, "first conflict reported");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pair = (List<Map<String, Object>>) conflict.get("events");
        Asserts.assertEquals(2, pair.size(), "conflict names a pair of events");
        Asserts.assertTrue(
                pair.get(0).get("id").equals("LX") || pair.get(0).get("id").equals("RX"),
                "first conflict event identified");
    }

    @Test
    public void sharedEventsInDifferentOrderAreRejected() {
        Session s = Session.create("t5d", "orderconflict", Fixtures.turnstile(),
                new ArrayList<>());
        var cp = s.checkpoint("br-main", "root");
        var left = s.fork("br-main", cp.id, "L",
                List.of(Fixtures.event("shared-x", "push", 1L, 0, 1L, null),
                        Fixtures.event("shared-y", "push", 2L, 0, 2L, null)));
        var right = s.fork("br-main", cp.id, "R",
                List.of(Fixtures.event("shared-y", "push", 1L, 0, 2L, null),
                        Fixtures.event("shared-x", "push", 2L, 0, 1L, null)));
        s.runToEnd(left.id);
        s.runToEnd(right.id);
        Map<String, Object> merged = s.merge(left.id, right.id, "bad", false);
        Asserts.assertEquals("rejected", merged.get("status"),
                "contradictory ordering of shared events rejected");
        @SuppressWarnings("unchecked")
        Map<String, Object> conflict = (Map<String, Object>) merged.get("firstConflict");
        Asserts.assertTrue(String.valueOf(conflict.get("reason")).contains("ordered differently"),
                "reason explains ordering conflict");
    }

    @Test
    public void mergeIsNotFinalStateOverwrite() {
        // The merged branch replays the union from the ancestor rather than
        // copying one side's final state. Verify via the resulting step count.
        Session s = Session.create("t5e", "union", Fixtures.turnstile(),
                List.of(Fixtures.event("c0", "coin", 0L, 0, 0L, null)));
        s.step("br-main", 1);
        var cp = s.checkpoint("br-main", "ancestor");
        var left = s.fork("br-main", cp.id, "L",
                List.of(Fixtures.event("L1", "push", 1L, 0, 0L, null)));
        var right = s.fork("br-main", cp.id, "R",
                List.of(Fixtures.event("R1", "push", 2L, 0, 1L, null)));
        s.runToEnd(left.id);
        s.runToEnd(right.id);
        Map<String, Object> merged = s.merge(left.id, right.id, "union", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> mb = (Map<String, Object>) merged.get("mergedBranch");
        // ancestor already has 1 step; merged union adds 2 pushes
        Asserts.assertEquals(3L, ((Number) mb.get("stepCount")).longValue(),
                "merged branch replayed union events, not overwritten state");
    }
}
