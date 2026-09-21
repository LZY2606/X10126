package io.example.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReplayState {
    public String currentState;
    public final Map<String, Object> data = new LinkedHashMap<>();
    public final Map<String, Object> variables = new LinkedHashMap<>();
    public final List<StoredEvent> internalQueue = new ArrayList<>();
    public final List<TraceStep> trace = new ArrayList<>();
    public long nextInternalSeq = 1L;
    public long randomSeedState;
    public long randomDraws;

    public ReplayState(String currentState, Map<String, Object> data, long originalSeed) {
        this.currentState = currentState;
        this.data.putAll(Json.object(Json.deepCopy(data)));
        this.randomSeedState = originalSeed;
    }

    ReplayState() {
    }

    public DeterministicRandom random() {
        return DeterministicRandom.fromState(randomSeedState, randomDraws);
    }

    public void saveRandom(DeterministicRandom random) {
        randomSeedState = random.seed();
        randomDraws = random.draws();
    }

    public ReplayState copy() {
        ReplayState result = new ReplayState();
        result.currentState = currentState;
        result.data.putAll(Json.object(Json.deepCopy(data)));
        result.variables.putAll(Json.object(Json.deepCopy(variables)));
        for (StoredEvent event : internalQueue) {
            result.internalQueue.add(event.copy());
        }
        for (TraceStep step : trace) {
            result.trace.add(step.copy());
        }
        result.nextInternalSeq = nextInternalSeq;
        result.randomSeedState = randomSeedState;
        result.randomDraws = randomDraws;
        return result;
    }

    public Map<String, Object> snapshotJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", currentState);
        result.put("data", Json.deepCopy(data));
        result.put("variables", Json.deepCopy(variables));
        return result;
    }
}
