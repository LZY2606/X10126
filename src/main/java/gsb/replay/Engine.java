package gsb.replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单步执行引擎。只处理“一个事件”，队列/分支编排由 Replay 负责。
 *
 * 同时刻外部事件顺序：(time 升序, priority 降序, seq 升序, id 升序)
 * 内部事件由 Replay 的 FIFO 内部队列保证“排在当前事件之后”，
 * 且同一逻辑时刻下内部事件先于外部事件处理。
 */
public final class Engine {
    private Engine() {
    }

    public static final Comparator<Map<String, Object>> EXTERNAL_ORDER =
            Comparator
                    .comparingLong((Map<String, Object> e) -> Json.asLong(e.get("time"), 0L))
                    .thenComparingLong((Map<String, Object> e) -> -Json.asLong(e.get("priority"), 0L))
                    .thenComparingLong((Map<String, Object> e) -> Json.asLong(e.get("seq"), 0L))
                    .thenComparing(e -> Json.str(e.get("id")));

    /** 取下一个待处理事件：内部队列非空且时间不晚于最早外部事件时用内部，否则取外部。 */
    public static Map<String, Object> peekNext(List<Map<String, Object>> internals,
                                               List<Map<String, Object>> externals) {
        Map<String, Object> ext = externals.isEmpty() ? null : externals.get(0);
        if (internals.isEmpty()) {
            return ext;
        }
        Map<String, Object> in = internals.get(0);
        if (ext == null || Json.asLong(in.get("time"), 0L) <= Json.asLong(ext.get("time"), 0L)) {
            return in;
        }
        return ext;
    }

    /**
     * 处理一个事件。返回轨迹条目；运行时字段（state/currentState/rng）就地更新或回滚。
     *
     * @param runtime 可变运行时：currentState, state, rng
     * @param event   事件（读取其快照，不修改）
     */
    public static Map<String, Object> step(Definition def, Map<String, Object> runtime,
                                           Map<String, Object> event) {
        String currentState = (String) runtime.get("currentState");
        Map<String, Object> preState = Definition.deepCopy(Json.obj(runtime.get("state"), "runtime.state"));
        Map<String, Object> workState = Definition.deepCopy(preState);
        Rng rng = (Rng) runtime.get("rngRef");
        long rngBefore = rng.snapshot();

        Language.ActionResult ar = new Language.ActionResult();
        String status = "ok";
        String matched = null;
        String target = null;
        String failure = null;
        int index = -1;

        Map<String, Object> chosen = null;
        int ti = 0;
        for (Map<String, Object> tr : def.transitions()) {
            if (!tr.get("event").equals(event.get("type"))) {
                ti++;
                continue;
            }
            Object sources = tr.get("sources");
            if (sources instanceof List && !((List<?>) sources).contains(currentState)) {
                ti++;
                continue;
            }
            boolean cond;
            try {
                cond = Language.condition(tr.get("condition"), preState, event, rng); // 条件读处理前快照
            } catch (Language.EvalException ex) {
                cond = false;
            }
            if (cond) {
                chosen = tr;
                index = ti;
                break;
            }
            ti++;
        }

        if (chosen != null) {
            matched = String.valueOf(chosen.get("event"));
            target = (String) chosen.get("target");
            Object actions = chosen.get("actions");
            try {
                if (actions instanceof List) {
                    for (Object a : (List<Object>) actions) {
                        Language.execute(Json.obj(a, "action"), workState, event, rng, ar,
                                Json.asLong(event.get("time"), 0L));
                    }
                }
            } catch (Language.EvalException ex) {
                status = "failed";
                failure = ex.getMessage();
                rng.restore(rngBefore);
            }
        } else {
            status = "nomatch";
        }

        if ("failed".equals(status)) {
            // 状态改变与派生内部事件一起回滚：保留处理前快照，丢弃工作副本与 outputs/internals。
            runtime.put("state", Definition.deepCopy(preState));
        } else {
            runtime.put("state", workState);
            if (target != null) {
                runtime.put("currentState", target);
            }
            runtime.put("emitted", ar.internals);
            runtime.put("outputs", ar.outputs);
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("seq", runtime.get("stepCount"));
        entry.put("event", publicEvent(event));
        entry.put("status", status);
        entry.put("from", currentState);
        entry.put("to", runtime.get("currentState"));
        if (chosen != null) {
            entry.put("transition", index);
        }
        if ("failed".equals(status)) {
            entry.put("failure", failure);
        } else {
            entry.put("outputs", ar.outputs);
        }
        return entry;
    }

    /** 轨迹中记录的事件视图：内部事件也带 id 便于对比。 */
    private static Map<String, Object> publicEvent(Map<String, Object> event) {
        Map<String, Object> view = new LinkedHashMap<>();
        for (String k : new String[]{"id", "type", "time", "priority", "seq", "source", "internal", "payload"}) {
            if (event.containsKey(k)) {
                view.put(k, event.get(k));
            }
        }
        return view;
    }
}
