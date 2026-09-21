package replay;

import java.util.List;
import java.util.Map;

import replay.engine.Session;

/** Export -> import must verify and reproduce identical trajectory hashes. */
public class ExportReplayTest {

    private Session populatedSession(String id) {
        Session s = Session.create(id, "export", Fixtures.turnstile(),
                List.of(Fixtures.event("coin-1", "coin", 10L, 0, 0L, null),
                        Fixtures.event("push-1", "push", 20L, 0, 1L, null)));
        s.step("br-main", 1);
        var cp = s.checkpoint("br-main", "after-coin");
        var fork = s.fork("br-main", cp.id, "what-if-push",
                List.of(Fixtures.event("extra", "push", 15L, 0, 50L, null)));
        s.runToEnd("br-main");
        s.runToEnd(fork.id);
        return s;
    }

    @Test
    public void exportedSessionImportsWithSameTrajectoryHashes() {
        Session original = populatedSession("t6");
        Map<String, Object> pkg = original.export();
        String json = Json.write(pkg);

        @SuppressWarnings("unchecked")
        Map<String, Object> reparsed = (Map<String, Object>) Json.parse(json);
        Session imported = Session.importVerified(reparsed);

        Asserts.assertEquals(original.lockFingerprint, imported.lockFingerprint,
                "lock fingerprint survives export/import");
        for (String branchId : new String[]{"br-main"}) {
            String before = original.branches.get(branchId).engine.getTraceHash();
            String after = imported.branches.get(branchId).engine.getTraceHash();
            Asserts.assertEquals(before, after,
                    "branch " + branchId + " reproduces trajectory hash");
        }
        // Forked branch hash reproduces as well.
        String forkBefore = null;
        String forkAfter = null;
        for (var e : original.branches.entrySet()) {
            if (e.getKey().startsWith("br-") && !e.getKey().equals("br-main")) {
                forkBefore = e.getValue().engine().getTraceHash();
            }
        }
        for (var e : imported.branches.entrySet()) {
            if (e.getKey().startsWith("br-") && !e.getKey().equals("br-main")) {
                forkAfter = e.getValue().engine().getTraceHash();
            }
        }
        Asserts.assertEquals(forkBefore, forkAfter, "forked branch hash reproduces");
    }

    @Test
    public void tamperedExportFailsVerification() {
        Session original = populatedSession("t6b");
        Map<String, Object> pkg = original.export();
        @SuppressWarnings("unchecked")
        List<Object> steps = (List<Object>)
                ((Map<String, Object>) ((List<Object>) pkg.get("branches")).get(0)).get("steps");
        @SuppressWarnings("unchecked")
        Map<String, Object> tamperedStep = (Map<String, Object>) steps.get(0);
        tamperedStep.put("afterState", "open-open-tampered");
        try {
            Session.importVerified(pkg);
            throw new AssertionError("tampered trajectory must not import");
        } catch (Session.ApiException expected) {
            Asserts.assertEquals(400, expected.status(), "bad request on verification failure");
            Asserts.assertTrue(expected.getMessage().contains("trajectory hash"),
                    "explains verification failure");
        }
    }

    @Test
    public void definitionAndSeedArePartOfLock() {
        Session a = Session.create("a", "x", Fixtures.turnstile(),
                List.of(Fixtures.event("c", "coin", 1L, 0, 0L, null)));
        var changed = Fixtures.turnstile().toMap();
        changed.put("seed", 404L);
        Session b = Session.create("b", "x",
                replay.engine.Definition.fromMap(changed),
                List.of(Fixtures.event("c", "coin", 1L, 0, 0L, null)));
        Asserts.assertFalse(a.lockFingerprint.equals(b.lockFingerprint),
                "different seed -> different lock fingerprint");
    }
}
