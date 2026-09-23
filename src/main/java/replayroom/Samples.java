package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Samples {
    private Samples() {
    }

    static Workspace workspace() {
        String definitionJson = """
            {
              "name": "工单审批状态机",
              "version": 3,
              "seed": 20260924,
              "initialState": "idle",
              "initialVars": {"attempts": 0, "score": 0},
              "states": ["idle", "submitted", "approved", "rejected", "done"],
              "priorities": {"ops": 5, "api": 10, "timer": 20, "internal": 1},
              "transitions": [
                {
                  "event": "poison",
                  "from": "idle",
                  "actions": [
                    {"type": "set", "name": "attempts", "value": 99},
                    {"type": "fail", "message": "毒物事件必须回滚"}
                  ]
                },
                {
                  "event": "submit",
                  "from": "idle",
                  "to": "submitted",
                  "actions": [
                    {"type": "random", "name": "score", "bound": 100},
                    {"type": "add", "name": "attempts", "delta": 1},
                    {"type": "emitEvent", "eventType": "riskCheck", "time": "=event.time", "source": {"name": "internal", "priority": 1, "seq": 0}},
                    {"type": "emit", "channel": "audit", "payload": "=event.payload"}
                  ]
                },
                {
                  "event": "riskCheck",
                  "from": "submitted",
                  "actions": [
                    {"type": "emit", "channel": "risk", "payload": {"score": "=state.score", "ticket": "=event.payload.ticket"}}
                  ]
                },
                {
                  "event": "approve",
                  "from": "submitted",
                  "to": "approved",
                  "actions": [
                    {"type": "emit", "channel": "result", "payload": {"decision": "approved", "ticket": "=event.payload.ticket"}}
                  ]
                },
                {
                  "event": "complete",
                  "from": "approved",
                  "to": "done",
                  "actions": [
                    {"type": "emit", "channel": "result", "payload": {"decision": "completed"}}
                  ]
                },
                {
                  "event": "cancel",
                  "from": "submitted",
                  "to": "rejected",
                  "actions": [
                    {"type": "emit", "channel": "result", "payload": {"decision": "rejected"}}
                  ]
                }
              ]
            }
            """;
        Models.Definition definition = Models.Definition.fromMap(Json.parse(definitionJson));
        definition.validate();
        List<Models.EventEnvelope> events = List.of(
            event("evt-poison", "poison", 1, "ops", 1, Map.of("reason", "demo")),
            event("evt-submit", "submit", 1, "api", 1, Map.of("ticket", "T-100", "amount", 42)),
            event("evt-timeout", "timeout", 2, "timer", 1, Map.of()),
            event("evt-approve", "approve", 3, "api", 2, Map.of("ticket", "T-100", "reviewer", "ada")),
            event("evt-complete", "complete", 4, "api", 3, Map.of())
        );
        Branch main = new Branch(
            "branch-main", "主干", definition.fingerprint(), null, null, null,
            definition.initialState(), new LinkedHashMap<>(definition.initialVars()), definition.seed(),
            new ArrayList<>(events), new ArrayList<>(), 0, new ArrayList<>(), new ArrayList<>(),
            Engine.genesisHash(definition), false
        );
        Checkpoint genesis = new Checkpoint(
            "genesis", "初始检查点", main.id(), 0L, definition.fingerprint(), main.lastTraceHash(),
            Engine.snapshot(definition.initialState(), definition.initialVars()), definition.initialState(),
            new LinkedHashMap<>(definition.initialVars()), definition.seed(), 0, new ArrayList<>(),
            new ArrayList<>(), null
        );
        return new Workspace("workspace-demo", 1, definition, List.of(main), List.of(genesis), main.id(), 0L, null);
    }

    private static Models.EventEnvelope event(String id, String type, long time, String source, long seq, Map<String, Object> payload) {
        int priority = source.equals("ops") ? 5 : source.equals("timer") ? 20 : 10;
        return new Models.EventEnvelope(id, type, time, source, seq, new LinkedHashMap<>(payload), false, null, 0L, priority, seq - 1);
    }
}
