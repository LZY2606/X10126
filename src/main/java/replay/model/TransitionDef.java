package replay.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 带条件的事件跳转：on 匹配事件名，when 针对处理前快照求值。 */
public final class TransitionDef {
    public final String on;
    public final String when;
    public final String to;
    public final List<ActionDef> actions = new ArrayList<>();

    public TransitionDef(Map<String, Object> raw) {
        this.on = replay.core.Json.strOr(raw.get("on"), "");
        this.when = replay.core.Json.strOr(raw.get("when"), null);
        this.to = replay.core.Json.strOr(raw.get("to"), null);
        for (Object a : replay.core.Json.list(raw.get("actions"))) {
            actions.add(new ActionDef(replay.core.Json.obj(a)));
        }
    }

    public Map<String, Object> toRaw() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("on", on);
        if (when != null) {
            m.put("when", when);
        }
        if (to != null) {
            m.put("to", to);
        }
        List<Object> acts = new ArrayList<>();
        for (ActionDef a : actions) {
            acts.add(a.toRaw());
        }
        m.put("actions", acts);
        return m;
    }
}
