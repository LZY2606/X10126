package replayroom.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replayroom.model.Envelope;

public final class InternalEventQueueTest {

    public static void testEmittedInternalEventsAreQueuedAfterCurrentAndDepthFirst() {
        List<Map<String, Object>> triggerActions = new ArrayList<>();
        triggerActions.add(TestFixtures.action("emit", Map.of(
                "event", "internal-a", "source", "machine", "timeDelta", 0L)));
        triggerActions.add(TestFixtures.action("emit", Map.of(
                "event", "internal-b", "source", "machine", "timeDelta", 0L)));

        List<Map<String, Object>> aActions = new ArrayList<>();
        aActions.add(TestFixtures.action("emit", Map.of(
                "event", "internal-c", "source", "machine", "timeDelta", 0L)));
        aActions.add(TestFixtures.action("set", Map.of("target", "sawA", "valueExpr", "'yes'")));

        List<Map<String, Object>> recordActions = new ArrayList<>();
        recordActions.add(TestFixtures.action("set", Map.of(
                "target", "order",
                "valueExpr", "order + ',' + e.name")));

        List<Map<String, Object>> transitions = List.of(
                TestFixtures.transition("trigger", null, null, null, triggerActions),
                TestFixtures.transition("internal-a", null, null, null, aActions),
                TestFixtures.transition("internal-b", null, null, null, recordActions),
                TestFixtures.transition("internal-c", null, null, null, recordActions),
                TestFixtures.transition("external-later", null, null, null, recordActions));

        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("order", "");
        initial.put("sawA", "no");
        var def = TestFixtures.definition("internal-queue", "s", initial, transitions);
        var compiled = CompiledDefinition.compile(def);

        Envelope laterExternal = TestFixtures.ext("ext-later", "external-later", "outside", 5, 1, 0);
        Envelope trigger = TestFixtures.ext("ext-trigger", "trigger", "outside", 5, 0, 100);
        var session = TestFixtures.freshSession(def, 7, laterExternal, trigger);

        session.step(compiled); // trigger emits a, b
        session.step(compiled); // a emits c
        session.step(compiled); // c must run before b (depth-first chain)
        session.step(compiled); // b
        session.step(compiled); // external even though its priority is higher
        String order = String.valueOf(session.vars().get("order"));
        replayroom.Assert.assertEquals(",internal-c,internal-b,external-later", order,
                "internal events must drain after the triggering event before later externals");
        replayroom.Assert.assertEquals("yes", session.vars().get("sawA"), "internal-a executed");

        for (var entry : session.trace()) {
            if (!"external-later".equals(entry.event().name())) continue;
            replayroom.Assert.assertTrue(entry.event().time() == 5, "virtual logical clock only");
        }
    }

    public static void testInternalEventCarriesParentId() {
        List<Map<String, Object>> actions = List.of(
                TestFixtures.action("emit", Map.of("event", "child", "source", "m", "timeDelta", 2L)));
        var def = TestFixtures.definition("parent", "s", new LinkedHashMap<>(),
                List.of(TestFixtures.transition("parent-event", null, null, null, actions)));
        var compiled = CompiledDefinition.compile(def);
        var session = TestFixtures.freshSession(def, 1,
                TestFixtures.ext("p", "parent-event", "outside", 3, 0, 0));
        session.step(compiled);
        replayroom.Assert.assertEquals(1, session.pending().size(), "one internal event queued");
        Envelope child = session.pending().get(0);
        replayroom.Assert.assertTrue(child.internal(), "emitted event is internal");
        replayroom.Assert.assertEquals("p", child.parentId(), "parent id preserved");
        replayroom.Assert.assertEquals(5L, child.time(), "timeDelta is virtual logical time only");
    }
}
