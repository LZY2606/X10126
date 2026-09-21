package com.replayroom.engine;

import java.util.List;
import java.util.Map;

/** Outcome of processing one event. */
public final class StepResult {

    /** ok | failed | no_transition */
    public String status;
    public Map<String, Object> event;
    public Map<String, Object> transition;
    public boolean matched;
    public String failure;

    public String stateBefore;
    public String stateAfter;
    public Map<String, Object> dataBefore;
    public Map<String, Object> dataAfter;

    public List<Map<String, Object>> outputs = List.of();
    public List<Map<String, Object>> emittedInternal = List.of();
    public long rngBefore;
    public long rngAfter;
}
