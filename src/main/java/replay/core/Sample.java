package replay.core;

import replay.json.Json;

/** Built-in demo definition + event log so the UI is usable immediately. */
public final class Sample {
    private Sample() {}

    public static Definition definition() {
        return Definition.fromJson(Json.parseObject("""
            {
              "name": "link-monitor",
              "initialState": "IDLE",
              "states": ["IDLE", "CONNECTING", "ONLINE", "DEGRADED", "DOWN"],
              "sources": {"sensor": 1, "operator": 2, "audit": 3},
              "transitions": [
                {"from": "IDLE", "event": "dial", "to": "CONNECTING",
                 "actions": [{"set": "attempts", "value": 1}, {"output": "dialing (attempt ${attempts})"}]},
                {"from": "CONNECTING", "event": "dial",
                 "actions": [{"set": "attempts", "expr": "attempts + 1"}, {"output": "redial #${attempts}"}]},
                {"from": "CONNECTING", "event": "linkUp", "to": "ONLINE",
                 "actions": [{"set": "loss", "value": 0}, {"output": "link is up"}]},
                {"from": "ONLINE", "event": "packetLoss", "condition": "event.payload.loss > 20", "to": "DEGRADED",
                 "actions": [{"set": "loss", "expr": "event.payload.loss"},
                             {"output": "degraded: loss ${loss}%"},
                             {"emit": "pageOperator", "payload": {"loss": {"$expr": "event.payload.loss"}}}]},
                {"from": "ONLINE", "event": "packetLoss",
                 "actions": [{"set": "loss", "expr": "event.payload.loss"}]},
                {"from": "DEGRADED", "event": "packetLoss", "condition": "event.payload.loss <= 20", "to": "ONLINE",
                 "actions": [{"output": "recovered, loss ${event.payload.loss}%"}]},
                {"from": "*", "event": "pageOperator",
                 "actions": [{"output": "operator paged: loss ${event.payload.loss}%"}]},
                {"from": "*", "event": "linkDown", "to": "DOWN",
                 "actions": [{"set": "loss", "value": 100}, {"output": "link is DOWN"}]},
                {"from": "DOWN", "event": "reset", "to": "IDLE",
                 "actions": [{"set": "attempts", "value": 0}, {"output": "reset to idle"}]},
                {"from": "*", "event": "selfTest",
                 "actions": [{"set": "probe", "expr": "rand(100)"},
                             {"failIf": "event.payload.mode == 'strict' && loss > 50", "message": "self-test rejected while loss too high"},
                             {"output": "self-test probe=${probe}"}]}
              ]
            }
            """));
    }

    public static String sampleEventsJson() {
        return """
            [
              {"time": 10, "source": "operator", "type": "dial"},
              {"time": 20, "source": "sensor", "type": "linkUp"},
              {"time": 30, "source": "sensor", "type": "packetLoss", "payload": {"loss": 5}},
              {"time": 40, "source": "sensor", "type": "packetLoss", "payload": {"loss": 35}},
              {"time": 40, "source": "audit", "type": "selfTest", "payload": {"mode": "strict"}},
              {"time": 50, "source": "sensor", "type": "packetLoss", "payload": {"loss": 8}},
              {"time": 60, "source": "sensor", "type": "linkDown"},
              {"time": 70, "source": "operator", "type": "reset"}
            ]
            """;
    }
}
