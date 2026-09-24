package replayroom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性回放引擎：
 * - 同一逻辑时刻的事件按来源优先级 + 原始序号 + 出生序稳定全序；
 * - 条件读取处理事件前快照；动作派生的内部事件只能排在当前事件之后；
 * - 动作失败时状态改变与派生内部事件整体回滚，失败记录仍进入轨迹；
 * - 哈希链锁定每一步，检查点锁定定义指纹；分叉/合并按外部事件集合判定。
 */
public final class Engine {
    public static final String GENESIS = Hashing.sha256Hex("replay-room-genesis-v1");

    public static class EngineException extends RuntimeException {
        public final int status;
        public EngineException(int status, String msg) { super(msg); this.status = status; }
    }

    // ---------- 会话 ----------

    public Session createSession(String name, MachineDef machine, long seed) {
        machine.validate();
        Session s = new Session();
        s.id = "s" + 1;
        s.idCounter = 0;
        s.name = name == null ? machine.name : name;
        s.machine = machine;
        s.seed = seed;
        s.defFp = machine.fingerprint();
        s.createdAt = 1;

        Branch root = new Branch();
        root.id = "b1";
        s.idCounter = 1;
        root.name = "main";
        root.parentBranchId = null;
        root.parentStepIndex = -1;
        root.state = machine.initialState;
        root.vars = copy(machine.initialVars);
        root.rngState = new DetRandom(seed).state();
        root.pending = new ArrayList<>();
        root.birthCounter = 0;
        s.branches.put(root.id, root);
        s.configFp = configFp(s, root);
        return s;
    }

    private String nextId(Session s, String prefix) {
        s.idCounter++;
        return prefix + s.idCounter;
    }

    public String configFp(Session s, Branch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defFp", s.defFp);
        m.put("seed", s.seed);
        m.put("initialState", s.machine.initialState);
        m.put("logFp", logFp(b));
        return Hashing.fingerprint(m);
    }

    /** 外部事件日志指纹：按导入批次与出生序（即导入内容本身）。 */
    public String logFp(Branch b) {
        List<Object> evs = new ArrayList<>();
        for (Branch.ImportBatch batch : b.imports)
            for (Event e : batch.events)
                evs.add(logicalEvent(e));
        return Hashing.fingerprint(evs);
    }

