package replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import replay.Model.Branch;
import replay.Model.Checkpoint;
import replay.Model.Definition;
import replay.Model.Event;
import replay.Model.TraceEntry;

/** A replay session: locks definition + seed + initial state + event content together. */
public final class Session {
    public String id;
    public String name;
    public Definition definition;
    public long seed;
    public Map<String, Integer> sourcePriorities = new LinkedHashMap<>();
    public final Map<String, Branch> branches = new LinkedHashMap<>();
    public String currentBranchId;
    private long counter;

    public Session(String id, String name, Definition definition, long seed,
                   Map<String, Integer> sourcePriorities) {
        this.id = id;
        this.name = name;
        this.definition = definition;
        this.seed = seed;
        if (sourcePriorities != null) this.sourcePriorities.putAll(sourcePriorities);
        Branch main = new Branch("main", "main");
        branches.put(main.id, main);
        currentBranchId = main.id;
    }

    public Branch currentBranch() { return branches.get(currentBranchId); }

    private String nextId(String prefix) { return prefix + (++counter); }

    /** Stable fingerprint: definition version + initial state + event content + seed. */
    public String fingerprint() {
        Map<String, Object> m = Json.map();
        m.put("definition", definition.raw);
        m.put("seed", seed);
        m.put("initialState", definition.initialState);
        m.put("sourcePriorities", new TreeMap<>(sourcePriorities));
        List<Object> evs = Json.list();
        List<Event> sorted = new ArrayList<>(currentBranch().events);
        sorted.sort(Engine.ordering(sourcePriorities));
        for (Event e : sorted) evs.add(e.toJson());
        m.put("events", evs);
        return Model.sha256(Json.canonical(m));
    }

    public Engine.Outcome replay(Branch branch, int upToSteps) {
        return Engine.replay(definition, seed, branch.events, upToSteps, sourcePriorities);
    }

    public Engine.Outcome replayToCursor(Branch branch) {
        return replay(branch, branch.cursor);
    }

    public String traceHash(Branch branch) {
        Engine.Outcome o = replayToCursor(branch);
        List<Object> entries = Json.list();
        for (TraceEntry t : o.trace) entries.add(t.toJson());
        return Model.sha256(Json.canonical(entries));
    }

    public void addEvents(List<Event> events) {
        Branch b = currentBranch();
        for (Event e : events) {
            if (b.events.stream().anyMatch(x -> x.id.equals(e.id))) {
                throw new ApiException(409, "duplicate event id: " + e.id);
            }
        }
        b.events.addAll(events);
    }

    public TraceEntry step() {
        Branch b = currentBranch();
        Engine.Outcome o = replay(b, b.cursor + 1);
        if (o.trace.size() <= b.cursor) {
            throw new ApiException(409, "no more events to replay");
        }
        b.cursor++;
        return o.trace.get(o.trace.size() - 1);
    }

    public int runToEnd() {
        Branch b = currentBranch();
        int total = (int) Engine.replay(definition, seed, b.events, -1, sourcePriorities)
                .trace.size();
        int stepped = total - b.cursor;
        b.cursor = total;
        return stepped;
    }

    public void resetCursor() {
        currentBranch().cursor = 0;
    }

    public Checkpoint checkpoint(String name) {
        Branch b = currentBranch();
        Engine.Outcome o = replayToCursor(b);
        Map<String, Object> stateSnap = Json.map();
        stateSnap.put("state", o.state);
        stateSnap.put("vars", o.vars);
        Checkpoint cp = new Checkpoint(nextId("cp"), name, b.cursor,
                definition.fingerprint, Model.sha256(Json.canonical(stateSnap)));
        b.checkpoints.add(cp);
        return cp;
    }

    /** Restores a checkpoint, refusing ones taken under a different definition version. */
    public void restore(String checkpointId) {
        Branch b = currentBranch();
        Checkpoint cp = b.checkpoints.stream()
                .filter(c -> c.id.equals(checkpointId)).findFirst()
                .orElseThrow(() -> new ApiException(404, "checkpoint not found: " + checkpointId));
        if (!cp.defFingerprint.equals(definition.fingerprint)) {
            throw new ApiException(409, "checkpoint-definition-mismatch: checkpoint " + cp.id
                    + " was taken under definition " + cp.defFingerprint.substring(0, 12)
                    + " but current definition is " + definition.fingerprint.substring(0, 12));
        }
        b.cursor = cp.step;
    }

    public Branch fork(String name, String checkpointId) {
        Branch base = currentBranch();
        int atStep = base.cursor;
        if (checkpointId != null) {
            Checkpoint cp = base.checkpoints.stream()
                    .filter(c -> c.id.equals(checkpointId)).findFirst()
                    .orElseThrow(() -> new ApiException(404, "checkpoint not found: " + checkpointId));
            if (!cp.defFingerprint.equals(definition.fingerprint)) {
                throw new ApiException(409, "checkpoint-definition-mismatch: " + cp.id);
            }
            atStep = cp.step;
        }
        Branch fork = new Branch(nextId("br"), name);
        fork.events.addAll(base.events);
        fork.cursor = atStep;
        fork.forkedFrom = base.id;
        fork.forkStep = atStep;
        branches.put(fork.id, fork);
        currentBranchId = fork.id;
        return fork;
    }

