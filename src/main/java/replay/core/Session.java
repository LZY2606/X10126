package replay.core;

import replay.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A replay session: one locked definition + seed, several branches.
 * The session fingerprint binds definition version, initial state and random seed;
 * every branch trace hash chains every processed event's content onto it.
 */
public final class Session {
    public String id = "session";
    public String name = "replay";
    public long seed;
    public Definition definition;
    public final LinkedHashMap<String, Branch> branches = new LinkedHashMap<>();
    public String activeBranchId;
    public long eventIdCounter = 0;
    public long importSeqCounter = 0;
    public long branchCounter = 0;
    public long checkpointCounter = 0;

    public static Session create(String name, long seed, Definition def) {
        Session s = new Session();
        s.name = name == null ? "replay" : name;
        s.seed = seed;
        s.definition = def;
        Branch main = new Branch(def, "main", "main", seed, s.fingerprint());
        s.branches.put(main.id, main);
        s.activeBranchId = main.id;
        return s;
    }

    /** Fingerprint locking definition version + initial state + random seed. */
    public String fingerprint() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definition", definition.toJson());
        m.put("seed", seed);
        return Definition.sha256(Json.canonical(m));
    }

    public Branch active() {
        return branches.get(activeBranchId);
    }

    public Branch branch(String id) {
        Branch b = branches.get(id);
        if (b == null) throw new ApiException(404, "unknown branch: " + id);
        return b;
    }

    /** Replace the definition. Existing checkpoints keep their old fingerprint and will be rejected on fork. */
    public void updateDefinition(Definition def, boolean resetBranches) {
        this.definition = def;
        if (resetBranches) {
            branches.clear();
            Branch main = new Branch(def, "main", "main", seed, fingerprint());
            branches.put(main.id, main);
            activeBranchId = main.id;
        }
    }

    // ---------- events ----------

    public Event importEvent(Branch branch, Map<String, Object> m) {
        Event e = new Event();
        Object id = m.get("id");
        e.id = id == null ? "e" + (++eventIdCounter) : Json.asString(id, "event.id");
        Object time = m.get("time");
        if (time == null) throw new ApiException(400, "event is missing logical time");
        e.time = Json.asLong(time, "event.time");
        Object source = m.get("source");
        e.source = source == null ? "default" : Json.asString(source, "event.source");
        e.type = Json.asString(m.get("type"), "event.type");
        Object payload = m.get("payload");
        if (payload != null) e.payload = new LinkedHashMap<>(Json.asMap(payload, "event.payload"));
        Object seq = m.get("seq");
        e.seq = seq == null ? ++importSeqCounter : Json.asLong(seq, "event.seq");
        e.internal = false;
        branch.queue.add(e);
        return e;
    }

    // ---------- checkpoints & branches ----------

    public Checkpoint checkpoint(Branch branch, String name) {
        String id = "c" + (++checkpointCounter);
        return branch.checkpoint(id, name == null ? id : name);
    }

    public static final class CheckpointMismatch extends RuntimeException {
        public final String expected;
        public final String actual;
        public CheckpointMismatch(String expected, String actual) {
            super("checkpoint definition fingerprint mismatch");
            this.expected = expected;
            this.actual = actual;
        }
    }

    public Branch fork(String checkpointId, String name) {
        Checkpoint cp = findCheckpoint(checkpointId);
        String current = definition.fingerprint();
        if (!cp.defFingerprint.equals(current)) {
            throw new CheckpointMismatch(cp.defFingerprint, current);
        }
        String id = "b" + (++branchCounter);
        Branch b = Branch.fork(definition, cp, id, name == null ? id : name);
        branches.put(id, b);
        activeBranchId = id;
        return b;
    }

    public Checkpoint findCheckpoint(String checkpointId) {
        for (Branch b : branches.values()) {
            for (Checkpoint cp : b.checkpoints) {
                if (cp.id.equals(checkpointId)) return cp;
            }
        }
        throw new ApiException(404, "unknown checkpoint: " + checkpointId);
    }

    // ---------- merge ----------

    public static final class MergeConflict extends RuntimeException {
        public final Event a;
        public final Event b;
        public final String reason;
        public MergeConflict(Event a, Event b, String reason) {
            super(reason);
            this.a = a;
            this.b = b;
            this.reason = reason;
        }
    }

    public static final class MergeResult {
        public int mergedEvents;
        public String traceHash;
    }

    /**
     * Merge the external events applied on {@code sourceId} since the common ancestor
     * checkpoint into {@code targetId}. Never copies final state across: the target is
     * rewound to the ancestor snapshot and the union of external events is replayed.
     */
    public MergeResult merge(String sourceId, String targetId) {
        Branch src = branch(sourceId);
        Branch tgt = branch(targetId);
        if (src == tgt) throw new ApiException(400, "cannot merge a branch into itself");
        Checkpoint ancestor = commonAncestor(src, tgt);
        if (ancestor == null) {
            throw new ApiException(409, "branches share no common ancestor checkpoint");
        }
        List<Event> targetEvents = suffixAfter(tgt.externalApplied, ancestor.externalPrefix, "target");
        List<Event> sourceEvents = suffixAfter(src.externalApplied, ancestor.externalPrefix, "source");

        // detect conflicts in deterministic merged order
        List<Event> union = new ArrayList<>();
        union.addAll(targetEvents);
        union.addAll(sourceEvents);
        union.sort(Event.order(definition));

        Map<String, Event> byId = new LinkedHashMap<>();
        Map<String, Event> byPosition = new LinkedHashMap<>();
        for (Event e : union) {
            Event sameId = byId.get(e.id);
            if (sameId != null && !sameId.contentKey(definition).equals(e.contentKey(definition))) {
                throw new MergeConflict(sameId, e, "same event id with different content");
            }
            Event samePos = byPosition.get(e.positionKey(definition));
            if (samePos != null && !samePos.id.equals(e.id)) {
                throw new MergeConflict(samePos, e, "ambiguous order: distinct events share time/source-priority/seq");
            }
            byId.putIfAbsent(e.id, e);
            byPosition.putIfAbsent(e.positionKey(definition), e);
        }

        // compatible: rewind target to ancestor and replay the union
        List<Event> deduped = new ArrayList<>(byId.values());
        tgt.restore(ancestor);
        for (Event e : deduped) tgt.queue.add(e.copy());
        tgt.runToCompletion(1_000_000);
        MergeResult r = new MergeResult();
        r.mergedEvents = deduped.size();
        r.traceHash = tgt.traceHash;
        return r;
    }

    private Checkpoint commonAncestor(Branch src, Branch tgt) {
        // walk the source's ancestor chain; a checkpoint is common when the target's
        // applied external history still starts with the checkpoint's external prefix
        String cpId = src.ancestorCheckpointId;
        int guard = 0;
        while (cpId != null && guard++ < 1000) {
            Checkpoint cp = findCheckpoint(cpId);
            if (isPrefix(cp.externalPrefix, tgt.externalApplied)) return cp;
            Branch owner = branches.get(cp.branchId);
            cpId = owner == null ? null : owner.ancestorCheckpointId;
        }
        return null;
    }

    private static boolean isPrefix(List<Event> prefix, List<Event> full) {
        if (prefix.size() > full.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).id.equals(full.get(i).id)) return false;
        }
        return true;
    }

    private List<Event> suffixAfter(List<Event> applied, List<Event> prefix, String side) {
        if (!isPrefix(prefix, applied)) {
            throw new ApiException(409, side + " branch history no longer contains the ancestor checkpoint");
        }
        return new ArrayList<>(applied.subList(prefix.size(), applied.size()));
    }

    // ---------- compare ----------

    public Map<String, Object> compare(String aId, String bId) {
        Branch a = branch(aId);
        Branch b = branch(bId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", aId);
        m.put("b", bId);
        m.put("stateA", a.state);
        m.put("stateB", b.state);
        m.put("stateEqual", a.state.equals(b.state));
        m.put("varsA", Json.deepCopy(a.vars));
        m.put("varsB", Json.deepCopy(b.vars));
        List<Object> varDiff = new ArrayList<>();
        java.util.Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(a.vars.keySet());
        keys.addAll(b.vars.keySet());
        for (String k : keys) {
            Object va = a.vars.get(k);
            Object vb = b.vars.get(k);
            if (va == null ? vb != null : !va.equals(vb)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("var", k);
                d.put("a", va);
                d.put("b", vb);
                varDiff.add(d);
            }
        }
        m.put("varDiff", varDiff);
        m.put("outputsA", new ArrayList<>(a.outputs));
        m.put("outputsB", new ArrayList<>(b.outputs));
        m.put("outputsEqual", a.outputs.equals(b.outputs));
        m.put("traceHashA", a.traceHash);
        m.put("traceHashB", b.traceHash);
        return m;
    }

    // ---------- export / import ----------

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "state-machine-replay-room/1");
        m.put("id", id);
        m.put("name", name);
        m.put("seed", seed);
        m.put("definition", definition.toJson());
        m.put("activeBranchId", activeBranchId);
        m.put("eventIdCounter", eventIdCounter);
        m.put("importSeqCounter", importSeqCounter);
        m.put("branchCounter", branchCounter);
        m.put("checkpointCounter", checkpointCounter);
        List<Object> bs = new ArrayList<>();
        for (Branch b : branches.values()) bs.add(b.toJson());
        m.put("branches", bs);
        return m;
    }

    /**
     * Rebuild a session from an export. Every branch's trace hash is recomputed by
     * replaying the hash chain over the stored trace entries; a mismatch rejects the import.
     */
    public static Session fromJson(Map<String, Object> m) {
        Session s = new Session();
        s.id = Json.asString(m.getOrDefault("id", "session"), "session.id");
        s.name = Json.asString(m.getOrDefault("name", "replay"), "session.name");
        s.seed = Json.asLong(m.get("seed"), "session.seed");
        s.definition = Definition.fromJson(Json.asMap(m.get("definition"), "session.definition"));
        s.eventIdCounter = Json.asLong(m.getOrDefault("eventIdCounter", 0L), "session.eventIdCounter");
        s.importSeqCounter = Json.asLong(m.getOrDefault("importSeqCounter", 0L), "session.importSeqCounter");
        s.branchCounter = Json.asLong(m.getOrDefault("branchCounter", 0L), "session.branchCounter");
        s.checkpointCounter = Json.asLong(m.getOrDefault("checkpointCounter", 0L), "session.checkpointCounter");
        String baseHash = s.fingerprint();
        for (Object o : Json.asList(m.get("branches"), "session.branches")) {
            Branch b = Branch.fromJson(s.definition, Json.asMap(o, "branch"));
            // verify the recorded trace hash by replaying the hash chain
            String hash = baseHash;
            for (Map<String, Object> entry : b.trace) {
                hash = Definition.sha256(hash + "|" + Json.canonical(entry));
            }
            if (!hash.equals(b.traceHash)) {
                throw new ApiException(422, "trace hash mismatch on branch " + b.id + ": import rejected");
            }
            s.branches.put(b.id, b);
        }
        s.activeBranchId = Json.asString(m.get("activeBranchId"), "session.activeBranchId");
        if (!s.branches.containsKey(s.activeBranchId)) {
            throw new ApiException(422, "export references unknown active branch");
        }
        return s;
    }

    public static final class ApiException extends RuntimeException {
        public final int status;
        public ApiException(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }
}
