package replay;

import java.util.List;
import java.util.Map;

import replay.engine.Definition;
import replay.engine.Session;

/** Same definition + initial state + events + seed => same fingerprint, always. */
public class DeterminismTest {

    private Definition rngMachine() {
        var actions = new java.util.ArrayList<>();
        Map<String, Object> set = Fixtures.action("set", "name", "pick");
        set.put("value", Map.of("$expr", "randInt(1000)"));
        actions.add(set);
        Map<String, Object> emit = Fixtures.action("emit", "channel", "pick");
        emit.put("payload", Map.of("$expr", "vars.pick"));
        actions.add(emit);
        Map<String, Object> def = new java.util.LinkedHashMap<>();
        def.put("version", 1);
        def.put("name", "rng");
        def.put("states", List.of("s"));
        def.put("initialState", "s");
        def.put("initialVars", new java.util.LinkedHashMap<>());
        def.put("seed", 1234567L);
        def.put("transitions", List.of(
                Fixtures.transition("s", "roll", null, "s", actions)));
        return Definition.fromMap(def);
    }

    @Test
    public void independentRunsYieldIdenticalHashesAndValues() {
        List<Object> events = new java.util.ArrayList<>();
        for (long i = 0; i < 5; i++) {
            events.add(Fixtures.event("r" + i, "roll", i, 0, i, null));
        }
        Session a = Session.create("a", "a", rngMachine(), events);
        Session b = Session.create("b", "b", rngMachine(), events);
        Map<String, Object> ra = a.runToEnd("br-main");
        Map<String, Object> rb = b.runToEnd("br-main");
        Asserts.assertEquals(ra.get("traceHash"), rb.get("traceHash"),
                "deterministic RNG -> identical chained trajectory hash");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sa = (List<Map<String, Object>>) ra.get("steps");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sb = (List<Map<String, Object>>) rb.get("steps");
        for (int i = 0; i < sa.size(); i++) {
            Asserts.assertEquals(sa.get(i).get("afterVars"), sb.get(i).get("afterVars"),
                    "rng value identical at step " + i);
            Asserts.assertEquals(sa.get(i).get("outputs"), sb.get(i).get("outputs"),
                    "outputs identical at step " + i);
        }
    }

    @Test
    public void fingerprintChangesWhenDefinitionChanges() {
        String fp1 = Fixtures.turnstile().fingerprint();
        var map = Fixtures.turnstile().toMap();
        map.put("version", 99);
        String fp2 = Definition.fromMap(map).fingerprint();
        Asserts.assertFalse(fp1.equals(fp2), "version change changes fingerprint");
    }
}
