package com.replayroom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单分支回放机。持有外部事件有序日志 + 游标、内部事件队列、
 * 当前状态/变量、确定性随机源与轨迹（带哈希链）。
 * 时间完全来自事件逻辑时间，不依赖真实时钟。
 */
public final class Machine {
    public static final class ActionFailure extends RuntimeException {
        public ActionFailure(String msg) { super(msg); }
    }

    /** 轨迹条目：记录处理前快照与处理后结果，失败也保留。 */
    public static final class TraceEntry {
        public int index;
        public Model.Event event;
        public String stateBefore;
        public Map<String, Object> varsBefore;
        public boolean skipped;
        public boolean failed;
        public String failure;
        public List<Object> outputs = new ArrayList<>();
        public String stateAfter;
        public Map<String, Object> varsAfter;
        public String hashAfter;   // 到此步为止的轨迹哈希

        @SuppressWarnings("unchecked")
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", (long) index);
            m.put("event", event.toJson());
            m.put("stateBefore", stateBefore);
            m.put("varsBefore", Json.deepCopy(varsBefore));
            m.put("skipped", skipped);
            m.put("failed", failed);
            if (failure != null) m.put("failure", failure);
            m.put("outputs", Json.deepCopy(outputs));
            m.put("stateAfter", stateAfter);
            m.put("varsAfter", Json.deepCopy(varsAfter));
            m.put("hashAfter", hashAfter);
            return m;
        }

