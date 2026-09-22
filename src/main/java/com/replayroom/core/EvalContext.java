package com.replayroom.core;

import java.util.Map;

/** Everything an expression may read while an event is being processed. */
public final class EvalContext {
    public final Map<String, Object> vars;
    public final String state;
    public final Map<String, Object> payload;
    public final long time;
    public final DeterministicRandom rng;

    public EvalContext(Map<String, Object> vars, String state, Map<String, Object> payload,
                       long time, DeterministicRandom rng) {
        this.vars = vars;
        this.state = state;
        this.payload = payload;
        this.time = time;
        this.rng = rng;
    }
}
