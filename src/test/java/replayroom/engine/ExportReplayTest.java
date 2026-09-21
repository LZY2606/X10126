package replayroom.engine;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replayroom.json.Json;
import replayroom.model.Envelope;
import replayroom.model.TraceEntry;

public final class ExportReplayTest {

    private static replayroom.model.Definition buildDef() {
        return TestFixtures.definition("export", "s",
                new LinkedHashMap<>(Map.of("n", 0L)),
                List.of(
                        TestFixtures.transition("inc", null, null, null, List.of(
                                TestFixtures.action("set", Map.of("target", "n", "valueExpr", "n + 1")),
                                TestFixtures.action("emit", Map.of("event", "inc-internal",
                                        "source", "m", "timeDelta", 0L)))),
                        TestFixtures.transition("inc-internal", null, null, null, List.of(
                                TestFixtures.action("output", Map.of("name", "internal-output"))))));
    }

    private static Session executedSession(replayroom.model.Definition def, long seed) {
        var session = Session.create("original", "orig", CompiledDefinition.compile(def), seed, List.of(
                TestFixtures.ext("i1", "inc", "src", 1, 2, 3),
                TestFixtures.ext("i2", "inc", "src", 1, 1, 3)));
        session.runToEnd(CompiledDefinition.compile(def));
        return session;
    }

    public static void testExportedImportedSessionHasIdenticalTraceHash() {
        var def = buildDef();
        var original = executedSession(def, 777);
        Object exportMap = original.toMap(false);
        String serialized = Json.canonical(exportMap);

        var reloaded = Session.fromMap(Json.object(Json.parse(serialized), "session"));
        replayroom.Assert.assertEquals(original.traceHeadHash(), reloaded.traceHeadHash(),
                "export -> import reproduces the trace head hash");
        replayroom.Assert.assertEquals(original.lockFingerprint(), reloaded.lockFingerprint(),
                "lock fingerprint survives export");
        for (int i = 0; i < original.trace().size(); i++) {
            TraceEntry a = original.trace().get(i);
            TraceEntry b = reloaded.trace().get(i);
            replayroom.Assert.assertEquals(a.hash(), b.hash(), "per-step hash at " + i);
        }
    }

    public static void testRestartFromDiskContinuesWithSameTrajectory() throws Exception {
        Path dir = FilesCreate.tempDir();
        Store store = new Store(dir);
        store.open();
        var def = buildDef();
        store.saveDefinition(def);
        var events = List.of(
                TestFixtures.ext("i1", "inc", "src", 1, 2, 3),
                TestFixtures.ext("i2", "inc", "src", 1, 1, 3));
        var session = Session.create("persisted", "persisted", CompiledDefinition.compile(def), 42, events);
        store.saveSession(session);
        session.step(CompiledDefinition.compile(def));
        store.saveSession(session);
        String hashAfterOneStep = session.traceHeadHash();
        int pendingBefore = session.pending().size();

        Store reopened = new Store(dir);
        reopened.open();
        var restored = reopened.session("persisted");
        replayroom.Assert.assertEquals(hashAfterOneStep, restored.traceHeadHash(), "hash restored after restart");
        replayroom.Assert.assertEquals(pendingBefore, restored.pending().size(), "pending queue restored");
        restored.runToEnd(reopened.compiled(restored.definitionFingerprint()));
        String finalHash = restored.traceHeadHash();

        var expected = Session.create("expected", "expected", CompiledDefinition.compile(def), 42, events);
        expected.runToEnd(CompiledDefinition.compile(def));
        replayroom.Assert.assertEquals(expected.traceHeadHash(), finalHash,
                "continuing after restart produces the same final trajectory");
    }

    public static void testTamperedTraceIsDetectedByChain() {
        var def = buildDef();
        var session = executedSession(def, 5);
        var map = session.toMap(false);
        @SuppressWarnings("unchecked")
        java.util.List<Object> trace = (java.util.List<Object>) map.get("trace");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> first = (java.util.Map<String, Object>) trace.get(0);
        first.put("outcome", "applied-tampered");
        String serialized = Json.canonical(map);
        var tampered = Session.fromMap(Json.object(Json.parse(serialized), "session"));
        boolean chainValid = true;
        String parent = null;
        for (TraceEntry entry : tampered.trace()) {
            var body = new LinkedHashMap<String, Object>();
            body.put("version", "sm-trace/v1");
            body.put("lock", tampered.lockFingerprint());
            body.put("parent", parent);
            body.put("entry", entry.hashBody());
            if (!Hashes.sha256Hex(Json.canonical(body)).equals(entry.hash())) {
                chainValid = false;
                break;
            }
            parent = entry.hash();
        }
        replayroom.Assert.assertFalse(chainValid, "tampered trace fails hash verification");
    }
}
