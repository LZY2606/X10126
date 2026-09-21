package io.example.replay;

import java.util.List;
import java.util.Map;

public final class SampleData {
    private SampleData() {
    }

    public static Object definition() {
        return Map.of(
                "name", "ticket-room",
                "version", "demo-v1",
                "initialState", "idle",
                "states", List.of("idle", "processing", "waiting", "failed", "done"),
                "initialData", Map.of("count", 0, "accepted", 0, "log", List.of()),
                "transitions", List.of(
                        Map.of(
                                "id", "start-high-priority",
                                "from", "idle",
                                "event", "start",
                                "condition", "payload.priority >= 5",
                                "to", "processing",
                                "actions", List.of(
                                        Map.of("type", "set", "path", "data.count",
                                                "expression", "data.count + 1"),
                                        Map.of("type", "output", "name", "accepted",
                                                "expression", "event.type"),
                                        Map.of("type", "emit", "eventType", "audit",
                                                "payload", Map.of("source", "action", "n", 1))
                                )
                        ),
                        Map.of(
                                "id", "start-low-priority",
                                "from", "idle",
                                "event", "start",
                                "condition", "payload.priority < 5",
                                "to", "waiting",
                                "actions", List.of(
                                        Map.of("type", "set", "path", "data.accepted", "value", 0),
                                        Map.of("type", "output", "name", "deferred", "value", true)
                                )
                        ),
                        Map.of(
                                "id", "audit",
                                "from", "*",
                                "event", "audit",
                                "to", "processing",
                                "actions", List.of(
                                        Map.of("type", "set", "path", "data.accepted",
                                                "expression", "data.accepted + payload.n"),
                                        Map.of("type", "output", "name", "audited",
                                                "expression", "payload.n")
                                )
                        ),
                        Map.of(
                                "id", "bad-audit-rollback",
                                "from", "*",
                                "event", "bad",
                                "to", "failed",
                                "actions", List.of(
                                        Map.of("type", "set", "path", "data.count",
                                                "expression", "data.count + 100"),
                                        Map.of("type", "emit", "eventType", "audit",
                                                "payload", Map.of("n", 99)),
                                        Map.of("type", "fail", "message", "simulated action failure")
                                )
                        ),
                        Map.of(
                                "id", "finish",
                                "from", "processing",
                                "event", "finish",
                                "to", "done",
                                "actions", List.of(
                                        Map.of("type", "output", "name", "done", "value", true)
                                )
                        )
                )
        );
    }

    public static Object events() {
        return List.of(
                Map.of("id", "e-low", "type", "start", "time", 10, "source", "api",
                        "priority", 20, "originalSeq", 2,
                        "payload", Map.of("priority", 1)),
                Map.of("id", "e-high", "type", "start", "time", 10, "source", "ops",
                        "priority", 5, "originalSeq", 1,
                        "payload", Map.of("priority", 9)),
                Map.of("id", "e-bad", "type", "bad", "time", 11, "source", "ops",
                        "priority", 1, "originalSeq", 1, "payload", Map.of()),
                Map.of("id", "e-finish", "type", "finish", "time", 12, "source", "api",
                        "priority", 1, "originalSeq", 1, "payload", Map.of())
        );
    }
}