        /** 不含哈希字段的序列化，用于计算哈希链。 */
        public Map<String, Object> toJsonWithoutHash() {
            Map<String, Object> m = toJson();
            m.remove("hashAfter");
            return m;
        }
    }

    public final String branchId;
    public String name;
    public final List<Model.Event> external = new ArrayList<>(); // 有序外部事件全集
    public int cursor;                                            // 下一个待处理外部事件下标
    public final Deque<Model.Event> internalQueue = new ArrayDeque<>();
    public String state;
    public Map<String, Object> vars;
    public long rngState;
    public final List<TraceEntry> trace = new ArrayList<>();
    public long internalSeq;
    public String forkedFrom;      // 来源分支 id（无则 null）
    public int forkStepIndex = -1; // 分叉点轨迹长度

    private final Session session;

    public Machine(Session session, String branchId, String name) {
        this.session = session;
        this.branchId = branchId;
        this.name = name;
        this.state = session.definition().initialState;
        this.vars = (Map<String, Object>) Json.deepCopy(session.definition().initialVars);
        this.rngState = session.seed();
    }

    public boolean hasNext() {
        return !internalQueue.isEmpty() || cursor < external.size();
    }

    public Model.Event peekNext() {
        if (!internalQueue.isEmpty()) return internalQueue.peek();
        if (cursor < external.size()) return external.get(cursor);
        return null;
    }

    /** xorshift64* 确定性随机源，种子来自会话。 */
    public long nextRand() {
        long x = rngState;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        rngState = x;
        return (x * 0x2545F4914F6CDD1DL) >>> 1;
    }

    private Expr.Context ctx(final Model.Event ev, final String snapState, final Map<String, Object> snapVars) {
        return new Expr.Context() {
            public Object resolve(String path) {
                if (path.equals("state")) return snapState;
                if (path.startsWith("vars.")) return snapVars.get(path.substring(5));
                if (path.equals("event.name")) return ev.name;
                if (path.equals("event.source")) return ev.source;
                if (path.equals("event.time")) return ev.time;
                if (path.equals("event.seq")) return ev.seq;
                if (path.startsWith("event.data.")) return ev.data.get(path.substring(11));
                return null;
            }
            public long randInt(long bound) {
                if (bound <= 0) throw new IllegalArgumentException("randInt 参数必须为正");
                return nextRand() % bound;
            }
        };
    }

    /**
     * 处理一个事件。内部事件优先于后续外部事件（保证派生事件紧跟当前事件）。
     * 条件在处理前快照上求值；动作失败时状态与派生内部事件整体回滚，失败仍入轨迹。
     */
    public TraceEntry step() {
        if (!hasNext()) return null;
        Model.Event ev;
        if (!internalQueue.isEmpty()) ev = internalQueue.poll();
        else { ev = external.get(cursor); cursor++; }

        final String stateBefore = state;
        @SuppressWarnings("unchecked")
        final Map<String, Object> varsBefore = (Map<String, Object>) Json.deepCopy(vars);
        final long rngBefore = rngState;
        final int internalMark = internalQueue.size();

        TraceEntry te = new TraceEntry();
        te.index = trace.size();
        te.event = ev;
        te.stateBefore = stateBefore;
        te.varsBefore = varsBefore;

        Expr.Context ctx = ctx(ev, stateBefore, varsBefore);
        Model.EventDef def = session.definition().match(ev.name, ctx);
        if (def == null) {
            te.skipped = true;
        } else {
            try {
                for (Model.Action a : def.actions) exec(a, ev, te, ctx);
            } catch (ActionFailure f) {
                // 回滚：状态、变量、随机源、本事件派生的内部事件与输出全部撤销
                state = stateBefore;
                vars = varsBefore;
                rngState = rngBefore;
                while (internalQueue.size() > internalMark) internalQueue.pollLast();
                te.outputs = new ArrayList<>();
                te.failed = true;
                te.failure = f.getMessage();
            }
        }
        te.stateAfter = state;
        @SuppressWarnings("unchecked")
        Map<String, Object> varsAfter = (Map<String, Object>) Json.deepCopy(vars);
        te.varsAfter = varsAfter;
        te.hashAfter = Json.sha256(traceHashPrefix() + Json.canonical(te.toJsonWithoutHash()));
        trace.add(te);
        return te;
    }

    private String traceHashPrefix() {
        return trace.isEmpty() ? session.sessionFingerprint() : trace.get(trace.size() - 1).hashAfter;
    }

    public String traceHash() {
        return trace.isEmpty()
                ? Json.sha256(session.sessionFingerprint() + ":empty")
                : trace.get(trace.size() - 1).hashAfter;
    }

    private void exec(Model.Action a, Model.Event ev, TraceEntry te, Expr.Context ctx) {
        switch (a.type) {
            case "set":
                vars.put(a.var, a.value.eval(ctx));
                break;
            case "transition": {
                Object to = a.to != null ? a.to : a.toExpr.eval(ctx);
                state = String.valueOf(to);
                break;
            }
            case "emit":
                te.outputs.add(a.value.eval(ctx));
                break;
            case "raise": {
                Model.Event ie = new Model.Event();
                ie.id = "i" + te.index + "-" + (++internalSeq);
                ie.name = a.event;
                ie.time = ev.time;              // 虚拟逻辑时钟：不前进
                ie.source = "internal";
                ie.seq = internalSeq;
                ie.internal = true;
                ie.causedBy = ev.id;
                ie.data = (Map<String, Object>) Json.deepCopy(a.data);
                internalQueue.addLast(ie);      // 排在当前事件之后
                break;
            }
            case "fail": {
                boolean fire = a.when == null || Expr.truthy(a.when.eval(ctx));
                if (fire) {
                    Object msg = a.value != null ? a.value.eval(ctx) : "action failed";
                    throw new ActionFailure(String.valueOf(msg));
                }
                break;
            }
            default:
                throw new IllegalArgumentException("未知动作类型: " + a.type);
        }
    }

    /** 当前完整快照（用于检查点）。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("vars", Json.deepCopy(vars));
        m.put("rngState", rngState);
        m.put("cursor", (long) cursor);
        m.put("internalSeq", internalSeq);
        List<Object> iq = new ArrayList<>();
        for (Model.Event e : internalQueue) iq.add(e.toJson());
        m.put("internalQueue", iq);
        m.put("traceLength", (long) trace.size());
        m.put("traceHash", traceHash());
        m.put("definitionFingerprint", session.definition().fingerprint);
        return m;
    }

    /** 从快照恢复（调用方需已校验定义指纹）。 */
    @SuppressWarnings("unchecked")
    public void restore(Map<String, Object> snap) {
        state = Json.str(snap.get("state"));
        vars = (Map<String, Object>) Json.deepCopy(Json.obj(snap.get("vars")));
        rngState = Json.num(snap.get("rngState"));
        cursor = (int) Json.num(snap.get("cursor"));
        internalSeq = Json.num(snap.get("internalSeq"));
        internalQueue.clear();
        for (Object e : Json.arr(snap.get("internalQueue"))) internalQueue.addLast(Model.Event.from(Json.obj(e)));
        int traceLen = (int) Json.num(snap.get("traceLength"));
        while (trace.size() > traceLen) trace.remove(trace.size() - 1);
    }
}
