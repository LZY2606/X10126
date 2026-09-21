package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import replay.engine.Session;

/** Events at the same logical time order by source priority, then seq. */
public class SameTimeOrderingTest {

    @Test
    public void sameTimeSortsByPriorityThenSeq() {
        List<Object> events = new ArrayList<>();
        // Input intentionally shuffled; ordering must be stable.
        events.add(Fixtures.event("a", "tick", 10L, 5, 2L, null));
        events.add(Fixtures.event("b", "tick", 10L, 1, 9L, null));
        events.add(Fixtures.event("c", "tick", 10L, 1, 3L, null));
        events.add(Fixtures.event("d", "tick", 5L, 9, 0L, null));

        Session s = Session.create("t1", "order", Fixtures.orderProbe(), events);
        Map<String, Object> run = s.runToEnd("br-main");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) run.get("steps");

        Asserts.assertEquals(4, steps.size(), "four external events");
        Asserts.assertEquals("d", steps.get(0).get("eventId"), "earlier time first");
        Asserts.assertEquals("c", steps.get(1).get("eventId"),
                "same time: priority first, then lower seq");
        Asserts.assertEquals("b", steps.get(2).get("eventId"), "higher seq after");
        Asserts.assertEquals("a", steps.get(3).get("eventId"), "higher priority last");
    }
}
