package replayroom.model;

import java.util.LinkedHashMap;
import java.util.Map;
import replayroom.json.Json;

public record OutputRec(String name, Map<String, Object> data) {
    public OutputRec {
        data = data == null ? new LinkedHashMap<>() : Json.deepCopyMap(data);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("data", data);
        return map;
    }

    public static OutputRec fromMap(Map<String, Object> map) {
        return new OutputRec(Json.string(map.get("name"), "output.name"), Json.optObject(map, "data"));
    }
}
