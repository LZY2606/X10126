package replayroom.core;

import com.fasterxml.jackson.databind.JsonNode;
import replayroom.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 状态机定义：状态、初始变量、来源优先级、规则（事件→条件→动作）。 */
public class Definition {
    public String name;
    public String initialState;
    public Map<String, Object> initialVariables = new LinkedHashMap<>();
    public List<String> sources = new ArrayList<>();
    public List<Rule> rules = new ArrayList<>();
    public JsonNode raw;
    public String fingerprint;

    public static Definition parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("定义必须是 JSON 对象");
        }
        Definition def = new Definition();
        def.raw = node.deepCopy();
        def.name = node.path("name").asText("unnamed");
        def.initialState = node.path("initialState").asText(null);
        if (def.initialState == null || def.initialState.isBlank()) {
            throw new IllegalArgumentException("定义缺少 initialState");
        }
        JsonNode vars = node.path("variables");
        if (vars.isObject()) {
            vars.fields().forEachRemaining(e ->
                    def.initialVariables.put(e.getKey(), Json.M.convertValue(e.getValue(), Object.class)));
        }
        JsonNode sources = node.path("sources");
        if (sources.isArray()) {
            for (JsonNode s : sources) {
                def.sources.add(s.asText());
            }
        }
        JsonNode rules = node.path("rules");
        if (!rules.isArray()) {
            throw new IllegalArgumentException("定义缺少 rules 数组");
        }
        for (JsonNode r : rules) {
            Rule rule = new Rule();
            rule.event = r.path("event").asText(null);
            if (rule.event == null || rule.event.isBlank()) {
                throw new IllegalArgumentException("规则缺少 event");
            }
            rule.from = r.hasNonNull("from") ? r.get("from").asText() : null;
            rule.to = r.hasNonNull("to") ? r.get("to").asText() : null;
            rule.condition = r.hasNonNull("condition") ? r.get("condition").asText() : null;
            JsonNode actions = r.path("actions");
            if (actions.isArray()) {
                for (JsonNode a : actions) {
                    Action action = new Action();
                    action.type = a.path("type").asText(null);
                    if (action.type == null) {
                        throw new IllegalArgumentException("动作缺少 type");
                    }
                    action.var = a.hasNonNull("var") ? a.get("var").asText() : null;
                    action.expr = a.hasNonNull("expr") ? a.get("expr").asText() : null;
                    action.event = a.hasNonNull("event") ? a.get("event").asText() : null;
                    action.condition = a.hasNonNull("condition") ? a.get("condition").asText() : null;
                    if (a.has("data") && a.get("data").isObject()) {
                        action.data = new LinkedHashMap<>();
                        a.get("data").fields().forEachRemaining(e ->
                                action.data.put(e.getKey(), Json.M.convertValue(e.getValue(), Object.class)));
                    }
                    switch (action.type) {
                        case "set":
                            if (action.var == null || action.expr == null) {
                                throw new IllegalArgumentException("set 动作需要 var 与 expr");
                            }
                            break;
                        case "emit":
                            if (action.event == null) {
                                throw new IllegalArgumentException("emit 动作需要 event");
                            }
                            break;
                        case "assert":
                            if (action.condition == null) {
                                throw new IllegalArgumentException("assert 动作需要 condition");
                            }
                            break;
                        default:
                            throw new IllegalArgumentException("未知动作类型: " + action.type);
                    }
                    rule.actions.add(action);
                }
            }
            def.rules.add(rule);
        }
        def.fingerprint = Json.sha256(Json.canonicalString(def.raw));
        return def;
    }

    /** 来源优先级：列表越靠前优先级越高；未知来源排在所有已知来源之后。 */
    public int sourcePriority(String source) {
        int idx = sources.indexOf(source);
        return idx >= 0 ? idx : sources.size();
    }
}
