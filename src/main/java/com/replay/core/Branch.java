package com.replay.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/** One replay branch: event log, pending queue, snapshot, RNG state and trace. */
public final class Branch {
    public String id;
    public String name;
    public String parentBranchId;
    public String parentCheckpointId;
    public int ancestorEventCount;

    public final List<Event> externalEvents = new ArrayList<>();
    public PriorityQueue<Event> pending;
    public Snapshot snapshot;
    public final Rng rng;
    public long eventSeq;
    public final List<JsonObject> trace = new ArrayList<>();
    public String traceHash = "0";

    private Definition definition;

    public Branch(String id, String name, Definition definition, Snapshot initial, long seed) {
        this.id = id;
        this.name = name;
        this.definition = definition;
        this.snapshot = initial;
        this.rng = new Rng(seed);
        this.pending = newQueue(definition);
    }

    private Branch(Definition definition) {
        this.definition = definition;
        this.rng = new Rng(0);
        this.pending = newQueue(definition);
    }

    private static PriorityQueue<Event> newQueue(Definition d) {
        return new PriorityQueue<>((a, b) -> a.compareTo(b, d::priorityOf));
    }

    public Definition definition() { return definition; }

    public void setDefinition(Definition d) {
        this.definition = d;
        PriorityQueue<Event> q = newQueue(d);
        q.addAll(pending);
        pending = q;
    }

    public int stepIndex() { return trace.size(); }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        if (parentBranchId != null) o.addProperty("parentBranchId", parentBranchId);
        if (parentCheckpointId != null) o.addProperty("parentCheckpointId", parentCheckpointId);
        o.addProperty("ancestorEventCount", ancestorEventCount);
        o.addProperty("eventSeq", eventSeq);
        o.addProperty("rngState", rng.getState());
        o.addProperty("traceHash", traceHash);
        o.add("snapshot", snapshot.toJson());
        JsonArray ev = new JsonArray();
        for (Event e : externalEvents) ev.add(e.toJson());
        o.add("externalEvents", ev);
        JsonArray pe = new JsonArray();
        List<Event> sorted = new ArrayList<>(pending);
        sorted.sort((a, b) -> a.compareTo(b, definition::priorityOf));
        for (Event e : sorted) pe.add(e.toJson());
        o.add("pending", pe);
        JsonArray tr = new JsonArray();
        for (JsonObject t : trace) tr.add(t);
        o.add("trace", tr);
        return o;
    }

    public static Branch fromJson(JsonObject o, Definition definition) {
        Branch b = new Branch(definition);
        b.id = o.get("id").getAsString();
        b.name = o.get("name").getAsString();
        b.parentBranchId = o.has("parentBranchId") ? o.get("parentBranchId").getAsString() : null;
        b.parentCheckpointId = o.has("parentCheckpointId") ? o.get("parentCheckpointId").getAsString() : null;
        b.ancestorEventCount = o.has("ancestorEventCount") ? o.get("ancestorEventCount").getAsInt() : 0;
        b.eventSeq = o.get("eventSeq").getAsLong();
        b.rng.setState(o.get("rngState").getAsLong());
        b.traceHash = o.get("traceHash").getAsString();
        b.snapshot = Snapshot.fromJson(o.getAsJsonObject("snapshot"));
        for (JsonElement e : o.getAsJsonArray("externalEvents")) b.externalEvents.add(Event.fromJson(e.getAsJsonObject()));
        for (JsonElement e : o.getAsJsonArray("pending")) b.pending.add(Event.fromJson(e.getAsJsonObject()));
        for (JsonElement e : o.getAsJsonArray("trace")) b.trace.add(e.getAsJsonObject());
        return b;
    }
}