    public void switchBranch(String branchId) {
        if (!branches.containsKey(branchId)) throw new ApiException(404, "branch not found: " + branchId);
        currentBranchId = branchId;
    }

    public void updateDefinition(Map<String, Object> raw) {
        this.definition = new Definition(raw);
    }

    public static final class MergeConflict extends RuntimeException {
        public final Map<String, Object> detail;
        public MergeConflict(String message, Map<String, Object> detail) {
            super(message);
            this.detail = detail;
        }
    }

    /**
     * Merges {@code otherId} into the current branch. Allowed only when the external
     * event sets appended after the common ancestor are compatible and their order is
     * unambiguous. Rejects with the first conflicting pair of events otherwise.
     */
    public Branch merge(String otherId) {
        Branch into = currentBranch();
        Branch other = branches.get(otherId);
        if (other == null) throw new ApiException(404, "branch not found: " + otherId);
        if (into == other) throw new ApiException(409, "cannot merge a branch into itself");

        int prefix = commonPrefix(into.events, other.events);
        List<Event> tailA = into.events.subList(prefix, into.events.size());
        List<Event> tailB = other.events.subList(prefix, other.events.size());

        Map<String, Event> byIdA = new HashMap<>();
        for (Event e : tailA) byIdA.put(e.id, e);
        Map<String, Event> byIdB = new HashMap<>();
        for (Event e : tailB) byIdB.put(e.id, e);

        List<Event> union = new ArrayList<>();
        union.addAll(tailA);
        for (Event e : tailB) if (!byIdA.containsKey(e.id)) union.add(e);
        union.sort(Engine.ordering(sourcePriorities));

        // First conflict in deterministic (sorted) order wins.
        for (int i = 0; i < union.size(); i++) {
            Event e = union.get(i);
            Event ea = byIdA.get(e.id);
            Event eb = byIdB.get(e.id);
            if (ea != null && eb != null && !ea.contentHash().equals(eb.contentHash())) {
                throw conflict("modified-event", ea, eb,
                        "event " + e.id + " has different content on the two branches");
            }
            for (int j = i + 1; j < union.size(); j++) {
                Event f = union.get(j);
                if (f.time != e.time) break;
                if (e.id.equals(f.id)) continue;
                int pa = sourcePriorities.getOrDefault(e.source, 0);
                int pb = sourcePriorities.getOrDefault(f.source, 0);
                if (pa == pb && e.seq == f.seq) {
                    throw conflict("ambiguous-order", e, f,
                            "events " + e.id + " and " + f.id
                                    + " share the same ordering key (time=" + e.time
                                    + ", priority=" + pa + ", seq=" + e.seq + ")");
                }
            }
        }

        Branch merged = new Branch(nextId("br"), into.name + "+" + other.name);
        merged.events.addAll(into.events.subList(0, prefix));
        merged.events.addAll(union);
        merged.forkedFrom = into.id;
        merged.forkStep = prefix;
        branches.put(merged.id, merged);
        currentBranchId = merged.id;
        merged.cursor = (int) Engine.replay(definition, seed, merged.events, -1, sourcePriorities)
                .trace.size();
        return merged;
    }

    private MergeConflict conflict(String kind, Event a, Event b, String message) {
        Map<String, Object> detail = Json.map();
        detail.put("kind", kind);
        detail.put("message", message);
        List<Object> pair = Json.list();
        pair.add(a.toJson());
        pair.add(b.toJson());
        detail.put("firstConflict", pair);
        return new MergeConflict(message, detail);
    }

    private static int commonPrefix(List<Event> a, List<Event> b) {
        int n = Math.min(a.size(), b.size());
        int i = 0;
        while (i < n && a.get(i).id.equals(b.get(i).id)) i++;
        return i;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = Json.map();
        m.put("id", id);
        m.put("name", name);
        m.put("definition", definition.raw);
        m.put("seed", seed);
        m.put("sourcePriorities", new TreeMap<>(sourcePriorities));
        Map<String, Object> bs = Json.map();
        for (Branch b : branches.values()) bs.put(b.id, b.toJson());
        m.put("branches", bs);
        m.put("currentBranchId", currentBranchId);
        m.put("counter", counter);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Session fromJson(Map<String, Object> m) {
        Map<String, Integer> prio = new LinkedHashMap<>();
        Object sp = m.get("sourcePriorities");
        if (sp != null) {
            for (Map.Entry<String, Object> e : Json.asMap(sp).entrySet()) {
                prio.put(e.getKey(), (int) Json.asLong(e.getValue()));
            }
        }
        Session s = new Session(
                Json.asString(m.get("id")),
                Json.asString(m.get("name")),
                new Definition(Json.asMap(m.get("definition"))),
                Json.asLong(m.getOrDefault("seed", 0L)),
                prio);
        s.branches.clear();
        for (Map.Entry<String, Object> e : Json.asMap(m.get("branches")).entrySet()) {
            Branch b = Branch.fromJson(Json.asMap(e.getValue()));
            s.branches.put(b.id, b);
        }
        s.currentBranchId = Json.asString(m.get("currentBranchId"));
        s.counter = Json.asLong(m.getOrDefault("counter", 0L));
        return s;
    }
}
