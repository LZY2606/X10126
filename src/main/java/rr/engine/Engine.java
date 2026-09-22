package rr.engine;

import rr.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 纯函数式单步引擎：所有可变状态都在 Runtime 与 StepRecord 上。 */
public final class Engine {
    private Engine() {}

    public static final class StepRecord {
        public int index;
        public Events.Event event;
        public boolean matched;
        public String transitionFrom;
        public String transitionTo;
        public String beforeState;
        public Map<String, Object> beforeData;
        public String afterState;
        public Map<String, Object> afterData;
        public List<Object> outputs = new ArrayList<>();
        public boolean failed;
        public String failureReason;
        public String actionType;
        public String recordHash;
        public String traceHash;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", index);
            m.put("event", Runtime.eventToMap(event));
            m.put("matched", matched);
            m.put("transitionFrom", transitionFrom);
            m.put("transitionTo", transitionTo);
            m.put("beforeState", beforeState);
            m.put("beforeData", Paths.copyData(beforeData));
            m.put("afterState", afterState);
            m.put("afterData", Paths.copyData(afterData));
            m.put("outputs", Paths.deepCopy(outputs));
            m.put("failed", failed);
            m.put("failureReason", failureReason);
            m.put("actionType", actionType);
            m.put("recordHash", recordHash);
            m.put("traceHash", traceHash);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static StepRecord fromMap(Map<String, Object> m) {
            StepRecord r = new StepRecord();
            r.index = (int) Json.optLng(m, "index", 0);
            r.event = Runtime.eventFromMap(m.get("event"));
            r.matched = Json.optBool(m, "matched", false);
            r.transitionFrom = Json.optStr(m, "transitionFrom", null);
            r.transitionTo = Json.optStr(m, "transitionTo", null);
            r.beforeState = Json.optStr(m, "beforeState", null);
            r.beforeData = Json.optObj(m, "beforeData");
            r.afterState = Json.optStr(m, "afterState", null);
            r.afterData = Json.optObj(m, "afterData");
            Object outs = m.get("outputs");
            r.outputs = outs == null ? new ArrayList<>() : new ArrayList<>(Json.asArr(outs));
            r.failed = Json.optBool(m, "failed", false);
            r.failureReason = Json.optStr(m, "failureReason", null);
            r.actionType = Json.optStr(m, "actionType", null);
            r.recordHash = Json.optStr(m, "recordHash", null);
            r.traceHash = Json.optStr(m, "traceHash", null);
            return r;
        }

