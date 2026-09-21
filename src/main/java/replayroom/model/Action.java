package replayroom.model;

import java.util.Map;
import replayroom.json.Json;

/**
 * One action on a transition.
 *
 * <ul>
 *   <li>set:    target (dotted path), valueExpr</li>
 *   <li>output: name, dataExpr (optional)</li>
 *   <li>emit:   event, source (optional), dataExpr (optional), timeDelta (optional)</li>
 *   <li>fail:   messageExpr, chance (0..1, optional)</li>
 * </ul>
 */
public record Action(
        String type,
        String target,
        String valueExpr,
        String name,
        String dataExpr,
        String event,
        String source,
        Long timeDelta,
        String messageExpr,
        Double chance
) {
    public static Action fromMap(Map<String, Object> map, int index) {
        String where = "actions[" + index + "]";
        String type = Json.optString(map, "type", "");
        return switch (type) {
            case "set" -> new Action(
                    type,
                    require(Json.optString(map, "target", null), where + ".target"),
                    require(Json.optString(map, "valueExpr", map.containsKey("value") ? String.valueOf(map.get("value")) : null),
                            where + ".valueExpr"),
                    null, null, null, null, null, null, null);
            case "output" -> new Action(
                    type, null, null,
                    require(Json.optString(map, "name", null), where + ".name"),
                    Json.optString(map, "dataExpr", null),
                    null, null, null, null, null);
            case "emit" -> new Action(
                    type, null, null, null,
                    Json.optString(map, "dataExpr", null),
                    require(Json.optString(map, "event", null), where + ".event"),
                    Json.optString(map, "source", "internal"),
                    map.containsKey("timeDelta") ? Json.longValue(map.get("timeDelta"), where + ".timeDelta") : 0L,
                    null, null);
            case "fail" -> {
                Double chance = Json.optDoubleObj(map, "chance");
                if (chance != null && (chance < 0.0 || chance > 1.0)) {
                    throw new IllegalArgumentException(where + ".chance must be within [0,1]");
                }
                yield new Action(
                        type, null, null, null, null, null, null, null,
                        Json.optString(map, "messageExpr", Json.optString(map, "message", "action failure")),
                        chance);
            }
            default -> throw new IllegalArgumentException(where + ".type must be set|output|emit|fail");
        };
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = Json.obj("type", type);
        switch (type) {
            case "set" -> {
                map.put("target", target);
                map.put("valueExpr", valueExpr);
            }
            case "output" -> {
                map.put("name", name);
                if (dataExpr != null) map.put("dataExpr", dataExpr);
            }
            case "emit" -> {
                map.put("event", event);
                map.put("source", source);
                map.put("timeDelta", timeDelta);
                if (dataExpr != null) map.put("dataExpr", dataExpr);
            }
            case "fail" -> {
                map.put("messageExpr", messageExpr);
                if (chance != null) map.put("chance", chance);
            }
            default -> throw new IllegalStateException(type);
        }
        return map;
    }
}
