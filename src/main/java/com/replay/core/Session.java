package com.replay.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.replay.expr.Expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A replay session: locks definition fingerprint, initial state, events and seed together.
 * All replay is driven by the virtual logical clock carried on events; no wall-clock time is used.
 */
public final class Session {
    public String id;
    public String name;
    public long seed;
    public Definition definition;
    public Snapshot initialSnapshot;
    public final Map<String, Branch> branches = new LinkedHashMap<>();
    public final Map<String, JsonObject> checkpoints = new LinkedHashMap<>();
    public String activeBranchId;

    public Session(String name, JsonObject definitionJson, long seed, JsonObject initialVars) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.name = name == null ? "session" : name;
        this.seed = seed;
        this.definition = new Definition(definitionJson);
        TreeMap<String, JsonElement> vars = new TreeMap<>();
        if (initialVars != null) {
            for (Map.Entry<String, JsonElement> e : initialVars.entrySet()) vars.put(e.getKey(), e.getValue());
        }
        this.initialSnapshot = new Snapshot(definition.initialState, vars);
        Branch main = new Branch("main", "main", definition, initialSnapshot.copy(), seed);
        branches.put(main.id, main);
        this.activeBranchId = main.id;
    }

    private Session() {}

    // ---------- fingerprints ----------

    public String definitionFingerprint() { return definition.fingerprint(); }

    /** Locks definition + seed + initial state + external event content of the main branch. */
    public String sessionFingerprint() {
        JsonObject o = new JsonObject();
        o.add("definition", definition.raw);
        o.addProperty("seed", seed);
        o.add("initialState", initialSnapshot.toJson());
        JsonArray ev = new JsonArray();
        for (Event e : branches.get("main").externalEvents) ev.add(e.toJson());
        o.add("events", ev);
        return Canonical.fingerprint(o);
    }

    // ---------- definition ----------

    public void updateDefinition(JsonObject defJson) {
        this.definition = new Definition(defJson);
        for (Branch b : branches.values()) b.setDefinition(definition);
    }

    // ---------- branches ----------

    public Branch branch(String id) {
        Branch b = branches.get(id == null ? activeBranchId : id);
        if (b == null) throw new IllegalArgumentException("unknown branch: " + id);
        return b;
    }

    // ---------- events ----------

    public List<Event> importEvents(String branchId, JsonArray eventsJson) {
        Branch b = branch(branchId);
        List<Event> imported = new ArrayList<>();
        for (JsonElement el : eventsJson) {
            JsonObject o = el.getAsJsonObject();
            long seq = o.has("seq") ? o.get("seq").getAsLong() : b.eventSeq;
            Event e = new Event(
                    o.get("time").getAsLong(),
                    o.has("source") ? o.get("source").getAsString() : "external",
                    seq,
                    o.get("name").getAsString(),
                    o.has("payload") && o.get("payload").isJsonObject() ? o.getAsJsonObject("payload") : new JsonObject());
            if (seq >= b.eventSeq) b.eventSeq = seq + 1;
            b.externalEvents.add(e);
            b.pending.add(e);
            imported.add(e);
        }
        b.externalEvents.sort((x, y) -> x.compareTo(y, definition::priorityOf));
        return imported;
    }

    // ---------- stepping ----------

    /** Process exactly one event from the pending queue. Returns the trace entry, or null if idle. */
    public JsonObject step(String branchId) {
        Branch b = branch(branchId);
        Event event = b.pending.poll();
        if (event == null) return null;

        Snapshot before = b.snapshot.copy();
        JsonObject entry = new JsonObject();
        entry.addProperty("index", b.trace.size());
        entry.addProperty("branchId", b.id);
        entry.add("event", event.toJson());
        entry.add("before", before.toJson());

        JsonArray outputs = new JsonArray();
        JsonArray emitted = new JsonArray();
        try {
            Definition.Transition t = findTransition(event, before);
            if (t == null) {
                entry.addProperty("status", "no-transition");
            } else {
                Snapshot working = before.copy();
                if (t.to != null) working.state = t.to;
                List<Event> internal = new ArrayList<>();
                for (JsonObject action : t.actions) {
                    executeAction(b, event, working, action, outputs, internal);
                }
                for (Event ie : internal) {
                    b.pending.add(ie);
                    emitted.add(ie.toJson());
                }
                b.snapshot = working;
                entry.addProperty("status", "ok");
                entry.add("transition", t.summary());
            }
        } catch (ActionFailure | IllegalArgumentException | IllegalStateException e) {
            // Rollback: snapshot untouched (worked on a copy), derived internal events discarded.
            entry.addProperty("status", "failed");
            entry.addProperty("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        entry.add("outputs", outputs);
        entry.add("emitted", emitted);
        entry.add("after", b.snapshot.toJson());
        entry.addProperty("pendingAfter", b.pending.size());

        JsonObject hashMaterial = entry.deepCopy();
        String h = Canonical.sha256(b.traceHash + "|" + Canonical.canonical(hashMaterial));
        entry.addProperty("hash", h);
        b.traceHash = h;
        b.trace.add(entry);
        return entry;
    }

    /** Process events until the pending queue is empty. */
    public int run(String branchId) {
        int n = 0;
        while (step(branchId) != null) n++;
        return n;
    }

    private Definition.Transition findTransition(Event event, Snapshot before) {
        for (Definition.Transition t : definition.transitions) {
            if (!t.event.equals(event.name)) continue;
            if (!"*".equals(t.from) && !t.from.equals(before.state)) continue;
            if (t.condition != null && !t.condition.isBlank()) {
                if (!Expr.evalBool(t.condition, env(before))) continue;
            }
            return t;
        }
        return null;
    }

    private Expr.Env env(Snapshot snap) {
        return name -> {
            if ("state".equals(name)) return new com.google.gson.JsonPrimitive(snap.state);
            return snap.vars.get(name);
        };
    }

    private void executeAction(Branch b, Event event, Snapshot working, JsonObject action,
                               JsonArray outputs, List<Event> internal) {
        String type = action.has("type") ? action.get("type").getAsString() : "";
        switch (type) {
            case "set": {
                String var = action.get("var").getAsString();
                JsonElement value;
                if (action.has("expr")) value = Expr.eval(action.get("expr").getAsString(), env(working));
                else value = action.has("value") ? action.get("value").deepCopy() : com.google.gson.JsonNull.INSTANCE;
                working.vars.put(var, value);
                break;
            }
            case "emit": {
                long delay = action.has("delay") ? action.get("delay").getAsLong() : 0;
                JsonObject payload = action.has("payload") && action.get("payload").isJsonObject()
                        ? action.getAsJsonObject("payload").deepCopy() : new JsonObject();
                internal.add(new Event(event.time + delay, Event.INTERNAL_SOURCE, b.eventSeq++,
                        action.get("event").getAsString(), payload));
                break;
            }
            case "output": {
                if (action.has("expr")) outputs.add(Expr.asString(Expr.eval(action.get("expr").getAsString(), env(working))));
                else outputs.add(action.has("text") ? action.get("text").getAsString() : "");
                break;
            }
            case "random": {
                long min = action.has("min") ? action.get("min").getAsLong() : 0;
                long max = action.has("max") ? action.get("max").getAsLong() : 100;
                working.vars.put(action.get("var").getAsString(), new com.google.gson.JsonPrimitive(b.rng.nextLong(min, max)));
                break;
            }
            case "fail":
                throw new ActionFailure(action.has("message") ? action.get("message").getAsString() : "action failed");
            default:
                throw new ActionFailure("unknown action type: '" + type + "'");
        }
    }

    // ---------- checkpoints ----------

    public JsonObject createCheckpoint(String branchId, String name) {
        Branch b = branch(branchId);
        JsonObject cp = new JsonObject();
        cp.addProperty("id", "cp-" + UUID.randomUUID().toString().substring(0, 8));
        cp.addProperty("name", name == null ? "checkpoint" : name);
        cp.addProperty("branchId", b.id);
        cp.addProperty("stepIndex", b.stepIndex());
        cp.addProperty("definitionFingerprint", definitionFingerprint());
        cp.add("state", b.toJson());
        checkpoints.put(cp.get("id").getAsString(), cp);
        return cp;
    }

    public void restoreCheckpoint(String checkpointId, String targetBranchId) {
        JsonObject cp = checkpoints.get(checkpointId);
        if (cp == null) throw new IllegalArgumentException("unknown checkpoint: " + checkpointId);
        String cpFp = cp.get("definitionFingerprint").getAsString();
        if (!cpFp.equals(definitionFingerprint())) {
            throw new CheckpointVersionException(
                    "checkpoint " + checkpointId + " was created under definition " + cpFp
                            + " but current definition is " + definitionFingerprint());
        }
        Branch target = branch(targetBranchId == null ? cp.get("branchId").getAsString() : targetBranchId);
        Branch restored = Branch.fromJson(cp.getAsJsonObject("state"), definition);
        restored.id = target.id;
        restored.name = target.name;
        restored.parentBranchId = target.parentBranchId;
        restored.parentCheckpointId = target.parentCheckpointId;
        restored.ancestorEventCount = target.ancestorEventCount;
        branches.put(target.id, restored);
    }

    // ---------- fork & merge ----------

    public Branch fork(String checkpointId, String name) {
        JsonObject cp = checkpoints.get(checkpointId);
        if (cp == null) throw new IllegalArgumentException("unknown checkpoint: " + checkpointId);
        String cpFp = cp.get("definitionFingerprint").getAsString();
        if (!cpFp.equals(definitionFingerprint())) {
            throw new CheckpointVersionException(
                    "checkpoint " + checkpointId + " was created under definition " + cpFp
                            + " but current definition is " + definitionFingerprint());
        }
        Branch b = Branch.fromJson(cp.getAsJsonObject("state"), definition);
        b.id = "br-" + UUID.randomUUID().toString().substring(0, 8);
        b.name = name == null ? b.id : name;
        b.parentBranchId = cp.get("branchId").getAsString();
        b.parentCheckpointId = cp.get("id").getAsString();
        b.ancestorEventCount = b.externalEvents.size();
        branches.put(b.id, b);
        return b;
    }

    /**
     * Merge source branch into target branch. Never overwrites state: the merge is only
     * allowed when both branches' post-ancestor external event sets are compatible
     * (no divergent event at the same ordering key). On success the target is rewound
     * to the common ancestor and replayed with the union of both event sets.
     */
    public JsonObject merge(String sourceId, String targetId) {
        Branch source = branch(sourceId);
        Branch target = branch(targetId);
        if (source.id.equals(target.id)) throw new IllegalArgumentException("cannot merge a branch into itself");

        boolean childToParent = source.parentBranchId != null && source.parentBranchId.equals(target.id);
        boolean siblings = source.parentCheckpointId != null && source.parentCheckpointId.equals(target.parentCheckpointId);
        if (!childToParent && !siblings) {
            throw new IllegalArgumentException("branches share no common ancestor checkpoint");
        }
        int ancestorCount = source.ancestorEventCount;
        if (target.externalEvents.size() < ancestorCount) {
            throw new IllegalArgumentException("target branch no longer contains the ancestor event prefix");
        }
        for (int i = 0; i < ancestorCount; i++) {
            if (!source.externalEvents.get(i).sameContent(target.externalEvents.get(i))) {
                throw new IllegalArgumentException("branches diverge inside the ancestor event prefix");
            }
        }

        List<Event> sourcePost = source.externalEvents.subList(ancestorCount, source.externalEvents.size());
        List<Event> targetPost = target.externalEvents.subList(ancestorCount, target.externalEvents.size());

        Map<String, Event> targetByKey = new TreeMap<>();
        for (Event e : targetPost) targetByKey.put(e.orderKey(definition::priorityOf), e);
        List<MergeConflictException.Conflict> conflicts = new ArrayList<>();
        for (Event e : sourcePost) {
            Event t = targetByKey.get(e.orderKey(definition::priorityOf));
            if (t != null && !t.sameContent(e)) {
                conflicts.add(new MergeConflictException.Conflict(e.orderKey(definition::priorityOf), e, t));
            }
        }
        if (!conflicts.isEmpty()) throw new MergeConflictException(conflicts);

        // Union of post-ancestor events, deduplicated by content.
        List<Event> merged = new ArrayList<>();
        for (Event e : targetPost) merged.add(e);
        outer:
        for (Event e : sourcePost) {
            for (Event m : merged) if (m.sameContent(e)) continue outer;
            merged.add(e);
        }
        merged.sort((a, b) -> a.compareTo(b, definition::priorityOf));

        // Rewind target to the common ancestor checkpoint, then replay the union.
        restoreCheckpoint(source.parentCheckpointId, target.id);
        Branch rewound = branch(target.id);
        importEvents(rewound.id, toArray(merged));
        int steps = run(rewound.id);

        JsonObject result = new JsonObject();
        result.addProperty("status", "merged");
        result.addProperty("targetBranch", target.id);
        result.addProperty("mergedEvents", merged.size());
        result.addProperty("replayedSteps", steps);
        result.addProperty("traceHash", rewound.traceHash);
        return result;
    }

    private static JsonArray toArray(List<Event> events) {
        JsonArray a = new JsonArray();
        for (Event e : events) a.add(e.toJson());
        return a;
    }

    // ---------- serialization ----------

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("seed", seed);
        o.add("definition", definition.raw);
        o.add("initialState", initialSnapshot.toJson());
        o.addProperty("activeBranchId", activeBranchId);
        JsonArray bs = new JsonArray();
        for (Branch b : branches.values()) bs.add(b.toJson());
        o.add("branches", bs);
        JsonArray cs = new JsonArray();
        for (JsonObject c : checkpoints.values()) cs.add(c);
        o.add("checkpoints", cs);
        o.addProperty("definitionFingerprint", definitionFingerprint());
        o.addProperty("sessionFingerprint", sessionFingerprint());
        return o;
    }

    public static Session fromJson(JsonObject o) {
        Session s = new Session();
        s.id = o.get("id").getAsString();
        s.name = o.get("name").getAsString();
        s.seed = o.get("seed").getAsLong();
        s.definition = new Definition(o.getAsJsonObject("definition"));
        s.initialSnapshot = Snapshot.fromJson(o.getAsJsonObject("initialState"));
        s.activeBranchId = o.get("activeBranchId").getAsString();
        for (JsonElement e : o.getAsJsonArray("branches")) {
            Branch b = Branch.fromJson(e.getAsJsonObject(), s.definition);
            s.branches.put(b.id, b);
        }
        for (JsonElement e : o.getAsJsonArray("checkpoints")) {
            JsonObject c = e.getAsJsonObject();
            s.checkpoints.put(c.get("id").getAsString(), c);
        }
        return s;
    }

    /** Export document: everything needed to reproduce identical trajectory hashes. */
    public JsonObject export() {
        JsonObject o = toJson();
        o.addProperty("format", "state-machine-replay-room/v1");
        JsonObject hashes = new JsonObject();
        for (Branch b : branches.values()) hashes.addProperty(b.id, b.traceHash);
        o.add("trajectoryHashes", hashes);
        return o;
    }

    public static Session importFrom(JsonObject exportDoc) {
        Session s = fromJson(exportDoc);
        // Verify trajectory hashes survive the round trip.
        if (exportDoc.has("trajectoryHashes")) {
            JsonObject hashes = exportDoc.getAsJsonObject("trajectoryHashes");
            for (Map.Entry<String, JsonElement> e : hashes.entrySet()) {
                Branch b = s.branches.get(e.getKey());
                if (b == null) throw new IllegalArgumentException("export references unknown branch " + e.getKey());
                if (!b.traceHash.equals(e.getValue().getAsString())) {
                    throw new IllegalArgumentException("trajectory hash mismatch on branch " + e.getKey());
                }
            }
        }
        return s;
    }

    public static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
