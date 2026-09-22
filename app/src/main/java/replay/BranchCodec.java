package replay;

import java.util.ArrayList;
import java.util.Map;

final class BranchCodec {
    private BranchCodec() {}

    static Models.Branch read(Map<String, Object> json) {
        Models.Branch branch = new Models.Branch();
        branch.id = Json.string(json, "id");
        branch.name = Json.optionalString(json, "name", branch.id);
        branch.baseCheckpointId = Json.string(json, "baseCheckpointId");
        branch.headCheckpointId = Json.string(json, "headCheckpointId");
        branch.cursor = Json.integer(json, "cursor");
        for (Object event : Json.list(Json.required(json, "events"))) {
            branch.events.add(EventCodec.fill(new Models.EventEnvelope(), Json.object(event)));
        }
        for (Object step : Json.list(Json.required(json, "trace"))) {
            branch.trace.add(StepCodec.read(Json.object(step)));
        }
        return branch;
    }
}
