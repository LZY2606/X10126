package replay;

import java.util.List;
import java.util.Map;

import replay.engine.Definition;
import replay.engine.Session;

/** Checkpoints bind to a definition fingerprint and reject mismatched definitions. */
public class CheckpointVersionTest {

    private Definition turnstileVersion(int version, long seed) {
        var d = Fixtures.turnstile();
        Map<String, Object> map = d.toMap();
        map.put("version", version);
        map.put("seed", seed);
        return Definition.fromMap(map);
    }

    @Test
    public void checkpointCarriesDefinitionFingerprint() {
        Session s = Session.create("t4", "cp", Fixtures.turnstile(),
                List.of(Fixtures.event("c1", "coin", 1L, 0, 0L, null)));
        s.step("br-main", 1);
        var cp = s.checkpoint("br-main", "after coin");
        Asserts.assertEquals(s.definition.fingerprint(), cp.definitionFingerprint,
                "checkpoint stores current definition fingerprint");
        Asserts.assertTrue(cp.toMap().containsKey("definitionFingerprint"),
                "exposed in checkpoint view");
    }

    @Test
    public void restoreRejectsCheckpointFromOldDefinition() {
        Definition v1 = turnstileVersion(1, 42L);
        Session s1 = Session.create("old", "old", v1,
                List.of(Fixtures.event("c1", "coin", 1L, 0, 0L, null)));
        s1.step("br-main", 1);
        var oldCp = s1.checkpoint("br-main", "v1 checkpoint");

        // New session with a changed definition (new version + changed seed).
        Definition v2 = turnstileVersion(2, 43L);
        Session s2 = Session.create("new", "new", v2,
                List.of(Fixtures.event("c1", "coin", 1L, 0, 0L, null)));
        // Inject the old checkpoint as if copied across definition versions.
        s2.branches.get("br-main").checkpoints.add(oldCp);

        try {
            s2.restoreCheckpoint("br-main", oldCp.id);
            throw new AssertionError("expected rejection of stale-definition checkpoint");
        } catch (Session.ApiException expected) {
            Asserts.assertEquals(409, expected.status(), "conflict status");
            Asserts.assertTrue(expected.getMessage().contains("cannot be replayed"),
                    "message explains fingerprint mismatch: " + expected.getMessage());
        }
    }

    @Test
    public void sameDefinitionCheckpointRestoresStateAndRng() {
        Session s = Session.create("t4c", "cp", Fixtures.turnstile(),
                List.of(Fixtures.event("c1", "coin", 1L, 0, 0L, null)));
        s.step("br-main", 1);
        var cp = s.checkpoint("br-main", "cp1");
        s.runToEnd("br-main");
        String hashAtEnd = s.branches.get("br-main").engine().getTraceHash();
        s.restoreCheckpoint("br-main", cp.id);
        var branch = s.branches.get("br-main");
        Asserts.assertEquals(cp.stepIndex, branch.engine().getStepIndex(),
                "step index restored");
        Asserts.assertEquals(cp.traceHash, branch.engine().getTraceHash(),
                "trace hash restored");
        // Continue replay -> deterministic end hash again.
        s.runToEnd("br-main");
        Asserts.assertEquals(hashAtEnd, branch.engine().getTraceHash(),
                "resumed replay reproduces the same end hash");
    }
}
