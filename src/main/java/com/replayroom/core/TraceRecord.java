package com.replayroom.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One replay step. before/after are snapshot maps ({state, variables, rngState}). */
public class TraceRecord {
    public static final String APPLIED = "APPLIED";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";
    public static final String UNMATCHED = "UNMATCHED";

    public long step;
    public String eventId;
    public String eventName;
    public boolean internal;
    public long eventTime;
    public String eventSource;
    public String status;
    public Map<String, Object> before;
    public Map<String, Object> after;
    public List<Object> outputs = new ArrayList<>();
    public List<String> raisedEvents = new ArrayList<>();
    public String error;
}
