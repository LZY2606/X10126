package replay;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {

    private static Map<String, Object> def(Object... kv) {
        Map<String, Object> m = Json.newObj();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Event ev(String id, long time, String source, long seq, String name) {
        return new Event(id, time, source, seq, name, Json.newObj(), false, 0);
    }

    // 1. 同时刻到达的事件按 来源优先级 + 原始序号 稳定排序
    @Test
    void sameTimestampOrderingIsDeterministic() {
        MachineDefinition def = MachineDefinition.parse(def(
                "id", "m", "version", 1, "initialState", "S",
                "sources", Map.of("high", 0, "low", 5),
                "transitions", List.of(
                        def("event", "E", "actions", List.of(def("type", "increment", "var", "n"))))));
        Engine engine = new Engine(def, 7);
        // deliberately inserted out of order
        engine.enqueueExternal(List.of(
                ev("e-low-late", 10, "low", 9, "E"),
                ev("e-high-late", 10, "high", 2, "E"),
                ev("e-low-early", 10, "low", 1, "E"),
                ev("e-high-early", 10, "high", 1, "E"),
                ev("e-later-time", 11, "high", 0, "E")));
        StringBuilder order = new StringBuilder();
        Map<String, Object> entry;
        while ((entry = engine.step()) != null) {
            order.append(entry.get("eventId")).append(',');
        }
        assertEquals("e-high-early,e-high-late,e-low-early,e-low-late,e-later-time,", order.toString());
        assertEquals(5L, engine.vars().get("n"));
    }

    // 2. 动作派生的内部事件排在当前事件之后（含同刻更高优先级外部事件之后）
    @Test
    void internalEventsAreQueuedAfterCurrentEvent() {
        MachineDefinition def = MachineDefinition.parse(def(
                "id", "m", "version", 1, "initialState", "S",
                "sources", Map.of("ext", 0),
                "transitions", List.of(
                        def("event", "GO", "actions", List.of(
                                def("type", "raise", "event", "INNER"),
                                def("type", "setVar", "var", "order", "go"))),
                        def("event", "AFTER", "actions", List.of(
                                def("type", "setVar", "var", "order", "after"))),
                        def("event", "INNER", "actions", List.of(
                                def("type", "setVar", "var", "innerSeen", true))))));
        Engine engine = new Engine(def, 1);
        // GO and AFTER at the same logical time; GO sorts first (lower seq)
        engine.enqueueExternal(List.of(
                ev("go", 5, "ext", 1, "GO"),
                ev("after", 5, "ext", 2, "AFTER")));
        Map<String, Object> first = engine.step();
        assertEquals("go", first.get("eventId"));
        // internal event must not jump ahead of the still-pending external event
        Map<String, Object> second = engine.step();
        assertEquals("after", second.get("eventId"));
        Map<String, Object> third = engine.step();
        assertEquals("INNER", third.get("eventName"));
        assertEquals(Boolean.TRUE, third.get("internal"));
        assertNull(engine.step());
        assertEquals(true, engine.vars().get("innerSeen"));
    }

    // 3. 动作失败：状态改变与派生内部事件一起回滚，失败记录仍进轨迹
    @Test
    void actionFailureRollsBackButIsTraced() {
        MachineDefinition def = MachineDefinition.parse(def(
                "id", "m", "version", 1, "initialState", "OK",
                "transitions", List.of(
                        def("event", "BOOM", "actions", List.of(
                                def("type", "setState", "state", "BROKEN"),
                                def("type", "setVar", "var", "x", 99),
                                def("type", "raise", "event", "ALARM"),
                                def("type", "fail", "message", "handler exploded"))),
                        def("event", "ALARM", "actions", List.of(
                                def("type", "setVar", "var", "alarmed", true))))));
        Engine engine = new Engine(def, 1);
        engine.enqueueExternal(List.of(ev("b1", 1, "ext", 1, "BOOM")));
        Map<String, Object> entry = engine.step();
        assertEquals("handler exploded", entry.get("failure"));
        // rolled back
        assertEquals("OK", engine.state());
        assertEquals("OK", entry.get("stateAfter"));
        assertNull(engine.vars().get("x"));
        // derived internal event rolled back too: queue is empty
        assertNull(engine.step());
        assertNull(engine.vars().get("alarmed"));
        // failure still recorded in the trace and hash chain advanced
        assertEquals(1, engine.trace().size());
        assertNotNull(entry.get("hash"));
    }

    // 条件读取的是处理事件前的快照
    @Test
    void conditionsReadPreEventSnapshot() {
        MachineDefinition def = MachineDefinition.parse(def(
                "id", "m", "version", 1, "initialState", "S",
                "initialVariables", Map.of("n", 0),
                "transitions", List.of(
                        def("event", "A", "condition", def("var", "n", "op", "==", "value", 0),
                                "actions", List.of(def("type", "increment", "var", "n"),
                                        def("type", "emit", "name", "first-only"))))));
        Engine engine = new Engine(def, 1);
        engine.enqueueExternal(List.of(ev("a1", 1, "ext", 1, "A"), ev("a2", 2, "ext", 2, "A")));
        Map<String, Object> first = engine.step();
        assertEquals(true, first.get("matched"));
        Map<String, Object> second = engine.step();
        // second event sees n=1 in the pre-event snapshot, so the condition fails
        assertEquals(false, second.get("matched"));
    }

    // 随机种子锁定 → 相同输入产生相同轨迹
    @Test
    void seededRandomnessIsReproducible() {
        Map<String, Object> raw = def(
                "id", "m", "version", 1, "initialState", "S",
                "transitions", List.of(
                        def("event", "R", "actions", List.of(
                                def("type", "setVar", "var", "roll", "random", 1000000)))));
        MachineDefinition def = MachineDefinition.parse(raw);
        Engine a = new Engine(def, 123);
        Engine b = new Engine(def, 123);
        a.enqueueExternal(List.of(ev("r1", 1, "ext", 1, "R"), ev("r2", 2, "ext", 2, "R")));
        b.enqueueExternal(List.of(ev("r1", 1, "ext", 1, "R"), ev("r2", 2, "ext", 2, "R")));
        a.run(10);
        b.run(10);
        assertEquals(a.vars().get("roll"), b.vars().get("roll"));
        assertEquals(a.trajectoryHash(), b.trajectoryHash());
    }
}
