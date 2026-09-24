package replay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Defaults {
    static MachineDefinition definition() {
        Map<String, Object> initialData = new LinkedHashMap<>();
        initialData.put("attempts", 0);
        initialData.put("alerts", 0);
        initialData.put("requestId", "");

        Action attempts = action("set", "data.attempts", "${data.attempts + 1}", null, null, 0);
        Action requestId = action("set", "data.requestId", "${event.requestId}", null, null, 0);
        Action alarmCount = action("set", "data.alerts", "${data.alerts + 1}", null, null, 0);
        Action deniedOutput = outputAction("access.denied", Map.of("requestId", "${event.requestId}", "state", "${state}"));
        Action grantedOutput = outputAction("access.granted", Map.of("requestId", "${event.requestId}", "door", "north"));
        Action alarmOutput = outputAction("alarm.raised", Map.of("code", "${event.code}"));
        Action emitReview = emitAction("security.review", 0,
                Map.of("requestId", "${event.requestId}", "reason", "alarm"));
        Action failAfterSideEffects = action("fail", null, null, null, "review.pipeline.unavailable", 0);

        List<Transition> transitions = List.of(
                new Transition("locked", "scan", "unlocked", "event.cardLevel >= 3",
                        List.of(attempts, requestId, grantedOutput)),
                new Transition("locked", "scan", "locked", "event.cardLevel < 3",
                        List.of(attempts, requestId, deniedOutput)),
                new Transition("unlocked", "scan", "locked", "true", List.of(attempts)),
                new Transition("alarm", "scan", "alarm", "true", List.of(attempts, deniedOutput)),
                new Transition("*", "alarm", "alarm", "state != 'alarm'",
                        List.of(alarmCount, alarmOutput, emitReview)),
                new Transition("alarm", "security.review", "alarm", "true",
                        List.of(failAfterSideEffects)),
                new Transition("*", "reset", "locked", "state != 'locked'", List.of())
        );
        return new MachineDefinition("门禁示例", "door-v1", "locked", 20260924L,
                initialData, transitions);
    }

    static List<EventRecord> sampleEvents() {
        return List.of(
                new EventRecord("evt_low_scan", "scan", 10, 20, 1, false,
                        Map.of("requestId", "r-100", "cardLevel", 2)),
                new EventRecord("evt_high_scan", "scan", 10, 10, 2, false,
                        Map.of("requestId", "r-101", "cardLevel", 5)),
                new EventRecord("evt_alarm", "alarm", 12, 5, 1, false,
                        Map.of("code", "door-forced", "requestId", "r-102"))
        );
    }

    private static Action action(String type, String path, Object value, String event,
                                 String output, long delay) {
        return new Action(type, path, value, event, output, 10, delay, new LinkedHashMap<>());
    }

    private static Action outputAction(String name, Map<String, Object> value) {
        return new Action("output", null, value, null, name, 10, 0, new LinkedHashMap<>());
    }

    private static Action emitAction(String event, long delay, Map<String, Object> payload) {
        return new Action("emit", null, null, event, null, 10, delay, new LinkedHashMap<>(payload));
    }

    private Defaults() {
    }
}
