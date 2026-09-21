package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import replay.engine.Session;

/**
 * Internal events are queued AFTER the current event and handled before later
 * external events.
 */
public class InternalQueueTest {

    @Test
    public void internalEventRunsBeforeLaterExternalEvent() {
        List<Object> events = new ArrayList<>();
        events.add(Fixtures.event("coin-1", "coin", 10L, 0, 0L, null));
        events.add(Fixtures.event("push-1", "push", 20L, 0, 1L, null));

        Session s = Session.create("t2", "internal", Fixtures.turnstile(), events);
        Map<String, Object> run = s.runToEnd("br-main");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) run.get("steps");

        Asserts.assertEquals(3, steps.size(), "coin + internal auto-close + push");
        Asserts.assertEquals("external", steps.get(0).get("kind"), "coin external");
        Asserts.assertEquals("open", steps.get(0).get("afterState"), "opens");
        Asserts.assertEquals("internal", steps.get(1).get("kind"), "auto-close internal");
        Asserts.assertEquals("auto-close", steps.get(1).get("event"), "internal event name");
        Asserts.assertEquals(0L, ((Number) steps.get(1).get("emissionStep")).longValue(),
                "emitted by step 0");
        Asserts.assertEquals("locked", steps.get(1).get("afterState"), "closes again");
        Asserts.assertEquals("external", steps.get(2).get("kind"), "push after internal drained");
        Asserts.assertEquals("locked", steps.get(2).get("afterState"), "push keeps locked");
    }

    @Test
    public void pendingInternalCountIsReported() {
        List<Object> events = List.of(Fixtures.event("coin-1", "coin", 10L, 0, 0L, null));
        Session s = Session.create("t2b", "internal", Fixtures.turnstile(), events);
        Map<String, Object> one = s.step("br-main", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> produced = (Map<String, Object>)
                ((List<Object>) one.get("steps")).get(0);
        Asserts.assertEquals(1L, ((Number) produced.get("pendingInternalCount")).longValue(),
                "one queued internal");
    }
}
