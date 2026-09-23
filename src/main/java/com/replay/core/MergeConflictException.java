package com.replay.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public class MergeConflictException extends RuntimeException {
    public final List<Conflict> conflicts;

    public MergeConflictException(List<Conflict> conflicts) {
        super("merge rejected: " + conflicts.size() + " conflict(s), first at " + conflicts.get(0).orderKey);
        this.conflicts = conflicts;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("error", getMessage());
        JsonArray arr = new JsonArray();
        for (Conflict c : conflicts) arr.add(c.toJson());
        o.add("conflicts", arr);
        return o;
    }

    public static final class Conflict {
        public final String orderKey;
        public final Event sourceEvent;
        public final Event targetEvent;

        public Conflict(String orderKey, Event sourceEvent, Event targetEvent) {
            this.orderKey = orderKey;
            this.sourceEvent = sourceEvent;
            this.targetEvent = targetEvent;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("orderKey", orderKey);
            o.add("sourceEvent", sourceEvent.toJson());
            o.add("targetEvent", targetEvent.toJson());
            return o;
        }
    }
}
