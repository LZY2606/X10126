package replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A replay session: locks a definition fingerprint, initial state, event content and
 * random seed together so every replay of the same input yields the same trajectory hash.
 */
public final class Session {
    public String id;
    public String name;
    public Map<String, Object> definition;
    public String definitionFingerprint;
    public long seed;
    public Map<String, Long> priorities = new LinkedHashMap<>();
    public final Map<String, Branch> branches = new LinkedHashMap<>();
    public final List<Checkpoint> checkpoints = new ArrayList<>();
    public long eventCounter = 0;
    public long branchCounter = 0;
    public long checkpointCounter = 0;

    public static final class Branch {
        public String id;
        public String name;
        public String parentId;
        public long forkStep;
        public List<Map<String, Object>> events = new ArrayList<>(); // sorted external log
        public long stepIndex = 0;
        public List<Map<String, Object>> steps = new ArrayList<>();
        public Snapshot snapshot;
    }

    public static final class Checkpoint {
        public String id;
        public String name;
        public String branchId;
        public long stepIndex;
        public Snapshot snapshot;
        public String definitionFingerprint;
    }

    public static Session create(String id, String name, Map<String, Object> definition, long seed,
                                 Map<String, Long> priorities) {
        Session s = new Session();
        s.id = id;
        s.name = name;
        s.definition = definition;
        s.definitionFingerprint = Json.sha256(Json.canonical(definition));
        s.seed = seed;
        if (priorities != null) s.priorities.putAll(priorities);
        Branch main = new Branch();
        main.id = "main";
        main.name = "main";
        main.parentId = null;
        main.forkStep = 0;
        main.snapshot = s.initialSnapshot();
        s.branches.put(main.id, main);
        return s;
    }

    public Snapshot initialSnapshot() {
        Object init = definition.get("initialState");
        return new Snapshot(init != null ? String.valueOf(init) : "INIT", new LinkedHashMap<>(), seed);
    }

    public long priorityOf(String source) {
        Long p = priorities.get(source);
        return p != null ? p : 0L;
    }

    public Comparator<Map<String, Object>> eventOrder() {
        return Comparator
                .comparingLong((Map<String, Object> e) -> num(e.get("time")))
                .thenComparingLong(e -> priorityOf(str(e.get("source"))))
                .thenComparingLong(e -> num(e.get("seq")))
                .thenComparing(e -> str(e.get("id")));
    }

