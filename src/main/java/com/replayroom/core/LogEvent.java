package com.replayroom.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One event in a replay. External events carry a logical time, a source and
 * the original sequence number; internal events are raised by actions and
 * only ever queued behind the event that produced them.
 */
public class LogEvent {
    public String id;
    public long time;
    public String source = "";
    public long seq;
    public String name;
    public Map<String, Object> args = new LinkedHashMap<>();
    public boolean internal;

    public LogEvent copy() {
        LogEvent c = new LogEvent();
        c.id = id;
        c.time = time;
        c.source = source;
        c.seq = seq;
        c.name = name;
        c.args = args == null ? new LinkedHashMap<>() : new LinkedHashMap<>(args);
        c.internal = internal;
        return c;
    }
}
