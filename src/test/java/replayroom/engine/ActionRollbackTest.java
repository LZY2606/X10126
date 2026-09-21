package replayroom.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ActionRollbackTest {

    public static void testFailedActionRollsBackStateAndEmittedEventsButRecordsTrace() {
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(TestFixtures.action("set", Map.of("target", "count", "valueExpr", "count + 1")));
        actions.add(TestFixtures.action("output", Map.of("name", "before-fail")));
        actions.add(TestFixtures.action("emit", Map.of("event", "never-runs", "source", "m", "timeDelta", 0L)));
        actions.add(TestFixtures.action("fail", Map.of("messageExpr", "'boom'", "chance", 1.0)));

        var def = TestFixtures.definition("rollback", "start",
                new LinkedHashMap<>(Map.of("count", 10L)),
                List.of(TestFixtures.transition("risky", "start", "end", null, actions)));
        var compiled = CompiledDefinition.compile(def);
        var session = TestFixtures.freshSession(def, 99,
                TestFixtures.ext("risk", "risky", "ops", 1, 0, 0));

        var entry = session.step(compiled);
        replayroom.Assert.assertEquals("failed", entry.outcome(), "outcome recorded");
        replayroom.Assert.assertEquals("boom", entry.failureMessage(), "failure message recorded");
        replayroom.Assert.assertEquals(10L, session.vars().get("count"), "state change rolled back");
        replayroom.Assert.assertEquals("start", session.currentState(), "state transition rolled back");
        replayroom.Assert.assertEquals(0, session.pending().size(), "derived internal events rolled back");
        replayroom.Assert.assertEquals(0, entry.outputs().size(), "failed outputs not committed");
        replayroom.Assert.assertEquals(1, session.trace().size(), "failure still entered the trace");
        replayroom.Assert.assertTrue(entry.hash() != null && entry.hash().length() == 64, "failure has hash");
    }

    public static void testExpressionErrorFailsAndRollsBack() {
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(TestFixtures.action("set", Map.of("target", "x", "valueExpr", "x + 1")));
        actions.add(TestFixtures.action("set", Map.of("target", "broken", "valueExpr", "missing.deep.value")));
        var def = TestFixtures.definition("expr-fail", "s",
                new LinkedHashMap<>(Map.of("x", 0L)),
                List.of(TestFixtures.transition("go", null, null, null, actions)));
        var compiled = CompiledDefinition.compile(def);
        var session = TestFixtures.freshSession(def, 1,
                TestFixtures.ext("g", "go", "src", 1, 0, 0));
        var entry = session.step(compiled);
        replayroom.Assert.assertEquals("failed", entry.outcome(), "expression error causes failure");
        replayroom.Assert.assertEquals(0L, session.vars().get("x"), "partial state mutation rolled back");
        replayroom.Assert.assertFalse(session.vars().containsKey("broken"), "no broken key");
    }

    public static void testConditionReadsPreEventSnapshot() {
        List<Map<String, Object>> actions = List.of(
                TestFixtures.action("set", Map.of("target", "v", "valueExpr", "v + 10")));
        var def = TestFixtures.definition("snapshot", "s",
                new LinkedHashMap<>(Map.of("v", 0L)),
                List.of(TestFixtures.transition("e", null, null, "v == 0", actions),
                        TestFixtures.transition("e", null, null, "v == 10", actions)));
        var compiled = CompiledDefinition.compile(def);
        var session = TestFixtures.freshSession(def, 1,
                TestFixtures.ext("e1", "e", "src", 1, 0, 0),
                TestFixtures.ext("e2", "e", "src", 2, 0, 0));
        session.step(compiled);
        replayroom.Assert.assertEquals(10L, session.vars().get("v"), "first matched pre-snapshot condition");
        session.step(compiled);
        replayroom.Assert.assertEquals(20L, session.vars().get("v"), "second sees the post-first-event snapshot");
    }
}
