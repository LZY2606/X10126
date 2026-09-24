package replay.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import replay.core.Hash;
import replay.core.Json;

/**
 * 状态机定义：名称/版本、状态集合、初始状态、初始变量、带条件跳转。
 * 定义指纹（fingerprint）对规范化定义 JSON 求 SHA-256，和随机种子一起被会话锁定。
 */
public final class SMDef {
    public String name;
    public String version;
    public final List<String> states = new ArrayList<>();
    public String initialState;
    public final Map<String, Object> initialVars = new LinkedHashMap<>();
    public final List<TransitionDef> transitions = new ArrayList<>();

    private String fingerprint;

    public SMDef(Map<String, Object> raw) {
        load(raw);
    }

    public void load(Map<String, Object> raw) {
        this.name = Json.strOr(raw.get("name"), "未命名状态机");
        this.version = Json.strOr(raw.get("version"), "v0");
        this.states.clear();
        for (Object s : Json.list(raw.get("states"))) {
            states.add(Json.str(s));
        }
        this.initialState = Json.strOr(raw.get("initialState"),
                states.isEmpty() ? "init" : states.get(0));
        this.initialVars.clear();
        this.initialVars.putAll(Json.obj(raw.get("initialVars")));
        this.transitions.clear();
        for (Object t : Json.list(raw.get("transitions"))) {
            transitions.add(new TransitionDef(Json.obj(t)));
        }
        validate();
        this.fingerprint = Hash.fingerprint(canonicalRaw(raw));
    }

    private static Map<String, Object> canonicalRaw(Map<String, Object> raw) {
        // 只对语义字段取指纹，避免 UI 附加字段影响确定性。
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", Json.strOr(raw.get("name"), "未命名状态机"));
        m.put("version", Json.strOr(raw.get("version"), "v0"));
        m.put("states", Json.list(raw.get("states")));
        m.put("initialState", Json.strOr(raw.get("initialState"),
                Json.list(raw.get("states")).isEmpty() ? "init" : Json.str(Json.list(raw.get("states")).get(0))));
        m.put("initialVars", Json.obj(raw.get("initialVars")));
        m.put("transitions", Json.list(raw.get("transitions")));
        return m;
    }

    public void validate() {
        if (states.isEmpty()) {
            throw new IllegalArgumentException("定义至少需要一个状态");
        }
        if (!states.contains(initialState)) {
            throw new IllegalArgumentException("initialState 不在 states 中：" + initialState);
        }
        for (TransitionDef t : transitions) {
            if (t.on == null || t.on.isBlank()) {
                throw new IllegalArgumentException("存在缺少 on 的跳转");
            }
            if (t.to != null && !states.contains(t.to)) {
                throw new IllegalArgumentException("跳转 " + t.on + " 的目标状态不存在：" + t.to);
            }
        }
    }

    public String fingerprint() {
        return fingerprint;
    }

    public Map<String, Object> toView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("version", version);
        m.put("states", new ArrayList<>(states));
        m.put("initialState", initialState);
        m.put("initialVars", new LinkedHashMap<>(initialVars));
        List<Object> ts = new ArrayList<>();
        for (TransitionDef t : transitions) {
            ts.add(t.toRaw());
        }
        m.put("transitions", ts);
        m.put("fingerprint", fingerprint);
        return m;
    }
}
