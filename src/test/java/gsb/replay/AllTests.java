package gsb.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 JDK 断言测试套件（无 JUnit、无 sleep、无网络）。
 * 使用虚拟逻辑时钟 time 字段决定时序。
 * 运行：./gradlew test
 */
public final class AllTests {
    private static int passed = 0;

    public static void main(String[] args) {
        sameTimestampOrdering();
        internalEventQueueing();
        actionFailureRollback();
        checkpointDefinitionRejection();
        branchConflictOnMerge();
        compatibleMerge();
        exportReplayHash();
        fingerprintStability();
        System.out.println("ALL TESTS PASSED: " + passed);
    }

    private static void check(boolean cond, String name) {
        if (!cond) {
            throw new AssertionError("FAILED: " + name);
        }
        passed++;
        System.out.println("  ok - " + name);
    }

    private static Map<String, Object> def() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", "test-machine");
        d.put("states", List.of("A", "B", "C"));
        d.put("initialState", "A");
        d.put("initialVars", new LinkedHashMap<>(Map.of("n", 0.0, "log", "")));
        d.put("seed", 7L);

        List<Object> tr = new ArrayList<>();
        // e1: A -> B，计数+1，输出，派生内部事件 inner（delay 0）
        tr.add(Map.of(
                "event", "e1", "sources", List.of("A"), "target", "B",
                "actions", List.of(
                        Map.of("op", "set", "path", "n", "value", List.of("add", "$.n", 1)),
                        Map.of("op", "output", "channel", "c", "value", "e1-fired"),
                        Map.of("op", "emit", "type", "inner", "delay", 0L,
                                "payload", Map.of("from", "e1")))));
        // inner: B -> C，追加日志
        tr.add(Map.of(
                "event", "inner", "sources", List.of("B"), "target", "C",
                "actions", List.of(
                        Map.of("op", "set", "path", "log", "value", "inner-ran"),
                        Map.of("op", "output", "channel", "c", "value", "inner-fired"))));
        // boom: 在 C 上执行 fail —— set 必须与 emit 一起回滚，失败进入轨迹
        tr.add(Map.of(
                "event", "boom", "sources", List.of("C"), "target", "A",
                "actions", List.of(
                        Map.of("op", "set", "path", "n", "value", 999),
                        Map.of("op", "emit", "type", "ghost", "delay", 0L, "payload", Map.of()),
                        Map.of("op", "fail", "message", "boom failure"))));
        // e2: B 上只在 n>=5 时触发（用于条件快照测试）
        tr.add(Map.of(
                "event", "e2", "sources", List.of("B"),
                "condition", List.of("ge", "$.n", 5), "target", "C",
                "actions", List.of(Map.of("op", "set", "path", "log", "value", "e2"))));
        tr.add(Map.of(
                "event", "noop", "target", "A"));
        d.put("transitions", tr);
        return d;
    }

    private static Map<String, Object> ext(String id, String type, long time, long priority, long seq) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id);
        e.put("type", type);
        e.put("time", time);
        e.put("priority", priority);
        e.put("seq", seq);
        e.put("source", "src-" + id);
        e.put("payload", new LinkedHashMap<>());
        return e;
    }

    private static Replay newReplay() {
        return new Replay();
    }

    private static Map<String, Object> newSession() {
        return newReplay().createSession(def());
    }

    // 1) 同一逻辑时刻：优先级高（priority 数值大）的先处理，同优先级按原始序号
    private static void sameTimestampOrdering() {
        System.out.println("[1] same-timestamp ordering");
        Replay rp = newReplay();
        Map<String, Object> s = newSession();
        // 逆序导入，验证稳定排序
        rp.importEvents(s, "main", List.of(
                ext("low-2", "noop", 10, 1, 202),
                ext("hi-2", "noop", 10, 9, 101),
                ext("hi-1", "noop", 10, 9, 100)));
        Map<String, Object> first = rp.peek(s, "main");
        check("hi-1".equals(first.get("id")), "highest priority then lowest seq first: " + first.get("id"));
        rp.step(s, "main");
        check("hi-2".equals(rp.peek(s, "main").get("id")), "same priority follows seq order");
        rp.step(s, "main");
        check("low-2".equals(rp.peek(s, "main").get("id")), "lower priority comes last at same time");
    }

    // 2) 内部事件排队：动作产生的内部事件排在当前事件之后，且先于同时刻外部事件
    private static void internalEventQueueing() {
        System.out.println("[2] internal event queueing");
        Replay rp = newReplay();
        Map<String, Object> s = newSession();
        rp.importEvents(s, "main", List.of(
                ext("e-at-10", "e1", 10, 9, 1),
                ext("x-at-10", "e2", 10, 1, 2)));
        Map<String, Object> step1 = rp.step(s, "main");
        check("e-at-10".equals(asMap(step1.get("event")).get("id")), "step1 is external e1");
        check("B".equals(step1.get("to")), "step1 transitions A->B");
        Map<String, Object> next = rp.peek(s, "main");
        check(Boolean.TRUE.equals(next.get("internal")), "queued event right after current is internal");
        check("inner".equals(next.get("type")), "internal event type is inner");

        Map<String, Object> step2 = rp.step(s, "main");
        check("inner".equals(asMap(step2.get("event")).get("type")), "step2 processes internal event");
        check("C".equals(step2.get("to")), "internal event transitions B->C");
        check("inner-ran".equals(asMap(branch(s).get("state")).get("log")),
                "internal event action effect persisted");
        // 高优先级外部 x-at-10 在内部事件之后才处理
        check("x-at-10".equals(rp.peek(s, "main").get("id")),
                "same-time external waits behind the internal chain");
    }

    // 3) 动作失败：状态修改 + 派生内部事件一起回滚，但失败记录进入轨迹
    private static void actionFailureRollback() {
        System.out.println("[3] action failure rollback");
        Replay rp = newReplay();
        Map<String, Object> s = newSession();
        rp.importEvents(s, "main", List.of(
                ext("e-at-10", "e1", 10, 1, 1),
                ext("boom-at-11", "boom", 11, 1, 2)));
        rp.runTo(s, "main", 0);
        Map<String, Object> b = branch(s);
        List<Map<String, Object>> trace = trace(b);
        Map<String, Object> boomEntry = null;
        for (Map<String, Object> e : trace) {
            if ("boom".equals(asMap(e.get("event")).get("type"))) {
                boomEntry = e;
            }
        }
        check(boomEntry != null, "boom event has a trace record");
        check("failed".equals(boomEntry.get("status")), "failure is recorded with status=failed");
        check(boomEntry.get("failure") != null, "failure message retained in trace");
        // 回滚：n 不能是 999；状态机状态保持 C（target A 不得提交）
        check(Double.valueOf(1.0).equals(asMap(b.get("state")).get("n")),
                "state mutation rolled back (n stays 1): " + asMap(b.get("state")).get("n"));
        check("C".equals(b.get("currentState")), "transition target rolled back, state stays C");
        // 派生的 ghost 内部事件不得入队：trace 中不能出现 ghost，待处理队列清空
        for (Map<String, Object> e : trace) {
            check(!"ghost".equals(asMap(e.get("event")).get("type")), "derived internal event rolled back");
        }
        check(!rp.canStep(s, "main"), "no ghost event queued after rollback");
        // 失败记录参与哈希链
        check(boomEntry.get("hash") != null, "failure entry is part of the hash chain");
    }

    // 4) 检查点版本拒绝：改定义后，旧定义检查点不能接到新定义上
    private static void checkpointDefinitionRejection() {
        System.out.println("[4] checkpoint definition fingerprint rejection");
        Replay rp = newReplay();
        Map<String, Object> s = newSession();
        rp.importEvents(s, "main", List.of(ext("e-at-10", "e1", 10, 1, 1)));
        rp.step(s, "main");
        Map<String, Object> cp = rp.checkpoint(s, "main", "old-cp");
        String oldFp = (String) cp.get("definitionFingerprint");

        // 模拟“新定义”：另一个会话，版本递增、状态集变化
        Map<String, Object> newDefInput = def();
        newDefInput.put("states", List.of("A", "B", "C", "D"));
        newDefInput.put("seed", 99L);
        Map<String, Object> s2 = rp.createSession(newDefInput);
        // 把旧检查点放到新会话里（等价于把旧落盘检查点接到新定义）
        Replay.checkpoints(s2).put("cp-old", cp);
        boolean rejected = false;
        try {
            rp.fork(s2, "cp-old", "cheater", true);
        } catch (Replay.ConflictException ce) {
            rejected = true;
            check(ce.detail.get("currentFingerprint") != null
                    && !ce.detail.get("currentFingerprint").equals(oldFp),
                    "conflict detail reports fingerprint mismatch");
        }
        check(rejected, "fork from an old-definition checkpoint is rejected");
    }

    // 5) 分支冲突：共同祖先之后外部事件集合不同，拒绝并指出第一组冲突事件
    private static void branchConflictOnMerge() {
        System.out.println("[5] branch merge conflict");
        Replay rp = newReplay();
        Map<String, Object> s = newSession();
        rp.importEvents(s, "main", List.of(ext("base-e1", "e1", 10, 1, 1)));
        rp.step(s, "main"); // main: A->B，inner 入队
        Map<String, Object> cp = rp.checkpoint(s, "main", "ancestor");

        Map<String, Object> brX = rp.fork(s, (String) cp.get("id"), "x", false);
        Map<String, Object> brY = rp.fork(s, (String) cp.get("id"), "y", false);
        // 公共祖先之后：x 收到 only-x，y 收到 only-y，同一逻辑时刻
        rp.importEvents(s, "x", List.of(ext("only-x", "boom", 20, 1, 200)));
        rp.importEvents(s, "y", List.of(ext("only-y", "boom", 20, 1, 201)));

        boolean rejected = false;
        try {
            rp.mergePlan(s, "x", "y", (String) cp.get("id"));
        } catch (Replay.ConflictException ce) {
            rejected = true;
            Map<String, Object> detail = ce.detail;
            check("external event sets differ since common ancestor".equals(detail.get("reason")),
                    "conflict reason identifies incompatible sets");
            check(Long.valueOf(20L).equals(detail.get("firstConflictAt")),
                    "first conflict time reported: " + detail.get("firstConflictAt"));
            check(asList(detail.get("onlyOnA")).size() == 1 && asList(detail.get("onlyOnB")).size() == 1,
                    "first conflicting event groups reported on both sides");
        }
        check(rejected, "merge with divergent external events is rejected");
    }

    // 6) 兼容合并：同组外部事件、顺序无歧义，重放后得到相同轨迹哈希
    private static void compatibleMerge() {
        System.out.println("[6] compatible merge");
        Replay rp = newReplay();
        Map<String, Object> s = newSession();
        rp.importEvents(s, "main", List.of(ext("base-e1", "e1", 10, 1, 1)));
        rp.step(s, "main");
        Map<String, Object> cp = rp.checkpoint(s, "main", "ancestor");

        Map<String, Object> brX = rp.fork(s, (String) cp.get("id"), "x2", false);
        Map<String, Object> brY = rp.fork(s, (String) cp.get("id"), "y2", false);
        // 两边集合相同，但导入顺序不同；规范顺序必须消除歧义
        rp.importEvents(s, "x2", List.of(
                ext("shared-a", "boom", 20, 1, 300),
                ext("shared-b", "boom", 21, 1, 301)));
        rp.importEvents(s, "y2", List.of(
                ext("shared-b", "boom", 21, 1, 301),
                ext("shared-a", "boom", 20, 1, 300)));
        rp.runTo(s, "x2", 0);
        rp.runTo(s, "y2", 0);
        check(rp.branch(s, "x2").get("traceHash").equals(rp.branch(s, "y2").get("traceHash")),
                "same event set in canonical order yields identical trace hash");

        Map<String, Object> merged = rp.merge(s, "x2", "y2", (String) cp.get("id"), "merged-ok");
        check(Boolean.TRUE.equals(merged.get("verified")), "merge replay verifies against parent hash");
        check(merged.get("traceHash").equals(rp.branch(s, "x2").get("traceHash")),
                "merged branch hash equals parents");
    }

    // 7) 导出再导入：相同轨迹哈希（使用虚拟时钟，无 sleep）
    private static void exportReplayHash() {
        System.out.println("[7] export/import replay hash");
        Replay rp = newReplay();
        Map<String, Object> s = newSession();
        rp.importEvents(s, "main", List.of(
                ext("a", "e1", 10, 1, 1),
                ext("b", "boom", 11, 1, 2)));
        rp.runTo(s, "main", 0);
        Map<String, Object> cp = rp.checkpoint(s, "main", "cp");
        Map<String, Object> fork = rp.fork(s, (String) cp.get("id"), "fork1", false);
        rp.importEvents(s, "fork1", List.of(ext("c", "boom", 30, 1, 3)));
        rp.runTo(s, "fork1", 0);

        Map<String, Object> bundle = rp.exportSession(s);
        String beforeHash = (String) rp.branch(s, "main").get("traceHash");
        String beforeFp = (String) bundle.get("sessionFingerprint");

        Replay rp2 = newReplay();
        Map<String, Object> restored = rp2.importSession(bundle, true);
        check(beforeHash.equals(rp2.branch(restored, "main").get("traceHash")),
                "imported session reproduces identical main trace hash");
        check(beforeFp.equals(rp2.sessionFingerprint(restored)),
                "imported session reproduces identical session fingerprint");
        check(Replay.branches(restored).containsKey("fork1"), "forked branch restored");
    }

    // 8) 指纹稳定性：定义指纹只随定义内容变；相同输入多次重放哈希一致
    private static void fingerprintStability() {
        System.out.println("[8] stable fingerprints");
        Replay rp = newReplay();
        String fp1 = rp.definitionFingerprint(newSession());
        String fp2 = rp.definitionFingerprint(newSession());
        check(fp1.equals(fp2), "definition fingerprint is stable across sessions");

        Map<String, Object> s1 = newSession();
        Map<String, Object> s2 = newSession();
        rp.importEvents(s1, "main", List.of(ext("a", "e1", 5, 2, 1), ext("b", "boom", 6, 1, 2)));
        rp.importEvents(s2, "main", List.of(ext("a", "e1", 5, 2, 1), ext("b", "boom", 6, 1, 2)));
        rp.runTo(s1, "main", 0);
        rp.runTo(s2, "main", 0);
        check(rp.branch(s1, "main").get("traceHash").equals(rp.branch(s2, "main").get("traceHash")),
                "identical definition + events + seed -> identical trace hash");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> branch(Map<String, Object> s) {
        return (Map<String, Object>) Replay.branches(s).get("main");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> trace(Map<String, Object> b) {
        return (List<Map<String, Object>>) (List<?>) b.get("trace");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMapSafe(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }
}
