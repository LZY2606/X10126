package replay.model;

import replay.core.ReplayException;
import replay.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 状态机定义：状态、带条件的事件变迁与动作。解析时做完整校验。 */
public final class Definition {
    public String name = "machine";
    public String version = "1";
    public long seed = 0;
    public String initialState;
    public List<String> states = new ArrayList<>();
    public Map<String, Object> initialData = Json.obj();
    public Map<String, EventType> eventTypes = new LinkedHashMap<>();

    public static final class EventType {
        public int priority;
        public List<Transition> transitions = new ArrayList<>();
    }

    public static final class Transition {
        public Set<String> from;
        public String to;
        public Map<String, Object> when;
        public List<Map<String, Object>> actions = new ArrayList<>();
    }

    private static final Set<String> CONDITION_OPS = Set.of(
            "and", "or", "not", "eq", "ne", "lt", "lte", "gt", "gte",
            "in", "contains", "exists", "matches", "true", "false");

    public int priorityOf(String type) {
        EventType et = eventTypes.get(type);
        return et == null ? 0 : et.priority;
    }

    public static Definition parse(Map<String, Object> raw) {
        Definition d = new Definition();
        d.name = Json.optString(raw, "name", "machine");
        d.version = Json.optString(raw, "version", "1");
        d.seed = Json.optLong(raw, "seed", 0);
        Object states = raw.get("states");
        if (states == null) throw new ReplayException(400, "定义缺少 states");
        for (Object o : Json.asList(states)) {
            String st = Json.asString(o);
            if (!d.states.contains(st)) d.states.add(st);
        }
        if (d.states.isEmpty()) throw new ReplayException(400, "states 不能为空");
        d.initialState = Json.optString(raw, "initialState", null);
        if (d.initialState == null || !d.states.contains(d.initialState)) {
            throw new ReplayException(400, "initialState 必须是 states 之一");
        }
        if (raw.get("initialData") != null) d.initialData = Json.asMap(raw.get("initialData"));
        List<String> emitRefs = new ArrayList<>();
        if (raw.get("eventTypes") != null) {
            for (Map.Entry<String, Object> e : Json.asMap(raw.get("eventTypes")).entrySet()) {
                d.eventTypes.put(e.getKey(), parseEventType(d, e.getKey(), Json.asMap(e.getValue()), emitRefs));
            }
        }
        for (String ref : emitRefs) {
            if (!d.eventTypes.containsKey(ref)) {
                throw new ReplayException(400, "emit 引用了未定义的事件类型: " + ref);
            }
        }
        return d;
    }

    private static EventType parseEventType(Definition d, String typeName, Map<String, Object> m,
                                            List<String> emitRefs) {
        EventType et = new EventType();
        et.priority = (int) Json.optLong(m, "sourcePriority", 0);
        if (m.get("transitions") != null) {
            for (Object o : Json.asList(m.get("transitions"))) {
                et.transitions.add(parseTransition(d, typeName, Json.asMap(o), emitRefs));
            }
        }
        return et;
    }

    private static Transition parseTransition(Definition d, String typeName, Map<String, Object> m,
                                              List<String> emitRefs) {
        Transition t = new Transition();
        Object from = m.get("from");
        if (from == null || "*".equals(from)) {
            t.from = null;
        } else if (from instanceof List) {
            t.from = new LinkedHashSet<>();
            for (Object o : Json.asList(from)) {
                String st = Json.asString(o);
                checkState(d, st);
                t.from.add(st);
            }
        } else {
            String st = Json.asString(from);
            checkState(d, st);
            t.from = Set.of(st);
        }
        if (m.get("to") != null) {
            t.to = Json.asString(m.get("to"));
            checkState(d, t.to);
        }
        if (m.get("when") != null) {
            t.when = Json.asMap(m.get("when"));
            validateCondition(t.when);
        }
        if (m.get("actions") != null) {
            for (Object a : Json.asList(m.get("actions"))) {
                t.actions.add(validateAction(Json.asMap(a), emitRefs));
            }
        }
        return t;
    }

    private static void checkState(Definition d, String st) {
        if (!d.states.contains(st)) throw new ReplayException(400, "未知状态: " + st);
    }

    private static Map<String, Object> validateAction(Map<String, Object> a, List<String> emitRefs) {
        String type = Json.optString(a, "type", null);
        if (type == null) throw new ReplayException(400, "动作缺少 type");
        switch (type) {
            case "set": {
                String path = Json.optString(a, "path", null);
                if (path == null || !path.startsWith("data.")) {
                    throw new ReplayException(400, "set 动作的 path 必须以 data. 开头");
                }
                break;
            }
            case "output":
                break;
            case "emit": {
                String et = Json.optString(a, "eventType", null);
                if (et == null) throw new ReplayException(400, "emit 动作缺少 eventType");
                emitRefs.add(et);
                break;
            }
            case "fail":
                break;
            default:
                throw new ReplayException(400, "未知动作类型: " + type);
        }
        return a;
    }

    private static void validateCondition(Map<String, Object> cond) {
        String op = Json.optString(cond, "op", null);
        if (op == null || !CONDITION_OPS.contains(op)) {
            throw new ReplayException(400, "未知条件 op: " + op);
        }
        switch (op) {
            case "and":
            case "or":
                if (cond.get("args") == null) throw new ReplayException(400, op + " 条件缺少 args");
                for (Object c : Json.asList(cond.get("args"))) validateCondition(Json.asMap(c));
                break;
            case "not":
                if (cond.get("arg") == null) throw new ReplayException(400, "not 条件缺少 arg");
                validateCondition(Json.asMap(cond.get("arg")));
                break;
            case "matches":
                try {
                    Pattern.compile(Json.optString(cond, "pattern", ""));
                } catch (PatternSyntaxException e) {
                    throw new ReplayException(400, "matches 条件正则非法: " + e.getMessage());
                }
                break;
            default:
                break;
        }
    }
}
