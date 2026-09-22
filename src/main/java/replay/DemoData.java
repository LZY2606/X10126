package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in demo machine: a tiny order fulfillment workflow. */
public final class DemoData {

    private DemoData() {
    }

    public static Map<String, Object> newProject() {
        Map<String, Object> definition = definition();
        Models.Definition def = new Models.Definition(definition);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", "root");
        root.put("definition", def.fingerprint);
        root.put("initial", DeterministicEngine.initialState(def));
        root.put("hash", Hashes.sha256("root:" + def.fingerprint));
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("root", root);

        Map<String, Object> main = new LinkedHashMap<>();
        main.put("head", "root");
        Map<String, Object> branches = new LinkedHashMap<>();
        branches.put("main", main);

        Map<String, Object> project = new LinkedHashMap<>();
        project.put("schemaVersion", 1);
        project.put("title", "状态机回放室 - 示例会话");
        project.put("definition", definition);
        project.put("initial", DeterministicEngine.initialState(def));
        project.put("events", events());
        project.put("nodes", nodes);
        project.put("branches", branches);
        project.put("checkpoints", new LinkedHashMap<>());
        project.put("root", "root");
        project.put("nextNodeSeq", 1L);
        return project;
    }

    public static Map<String, Object> definition() {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", "订单履约状态机");
        definition.put("seed", 42);
        definition.put("initial", "idle");
        definition.put("states", List.of("idle", "paid", "packing", "shipped", "cancelled"));

        List<Object> transitions = new ArrayList<>();
        transitions.add(transition("下单", "idle", "order_placed", "paid", null, List.of(
                action("set", "orderId", "=$event.id"),
                action("inc", "orders", null),
                logAction("=$state + ' 收到订单 ' + $event.id + '，金额 ' + payload.amount"))));
        transitions.add(transition("支付成功", "paid", "payment", "packing",
                "payload.ok == true", List.of(
                        action("inc", "paidCount", null),
                        emitAction("reserve_stock", Map.of("warehouse", "=payload.warehouse")),
                        logAction("='库存预留中，随机库位 ' + random(100)"))));
        transitions.add(transition("支付失败", "paid", "payment", "cancelled",
                "payload.ok != true", List.of(
                        action("inc", "failedPayments", null),
                        failAction("='支付被拒绝: ' + payload.reason"))));
        transitions.add(transition("库存预留完成", "packing", "reserve_stock", "shipped", null,
                List.of(
                        action("inc", "shipped", null),
                        logAction("='已从 ' + payload.warehouse + ' 发货，追踪号 ' + range(1000, 9999)"))));
        transitions.add(transition("取消", "*", "cancel", "cancelled", null, List.of(
                action("inc", "cancelled", null),
                logAction("='订单取消于 t=' + $eventTime"))));
        definition.put("transitions", transitions);
        return definition;
    }

    private static Map<String, Object> transition(String name, String from, String on,
                                                  String to, String when,
                                                  List<Object> actions) {
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("name", name);
        transition.put("from", from);
        transition.put("on", on);
        transition.put("to", to);
        if (when != null) {
            transition.put("when", when);
        }
        transition.put("actions", actions);
        return transition;
    }

    private static Map<String, Object> action(String type, String name, String value) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", type);
        action.put("name", name);
        if (value != null) {
            action.put("value", value);
        }
        return action;
    }

    private static Map<String, Object> logAction(String message) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "log");
        action.put("message", message);
        return action;
    }

    private static Map<String, Object> emitAction(String event, Map<String, Object> payload) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "emit");
        action.put("event", event);
        action.put("payload", payload);
        return action;
    }

    private static Map<String, Object> failAction(String message) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "fail");
        action.put("message", message);
        return action;
    }

    private static List<Object> events() {
        List<Object> events = new ArrayList<>();
        // Two events at the same logical time: lower priority value wins, then seq.
        events.add(event("e10", "order_placed", 100, 1, 1,
                "{\"amount\": 120, \"warehouse\": \"east\"}"));
        events.add(event("e11", "cancel", 100, 0, 2, "{\"reason\": \"user changed mind\"}"));
        events.add(event("e20", "payment", 200, 0, 1,
                "{\"ok\": true, \"warehouse\": \"east\"}"));
        events.add(event("e30", "order_placed", 300, 0, 1,
                "{\"amount\": 55, \"warehouse\": \"north\"}"));
        events.add(event("e31", "payment", 300, 0, 2,
                "{\"ok\": false, \"reason\": \"card declined\"}"));
        return events;
    }

    private static Map<String, Object> event(String id, String type, long time,
                                             long priority, long seq, String payloadJson) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("type", type);
        event.put("time", time);
        event.put("priority", priority);
        event.put("seq", seq);
        event.put("payload", Json.parseObject(payloadJson));
        event.put("internal", false);
        return event;
    }
}
