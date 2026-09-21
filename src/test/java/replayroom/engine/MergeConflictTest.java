package replayroom.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replayroom.model.Checkpoint;
import replayroom.model.Envelope;

public final class MergeConflictTest {

    private static final class FixedStore implements BranchMerger.StoreLike {
        private final Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();
        private final CompiledDefinition compiled;

        FixedStore(CompiledDefinition compiled) {
            this.compiled = compiled;
        }

        void put(Checkpoint checkpoint) { checkpoints.put(checkpoint.id(), checkpoint); }

        @Override public Checkpoint checkpoint(String id) { return checkpoints.get(id); }
        @Override public CompiledDefinition compiled(String fingerprint) { return compiled; }
    }

    private static DefinitionAndStore base() {
        var def = TestFixtures.definition("merge", "s",
                new LinkedHashMap<>(Map.of("seen", "")),
                List.of(TestFixtures.transition("mark", null, null, null,
                        List.of(TestFixtures.action("set",
                                Map.of("target", "seen", "valueExpr", "seen + e.data.tag"))))));
        var compiled = CompiledDefinition.compile(def);
        var root = TestFixtures.freshSession(def, 1,
                TestFixtures.ext("base", "mark", "src", 0, 0, 0, Map.of("tag", "0")));
        root.id("root");
        root.step(compiled);
        Checkpoint checkpoint = root.checkpoint("cp-base");
        FixedStore store = new FixedStore(compiled);
        store.put(checkpoint);
        return new DefinitionAndStore(def, compiled, store, checkpoint);
    }

    private record DefinitionAndStore(
            replayroom.model.Definition definition,
            CompiledDefinition compiled,
            FixedStore store,
            Checkpoint checkpoint) {}

    private static Session forkBranch(DefinitionAndStore base, String id, List<Envelope> events) {
        var branch = Session.fromCheckpoint(id, id, base.checkpoint(), base.compiled(), 1);
        branch.appendExternalEvents(events);
        branch.runToEnd(base.compiled());
        return branch;
    }

    public static void testAmbiguousSameTimeSamePriorityAcrossBranchesRejected() {
        var base = base();
        var a = forkBranch(base, "branch-a", List.of(
                TestFixtures.ext("a1", "mark", "src-a", 10, 1, 5, Map.of("tag", "A"))));
        var b = forkBranch(base, "branch-b", List.of(
                TestFixtures.ext("b1", "mark", "src-b", 10, 1, 5, Map.of("tag", "B"))));

        Object result = new BranchMerger(base.store()).merge("merged", "merged", a, b);
        replayroom.Assert.assertTrue(result instanceof MergeConflict, "merge rejected");
        MergeConflict conflict = (MergeConflict) result;
        replayroom.Assert.assertEquals("ambiguous-order", conflict.reason(), "reason identifies ordering ambiguity");
        String json = replayroom.json.Json.canonical(conflict.toMap());
        replayroom.Assert.assertTrue(json.contains("a1") && json.contains("b1"),
                "first conflict group names both events");
    }

    public static void testUnambiguousBranchesMergeWithDeterministicOrder() {
        var base = base();
        var a = forkBranch(base, "branch-a", List.of(
                TestFixtures.ext("a1", "mark", "src-a", 10, 1, 5, Map.of("tag", "A"))));
        var b = forkBranch(base, "branch-b", List.of(
                TestFixtures.ext("b1", "mark", "src-b", 10, 1, 8, Map.of("tag", "B"))));

        Object result = new BranchMerger(base.store()).merge("merged", "merged", a, b);
        replayroom.Assert.assertTrue(result instanceof MergeResult, "merge accepted");
        MergeResult merged = (MergeResult) result;
        replayroom.Assert.assertEquals("BA", merged.session().vars().get("seen"),
                "higher source priority event runs first in the merged replay");
    }

    public static void testSharedEventsWithDifferentOrderRejected() {
        var base = base();
        var sharedX = TestFixtures.ext("x", "mark", "src-x", 10, 1, 10, Map.of("tag", "X"));
        var sharedY = TestFixtures.ext("y", "mark", "src-y", 20, 1, 10, Map.of("tag", "Y"));
        var a = forkBranch(base, "branch-a", new ArrayList<>(List.of(sharedX, sharedY)));
        var bEvents = new ArrayList<Envelope>();
        bEvents.add(Envelope.external("y", "mark", "src-y", 20, 1, 10, Map.of("tag", "DIFFERENT")));
        bEvents.add(Envelope.external("x", "mark", "src-x", 10, 1, 10, Map.of("tag", "X")));
        var b = forkBranch(base, "branch-b", bEvents);

        Object result = new BranchMerger(base.store()).merge("merged", "merged", a, b);
        replayroom.Assert.assertTrue(result instanceof MergeConflict, "different shared content rejected");
        replayroom.Assert.assertEquals("content-mismatch", ((MergeConflict) result).reason(), "content mismatch");
    }

    public static void testNonCheckpointSessionsCannotMerge() {
        var base = base();
        var plainA = TestFixtures.freshSession(base.definition(), 1,
                TestFixtures.ext("a", "mark", "src-a", 1, 1, 1, Map.of("tag", "A")));
        plainA.id("plain-a");
        var b = forkBranch(base, "branch-b", List.of(
                TestFixtures.ext("b", "mark", "src-b", 2, 1, 9, Map.of("tag", "B"))));
        Object result = new BranchMerger(base.store()).merge("merged", "merged", plainA, b);
        replayroom.Assert.assertTrue(result instanceof MergeConflict, "non-fork session rejected");
        replayroom.Assert.assertEquals("ancestor-mismatch", ((MergeConflict) result).reason(), "ancestor reason");
    }
}
