package replay.core;

import replay.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态机定义：状态集合、初始状态、初始变量、来源优先级、迁移表。
 * 定义指纹 = 规范化 JSON 的 SHA-256，用于锁定定义版本。
 */
public final class Definition {

    public static final class Transition {
        public final String event;
        public final String from;          // "*" 表示任意状态
        public final String to;            // null 表示保持原状态
        public final String guard;         // null 表示无条件
        public final List<Map<String, Object>> actions;

        Transition(String event, String from, String to, String guard, List<Map<String, Object>> actions) {
            this.event = event;
            this.from = from;
            this.to = to;
            this.guard = guard;
            this.actions = actions;
        }
    }

    public final Map<String, Object> raw;          // 原始定义（用于序列化与指纹）
    public final String name;
    public final List<String> states;
    public final String initial;
    public final Map<String, Object> initialVars;
    public final Map<String, Long> sourcePriority; // 数值越小优先级越高
    public final List<Transition> transitions;
    private final String fingerprint;

    @SuppressWarnings("unchecked")
    public Definition(Map<String, Object> raw) {
        this.raw = raw;
        this.name = raw.containsKey("name") ? Json.asString(raw.get("name"), "name") : "machine";
        this.states = new ArrayList<>();
        for (Object s : Json.asList(raw.get("states"), "states")) {
            this.states.add(Json.asString(s, "状态名"));
        }
        if (this.states.isEmpty()) throw new Json.JsonException("states 不能为空");
        this.initial = Json.asString(raw.get("initial"), "initial");
        if (!this.states.contains(this.initial)) {
            throw new Json.JsonException("initial 不在 states 中: " + this.initial);
        }
        this.initialVars = new LinkedHashMap<>();
        if (raw.containsKey("vars")) {
            this.initialVars.putAll(Json.asMap(raw.get("vars"), "vars"));
        }
        this.sourcePriority = new LinkedHashMap<>();
        if (raw.containsKey("sources")) {
            for (Map.Entry<String, Object> e : Json.asMap(raw.get("sources"), "sources").entrySet()) {
                this.sourcePriority.put(e.getKey(), Json.asLong(e.getValue(), "来源优先级"));
            }
        }
        this.transitions = new ArrayList<>();
        if (raw.containsKey("transitions")) {
            for (Object t : Json.asList(raw.get("transitions"), "transitions")) {
                Map<String, Object> tm = Json.asMap(t, "transition");
                String event = Json.asString(tm.get("event"), "transition.event");
                String from = tm.containsKey("from") ? Json.asString(tm.get("from"), "transition.from") : "*";
                String to = tm.containsKey("to") ? Json.asString(tm.get("to"), "transition.to") : null;
                String guard = tm.containsKey("guard") ? Json.asString(tm.get("guard"), "transition.guard") : null;
                List<Map<String, Object>> actions = new ArrayList<>();
                if (tm.containsKey("actions")) {
                    for (Object a : Json.asList(tm.get("actions"), "transition.actions")) {
                        actions.add(Json.asMap(a, "action"));
                    }
                }
                if (to != null && !this.states.contains(to)) {
                    throw new Json.JsonException("transition.to 不在 states 中: " + to);
                }
                this.transitions.add(new Transition(event, from, to, guard, actions));
            }
        }
        this.fingerprint = Hashes.sha256(Json.writeCanonical(raw));
    }

    public String fingerprint() { return fingerprint; }

    public long priorityOf(String source) {
        return sourcePriority.getOrDefault(source, 100L);
    }

    /** 依据处理前快照选择第一条匹配的迁移；无匹配返回 null。 */
    public Transition match(String eventType, String state, replay.expr.Exprs.Context ctx) {
        for (Transition t : transitions) {
            if (!t.event.equals(eventType)) continue;
            if (!t.from.equals("*") && !t.from.equals(state)) continue;
            if (t.guard != null && !replay.expr.Exprs.evalBool(t.guard, ctx)) continue;
            return t;
        }
        return null;
    }
}
