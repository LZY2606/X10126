package replayroom.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replayroom.model.Envelope;

public final class SameTimeOrderingTest {

    public static void testSameTimeUsesPriorityThenSeq() {
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(TestFixtures.action("set", Map.of("target", "last", "valueExpr", "e.source")));
        var def = TestFixtures.definition("order", "s", new LinkedHashMap<>(Map.of("last", "")),
                List.of(TestFixtures.transition("tick", null, null, null, actions)));
        var compiled = CompiledDefinition.compile(def);

        Envelope lowPriorityHighSeq = TestFixtures.ext("low", "tick", "low-src", 7, 99, 1);
        Envelope highPriority = TestFixtures.ext("high", "tick", "high-src", 7, 2, 10);
        Envelope samePriorityLaterSeq = TestFixtures.ext("mid-b", "tick", "mid-b", 7, 2, 10);

        var session = TestFixtures.freshSession(def, 1,
                lowPriorityHighSeq, highPriority, samePriorityLaterSeq);
        session.step(compiled);
        replayroom.Assert.assertEquals("high-src", session.vars().get("last"), "same priority -> lower seq first");
        session.step(compiled);
        replayroom.Assert.assertEquals("mid-b", session.vars().get("last"), "same priority -> higher seq next");
        session.step(compiled);
        replayroom.Assert.assertEquals("low-src", session.vars().get("last"), "low priority last");
    }
}
