package gsb.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 内置演示状态机与事件日志，首次启动且数据目录为空时自动播种。 */
public final class Demo {
    private Demo() {
    }

    public static Map<String, Object> definition() {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", "订单处理演示机");
        def.put("states", List.of("IDLE", "PROCESSING", "SETTLED", "DONE"));
        def.put("initialState", "IDLE");
        def.put("initialVars", Map.of(
                "count", 0.0,
                "total", 0.0,
                "last", "",
                "attempts", 0.0));
        def.put("seed", 4242L);

        List<Object> transitions = new ArrayList<>();

        transitions.add(Map.of(
                "event", "order",
                "sources", List.of("IDLE"),
                "target", "PROCESSING",
                "actions", List.of(
                        Map.of("op", "set", "path", "count", "value",
                                List.of("add", "$.count", 1)),
                        Map.of("op", "set", "path", "total", "value",
                                List.of("add", "$.total", List.of("payload", "amount"))),
                        Map.of("op", "set", "path", "last", "value", List.of("payload", "id")),
                        Map.of("op", "output", "channel", "audit", "value",
                                List.of("concat", "accepted:", List.of("payload", "id"))),
                        Map.of("op", "emit", "type", "settle", "delay", 2L,
                                "payload", Map.of("amount", List.of("payload", "amount")))
                )));

        transitions.add(Map.of(
                "event", "settle",
                "sources", List.of("PROCESSING"),
                "condition", List.of("ge", "$.total", 10),
                "target", "DONE",
                "actions", List.of(
                        Map.of("op", "emit", "type", "receipt", "delay", 0L,
                                "payload", Map.of("total", "$.total")),
                        Map.of("op", "output", "channel", "audit", "value",
                                List.of("concat", "settled-total:", "$.total"))
                )));

        // 金额不足：进入 SETTLED，等待补款
        transitions.add(Map.of(
                "event", "settle",
                "sources", List.of("PROCESSING"),
                "condition", List.of("lt", "$.total", 10),
                "target", "SETTLED",
                "actions", List.of(
                        Map.of("op", "set", "path", "attempts", "value",
                                List.of("add", "$.attempts", 1)),
                        Map.of("op", "output", "channel", "audit", "value", "need-more-funds"))));

        transitions.add(Map.of(
                "event", "topup",
                "sources", List.of("SETTLED"),
                "target", "PROCESSING",
                "actions", List.of(
                        Map.of("op", "set", "path", "total", "value",
                                List.of("add", "$.total", List.of("payload", "amount"))),
                        Map.of("op", "emit", "type", "settle", "delay", 1L,
                                "payload", Map.of("amount", List.of("payload", "amount"))))));

        // 动作失败：随机判定（确定性种子），失败时本次状态修改/内部事件全部回滚
        transitions.add(Map.of(
                "event", "risk",
                "target", "IDLE",
                "condition", List.of("lt", List.of("random", 10), 99),
                "actions", List.of(
                        Map.of("op", "set", "path", "last", "value", "should-not-persist"),
                        Map.of("op", "fail", "when", List.of("lt", List.of("random", 4), 2),
                                "message", "risk check failed (rolled back)"))));

        def.put("transitions", transitions);
        return def;
    }

    public static void seedDemo(Replay replay, Map<String, Object> session) {
        List<Object> events = new ArrayList<>();
        // 同一逻辑时刻 10：来源优先级 + 原始序号决定顺序
        events.add(ext("evt-1", "order", 10, 1, 100, "gateway",
                Map.of("id", "A-1", "amount", 7.0)));
        events.add(ext("evt-2", "risk", 10, 5, 101, "risk-engine", Map.of()));
        events.add(ext("evt-3", "risk", 10, 5, 102, "risk-engine", Map.of()));
        events.add(ext("evt-4", "topup", 13, 2, 103, "ops",
                Map.of("amount", 5.0)));
        replay.importEvents(session, "main", events);

        // 先处理到 settle 内部事件出现后建检查点，方便演示分叉
        replay.runTo(session, "main", 4);
        replay.checkpoint(session, "main", "初始处理后");
    }

    private static Map<String, Object> ext(String id, String type, long time, long priority,
                                           long seq, String source, Map<String, Object> payload) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id);
        e.put("type", type);
        e.put("time", time);
        e.put("priority", priority);
        e.put("seq", seq);
        e.put("source", source);
        e.put("payload", payload);
        return e;
    }
}
