package rr.engine;

import rr.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 状态机定义（版本化）。
 * 形态：
 * {
 *   "name":"订单流程", "version":"1", "initialState":"created",
 *   "initialData":{...},
 *   "transitions":[
 *     {"from":"created|*","on":"pay","when":"expr","goto":"paid",
 *      "actions":[ {动作} ... ]}
 *   ]
 * }
 * 动作类型：
 *   {"type":"set","path":"a.b","expr":"..."}
 *   {"type":"emit","type":"ship","payload":{... 字面量，字符串以 "=" 开头表示表达式 ...}}
 *   {"type":"fail","expr":"'原因'"}
 *   {"type":"rand","path":"x","min":0,"max":100}   // [min,max) 确定性随机整数
 *   {"type":"inc","path":"n","expr":"1"}
 */
public final class Definition {
    public String name = "";
    public String version = "1";
    public String initialState;
    public Map<String, Object> initialData;
    public List<Transition> transitions = new ArrayList<>();
    public String rawJson;
    public String fingerprint;

    public static final class Action {
        public String type;
        public String path;
        public String expr;
        public long min, max;
        public Map<String, Object> payload;
    }

    public static final class Transition {
        public String from; // 可为 null 表示 "*"
        public String on;
        public String when; // 可为 null
        public String to;
        public List<Action> actions = new ArrayList<>();

        boolean fromMatches(String state) {
            return from == null || from.equals("*") || from.equals(state);
        }
    }

    public static Definition parse(String text) {
        Object root;
        try {
            root = Json.parse(text);
        } catch (Json.JsonException e) {
            throw new BadDefinition("定义不是合法 JSON：" + e.getMessage());
        }
        return fromParsed(root, text);
    }

    public static Definition fromParsed(Object root, String rawJson) {
        if (!(root instanceof Map)) throw new BadDefinition("定义顶层必须是 object");
        Definition d = new Definition();
        Map<String, Object> m = Json.asObj(root);
        d.name = Json.optStr(root, "name", "");
        d.version = Json.optStr(root, "version", "1");
        d.initialState = Json.str(root, "initialState");
        d.initialData = Json.optObj(root, "initialData");
        d.rawJson = rawJson;
        Object ts = m.get("transitions");
        if (!(ts instanceof List)) throw new BadDefinition("transitions 必须是数组");
        int ti = 0;
        for (Object t : Json.asArr(ts)) {
            d.transitions.add(parseTransition(t, ti++));
        }
        if (d.initialState == null || d.initialState.isBlank())
            throw new BadDefinition("initialState 不能为空");
        d.fingerprint = fingerprint(root);
        return d;
    }

    private static Transition parseTransition(Object tv, int idx) {
        if (!(tv instanceof Map)) throw new BadDefinition("第 " + idx + " 条迁移必须是 object");
        Transition t = new Transition();
        String from = Json.optStr(tv, "from", "*");
        t.from = "*".equals(from) ? null : from;
        t.on = Json.str(tv, "on");
        t.when = Json.optStr(tv, "when", null);
        t.to = Json.optStr(tv, "goto", Json.optStr(tv, "to", null));
        if (t.on == null || t.on.isBlank()) throw new BadDefinition("第 " + idx + " 条迁移缺少 on");
        for (Object a : Json.optArr(tv, "actions")) {
            t.actions.add(parseAction(a, idx));
        }
        return t;
    }

    private static Action parseAction(Object av, int ti) {
        if (!(av instanceof Map)) throw new BadDefinition("第 " + ti + " 条迁移的动作必须是 object");
        Action a = new Action();
        a.type = Json.str(av, "type");
        a.path = Json.optStr(av, "path", null);
        a.expr = Json.optStr(av, "expr", null);
        a.min = Json.optLng(av, "min", 0);
        a.max = Json.optLng(av, "max", 0);
        Object pl = Json.asObj(av).get("payload");
        if (pl != null) {
            if (!(pl instanceof Map)) throw new BadDefinition("emit 的 payload 必须是 object");
            a.payload = Json.asObj(pl);
        }
        switch (a.type) {
            case "set", "inc" -> {
                if (a.path == null || a.path.isBlank())
                    throw new BadDefinition("动作 " + a.type + " 需要 path");
                if (a.expr == null) throw new BadDefinition("动作 " + a.type + " 需要 expr");
            }
            case "emit" -> {
                String at = Json.optStr(av, "eventType", Json.optStr(av, "event", null));
                if (at != null) {
                    // 允许 {"type":"emit","eventType":"x",...} 的简写
                    a.payload = a.payload == null ? new java.util.LinkedHashMap<>() : a.payload;
                    a.payload.putIfAbsent("__eventType", at);
                }
                if (a.payload != null) {
                    Object et = a.payload.get("__eventType");
                    if (!(et instanceof String es) || es.isBlank())
                        throw new BadDefinition("emit 动作需要 payload.__eventType");
                } else {
                    throw new BadDefinition("emit 动作需要 payload");
                }
            }
            case "fail" -> {
                if (a.expr == null) throw new BadDefinition("fail 动作需要 expr（失败原因表达式）");
            }
            case "rand" -> {
                if (a.path == null) throw new BadDefinition("rand 动作需要 path");
                if (a.max <= a.min) throw new BadDefinition("rand 要求 max > min");
            }
            default -> throw new BadDefinition("未知动作类型: " + a.type);
        }
        return a;
    }

    /** 定义指纹 = 规范化 JSON（键排序、整数归一）的 SHA-256。 */
    public static String fingerprint(Object parsedRoot) {
        return Util.sha256(Json.canonical(parsedRoot));
    }
}
