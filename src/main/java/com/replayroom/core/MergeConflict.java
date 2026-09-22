package com.replayroom.core;

import java.util.List;

/** Merge rejected. Carries the first group of conflicting events (same ordering key, different ids). */
public final class MergeConflict extends RuntimeException {
    public final List<Event> conflictingEvents;
    public final String orderKey;

    public MergeConflict(String message) {
        this(message, null, List.of());
    }

    public MergeConflict(String message, String orderKey, List<Event> conflictingEvents) {
        super(message);
        this.orderKey = orderKey;
        this.conflictingEvents = conflictingEvents;
    }
}
