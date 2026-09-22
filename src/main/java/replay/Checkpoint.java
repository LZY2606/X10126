package replay;

import java.util.Map;

/** A named restore point. Bound to the definition fingerprint it was created under. */
public final class Checkpoint {
    public final String id;
    public final String name;
    public final String definitionFingerprint;
    public final long stepIndex;
    public final Snapshot snapshot;

    public Checkpoint(String id, String name, String definitionFingerprint, long stepIndex, Snapshot snapshot) {
        this.id = id;
        this.name = name;
        this.definitionFingerprint = definitionFingerprint;
        this.stepIndex = stepIndex;
        this.snapshot = snapshot;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = Json.map();
        m.put("id", id);
        m.put("name", name);
        m.put("definitionFingerprint", definitionFingerprint);
        m.put("stepIndex", stepIndex);
        m.put("snapshot", snapshot.toJson());
        return m;
    }

    public static Checkpoint fromJson(Map<String, Object> json) {
        return new Checkpoint(
                Json.asString(json.get("id"), "checkpoint.id"),
                Json.asString(json.get("name"), "checkpoint.name"),
                Json.asString(json.get("definitionFingerprint"), "checkpoint.definitionFingerprint"),
                Json.asLong(json.get("stepIndex"), "checkpoint.stepIndex"),
                Snapshot.fromJson(Json.asMap(json.get("snapshot"), "checkpoint.snapshot")));
    }
}