    static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0L; }
    static String str(Object o) { return o != null ? String.valueOf(o) : ""; }

    public Branch branch(String id) {
        Branch b = branches.get(id);
        if (b == null) throw new ApiException(404, "unknown branch: " + id);
        return b;
    }

    /** Adds external events to a branch. New events must sort after everything already processed. */
    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> addEvents(String branchId, List<Object> rawEvents) {
        Branch b = branch(branchId);
        List<Map<String, Object>> added = new ArrayList<>();
        for (Object raw : rawEvents) {
            Map<String, Object> e = (Map<String, Object>) Json.deepCopy(raw);
            if (e.get("id") == null) e.put("id", "e" + (++eventCounter));
            if (e.get("seq") == null) e.put("seq", ++eventCounter);
            if (e.get("time") == null) e.put("time", 0L);
            if (e.get("source") == null) e.put("source", "external");
            if (e.get("payload") == null) e.put("payload", new LinkedHashMap<>());
            e.put("internal", false);
            added.add(e);
        }
        List<Map<String, Object>> merged = new ArrayList<>(b.events);
        merged.addAll(added);
        merged.sort(eventOrder());
        if (b.stepIndex > 0) {
            Map<String, Object> lastProcessed = b.events.get((int) b.stepIndex - 1);
            for (Map<String, Object> e : added) {
                if (eventOrder().compare(e, lastProcessed) <= 0) {
                    throw new ApiException(409, "event " + e.get("id")
                            + " sorts at or before already-processed event " + lastProcessed.get("id"));
                }
            }
        }
        b.events = merged;
        return added;
    }

    public synchronized Map<String, Object> step(String branchId) {
        Branch b = branch(branchId);
        if (b.stepIndex >= b.events.size())
            throw new ApiException(409, "no pending events on branch " + branchId);
        Map<String, Object> event = b.events.get((int) b.stepIndex);
        Engine.StepOutcome outcome = Engine.runStep(definition, b.snapshot, event, b.stepIndex);
        b.steps.add(outcome.record);
        b.snapshot = outcome.snapshot;
        b.stepIndex++;
        return outcome.record;
    }

    public synchronized Map<String, Object> run(String branchId, long max) {
        Branch b = branch(branchId);
        List<Object> records = new ArrayList<>();
        long n = 0;
        while (b.stepIndex < b.events.size() && n < max) {
            records.add(step(branchId));
            n++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("steps", records);
        result.put("stepIndex", b.stepIndex);
        result.put("pending", b.events.size() - b.stepIndex);
        result.put("trajectoryHash", trajectoryHash(branchId));
        return result;
    }

    public synchronized Checkpoint checkpoint(String branchId, String name) {
        Branch b = branch(branchId);
        Checkpoint c = new Checkpoint();
        c.id = "c" + (++checkpointCounter);
        c.name = name != null ? name : c.id;
        c.branchId = branchId;
        c.stepIndex = b.stepIndex;
        c.snapshot = b.snapshot.copy();
        c.definitionFingerprint = definitionFingerprint;
        checkpoints.add(c);
        return c;
    }

    public Checkpoint findCheckpoint(String id) {
        for (Checkpoint c : checkpoints) if (c.id.equals(id)) return c;
        return null;
    }

    /**
     * Forks a new branch from a checkpoint. The checkpoint may belong to another session,
     * but its definition fingerprint must match this session's definition.
     */
    public synchronized Branch fork(Checkpoint c, String name) {
        if (!definitionFingerprint.equals(c.definitionFingerprint)) {
            throw new ApiException(409, "checkpoint definition fingerprint mismatch: checkpoint="
                    + c.definitionFingerprint + " session=" + definitionFingerprint);
        }
        Branch src = branch(c.branchId);
        Branch b = new Branch();
        b.id = "b" + (++branchCounter);
        b.name = name != null ? name : b.id;
        b.parentId = c.branchId;
        b.forkStep = c.stepIndex;
        b.events = new ArrayList<>();
        for (int i = 0; i < c.stepIndex; i++) b.events.add(src.events.get(i));
        b.steps = new ArrayList<>();
        for (int i = 0; i < c.stepIndex; i++) b.steps.add(src.steps.get(i));
        b.stepIndex = c.stepIndex;
        b.snapshot = c.snapshot.copy();
        branches.put(b.id, b);
        return b;
    }

    public static final class MergeResult {
        public boolean ok;
        public String branchId;
        public Map<String, Object> conflictA;
        public Map<String, Object> conflictB;
        public String reason;
    }

    /**
     * Merges two branches that share a common ancestor. Allowed only when the external
     * event sets added since the ancestor are compatible (identical ids carry identical
     * content) and the merged order is unambiguous (no two distinct events share the same
     * sort key). On rejection the first conflicting pair is reported.
     */
    public synchronized MergeResult merge(String aId, String bId) {
        Branch a = branch(aId);
        Branch b = branch(bId);
        if (a == b) throw new ApiException(409, "cannot merge a branch with itself");

        Branch ancestor;
        Branch descendant;
        if (isAncestor(a, b)) { ancestor = a; descendant = b; }
        else if (isAncestor(b, a)) { ancestor = b; descendant = a; }
        else throw new ApiException(409, "branches share no common ancestor: " + aId + ", " + bId);

        long base = descendant.forkStep;
        List<Map<String, Object>> sideA = ancestor.events.subList((int) base, ancestor.events.size());
        List<Map<String, Object>> sideB = descendant.events.subList((int) base, descendant.events.size());

        Map<String, Map<String, Object>> byIdA = new LinkedHashMap<>();
        for (Map<String, Object> e : sideA) byIdA.put(str(e.get("id")), e);
        Map<String, Map<String, Object>> byIdB = new LinkedHashMap<>();
        for (Map<String, Object> e : sideB) byIdB.put(str(e.get("id")), e);

        List<MergeResult> conflicts = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> en : byIdA.entrySet()) {
            Map<String, Object> other = byIdB.get(en.getKey());
            if (other != null && !Json.canonical(en.getValue()).equals(Json.canonical(other))) {
                conflicts.add(conflict(en.getValue(), other, "same event id with different content"));
            }
        }
        Comparator<Map<String, Object>> order = eventOrder();
        for (Map<String, Object> ea : sideA) {
            if (byIdB.containsKey(str(ea.get("id")))) continue;
            for (Map<String, Object> eb : sideB) {
                if (byIdA.containsKey(str(eb.get("id")))) continue;
                if (order.compare(ea, eb) == 0) {
                    conflicts.add(conflict(ea, eb, "ambiguous order: identical sort key (time, priority, seq)"));
                }
            }
        }
        if (!conflicts.isEmpty()) {
            conflicts.sort((x, y) -> {
                long ka = Math.min(sortKey(x.conflictA), sortKey(x.conflictB));
                long kb = Math.min(sortKey(y.conflictA), sortKey(y.conflictB));
                return Long.compare(ka, kb);
            });
            return conflicts.get(0);
        }

        List<Map<String, Object>> union = new ArrayList<>(sideA);
        for (Map<String, Object> e : sideB) {
            if (!byIdA.containsKey(str(e.get("id")))) union.add(e);
        }
        union.sort(order);

        Branch merged = new Branch();
        merged.id = "b" + (++branchCounter);
        merged.name = "merge-" + ancestor.id + "-" + descendant.id;
        merged.parentId = ancestor.id;
        merged.forkStep = base;
        merged.events = new ArrayList<>();
        for (int i = 0; i < base; i++) merged.events.add(ancestor.events.get(i));
        merged.events.addAll(union);
        merged.steps = new ArrayList<>();
        for (int i = 0; i < base; i++) merged.steps.add(ancestor.steps.get(i));
        merged.stepIndex = base;
        merged.snapshot = base == 0 ? initialSnapshot()
                : Snapshot.fromJson((Map<String, Object>) merged.steps.get((int) base - 1).get("after"));
        branches.put(merged.id, merged);
        while (merged.stepIndex < merged.events.size()) step(merged.id);

        MergeResult ok = new MergeResult();
        ok.ok = true;
        ok.branchId = merged.id;
        return ok;
    }

    private long sortKey(Map<String, Object> e) {
        return num(e.get("time")) * 1_000_003L + priorityOf(str(e.get("source"))) * 10_007L + num(e.get("seq"));
    }

    private static MergeResult conflict(Map<String, Object> a, Map<String, Object> b, String reason) {
        MergeResult r = new MergeResult();
        r.ok = false;
        r.conflictA = a;
        r.conflictB = b;
        r.reason = reason;
        return r;
    }

    private boolean isAncestor(Branch maybeAncestor, Branch b) {
        Branch cur = b;
        while (cur != null) {
            if (cur.parentId != null && cur.parentId.equals(maybeAncestor.id)) return true;
            cur = cur.parentId != null ? branches.get(cur.parentId) : null;
        }
        return false;
    }

    /** Stable fingerprint of a branch trajectory: definition + seed + initial state + steps. */
    public String trajectoryHash(String branchId) {
        Branch b = branch(branchId);
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("definitionFingerprint", definitionFingerprint);
        material.put("seed", seed);
        material.put("initial", initialSnapshot().toJson());
        material.put("steps", b.steps);
        return Json.sha256(Json.canonical(material));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("definition", Json.deepCopy(definition));
        m.put("definitionFingerprint", definitionFingerprint);
        m.put("seed", seed);
        m.put("sourcePriorities", new LinkedHashMap<>(priorities));
        m.put("eventCounter", eventCounter);
        m.put("branchCounter", branchCounter);
        m.put("checkpointCounter", checkpointCounter);
        List<Object> bs = new ArrayList<>();
        for (Branch b : branches.values()) {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("id", b.id);
            bm.put("name", b.name);
            bm.put("parentId", b.parentId);
            bm.put("forkStep", b.forkStep);
            bm.put("events", Json.deepCopy(b.events));
            bm.put("stepIndex", b.stepIndex);
            bm.put("steps", Json.deepCopy(b.steps));
            bm.put("snapshot", b.snapshot.toJson());
            bm.put("trajectoryHash", trajectoryHash(b.id));
            bs.add(bm);
        }
        m.put("branches", bs);
        List<Object> cs = new ArrayList<>();
        for (Checkpoint c : checkpoints) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.id);
            cm.put("name", c.name);
            cm.put("branchId", c.branchId);
            cm.put("stepIndex", c.stepIndex);
            cm.put("snapshot", c.snapshot.toJson());
            cm.put("definitionFingerprint", c.definitionFingerprint);
            cs.add(cm);
        }
        m.put("checkpoints", cs);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Session fromJson(Map<String, Object> m) {
        Session s = new Session();
        s.id = str(m.get("id"));
        s.name = str(m.get("name"));
        s.definition = (Map<String, Object>) m.get("definition");
        s.definitionFingerprint = str(m.get("definitionFingerprint"));
        s.seed = num(m.get("seed"));
        Object pr = m.get("sourcePriorities");
        if (pr instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) pr).entrySet())
                s.priorities.put(e.getKey(), num(e.getValue()));
        }
        s.eventCounter = num(m.get("eventCounter"));
        s.branchCounter = num(m.get("branchCounter"));
        s.checkpointCounter = num(m.get("checkpointCounter"));
        for (Object bo : (List<Object>) m.getOrDefault("branches", List.of())) {
            Map<String, Object> bm = (Map<String, Object>) bo;
            Branch b = new Branch();
            b.id = str(bm.get("id"));
            b.name = str(bm.get("name"));
            Object pid = bm.get("parentId");
            b.parentId = pid != null ? String.valueOf(pid) : null;
            b.forkStep = num(bm.get("forkStep"));
            for (Object e : (List<Object>) bm.getOrDefault("events", List.of()))
                b.events.add((Map<String, Object>) e);
            b.stepIndex = num(bm.get("stepIndex"));
            for (Object st : (List<Object>) bm.getOrDefault("steps", List.of()))
                b.steps.add((Map<String, Object>) st);
            b.snapshot = Snapshot.fromJson((Map<String, Object>) bm.get("snapshot"));
            s.branches.put(b.id, b);
        }
        for (Object co : (List<Object>) m.getOrDefault("checkpoints", List.of())) {
            Map<String, Object> cm = (Map<String, Object>) co;
            Checkpoint c = new Checkpoint();
            c.id = str(cm.get("id"));
            c.name = str(cm.get("name"));
            c.branchId = str(cm.get("branchId"));
            c.stepIndex = num(cm.get("stepIndex"));
            c.snapshot = Snapshot.fromJson((Map<String, Object>) cm.get("snapshot"));
            c.definitionFingerprint = str(cm.get("definitionFingerprint"));
            s.checkpoints.add(c);
        }
        return s;
    }
}
