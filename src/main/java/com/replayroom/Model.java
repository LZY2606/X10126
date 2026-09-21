package com.replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 状态机定义与事件的内存模型。定义一旦导入即被指纹锁定。 */
public final class Model {

    /** 状态机定义：状态集合、初始状态/变量、带条件的事件处理器与动作。 */
    public static final class Definition {
        public final String name;
        public final String initialState;
        public final Map<String, Object> initialVars;
        public final List<String> states;
        public final List<EventDef> events;
        public final Map<String, Object> raw;   // 原始定义（用于指纹与导出）
        public final String fingerprint;        // 定义指纹 = sha256(canonical(raw))

        private Definition(String name, String initialState, Map<String, Object> initialVars,
                           List<String> states, List<EventDef> events, Map<String, Object> raw) {
            this.name = name;
            this.initialState = initialState;
            this.initialVars = initialVars;
            this.states = states;
            this.events = events;
            this.raw = raw;
            this.fingerprint = Json.sha256(Json.canonical(raw));
        }

        @SuppressWarnings("unchecked")
        public static Definition from(Map<String, Object> raw) {
            Map<String, Object> copy = (Map<String, Object>) Json.deepCopy(raw);
            String name = copy.containsKey("name") ? Json.str(copy.get("name")) : "machine";
            String initialState = Json.str(copy.get("initialState"));
            if (initialState == null) throw new IllegalArgumentException("定义缺少 initialState");
            Map<String, Object> initialVars = copy.containsKey("initialVars")
                    ? Json.obj(copy.get("initialVars")) : new LinkedHashMap<>();
            List<String> states = new ArrayList<>();
            if (copy.containsKey("states"))
                for (Object s : Json.arr(copy.get("states"))) states.add(Json.str(s));
            if (!states.contains(initialState)) states.add(initialState);
            List<EventDef> defs = new ArrayList<>();
            if (copy.containsKey("events"))
                for (Object e : Json.arr(copy.get("events"))) defs.add(EventDef.from(Json.obj(e)));
            return new Definition(name, initialState,
                    (Map<String, Object>) Json.deepCopy(initialVars), states, defs, copy);
        }

        /** 找到第一个名称匹配且条件在处理前快照上为真的处理器。 */
        public EventDef match(String eventName, Expr.Context ctx) {
            for (EventDef d : events) {
                if (!d.name.equals(eventName)) continue;
                if (d.condition == null) return d;
                try {
                    if (Expr.truthy(d.condition.eval(ctx))) return d;
                } catch (RuntimeException e) {
                    // 条件求值失败视为不匹配（例如引用了不存在的变量）
                }
            }
            return null;
        }
    }

    public static final class EventDef {
        public final String name;
        public final Expr condition;      // 可为 null（无条件）
        public final String conditionSrc;
        public final List<Action> actions;

        private EventDef(String name, Expr condition, String conditionSrc, List<Action> actions) {
            this.name = name;
            this.condition = condition;
            this.conditionSrc = conditionSrc;
            this.actions = actions;
        }

        static EventDef from(Map<String, Object> m) {
            String name = Json.str(m.get("name"));
            if (name == null) throw new IllegalArgumentException("事件定义缺少 name");
            String condSrc = m.containsKey("condition") ? Json.str(m.get("condition")) : null;
            Expr cond = condSrc == null ? null : Expr.compile(condSrc);
            List<Action> actions = new ArrayList<>();
            if (m.containsKey("actions"))
                for (Object a : Json.arr(m.get("actions"))) actions.add(Action.from(Json.obj(a)));
            return new EventDef(name, cond, condSrc, actions);
        }
    }

    /** 动作：set / transition / emit / raise / fail。fail 触发整体回滚。 */
    public static final class Action {
        public final String type;
        public final String var;        // set
        public final Expr value;        // set / emit / fail.message / fail.when
        public final String to;         // transition（字面量状态名）
        public final Expr toExpr;       // transition（表达式）
        public final String event;      // raise
        public final Map<String, Object> data; // raise 附带数据
        public final Expr when;         // fail 的触发条件

