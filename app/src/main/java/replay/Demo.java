package replay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Demo {
    private Demo() {}

    static Models.Definition definition() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", "demo-v1");
        json.put("initialState", "idle");
        json.put("seed", 20260923L);
        json.put("initialVariables", Map.of("accepted", 0L, "retries", 0L, "last", ""));
        json.put("sourcePriority", Map.of("operator", 10, "sensor", 5, "timer", 1, "internal", 100));
        json.put("transitions", List.of(
                Map.of(
                        "name", "accept-order",
                        "from", "idle",
                        "event", "order",
                        "condition", "data.kind == 'normal'",
                        "to", "accepted",
                        "actions", List.of(
                                Map.of("type", "set", "target", "vars.accepted", "value", "vars.accepted + 1"),
                                Map.of("type", "set", "target", "vars.last", "value", "data.id"),
                                Map.of("type", "output", "name", "orderAccepted", "payload", "event.id"),
                                Map.of("type", "emit", "event", "reserve", "timeDelta", 0L, "source", "internal", "sequence", 1L, "payload", "'stock'"))),
                Map.of(
                        "name", "reserve-stock",
                        "from", "accepted",
                        "event", "reserve",
                        "condition", "data == 'stock'",
                        "to", "reserved",
                        "actions", List.of(
                                Map.of("type", "output", "name", "stockReserved"),
                                Map.of("type", "randomInt", "target", "vars.bin", "min", "1", "max", "4"))),
                Map.of(
                        "name", "bad-order-retry",
                        "from", "idle",
                        "event", "order",
                        "condition", "data.kind == 'bad'",
                        "to", "failed",
                        "actions", List.of(
                                Map.of("type", "set", "target", "vars.retries", "value", "vars.retries + 1"),
                                Map.of("type", "output", "name", "beforeFailure"),
                                Map.of("type", "fail", "error", "'inventory rejected bad order'", "payload", "event.id"),
                                Map.of("type", "set", "target", "vars.last", "value", "data.id"))),
                Map.of(
                        "name", "reset",
                        "from", "*",
                        "event", "reset",
                        "condition", "",
                        "to", "idle",
                        "actions", List.of(Map.of("type", "output", "name", "reset")))
        ));
        return DefinitionCodec.parse(json);
    }
}
