package replay.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个动作的静态定义。
 * op 取值：set / inc / rand / output / internal / fail
 */
public final class ActionDef {
    public final String op;
    public final String target;
    public final String expr;
    public final String name;
    public final String message;
    public final boolean failOnZero;
    public final Map<String, String> payloadExpr;

    public ActionDef(Map<String, Object> raw) {
        this.op = replay.core.Json.strOr(raw.get("op"), "").trim();
        this.target = replay.core.Json.strOr(raw.get("target"), null);
        this.expr = replay.core.Json.strOr(raw.get("value"), raw.get("expr") == null ? null : replay.core.Json.str(raw.get("expr")));
        this.name = replay.core.Json.strOr(raw.get("name"), null);
        this.message = replay.core.Json.strOr(raw.get("message"), "动作失败");
        this.failOnZero = replay.core.Json.boolOr(raw.get("failOnZero"), false);
        this.payloadExpr = new LinkedHashMap<>();
        Object payload = raw.get("payload");
        if (payload instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) payload).entrySet()) {
                Object v = e.getValue();
                payloadExpr.put(String.valueOf(e.getKey()), v == null ? "null" : String.valueOf(v));
            }
        }
        if (this.expr == null && raw.get("expr") instanceof String) {
            this.expr = (String) raw.get("expr");
        }
    }

    public Map<String, Object> toRaw() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        if (target != null) {
            m.put("target", target);
        }
        if (expr != null) {
            m.put("value", expr);
        }
        if (name != null) {
            m.put("name", name);
        }
        if (failOnZero) {
            m.put("failOnZero", true);
        }
        if (!"动作失败".equals(message)) {
            m.put("message", message);
        }
        if (!payloadExpr.isEmpty()) {
            m.put("payload", new LinkedHashMap<>(payloadExpr));
        }
        return m;
    }
}
