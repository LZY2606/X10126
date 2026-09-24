package com.replayroom.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned state machine definition. Together with the initial state,
 * imported event log and random seed it is locked into the session
 * fingerprint; the definition alone yields the definition fingerprint used
 * to validate checkpoints.
 */
public class MachineDefinition {
    public String name = "machine";
    public int version = 1;
    public long seed = 1L;
    public String initialState = "idle";
    public Map<String, Object> initialVariables = new LinkedHashMap<>();
    public List<String> states = new ArrayList<>();
    /** Sources ordered by priority: earlier means processed first at equal logical time. */
    public List<String> sourcePriority = new ArrayList<>();
    public List<EventDef> events = new ArrayList<>();

    public EventDef findEvent(String eventName) {
        for (EventDef def : events) {
            if (def.name.equals(eventName)) {
                return def;
            }
        }
        return null;
    }

    public int priorityOf(String source) {
        int idx = sourcePriority.indexOf(source);
        return idx >= 0 ? idx : sourcePriority.size();
    }

    public static class EventDef {
        public String name;
        /** Expression evaluated against the snapshot taken before the event is processed. */
        public String condition = "true";
        public List<Action> actions = new ArrayList<>();
    }

    public static class Action {
        /** set | transition | emit | raise | fail */
        public String type;
        /** set: target variable name. */
        public String var;
        /** set / emit: expression evaluated at apply time. */
        public String expr;
        /** transition: target state name. */
        public String to;
        /** emit: literal output value (used when expr is absent). */
        public Object value;
        /** raise: internal event name. */
        public String event;
        /** raise: literal arguments for the internal event. */
        public Map<String, Object> args;
        /** fail: failure message. */
        public String message;
    }
}
