package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StepCodec {
    private StepCodec() {}

    static Models.StepRecord read(Map<String, Object> json) {
        Models.StepRecord step = new Models.StepRecord();
        step.step = Json.integer(json, "step");
        step.event = EventCodec.fill(new Models.EventEnvelope(), Json.object(Json.required(json, "event")));
        step.matched = Boolean.TRUE.equals(json.get("matched"));
        step.transition = json.get("transition") == null ? null : String.valueOf(json.get("transition"));
        step.fromState = Json.string(json, "fromState");
        step.toState = Json.string(json, "toState");
        step.failed = Boolean.TRUE.equals(json.get("failed"));
        step.error = json.get("error") == null ? null : String.valueOf(json.get("error"));
        step.beforeSnapshot = Json.object(Json.required(json, "beforeSnapshot"));
        step.afterSnapshot = Json.object(Json.required(json, "afterSnapshot"));
        step.changes = readChanges(Json.list(Json.required(json, "changes")));
        step.outputs = readObjectList(Json.list(Json.required(json, "outputs")));
        step.emitted = new ArrayList<>();
        Object emitted = json.get("emitted");
        if (emitted instanceof List<?> list) {
            for (Object item : list) step.emitted.add(EventCodec.fill(new Models.EventEnvelope(), Json.object(item)));
        }
        step.beforeHash = Json.optionalString(json, "beforeHash", "");
        step.afterHash = Json.optionalString(json, "afterHash", "");
        step.traceHash = Json.string(json, "traceHash");
        step.stateHash = Json.string(json, "stateHash");
        return step;
    }

    static Map<String, Object> write(Models.StepRecord step, boolean persisted) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("step", step.step);
        json.put("event", EventCodec.write(step.event));
        json.put("matched", step.matched);
        json.put("transition", step.transition);
        json.put("fromState", step.fromState);
        json.put("toState", step.toState);
        json.put("failed", step.failed);
        json.put("error", step.error);
        json.put("beforeSnapshot", step.beforeSnapshot);
        json.put("afterSnapshot", step.afterSnapshot);
        json.put("changes", step.changes);
        json.put("outputs", step.outputs);
        json.put("emitted", step.emitted.stream().map(EventCodec::write).toList());
        json.put("beforeHash", step.beforeHash);
        json.put("afterHash", step.afterHash);
        json.put("traceHash", step.traceHash);
        json.put("stateHash", step.stateHash);
        return json;
    }

    private static List<Map<String, Object>> readChanges(List<Object> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) result.add(Json.object(value));
        return result;
    }

    private static List<Map<String, Object>> readObjectList(List<Object> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) result.add(Json.object(value));
        return result;
    }
}
