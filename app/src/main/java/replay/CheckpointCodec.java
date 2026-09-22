package replay;

import java.util.Map;

final class CheckpointCodec {
    private CheckpointCodec() {}

    static Models.Checkpoint read(Map<String, Object> json) {
        Models.Checkpoint checkpoint = new Models.Checkpoint();
        checkpoint.id = Json.string(json, "id");
        checkpoint.branchId = Json.string(json, "branchId");
        checkpoint.step = Json.integer(json, "step");
        checkpoint.parentCheckpointId = json.get("parentCheckpointId") == null ? null : String.valueOf(json.get("parentCheckpointId"));
        checkpoint.definitionFingerprint = Json.string(json, "definitionFingerprint");
        checkpoint.stateHash = Json.string(json, "stateHash");
        checkpoint.traceHash = Json.string(json, "traceHash");
        checkpoint.state = Json.string(json, "state");
        checkpoint.variables = Json.object(Json.required(json, "vars"));
        checkpoint.randomState = Json.longValue(json, "randomState");
        for (Object item : Json.list(Json.required(json, "internalQueue"))) {
            checkpoint.internalQueue.add(EventCodec.fill(new Models.EventEnvelope(), Json.object(item)));
        }
        return checkpoint;
    }
}
