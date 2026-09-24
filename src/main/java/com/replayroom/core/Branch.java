package com.replayroom.core;

import java.util.ArrayList;
import java.util.List;

/** A replay branch: full trace from step 0 plus the not-yet-consumed events. */
public class Branch {
    public String id;
    public String name;
    public String parentBranchId;
    public long forkStep;
    public List<TraceRecord> trace = new ArrayList<>();
    public StateSnapshot snapshot;
    public List<LogEvent> internalQueue = new ArrayList<>();
    public List<LogEvent> pendingExternal = new ArrayList<>();
    public List<Object> outputs = new ArrayList<>();

    public boolean exhausted() {
        return internalQueue.isEmpty() && pendingExternal.isEmpty();
    }
}
