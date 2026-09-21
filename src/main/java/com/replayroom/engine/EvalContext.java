package com.replayroom.engine;

import java.util.Map;

/**
 * Immutable-at-evaluation view used by conditions and actions. Conditions
 * read the snapshot captured before the event is processed; the engine
 * swaps in a writable state map while running the action transaction.
 */
public final class EvalContext {

    /** Sentinel distinct from a legitimately null root value. */
    public static final Object MISSING = new Object();

    private final Map<String, Object> stateData;
    private final Map<String, Object> eventData;
    private final long eventTime;
    private final long eventSeq;
    private final String eventSource;
    private final String eventType;
    private final String eventId;
    private final Rng rng;

    public EvalContext(Map<String, Object> stateData,
                       Map<String, Object> eventData,
                       long eventTime,
                       long eventSeq,
                       String eventSource,
                       String eventType,
                       String eventId,
                       Rng rng) {
        this.stateData = stateData;
        this.eventData = eventData;
        this.eventTime = eventTime;
        this.eventSeq = eventSeq;
        this.eventSource = eventSource;
        this.eventType = eventType;
        this.eventId = eventId;
        this.rng = rng;
    }

    public Object lookupRoot(String name) {
        return switch (name) {
            case "state" -> stateData;
            case "data" -> eventData;
            case "event" -> eventData;
            case "time" -> eventTime;
            case "seq" -> eventSeq;
            case "source" -> eventSource;
            case "type" -> eventType;
            case "eventId" -> eventId;
            default -> MISSING;
        };
    }

    public Rng rng() {
        return rng;
    }
}
