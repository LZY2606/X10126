package com.replay.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** A single event. Ordering: (time, sourcePriority, seq) — always a total order per branch. */
public final class Event implements Comparable<Event> {
    public static final String INTERNAL_SOURCE = "@internal";

    public final long time;
    public final String source;
    public final long seq;
    public final String name;
    public final JsonObject payload;

    public Event(long time, String source, long seq, String name, JsonObject payload) {
        this.time = time;
        this.source = source;
        this.seq = seq;
        this.name = name;
        this.payload = payload == null ? new JsonObject() : payload;
    }

    public boolean isInternal() { return INTERNAL_SOURCE.equals(source); }

    public int compareTo(Event o, java.util.function.ToIntFunction<String> priority) {
        if (time != o.time) return Long.compare(time, o.time);
        int p = Integer.compare(priority.applyAsInt(source), priority.applyAsInt(o.source));
        if (p != 0) return p;
        return Long.compare(seq, o.seq);
    }

    /** Ordering key string used for merge conflict identification. */
    public String orderKey(java.util.function.ToIntFunction<String> priority) {
        return time + "|" + priority.applyAsInt(source) + "|" + seq;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("time", time);
        o.addProperty("source", source);
        o.addProperty("seq", seq);
        o.addProperty("name", name);
        o.add("payload", payload);
        return o;
    }

    public static Event fromJson(JsonObject o) {
        return new Event(
                o.get("time").getAsLong(),
                o.get("source").getAsString(),
                o.get("seq").getAsLong(),
                o.get("name").getAsString(),
                o.has("payload") && o.get("payload").isJsonObject() ? o.getAsJsonObject("payload") : new JsonObject());
    }

    /** Content equality including ordering fields. */
    public boolean sameContent(Event o) {
        return time == o.time && seq == o.seq && source.equals(o.source)
                && name.equals(o.name) && Canonical.canonical(payload).equals(Canonical.canonical(o.payload));
    }

    @Override
    public int compareTo(Event o) {
        throw new UnsupportedOperationException("use compareTo(Event, priority)");
    }
}
