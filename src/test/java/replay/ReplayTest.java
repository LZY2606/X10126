package replay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReplayTest {

    private static Definition def(Map<String, Object> events) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", "v1");
        json.put("states", List.of("idle", "open", "closed", "alarm"));
        json.put("sources", List.of("sensor", "operator", "timer"));
        json.put("events", events);
        return Definition.fromJson(json);
    }

    private static Map<String, Object> ev(String condition, List<Map<String, Object>> actions) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (condition != null) m.put("condition", condition);
        m.put("actions", actions);
        return m;
    }

    private static Map<String, Object> transition(String state) {
        return Map.of("type", "transition", "state", state);
    }

    private static Map<String, Object> set(String var, String expr) {
        return Map.of("type", "set", "var", var, "expr", expr);
    }

    private static Map<String, Object> emit(String event, Map<String, Object> payload) {
        return Map.of("type", "emit", "event", event, "payload", payload);
    }

    private static Map<String, Object> output(String expr) {
        return Map.of("type", "output", "expr", expr);
    }

    private static EventInstance at(long time, String source, long seq, String name) {
        return new EventInstance(time, source, seq, name, Map.of(), false);
    }

    // 1) Same logical time: order decided by source priority, then original seq.
    @Test
    void sameTimestampOrderingByPriorityThenSeq() {
        Definition def = def(Map.of(
                "mark", ev(null, List.of(set("trail", "trail + payload.who")))));
        Engine engine = new Engine(def, "idle", Map.of("trail", ""), 7L);
        // Arrive out of order; all at logical time 5.
        engine.enqueue(new EventInstance(5, "timer", 2, "mark", Map.of("who", "T2"), false));
        engine.enqueue(new EventInstance(5, "operator", 9, "mark", Map.of("who", "O9"), false));
        engine.enqueue(new EventInstance(5, "sensor", 3, "mark", Map.of("who", "S3"), false));
        engine.enqueue(new EventInstance(5, "sensor", 1, "mark", Map.of("who", "S1"), false));
        engine.enqueue(new EventInstance(5, "operator", 4, "mark", Map.of("who", "O4"), false));
        while (engine.step() != null) { }
        // sensor(p0) < operator(p1) < timer(p2); within a source, seq ascends.
        assertEquals("S1S3O4O9T2", engine.vars().get("trail"));
        List<TraceEntry> trace = engine.trace();
        assertEquals("S1", trace.get(0).event.payload.get("who"));
        assertEquals("S3", trace.get(1).event.payload.get("who"));
        assertEquals("O4", trace.get(2).event.payload.get("who"));
    }

    // 2) Internal events queue behind the current event, before later-time externals.
    @Test
    void internalEventsQueuedAfterCurrentEvent() {
        Definition def = def(Map.of(
                "go", ev(null, List.of(
                        set("order", "order + payload.who"),
                        emit("inner", Map.of("who", "\"I\"")))),
                "inner", ev(null, List.of(set("order", "order + payload.who")))));
        Engine engine = new Engine(def, "idle", Map.of("order", ""), 1L);
        engine.enqueue(new EventInstance(10, "sensor", 1, "go", Map.of("who", "A"), false));
        engine.enqueue(new EventInstance(10, "sensor", 2, "go", Map.of("who", "B"), false));
        engine.enqueue(new EventInstance(11, "sensor", 3, "go", Map.of("who", "C"), false));
        while (engine.step() != null) { }
        // A, then A's inner, then B, then B's inner, then C, C's inner.
        assertEquals("AIBICI", engine.vars().get("order"));
        List<TraceEntry> trace = engine.trace();
        assertTrue(trace.get(1).event.internal);
        assertEquals("inner", trace.get(1).event.name);
        assertEquals(10, trace.get(1).event.time);
    }

    // 3) Condition reads the pre-event snapshot.
    @Test
    void conditionSeesPreEventSnapshot() {
        Definition def = def(Map.of(
                "bump", ev("count < 2", List.of(set("count", "count + 1"))),
                "check", ev("count == 1", List.of(transition("open")))));
        Engine engine = new Engine(def, "idle", Map.of("count", 0L), 1L);
        engine.enqueue(at(1, "sensor", 1, "bump"));
        engine.enqueue(at(2, "sensor", 2, "check"));
        engine.enqueue(at(3, "sensor", 3, "bump"));
        engine.enqueue(at(4, "sensor", 4, "bump")); // count==2 by now -> skipped
        while (engine.step() != null) { }
        assertEquals("open", engine.state());
        assertEquals(2L, engine.vars().get("count"));
        assertEquals("skipped", engine.trace().get(3).status());
    }

    // 4) Failed action rolls back state changes and derived internal events, failure still traced.
    @Test
    void failedActionRollsBackStateAndDerivedEvents() {
        Definition def = def(Map.of(
                "boom", ev(null, List.of(
                        set("count", "count + 100"),
                        transition("open"),
                        emit("inner", Map.of()),
                        transition("nowhere") // undefined state -> failure
                )),
                "inner", ev(null, List.of(set("count", "count + 1")))));
        Engine engine = new Engine(def, "idle", Map.of("count", 1L), 1L);
        engine.enqueue(at(1, "sensor", 1, "boom"));
        TraceEntry entry = engine.step();
        assertEquals("failed", entry.status());
        assertTrue(entry.error().contains("nowhere"));
        assertEquals("idle", engine.state());           // transition rolled back
        assertEquals(1L, engine.vars().get("count"));   // set rolled back
        assertNull(engine.step());                      // derived internal event discarded
        assertEquals(1, engine.trace().size());         // failure recorded in trace
    }

    // 5) Checkpoints validate the definition fingerprint.
    @Test
    void checkpointRejectsMismatchedDefinitionVersion() {
        Definition v1 = def(Map.of("go", ev(null, List.of(transition("open")))));
        Session s1 = Session.create("s1", v1, "idle", Map.of(), 1L, List.of(at(1, "sensor", 1, "go")));
        s1.engine.step();
        Checkpoint cp = s1.checkpoint("after-go");

        // Same machine shape but a new definition version -> different fingerprint.
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", "v2");
        json.put("states", List.of("idle", "open", "closed", "alarm"));
        json.put("sources", List.of("sensor", "operator", "timer"));
        json.put("events", Map.of("go", ev(null, List.of(transition("open")))));
        Definition v2 = Definition.fromJson(json);
        assertNotEquals(v1.fingerprint(), v2.fingerprint());

        Session s2 = Session.create("s2", v2, "idle", Map.of(), 1L, List.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> s2.restore(cp));
        assertTrue(ex.getMessage().contains("fingerprint"));

        // Same definition restores fine.
        s1.restore(cp);
        assertEquals("open", s1.engine.state());
    }

    // 6) Branch merge: conflict reported with first conflicting group; compatible branches merge.
    @Test
    void branchMergeConflictAndCompatibleMerge() {
        Definition def = def(Map.of(
                "mark", ev(null, List.of(set("trail", "trail + payload.who")))));
        Session root = Session.create("root", def, "idle", Map.of("trail", ""), 1L,
                List.of(new EventInstance(1, "sensor", 1, "mark", Map.of("who", "R"), false)));
        root.engine.step();
        Checkpoint cp = root.checkpoint("base");

        Session a = root.branch(cp, "A");
        Session b = root.branch(cp, "B");
        a.addEvents(List.of(new EventInstance(5, "sensor", 1, "mark", Map.of("who", "A"), false)));
        b.addEvents(List.of(new EventInstance(5, "sensor", 2, "mark", Map.of("who", "B"), false)));
        while (a.engine.step() != null) { }
        while (b.engine.step() != null) { }

        Merge.Result conflict = Merge.merge(a, b);
        assertNull(conflict.merged);
        assertNotNull(conflict.conflict);
        assertFalse(conflict.conflict.firstConflictGroup.isEmpty());
        assertEquals(5, conflict.conflict.firstConflictGroup.get(0).time);

        // Compatible branches: identical external events since the ancestor.
        Session c = root.branch(cp, "C");
        Session d = root.branch(cp, "D");
        EventInstance shared = new EventInstance(7, "sensor", 1, "mark", Map.of("who", "X"), false);
        c.addEvents(List.of(shared));
        d.addEvents(List.of(shared));
        while (c.engine.step() != null) { }
        while (d.engine.step() != null) { }
        Merge.Result ok = Merge.merge(c, d);
        assertNull(ok.conflict);
        assertNotNull(ok.merged);
        assertEquals("RX", ok.merged.engine.vars().get("trail"));
        assertEquals(c.trajectoryHash(), ok.merged.trajectoryHash());
    }

    // 7) Export/import reproduces the identical trajectory hash; replay is deterministic.
    @Test
    void exportImportAndReplayDeterminism() {
        Definition def = def(Map.of(
                "go", ev(null, List.of(
                        set("n", "n + randInt(10)"),
                        emit("inner", Map.of()),
                        output("\"n=\" + n"))),
                "inner", ev(null, List.of(set("n", "n * 2")))));
        List<EventInstance> events = List.of(
                at(1, "sensor", 1, "go"), at(2, "sensor", 2, "go"), at(3, "sensor", 3, "go"));
        Session first = Session.create("x", def, "idle", Map.of("n", 0L), 42L, events);
        while (first.engine.step() != null) { }

        // Same inputs -> same fingerprint and trajectory.
        Session second = Session.create("y", def, "idle", Map.of("n", 0L), 42L, events);
        while (second.engine.step() != null) { }
        assertEquals(first.fingerprint, second.fingerprint);
        assertEquals(first.trajectoryHash(), second.trajectoryHash());

        // Export -> import -> identical trajectory hash.
        Map<String, Object> exported = first.toJson();
        Session imported = Session.fromJson(Json.parseObject(Json.canonical(exported)));
        assertEquals(first.trajectoryHash(), imported.trajectoryHash());
        assertEquals(first.fingerprint, imported.fingerprint);
        assertEquals(first.engine.state(), imported.engine.state());
        assertEquals(first.engine.vars(), imported.engine.vars());
    }

    // 8) Persistence: a store restart resumes the session mid-replay.
    @Test
    void persistenceAcrossRestart() {
        java.nio.file.Path dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("replay-store");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        Definition def = def(Map.of("go", ev(null, List.of(set("n", "n + 1")))));
        List<EventInstance> events = List.of(at(1, "sensor", 1, "go"), at(2, "sensor", 2, "go"), at(3, "sensor", 3, "go"));

        SessionStore store1 = new SessionStore(dir);
        Session s = Session.create("persisted", def, "idle", Map.of("n", 0L), 9L, events);
        store1.put(s);
        s.engine.step();
        store1.save(s);
        String hashAfterOne = s.trajectoryHash();

        SessionStore store2 = new SessionStore(dir); // simulated restart
        Session resumed = store2.get(s.id);
        assertEquals(hashAfterOne, resumed.trajectoryHash());
        assertEquals(1L, resumed.engine.vars().get("n"));
        assertEquals(2, resumed.engine.pending());
        while (resumed.engine.step() != null) { }
        assertEquals(3L, resumed.engine.vars().get("n"));
    }
}
