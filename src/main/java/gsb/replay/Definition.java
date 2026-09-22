package gsb.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态机定义（版本化）。字段：
 *  version        定义版本号，保存定义时由服务端递增
 *  name           定义名称
 *  states         合法状态名列表
 *  initialState   初始状态
 *  initialVars    初始变量（状态数据）
 *  seed           随机种子（与定义版本一起锁定）
 *  transitions    转移规则：{event, sources(可空=任意), condition(可空), target, actions}
 *
 * 指纹 fingerprint = sha256(canonical(version,name,states,initialState,initialVars,seed,transitions))
 */
public final class Definition {
    public final Map<String, Object> raw;

    private Definition(Map<String, Object> raw) {
        this.raw = raw;
    }

    public static Definition fromRaw(Map<String, Object> raw) {
        validate(raw);
        return new Definition(new LinkedHashMap<>(raw));
    }

    public String version() {
        return Json.str(raw.get("version"));
    }

    public String initialState() {
        return (String) raw.get("initialState");
    }

    public long seed() {
        return Json.asLong(raw.get("seed"), 0L);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> initialVars() {
        Object v = raw.get("initialVars");
        if (v instanceof Map) {
            return deepCopy((Map<String, Object>) v);
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> transitions() {
        List<Map<String, Object>> out = new ArrayList<>();
        Object t = raw.get("transitions");
        if (t instanceof List) {
            for (Object o : (List<Object>) t) {
                out.add((Map<String, Object>) o);
            }
        }
        return out;
    }

    /** 定义指纹：规范化 JSON（键排序）的 SHA-256。 */
    public String fingerprint() {
        return Hash.sha256Hex(Json.canonical(fingerprintBody()));
    }

    private Map<String, Object> fingerprintBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", raw.get("version"));
        body.put("name", raw.get("name"));
        body.put("states", raw.get("states"));
        body.put("initialState", raw.get("initialState"));
        body.put("initialVars", raw.get("initialVars"));
        body.put("seed", raw.get("seed"));
        body.put("transitions", raw.get("transitions"));
        return body;
    }

    @SuppressWarnings("unchecked")
    static void validate(Map<String, Object> raw) {
        require(raw.get("version") != null, "version is required");
        require(raw.get("initialState") instanceof String, "initialState must be a string");
        List<Object> states = Json.list(raw.get("states"), "definition.states");
        String initial = (String) raw.get("initialState");
        require(states.contains(initial), "initialState must be one of states");
        if (!(raw.get("initialVars") instanceof Map)) {
            raw.put("initialVars", new LinkedHashMap<String, Object>());
        }
        Object ts = raw.get("transitions");
        require(ts instanceof List, "transitions must be a list");
        int i = 0;
        for (Object o : (List<Object>) ts) {
            require(o instanceof Map, "transitions[" + i + "] must be an object");
            Map<String, Object> tr = (Map<String, Object>) o;
            require(tr.get("event") instanceof String, "transitions[" + i + "].event required");
            require(tr.get("target") instanceof String, "transitions[" + i + "].target required");
            require(states.contains(tr.get("target")),
                    "transitions[" + i + "] target not in states");
            Object sources = tr.get("sources");
            if (sources instanceof List) {
                for (Object s : (List<Object>) sources) {
                    require(states.contains(s), "transitions[" + i + "] source not in states");
                }
            }
            Object actions = tr.get("actions");
            require(actions == null || actions instanceof List,
                    "transitions[" + i + "].actions must be a list");
            i++;
        }
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw new Json.JsonException(msg);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepCopy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Object deepCopyValue(Object v) {
        if (v instanceof Map) {
            return deepCopy((Map<String, Object>) v);
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object e : (List<Object>) v) {
                out.add(deepCopyValue(e));
            }
            return out;
        }
        return v;
    }
}
