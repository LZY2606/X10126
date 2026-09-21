package com.replayroom.engine;

import com.replayroom.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable replay position: current machine state, state data and RNG.
 * Snapshot/restore are used both for action-transaction rollback and for
 * creating branch checkpoints.
 */
public final class Frame {

    private String state;
    private Map<String, Object> data;
    private final Rng rng;

    public Frame(String state, Map<String, Object> data, long seed) {
        this.state = state;
        this.data = new LinkedHashMap<>(data);
        this.rng = new Rng(seed);
    }

    public String state() {
        return state;
    }

    public Map<String, Object> data() {
        return data;
    }

    public Rng rng() {
        return rng;
    }

    public void restoreData(Map<String, Object> snapshot) {
        this.data = deepCopy(snapshot);
    }

    public void commit(String newState, Map<String, Object> newData) {
        this.state = newState;
        this.data = deepCopy(newData);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> value) {
        return (Map<String, Object>) Json.deepCopy(value);
    }
}
