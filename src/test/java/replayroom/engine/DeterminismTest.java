package replayroom.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeterminismTest {

    private static replayroom.model.Definition def() {
        var actions = new java.util.ArrayList<Map<String, Object>>();
        actions.add(TestFixtures.action("set",
                Map.of("target", "sum", "valueExpr", "sum + randInt(0, 100)")));
        actions.add(TestFixtures.action("set",
                Map.of("target", "log", "valueExpr", "log + ',' + str(sum)")));
        actions.add(TestFixtures.action("fail",
                Map.of("messageExpr", "'maybe'", "chance", 0.25)));
        return TestFixtures.definition("determinism", "s",
                new LinkedHashMap<>(Map.of("sum", 0L, "log", "")),
                List.of(TestFixtures.transition("draw", null, null, null, actions)));
    }

    public static void testSameLockProducesSameFingerprintAcrossRuns() {
        var definition = def();
        var events = new ArrayList<replayroom.model.Envelope>();
        for (int i = 0; i < 12; i++) {
            events.add(TestFixtures.ext("d" + i, "draw", "src", i, i, i % 3));
        }
        var first = Session.create("a", "a", CompiledDefinition.compile(definition), 2026, events);
        var second = Session.create("b", "b", CompiledDefinition.compile(definition), 2026, events);
        replayroom.Assert.assertEquals(first.lockFingerprint(), second.lockFingerprint(),
                "definition + initial state + events + seed lock to one fingerprint");

        first.runToEnd(CompiledDefinition.compile(definition));
        second.runToEnd(CompiledDefinition.compile(definition));
        replayroom.Assert.assertEquals(first.traceHeadHash(), second.traceHeadHash(),
                "identical locks produce identical trace hashes");
        replayroom.Assert.assertEqualsDeep(first.vars(), second.vars(), "final variables match");
    }

    public static void testDifferentSeedChangesTrajectory() {
        var definition = def();
        var events = List.of(TestFixtures.ext("d0", "draw", "src", 1, 0, 0));
        var first = Session.create("a", "a", CompiledDefinition.compile(definition), 1, events);
        var second = Session.create("b", "b", CompiledDefinition.compile(definition), 2, events);
        first.runToEnd(CompiledDefinition.compile(definition));
        second.runToEnd(CompiledDefinition.compile(definition));
        replayroom.Assert.assertFalse(first.traceHeadHash().equals(second.traceHeadHash()),
                "different seeds should produce different RNG-driven traces");
    }

    public static void testDefinitionEditChangesDefinitionFingerprint() {
        var v1 = def();
        var map = v1.toMap();
        @SuppressWarnings("unchecked")
        java.util.List<Object> transitions = (java.util.List<Object>) map.get("transitions");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> only = (java.util.Map<String, Object>) transitions.get(0);
        @SuppressWarnings("unchecked")
        java.util.List<Object> actions = (java.util.List<Object>) only.get("actions");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> firstAction = (java.util.Map<String, Object>) actions.get(0);
        firstAction.put("valueExpr", "sum + randInt(0, 101)");
        map.remove("fingerprint");
        var v2 = replayroom.model.Definition.fromMap(map);
        replayroom.Assert.assertFalse(v1.fingerprint().equals(v2.fingerprint()),
                "changing an action expression changes the definition fingerprint");
    }
}