        private Action(String type, String var, Expr value, String to, Expr toExpr,
                       String event, Map<String, Object> data, Expr when) {
            this.type = type;
            this.var = var;
            this.value = value;
            this.to = to;
            this.toExpr = toExpr;
            this.event = event;
            this.data = data;
            this.when = when;
        }

        @SuppressWarnings("unchecked")
        static Action from(Map<String, Object> m) {
            String type = Json.str(m.get("type"));
            if (type == null) throw new IllegalArgumentException("动作缺少 type");
            switch (type) {
                case "set": {
                    String var = Json.str(m.get("var"));
                    Expr value = Expr.compile(Json.str(m.get("value")));
                    return new Action(type, var, value, null, null, null, null, null);
                }
                case "transition": {
                    Object to = m.get("to");
                    if (to instanceof String && m.get("toExpr") == null)
                        return new Action(type, null, null, (String) to, null, null, null, null);
                    return new Action(type, null, null, null, Expr.compile(Json.str(m.get("toExpr"))), null, null, null);
                }
                case "emit": {
                    Expr value = Expr.compile(Json.str(m.get("output")));
                    return new Action(type, null, value, null, null, null, null, null);
                }
                case "raise": {
                    String event = Json.str(m.get("event"));
                    Map<String, Object> data = m.containsKey("data")
                            ? (Map<String, Object>) Json.deepCopy(Json.obj(m.get("data")))
                            : new LinkedHashMap<>();
                    return new Action(type, null, null, null, null, event, data, null);
                }
                case "fail": {
                    Expr msg = m.containsKey("message") ? Expr.compile(Json.str(m.get("message"))) : null;
                    Expr when = m.containsKey("when") ? Expr.compile(Json.str(m.get("when"))) : null;
                    return new Action(type, null, msg, null, null, null, null, when);
                }
                default:
                    throw new IllegalArgumentException("未知动作类型: " + type);
            }
        }
    }

    /** 事件：逻辑时间 + 来源 + 原始序号决定稳定顺序。 */
    public static final class Event {
        public String id;
        public String name;
        public long time;
        public String source;
        public long seq;
        public boolean internal;
        public String causedBy;          // 内部事件的来源事件 id
        public Map<String, Object> data;

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("time", time);
            m.put("source", source);
            m.put("seq", seq);
            if (internal) m.put("internal", true);
            if (causedBy != null) m.put("causedBy", causedBy);
            if (data != null && !data.isEmpty()) m.put("data", data);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Event from(Map<String, Object> m) {
            Event e = new Event();
            e.id = m.containsKey("id") ? Json.str(m.get("id")) : null;
            e.name = Json.str(m.get("name"));
            if (e.name == null) throw new IllegalArgumentException("事件缺少 name");
            e.time = m.containsKey("time") ? Json.num(m.get("time")) : 0;
            e.source = m.containsKey("source") ? Json.str(m.get("source")) : "default";
            e.seq = m.containsKey("seq") ? Json.num(m.get("seq")) : 0;
            e.internal = Boolean.TRUE.equals(m.get("internal"));
            e.causedBy = m.containsKey("causedBy") ? Json.str(m.get("causedBy")) : null;
            e.data = m.containsKey("data") ? (Map<String, Object>) Json.deepCopy(Json.obj(m.get("data")))
                    : new LinkedHashMap<>();
            return e;
        }

        public Event copy() { return from(toJson()); }

        /** 内容一致性比较（不含 id），用于合并冲突检测。 */
        public boolean sameContent(Event o) {
            return name.equals(o.name) && time == o.time && source.equals(o.source)
                    && seq == o.seq && Json.canonical(data).equals(Json.canonical(o.data));
        }
    }
}