    private Map<String, Object> logicalEvent(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("birth", e.birth);
        m.put("time", e.time);
        m.put("source", e.source);
        m.put("seq", e.seq);
        m.put("type", e.type);
        m.put("payload", e.payload);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static <T> T copy(T v) {
        return (T) Json.parse(Json.canonical(v));
    }

    private Branch newBranch(Session s, String name, String parentId, int parentStep,
                             String state, Map<String, Object> vars, long rng,
                             List<Event> pending, long birth) {
        Branch b = new Branch();
        b.id = nextId(s, "b");
        b.name = name;
        b.parentBranchId = parentId;
        b.parentStepIndex = parentStep;
        b.state = state;
        b.vars = vars;
        b.rngState = rng;
        b.pending = pending;
        b.birthCounter = birth;
        return b;
    }

    // ---------- 事件导入 ----------

    /** 导入外部事件；同一逻辑时刻的顺序由 rank/seq/birth 稳定决定，不依赖导入先后覆盖逻辑序。 */
    public List<Event> importEvents(Session s, String branchId, List<Map<String, Object>> rawList) {
        Branch b = mustBranch(s, branchId);
        List<Event> added = new ArrayList<>();
        Branch.ImportBatch batch = new Branch.ImportBatch();
        batch.afterStep = b.steps.size();
        for (Map<String, Object> raw : rawList) {
            Event e = new Event();
            e.kind = "external";
            e.time = num(raw, "time", 0);
            e.source = (String) raw.get("source");
            e.seq = num(raw, "seq", 0);
            e.type = (String) raw.get("type");
            Object p = raw.get("payload");
            if (p instanceof Map) e.payload = new LinkedHashMap<>(Json.obj(p));
            if (e.source == null || e.source.isEmpty())
                throw new EngineException(400, "event requires source");
            if (e.type == null || e.type.isEmpty())
                throw new EngineException(400, "event requires type");
            if (e.source.equals(Event.INTERNAL_SOURCE))
                throw new EngineException(400, "source is reserved: " + Event.INTERNAL_SOURCE);
            b.birthCounter++;
            e.birth = b.birthCounter;
            e.id = nextId(s, "e");
            b.pending.add(e);
            batch.events.add(e);
            added.add(e);
        }
        if (!batch.events.isEmpty()) b.imports.add(batch);
        return added;
    }

    private static long num(Map<String, Object> m, String k, long dflt) {
        Object v = m.get(k);
        return v == null ? dflt : ((Number) v).longValue();
    }

    private Branch mustBranch(Session s, String id) {
        Branch b = s.branches.get(id);
        if (b == null) throw new EngineException(404, "branch not found: " + id);
        return b;
    }

    private Event pickNext(Session s, Branch b) {
        if (b.pending.isEmpty()) return null;
        Event best = null;
        Comparator<Event> cmp = comparator(s.machine);
        for (Event e : b.pending) {
            if (best == null || cmp.compare(e, best) < 0) best = e;
        }
        return best;
    }

    /** 稳定全序：时间 → 来源优先级（小者先）→ 原始序号 → 出生序 → 来源名 → id。 */
    static Comparator<Event> comparator(MachineDef machine) {
        return (x, y) -> {
            int c = Long.compare(x.time, y.time);
            if (c != 0) return c;
            c = Integer.compare(x.rank(machine), y.rank(machine));
            if (c != 0) return c;
            c = Long.compare(x.seq, y.seq);
            if (c != 0) return c;
            c = Long.compare(x.birth, y.birth);
            if (c != 0) return c;
            c = x.source.compareTo(y.source);
            if (c != 0) return c;
            return x.id.compareTo(y.id);
        };
    }

    // ---------- 单步回放 ----------

    public StepRecord step(Session s, String branchId) {
        Branch b = mustBranch(s, branchId);
        Event event = pickNext(s, b);
        if (event == null) throw new EngineException(409, "no pending events");

        StepRecord rec = new StepRecord();
        rec.index = b.steps.size();
        rec.prevHash = b.steps.isEmpty() ? GENESIS : b.steps.get(b.steps.size() - 1).stepHash;
        rec.event = event;
        rec.stateBefore = b.state;
        rec.varsBefore = copy(b.vars);
        rec.rngBefore = b.rngState;

        b.pending.remove(event);

        MachineDef.Transition matched = null;
        int matchedIndex = -1;
        for (int i = 0; i < s.machine.transitions.size(); i++) {
            MachineDef.Transition t = s.machine.transitions.get(i);
            if (t.eventType != null && !t.eventType.equals(event.type)) continue;
            if (t.from != null && !"*".equals(t.from) && !t.from.equals(b.state)) continue;
            if (t.guard != null && !t.guard.isEmpty()) {
                Expr.Eval guardCtx = new Expr.Eval(rec.varsBefore, event.toJson(), new DetRandom(0));
                boolean ok;
                try {
                    ok = Expr.truthy(Expr.eval(t.guard, guardCtx));
                } catch (RuntimeException ex) {
                    ok = false;
                }
                if (!ok) continue;
            }
            matched = t;
            matchedIndex = i;
            break;
        }

        if (matched == null) {
            rec.matched = false;
            rec.success = true;
            rec.stateAfter = b.state;
            rec.varsAfter = copy(b.vars);
            rec.rngAfter = b.rngState;
        } else {
            rec.matched = true;
            rec.transitionIndex = matchedIndex;
            executeTransition(s, b, event, matched, rec);
        }

        rec.pendingAfter = copy(b.pending);
        rec.stepHash = Hashing.fingerprint(rec.canonical());
        b.steps.add(rec);
        return rec;
    }

    /** 执行一个匹配的转换。工作副本与临时队列保证失败时整体回滚。 */
    private void executeTransition(Session s, Branch b, Event event,
                                   MachineDef.Transition t, StepRecord rec) {
        Map<String, Object> working = copy(b.vars);
        DetRandom rng = new DetRandom(0);
        rng.restoreState(b.rngState);
        List<StepRecord.Output> outputs = new ArrayList<>();
        List<Event> staged = new ArrayList<>();
        String failure = null;

        try {
            for (MachineDef.Action a : t.actions) {
                Expr.Eval ctx = new Expr.Eval(working, event.toJson(), rng);
                switch (a.op) {
                    case "set":
                        working.put(a.key, evaluatePayloadValue(a.expr, ctx));
                        break;
                    case "emit": {
                        StepRecord.Output o = new StepRecord.Output();
                        o.channel = a.channel;
                        o.payload = evaluatePayload(a.payload, a.expr, ctx);
                        outputs.add(o);
                        break;
                    }
                    case "raise": {
                        Event ie = new Event();
                        ie.kind = "internal";
                        ie.source = Event.INTERNAL_SOURCE;
                        ie.time = event.time + a.delay;
                        ie.type = a.type;
                        ie.seq = event.seq;
                        ie.payload = evaluatePayload(a.payload, a.expr, ctx);
                        ie.parentEventId = event.id;
                        staged.add(ie);
                        break;
                    }
                    case "fail":
                        throw new Expr.ExprException(
                                a.expr != null && !a.expr.isEmpty() ? a.expr : "action failed");
                    default:
                        throw new Expr.ExprException("unknown action " + a.op);
                }
            }
        } catch (RuntimeException ex) {
            failure = ex.getMessage();
        }

        if (failure != null) {
            rec.success = false;
            rec.failure = failure;
            rec.stateAfter = b.state;
            rec.varsAfter = copy(b.vars);
            rec.rngAfter = b.rngState;
            return;
        }

        String nextState = (t.to == null || t.to.isEmpty()) ? b.state : t.to;
        b.state = nextState;
        b.vars = working;
        b.rngState = rng.state();
        for (Event ie : staged) {
            b.birthCounter++;
            ie.birth = b.birthCounter;
            ie.id = nextId(s, "i");
            b.pending.add(ie);
            rec.raised.add(ie);
        }
        rec.outputs = outputs;
        rec.stateAfter = b.state;
        rec.varsAfter = copy(b.vars);
        rec.rngAfter = b.rngState;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> evaluatePayload(Map<String, Object> template, String expr,
                                                Expr.Eval ctx) {
        Map<String, Object> result;
        if (template != null && !template.isEmpty()) {
            Object filled = fillPayload(template, ctx);
            result = filled instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) filled)
                    : new LinkedHashMap<>();
            if (!(filled instanceof Map)) result.put("value", filled);
        } else if (expr != null && !expr.isEmpty()) {
            Object v = Expr.eval(expr, ctx);
            if (v instanceof Map) result = new LinkedHashMap<>((Map<String, Object>) v);
            else {
                result = new LinkedHashMap<>();
                result.put("value", v);
            }
        } else {
            result = new LinkedHashMap<>();
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object fillPayload(Object template, Expr.Eval ctx) {
        if (template instanceof Map) {
            Map<String, Object> tm = (Map<String, Object>) template;
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : tm.entrySet()) {
                if (e.getKey().equals("__expr__")) continue;
                out.put(e.getKey(), fillPayload(e.getValue(), ctx));
            }
            return out;
        }
        if (template instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<Object>) template) out.add(fillPayload(item, ctx));
            return out;
        }
        if (template instanceof String) {
            String str = (String) template;
            if (str.startsWith("=")) return Expr.eval(str.substring(1), ctx);
        }
        return template;
    }

    private Object evaluatePayloadValue(String expr, Expr.Eval ctx) {
        if (expr == null || expr.isEmpty()) return null;
        return Expr.eval(expr, ctx);
    }

    public List<StepRecord> run(Session s, String branchId, int maxSteps) {
        Branch b = mustBranch(s, branchId);
        List<StepRecord> done = new ArrayList<>();
        int cap = maxSteps > 0 ? maxSteps : Integer.MAX_VALUE;
        while (!b.pending.isEmpty() && done.size() < cap) done.add(step(s, branchId));
        return done;
    }

    public String traceHash(Branch b) {
        return b.steps.isEmpty() ? GENESIS : b.steps.get(b.steps.size() - 1).stepHash;
    }

    // ---------- 检查点 ----------

    public Checkpoint createCheckpoint(Session s, String branchId, int stepIndex, String label) {
        Branch b = mustBranch(s, branchId);
        if (stepIndex < 0 || stepIndex > b.steps.size())
            throw new EngineException(400, "step index out of range: " + stepIndex);
        StepRecord at = stepIndex == 0 ? null : b.steps.get(stepIndex - 1);
        Checkpoint c = new Checkpoint();
        c.id = nextId(s, "c");
        c.label = label == null ? ("检查点@" + branchId + "#" + stepIndex) : label;
        c.defFp = s.defFp;
        c.configFp = s.configFp;
        c.branchId = branchId;
        c.stepIndex = stepIndex;
        c.stepHash = at == null ? GENESIS : at.stepHash;
        c.traceHash = c.stepHash;
        c.createdAt = s.checkpoints.size() + 1L;
        s.checkpoints.add(c);
        return c;
    }

    /** 从检查点分叉：旧定义下的检查点不能接到新定义上（defFp 不匹配即拒绝）。 */
    public Branch forkFromCheckpoint(Session s, String checkpointId, String name) {
        Checkpoint c = mustCheckpoint(s, checkpointId);
        if (!c.defFp.equals(s.defFp)) {
            throw new EngineException(409,
                    "checkpoint definition mismatch: checkpoint=" + abbrev(c.defFp)
                            + " current=" + abbrev(s.defFp));
        }
        Branch parent = mustBranch(s, c.branchId);
        return forkAt(s, parent, c.stepIndex, name);
    }

    public Branch forkFromHead(Session s, String branchId, String name) {
        Branch parent = mustBranch(s, branchId);
        return forkAt(s, parent, parent.steps.size(), name);
    }

    public Branch forkAtStep(Session s, String branchId, int stepIndex, String name) {
        Branch parent = mustBranch(s, branchId);
        if (stepIndex < 0 || stepIndex > parent.steps.size())
            throw new EngineException(400, "step index out of range: " + stepIndex);
        return forkAt(s, parent, stepIndex, name);
    }

    private Checkpoint mustCheckpoint(Session s, String id) {
        for (Checkpoint c : s.checkpoints) if (c.id.equals(id)) return c;
        throw new EngineException(404, "checkpoint not found: " + id);
    }

    /** 在父分支第 k 步之后建立分叉，拷贝当时快照与未处理事件队列。 */
    private Branch forkAt(Session s, Branch parent, int k, String name) {
        String state;
        Map<String, Object> vars;
        long rng;
        List<Event> pending;
        long birth;
        if (k == 0) {
            state = s.machine.initialState;
            vars = copy(s.machine.initialVars);
            rng = new DetRandom(s.seed).state();
            pending = eventsImportedAt(parent, 0);
            birth = 0;
        } else {
            StepRecord rec = parent.steps.get(k - 1);
            state = rec.stateAfter;
            vars = copy(rec.varsAfter);
            rng = rec.rngAfter;
            pending = copy(rec.pendingAfter);
            birth = parent.birthCounter;
        }
        Branch child = newBranch(s,
                name == null ? parent.name + "-fork" + (s.branches.size() + 1) : name,
                parent.id, k, state, vars, rng, pending, birth);
        s.branches.put(child.id, child);
        return child;
    }

    private static String abbrev(String fp) {
        return fp == null ? "null" : fp.substring(0, 12);
    }

    private List<Event> eventsImportedAt(Branch b, int afterStep) {
        List<Event> evs = new ArrayList<>();
        for (Branch.ImportBatch batch : b.imports)
            if (batch.afterStep == afterStep) evs.addAll(copy(batch.events));
        return evs;
    }

    // ---------- 重建与校验（导出/导入同轨迹哈希）----------

    /** 从会话定义与导入记录重放一个分支，返回重放后的分支（尚未加入会话）。 */
    public Branch replayBranch(Session s, Branch blueprint) {
        Branch r;
        if (blueprint.parentBranchId == null) {
            r = newBranch(s, blueprint.name, null, -1,
                    s.machine.initialState, copy(s.machine.initialVars),
                    new DetRandom(s.seed).state(), new ArrayList<>(), 0);
        } else {
            Branch parent = s.branches.get(blueprint.parentBranchId);
            if (parent == null)
                throw new EngineException(400, "missing parent branch during replay: " + blueprint.parentBranchId);
            r = forkAt(s, parent, blueprint.parentStepIndex, blueprint.name);
        }

        int importCursor = 0;
        List<Branch.ImportBatch> ordered = new ArrayList<>(blueprint.imports);
        ordered.sort(Comparator.comparingInt(x -> x.afterStep));
        for (StepRecord expected : blueprint.steps) {
            while (importCursor < ordered.size()
                    && ordered.get(importCursor).afterStep <= r.steps.size()) {
                addImportBatch(s, r, ordered.get(importCursor));
                importCursor++;
            }
            StepRecord got = step(s, r.id);
            if (!got.stepHash.equals(expected.stepHash)) {
                throw new EngineException(409, "trace mismatch at step " + got.index
                        + ": expected " + abbrev(expected.stepHash)
                        + " got " + abbrev(got.stepHash));
            }
        }
        while (importCursor < ordered.size()) {
            if (ordered.get(importCursor).afterStep <= r.steps.size()) {
                addImportBatch(s, r, ordered.get(importCursor));
                importCursor++;
            } else {
                throw new EngineException(409, "import batch beyond replayed steps");
            }
        }
        if (r.pending.size() != blueprint.pending.size())
            throw new EngineException(409, "pending queue mismatch after replay");
        return r;
    }

    /** 按导入记录复制一批外部事件（保留原始 birth/id 语义）。 */
    private void addImportBatch(Session s, Branch b, Branch.ImportBatch batch) {
        Branch.ImportBatch copy = new Branch.ImportBatch();
        copy.afterStep = batch.afterStep;
        for (Event original : batch.events) {
            Event e = copy(original);
            e.kind = "external";
            b.pending.add(e);
            copy.events.add(e);
            if (e.birth > b.birthCounter) b.birthCounter = e.birth;
            long num = Long.parseLong(e.id.replaceAll("[^0-9]", ""));
            if (num > s.idCounter) s.idCounter = num;
        }
        b.imports.add(copy);
    }

    /** 导入前校验：定义指纹一致，且每个分支重放得到相同轨迹哈希。 */
    public void verifySession(Session s) {
        if (!s.defFp.equals(s.machine.fingerprint()))
            throw new EngineException(409, "definition fingerprint mismatch");
        for (Branch b : new ArrayList<>(s.branches.values())) {
            for (StepRecord step : b.steps) {
                String expect = step.index == 0 ? GENESIS
                        : b.steps.get(step.index - 1).stepHash;
                if (!expect.equals(step.prevHash))
                    throw new EngineException(409, "broken hash chain at branch "
                            + b.id + " step " + step.index);
            }
        }
        Map<String, Branch> rebuilt = new LinkedHashMap<>();
        List<Branch> order = new ArrayList<>(s.branches.values());
        for (Branch blueprint : order) {
            String savedId = blueprint.id;
            if (blueprint.parentBranchId != null && !rebuilt.containsKey(blueprint.parentBranchId)
                    && !s.branches.containsKey(blueprint.parentBranchId))
                throw new EngineException(409, "unknown parent branch " + blueprint.parentBranchId);
            Branch r;
            if (blueprint.parentBranchId == null) {
                r = replayRoot(s, blueprint);
            } else {
                Branch parent = rebuilt.containsKey(blueprint.parentBranchId)
                        ? rebuilt.get(blueprint.parentBranchId)
                        : s.branches.get(blueprint.parentBranchId);
                r = replayWithParent(s, blueprint, parent);
            }
            if (!traceHash(r).equals(traceHash(blueprint)))
                throw new EngineException(409,
                        "replay trace mismatch on branch " + savedId);
            r.id = savedId;
            rebuilt.put(savedId, r);
        }
    }

    private Branch replayRoot(Session s, Branch blueprint) {
        Session scratch = new Session();
        scratch.id = s.id;
        scratch.machine = s.machine;
        scratch.seed = s.seed;
        scratch.defFp = s.defFp;
        scratch.idCounter = s.idCounter;
        Branch root = newBranch(scratch, blueprint.name, null, -1,
                s.machine.initialState, copy(s.machine.initialVars),
                new DetRandom(s.seed).state(), new ArrayList<>(), 0);
        scratch.branches.put(root.id, root);
        return replayWithParent(scratch, blueprint, root);
    }

    private Branch replayWithParent(Session scratch, Branch blueprint, Branch parentOrSelf) {
        Branch r;
        if (blueprint.parentBranchId == null) {
            r = parentOrSelf;
        } else {
            r = forkAt(scratch, parentOrSelf, blueprint.parentStepIndex, blueprint.name);
        }
        int importCursor = 0;
        List<Branch.ImportBatch> ordered = new ArrayList<>(blueprint.imports);
        ordered.sort(Comparator.comparingInt(x -> x.afterStep));
        for (StepRecord expected : blueprint.steps) {
            while (importCursor < ordered.size()
                    && ordered.get(importCursor).afterStep <= r.steps.size()) {
                addImportBatch(scratch, r, ordered.get(importCursor));
                importCursor++;
            }
            StepRecord got = step(scratch, r.id);
            if (!got.stepHash.equals(expected.stepHash))
                throw new EngineException(409, "trace mismatch at step " + got.index);
        }
        while (importCursor < ordered.size()) {
            addImportBatch(scratch, r, ordered.get(importCursor));
            importCursor++;
        }
        return r;
    }
}
}
