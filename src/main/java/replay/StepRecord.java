package replay;

import java.util.List;
import java.util.Map;

/** One applied step in a branch trace. */
public final class StepRecord {
    public static final String OK = "OK";
    public static final String FAILED = "FAILED";
    public static final String NO_TRANSITION = "NO_TRANSITION";

    public final long index;
    public final Event event;
    public final String status;
    public final String beforeState;
    public final Map<String, Object> beforeVars;
    public final Snapshot after;
    public final List<Object> outputs;
    public final String failure;
    public final Integer transition;

    public StepRecord(long index, Event event, String status, String beforeState,
                      Map<String, Object> beforeVars, Snapshot after, List<Object> outputs,
                      String failure, Integer transition) {
        this.index = index;
        this.event = event;
        this.status = status;
        this.beforeState = beforeState;
        this.beforeVars = beforeVars;
        this.after = after;
        this.outputs = outputs;
        this.failure = failure;
        this.transition = transition;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = Json.map();
        m.put("index", index);
        m.put("event", event.toJson());
        m.put("status", status);
        Map<String, Object> before = Json.map();
        before.put("state", beforeState);
        before.put("vars", beforeVars);
        m.put("before", before);
        m.put("after", after.toJson());
        m.put("outputs", outputs);
        m.put("failure", failure);
        m.put("transition", transition);
        return m;
    }

    public static StepRecord fromJson(Map<String, Object> json) {
        Map<String, Object> before = Json.asMap(json.get("before"), "step.before");
        Object failure = json.get("failure");
        Object transition = json.get("transition");
        return new StepRecord(
                Json.asLong(json.get("index"), "step.index"),
                Event.fromStoredJson(Json.asMap(json.get("event"), "step.event")),
                Json.asString(json.get("status"), "step.status"),
                Json.asString(before.get("state"), "step.before.state"),
                Json.asMap(before.get("vars"), "step.before.vars"),
                Snapshot.fromJson(Json.asMap(json.get("after"), "step.after")),
                Json.asList(json.get("outputs"), "step.outputs"),
                failure instanceof String ? (String) failure : null,
                transition instanceof Number ? ((Number) transition).intValue() : null);
    }
}
