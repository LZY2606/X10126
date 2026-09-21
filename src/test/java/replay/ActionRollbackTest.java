package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import replay.engine.Definition;
import replay.engine.Session;

/** A failing action rolls back state/vars/outputs/internals but records failure. */
public class ActionRollbackTest {

    private Definition failingMachine(long seed) {
        List<Object> actions = new ArrayList<>();
        Map<String, Object> output = Fixtures.action("emit", "channel", "note");
        output.put("payload", "should-roll-back");
        actions.add(output);
        Map<String, Object> set = Fixtures.action("set", "name", "attempted");
        set.put("value", Map.of("$expr", "randInt(100)"));
        actions.add(set);
        Map<String, Object> internal = Fixtures.action("emitInternal", "event", "ghost");
        actions.add(internal);
        Map<String, Object> fail = Fixtures.action("fail", "message",
                Map.of("$expr", "'boom on ' + state"));
        actions.add(fail);

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("version", 1);
        def.put("name", "failer");
        def.put("states", List.of("a", "b"));
        def.put("initialState", "a");
        def.put("initialVars", Map.of("attempted", -1L));
        def.put("seed", seed);
        def.put("transitions", List.of(
                Fixtures.transition("a", "go", null, "b", actions),
                Fixtures.transition("b", "go", null, "a", List.of())));
        return Definition.fromMap(def);
    }

    @Test
    public void failedActionRollsBackEverythingButKeepsFailureRecord() {
        Session s = Session.create("t3", "rollback", failingMachine(99L),
                List.of(Fixtures.event("g1", "go", 1L, 0, 0L, null),
                        Fixtures.event("g2", "go", 2L, 0, 1L, null)));
        Map<String, Object> run = s.runToEnd("br-main");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) run.get("steps");

        Map<String, Object> first = steps.get(0);
        Asserts.assertEquals(false, first.get("success"), "step marked failed");
        Asserts.assertTrue(String.valueOf(first.get("failure")).contains("boom on a"),
                "failure message present: " + first.get("failure"));
        Asserts.assertEquals("a", first.get("afterState"), "state rolled back to a");
        Asserts.assertEquals(0, ((List<?>) first.get("outputs")).size(),
                "outputs rolled back");
        Asserts.assertEquals(0, ((List<?>) first.get("spawnedInternals")).size(),
                "spawned internals rolled back");
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = (Map<String, Object>) first.get("afterVars");
        Asserts.assertEquals(-1L, ((Number) vars.get("attempted")).longValue(),
                "variable write rolled back");
        Asserts.assertEquals(0L, ((Number) first.get("rngCalls")).longValue(),
                "random draw rolled back");
        Asserts.assertTrue(String.valueOf(first.get("traceHash")).length() == 64,
                "failure still enters the chained trajectory hash");

        // Second event: since state rolled back to "a", it fails identically.
        Map<String, Object> second = steps.get(1);
        Asserts.assertEquals(false, second.get("success"), "second also fails at a");
        Asserts.assertEquals(first.get("traceHash") == null, false, "hashes chain");
        Asserts.assertFalse(Asserts.fp(first).equals(Asserts.fp(second)),
                "chained hashes differ per step");
    }

    @Test
    public void failureHashIsStableAcrossRuns() {
        List<Object> events = List.of(Fixtures.event("g1", "go", 1L, 0, 0L, null));
        Session one = Session.create("x", "x", failingMachine(99L), events);
        Session two = Session.create("y", "y", failingMachine(99L), events);
        Asserts.assertEquals(one.runToEnd("br-main").get("traceHash"),
                two.runToEnd("br-main").get("traceHash"),
                "identical seed/events/definition -> identical failure trajectory");
    }
}
