package replayroom.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CheckpointVersionTest {

    public static void testCheckpointRejectsDifferentDefinitionFingerprint() {
        var v1 = TestFixtures.definition("v1", "a",
                new LinkedHashMap<>(Map.of("n", 0L)),
                List.of(TestFixtures.transition("go", "a", "b", null,
                        List.of(TestFixtures.action("set", Map.of("target", "n", "valueExpr", "1"))))));
        var v2 = TestFixtures.definition("v2-renamed", "a",
                new LinkedHashMap<>(Map.of("n", 0L)),
                List.of(TestFixtures.transition("go", "a", "c", null,
                        List.of(TestFixtures.action("set", Map.of("target", "n", "valueExpr", "2"))))));

        replayroom.Assert.assertFalse(v1.fingerprint().equals(v2.fingerprint()),
                "changed transition target must change definition fingerprint");

        var compiledV1 = CompiledDefinition.compile(v1);
        var session = TestFixtures.freshSession(v1, 5,
                TestFixtures.ext("g1", "go", "src", 1, 0, 0),
                TestFixtures.ext("g2", "go", "src", 2, 0, 0));
        session.step(compiledV1);
        var checkpoint = session.checkpoint("cp-old");

        try {
            Session.fromCheckpoint("forked", "branch", checkpoint, CompiledDefinition.compile(v2), 5);
            replayroom.Assert.fail("expected definition mismatch rejection");
        } catch (Session.DefinitionMismatchException expected) {
            replayroom.Assert.assertEquals(v1.fingerprint(), expected.checkpointFingerprint(),
                    "checkpoint fingerprint reported");
            replayroom.Assert.assertEquals(v2.fingerprint(), expected.actualFingerprint(),
                    "new definition fingerprint reported");
        }
    }

    public static void testCheckpointAcceptsSameDefinitionAndRestoresRngState() {
        var def = TestFixtures.definition("rng", "s", new LinkedHashMap<>(),
                List.of(TestFixtures.transition("use", null, null, null,
                        List.of(TestFixtures.action("set",
                                Map.of("target", "r", "valueExpr", "rand()"))))));
        var compiled = CompiledDefinition.compile(def);
        var session = TestFixtures.freshSession(def, 123,
                TestFixtures.ext("u", "use", "src", 1, 0, 0),
                TestFixtures.ext("u2", "use", "src", 2, 0, 0));
        session.step(compiled);
        var checkpoint = session.checkpoint("cp-ok");

        var forked = Session.fromCheckpoint("forked", "fork", checkpoint, compiled, 123);
        replayroom.Assert.assertEquals(checkpoint.rngState(), forked.rngState(),
                "fork restores the exact RNG state captured at the checkpoint");
        forked.step(compiled);
        session.step(compiled);
        replayroom.Assert.assertEquals(session.vars().get("r"), forked.vars().get("r"),
                "fork continues with the same RNG-driven value as the original");
        replayroom.Assert.assertEquals(session.currentState(), forked.currentState(),
                "forked current state matches the continuing original");
        replayroom.Assert.assertEquals(session.pending().size(), forked.pending().size(),
                "forked pending queue matches the continuing original");
        replayroom.Assert.assertEquals(checkpoint.id(), forked.parentCheckpointId(),
                "fork records its ancestor checkpoint");
    }
}
