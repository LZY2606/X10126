package io.example.replay;

import java.util.LinkedHashMap;
import java.util.Map;

public class Session {
    public String id;
    public String name;
    public MachineDefinition definition;
    public long seed;
    public Map<String, Object> initialState;
    public Map<String, Branch> branches = new LinkedHashMap<>();
    public String inputFingerprint;

    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        result.put("definition", definition.toJson());
        result.put("seed", seed);
        result.put("initialState", initialState);
        result.put("branches", branches.values().stream().map(Branch::toJson).toList());
        result.put("inputFingerprint", inputFingerprint);
        return result;
    }

    public static Session fromJson(Object value) {
        Map<String, Object> object = Json.object(value);
        Session result = new Session();
        result.id = Json.string(object, "id");
        result.name = Json.string(object, "name");
        result.definition = MachineDefinition.parse(object.get("definition"));
        result.seed = Json.longValue(object, "seed", 0L);
        result.initialState = Json.object(Json.deepCopy(object.get("initialState")));
        for (Object branch : Json.list(object.get("branches"))) {
            Branch parsed = Branch.fromJson(branch);
            result.branches.put(parsed.id, parsed);
        }
        result.inputFingerprint = Json.string(object, "inputFingerprint");
        return result;
    }
}
