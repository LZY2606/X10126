package com.replayroom.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Restorable point in a branch. Carries the definition fingerprint it was
 * created under; forking from it is rejected when the session definition has
 * since changed.
 */
public class Checkpoint {
    public String id;
    public String name;
    public String branchId;
    public long step;
    public String definitionFingerprint;
    public StateSnapshot snapshot;
    public List<LogEvent> internalQueue = new ArrayList<>();
    public List<LogEvent> pendingExternal = new ArrayList<>();
    public List<Object> outputs = new ArrayList<>();
    public String traceHash;
}
