package replayroom.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replayroom.json.Json;

/** One recorded step in the replay trace. */
public final class TraceEntry {
    private final int step;
    private final Envelope event;
    private final boolean matched;
    private final Integer transitionIndex;
    private final String outcome;
    private final Map<String, Object> stateBefore;
    private final Map<String, Object> stateAfter;
    private final List<OutputRec> outputs;
    private final String failureMessage;
    private final List<Envelope> emitted;
    private String hash;

    public TraceEntry(int step, Envelope event, boolean matched, Integer transitionIndex, String outcome,
                      Map<String, Object> stateBefore, Map<String, Object> stateAfter,
                      List<OutputRec> outputs, String failureMessage, List<Envelope> emitted) {
        this.step = step;
        this.event = event;
        this.matched = matched;
        this.transitionIndex = transitionIndex;
        this.outcome = outcome;
        this.stateBefore = stateBefore;
        this.stateAfter = stateAfter;
        this.outputs = List.copyOf(outputs);
        this.failureMessage = failureMessage;
        this.emitted = List.copyOf(emitted);
    }

    public Map<String, Object> hashBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("step", step);
        body.put("event", event.toMap());
        body.put("matched", matched);
        body.put("transitionIndex", transitionIndex);
        body.put("outcome", outcome);
        body.put("stateBefore", stateBefore);
        body.put("stateAfter", stateAfter);
        List<Object> outputMaps = new ArrayList<>();
        for (OutputRec output : outputs) outputMaps.add(output.toMap());
        body.put("outputs", outputMaps);
        body.put("failure", failureMessage);
        List<Object> emittedMaps = new ArrayList<>();
        for (Envelope envelope : emitted) emittedMaps.add(envelope.toMap());
        body.put("emitted", emittedMaps);
        return body;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("step", step);
        map.put("event", event.toMap());
        map.put("matched", matched);
        map.put("transitionIndex", transitionIndex);
        map.put("outcome", outcome);
        map.put("stateBefore", stateBefore);
        map.put("stateAfter", stateAfter);
        List<Object> outputMaps = new ArrayList<>();
        for (OutputRec output : outputs) outputMaps.add(output.toMap());
        map.put("outputs", outputMaps);
        map.put("failure", failureMessage);
        List<Object> emittedMaps = new ArrayList<>();
        for (Envelope envelope : emitted) emittedMaps.add(envelope.toMap());
        map.put("emitted", emittedMaps);
        map.put("hash", hash);
        return map;
    }

    public static TraceEntry fromMap(Map<String, Object> map) {
        List<OutputRec> outputs = new ArrayList<>();
        for (Object value : Json.optList(map, "outputs")) {
            outputs.add(OutputRec.fromMap(Json.object(value, "output")));
        }
        List<Envelope> emitted = new ArrayList<>();
        for (Object value : Json.optList(map, "emitted")) {
            emitted.add(Envelope.fromMap(Json.object(value, "emitted")));
        }
        TraceEntry entry = new TraceEntry(
                Json.intValue(map.get("step"), "step"),
                Envelope.fromMap(Json.object(map.get("event"), "event")),
                Json.bool(map.get("matched"), "matched"),
                map.get("transitionIndex") == null ? null : Json.intValue(map.get("transitionIndex"), "transitionIndex"),
                Json.string(map.get("outcome"), "outcome"),
                Json.optObject(map, "stateBefore"),
                Json.optObject(map, "stateAfter"),
                outputs,
                map.get("failure") == null ? null : Json.string(map.get("failure"), "failure"),
                emitted);
        entry.hash = Json.optString(map, "hash", null);
        return entry;
    }

    public int step() { return step; }
    public Envelope event() { return event; }
    public boolean matched() { return matched; }
    public Integer transitionIndex() { return transitionIndex; }
    public String outcome() { return outcome; }
    public Map<String, Object> stateBefore() { return stateBefore; }
    public Map<String, Object> stateAfter() { return stateAfter; }
    public List<OutputRec> outputs() { return outputs; }
    public String failureMessage() { return failureMessage; }
    public List<Envelope> emitted() { return emitted; }
    public String hash() { return hash; }
    public void hash(String value) { this.hash = value; }
}
