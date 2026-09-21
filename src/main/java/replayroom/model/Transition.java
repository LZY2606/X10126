package replayroom.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replayroom.json.Json;

public record Transition(
        String event,
        String from,
        String to,
        String condition,
        List<Action> actions
) {
    public static Transition fromMap(Map<String, Object> map, int index) {
        String where = "transitions[" + index + "]";
        String event = Json.optString(map, "event", null);
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException(where + ".event is required");
        }
        List<Action> actions = new ArrayList<>();
        int actionIndex = 0;
        for (Object actionValue : Json.optList(map, "actions")) {
            actions.add(Action.fromMap(Json.object(actionValue, where + ".actions[" + actionIndex + "]"), actionIndex));
            actionIndex++;
        }
        return new Transition(
                event,
                Json.optString(map, "from", null),
                Json.optString(map, "to", null),
                Json.optString(map, "condition", null),
                actions);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("event", event);
        if (from != null) map.put("from", from);
        if (to != null) map.put("to", to);
        if (condition != null && !condition.isBlank()) map.put("condition", condition);
        List<Object> actionMaps = new ArrayList<>();
        for (Action action : actions) actionMaps.add(action.toMap());
        map.put("actions", actionMaps);
        return map;
    }
}