        /** 仅包含决定轨迹的字段（排除 recordHash/traceHash 自身）。 */
        Map<String, Object> canonicalBody() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", index);
            m.put("event", Runtime.eventToMap(event));
            m.put("matched", matched);
            m.put("transitionFrom", transitionFrom);
            m.put("transitionTo", transitionTo);
            m.put("beforeState", beforeState);
            m.put("beforeData", Paths.copyData(beforeData));
            m.put("afterState", afterState);
            m.put("afterData", Paths.copyData(afterData));
            m.put("outputs", Paths.deepCopy(outputs));
            m.put("failed", failed);
            m.put("failureReason", failureReason);
            m.put("actionType", actionType);
            return m;
        }
    }

    public static Runtime newRuntime(Definition def, long seed) {
        Runtime rt = new Runtime();
        rt.state = def.initialState;
        rt.data = Paths.copyData(def.initialData);
        rt.rngState = new Rng(seed).getState();
        rt.traceHash = null;
        return rt;
    }

    /** 初始（0 步）轨迹哈希：锁定定义指纹、初始状态、初始数据与随机种子状态。 */
    public static String initialTraceHash(String defFp, Runtime rt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defFp", defFp);
        m.put("initialState", rt.state);
        m.put("initialData", Paths.copyData(rt.data));
        m.put("rngState", rt.rngState);
        return Util.sha256(Json.canonical(m));
    }

    public static boolean canStep(Runtime rt) { return !rt.exhausted(); }

    /** 执行一步。返回轨迹记录；失败时 Runtime 的状态/数据/队列按要求回滚。 */
    public static StepRecord step(Definition def, Runtime rt) {
        if (!canStep(rt)) throw new IllegalStateException("没有可处理的事件");
        Events.Event ev = rt.peek();

        StepRecord rec = new StepRecord();
        rec.index = rt.stepIndex;
        rec.event = ev;
        rec.beforeState = rt.state;
        rec.beforeData = Paths.copyData(rt.data);

        // 处理该事件前的快照（条件读取它）
        Map<String, Object> snapshotData = Paths.copyData(rt.data);
        String snapshotState = rt.state;

        Definition.Transition chosen = select(def, ev, snapshotState, snapshotData);

        if (chosen == null) {
            // 出队，不改变状态；未匹配也是确定性轨迹的一步
            consume(rt, ev);
            rec.matched = false;
            rec.afterState = rt.state;
            rec.afterData = Paths.copyData(rt.data);
        } else {
            rec.matched = true;
            rec.transitionFrom = chosen.from == null ? "*" : chosen.from;
            rec.transitionTo = chosen.to == null ? snapshotState : chosen.to;

            // 工作副本 + RNG 副本 + 派生事件暂存：动作失败时整体回滚
            Map<String, Object> workData = Paths.copyData(snapshotData);
            String workState = chosen.to == null ? snapshotState : chosen.to;
            Rng rng = Rng.fromState(rt.rngState);
            List<Events.Event> derived = new ArrayList<>();
            List<Object> outputs = new ArrayList<>();

            String failure = null;
            String failAction = null;
            for (Definition.Action a : chosen.actions) {
                try {
                    runAction(a, ev, workState, workData, rng, rt, derived, outputs);
                } catch (ActionFailure f) {
                    failure = f.getMessage();
                    failAction = a.type;
                    break;
                } catch (Eval.EvalException | Json.JsonException e) {
                    failure = a.type + " 执行错误: " + e.getMessage();
                    failAction = a.type;
                    break;
                }
            }

            if (failure != null) {
                // 回滚：状态、数据、RNG、派生内部事件全部丢弃；失败记录仍进轨迹
                rec.failed = true;
                rec.failureReason = failure;
                rec.actionType = failAction;
                rec.afterState = snapshotState;
                rec.afterData = Paths.copyData(snapshotData);
                consume(rt, ev);
            } else {
                rt.state = workState;
                rt.data = workData;
                rt.rngState = rng.getState();
                // 派生内部事件只能排在当前事件之后（FIFO 插到队首待处理列表尾部，
                // 但仍先于后续外部事件）
                rt.pendingInternals.addAll(derived);
                rt.emitCounter += derived.size();
                rec.afterState = rt.state;
                rec.afterData = Paths.copyData(rt.data);
                rec.outputs = outputs;
                consume(rt, ev);
            }
        }

        rt.stepIndex++;
        rec.index = rt.stepIndex - 1;
        rec.recordHash = Util.sha256(Json.canonical(rec.canonicalBody()));
        String prev = rt.traceHash == null ? initialTraceHash(def.fingerprint, rt) : rt.traceHash;
        rec.traceHash = Util.sha256Concat(prev, rec.recordHash);
        rt.traceHash = rec.traceHash;
        return rec;
    }

    private static void consume(Runtime rt, Events.Event ev) {
        if (!rt.pendingInternals.isEmpty() && rt.pendingInternals.get(0) == ev) {
            rt.pendingInternals.remove(0);
        } else {
            rt.extConsumed++;
        }
    }

    private static Definition.Transition select(Definition def, Events.Event ev, String state, Map<String, Object> data) {
        for (Definition.Transition t : def.transitions) {
            if (!t.on.equals(ev.type)) continue;
            if (!t.fromMatches(state)) continue;
            Eval.Scope scope = Eval.scope(state, data, ev.toExprMap(), null);
            if (scope.condition(t.when)) return t;
        }
        return null;
    }

    private static final class ActionFailure extends RuntimeException {
        ActionFailure(String m) { super(m); }
    }

    private static void runAction(Definition.Action a, Events.Event ev, String state,
                                  Map<String, Object> workData, Rng rng, Runtime rt,
                                  List<Events.Event> derived, List<Object> outputs) {
        Map<String, Object> eventMap = ev.toExprMap();
        switch (a.type) {
            case "set" -> {
                Eval.Scope sc = Eval.scope(state, workData, eventMap, null);
                Paths.set(workData, a.path, Json.wrap(sc.eval(a.expr)));
            }
            case "inc" -> {
                Eval.Scope sc = Eval.scope(state, workData, eventMap, null);
                Object cur = readPathOrZero(workData, a.path);
                double base = cur instanceof Number n ? n.doubleValue() : 0;
                double delta = Json.num(sc.eval(a.expr));
                Number out = (base == Math.rint(base) && delta == Math.rint(delta))
                        ? (long) (base + delta) : base + delta;
                Paths.set(workData, a.path, out);
            }
            case "rand" -> {
                long v = rng.range(a.min, a.max);
                Paths.set(workData, a.path, v);
            }
            case "fail" -> {
                Eval.Scope sc = Eval.scope(state, workData, eventMap, null);
                Object reason = sc.eval(a.expr);
                throw new ActionFailure(String.valueOf(reason));
            }
            case "emit" -> {
                String et = Json.str(a.payload, "__eventType");
                Map<String, Object> payload = buildPayload(a.payload, state, workData, eventMap, et);
                Events.Event in = new Events.Event();
                long order = rt.emitCounter + derived.size() + 1;
                in.internal = true;
                in.parentId = ev.id;
                in.emitOrder = order;
                in.id = ev.id + "#i" + order;
                in.time = ev.time; // 内部事件使用父事件的逻辑时刻
                in.sourcePriority = 0;
                in.seq = order;
                in.type = et;
                in.payload = payload;
                derived.add(in);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("kind", "emit");
                out.put("eventType", et);
                out.put("internalId", in.id);
                out.put("payload", Paths.copyData(payload));
                outputs.add(out);
            }
            default -> throw new ActionFailure("未知动作 " + a.type);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildPayload(Map<String, Object> tpl, String state,
                                                    Map<String, Object> data, Map<String, Object> event,
                                                    String selfType) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : tpl.entrySet()) {
            if (e.getKey().equals("__eventType")) continue;
            out.put(e.getKey(), buildValue(e.getValue(), state, data, event));
        }
        return out;
    }

    private static Object buildValue(Object v, String state, Map<String, Object> data, Map<String, Object> event) {
        if (v instanceof Map<?, ?> mv) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : mv.entrySet())
                out.put(String.valueOf(e.getKey()), buildValue(e.getValue(), state, data, event));
            return out;
        }
        if (v instanceof List<?> lv) {
            List<Object> out = new ArrayList<>(lv.size());
            for (Object x : lv) out.add(buildValue(x, state, data, event));
            return out;
        }
        if (v instanceof String s && s.startsWith("=")) {
            Eval.Scope sc = Eval.scope(state, data, event, null);
            return Json.wrap(sc.eval(s.substring(1)));
        }
        return Paths.deepCopy(v);
    }

    private static Object readPathOrZero(Map<String, Object> data, String path) {
        try {
            List<String> parts = Paths.split(path);
            Object cur = data;
            for (String p : parts) {
                if (!(cur instanceof Map)) return 0L;
                cur = ((Map<?, ?>) cur).get(p);
            }
            return cur;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
