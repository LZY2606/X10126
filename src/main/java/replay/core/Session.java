package replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replay.json.Json;

/** A replay session: one definition + seed, several branches, persistent and exportable. */
public final class Session {
    public String id;
    public String name;
    public Definition def;
    public long seed;
    public List<String> sourcePriorities = new ArrayList<>();
    public Map<String, Branch> branches = new LinkedHashMap<>();
    public String currentBranchId;
    public long branchCounter;

    public Session(String id, String name, Definition def, long seed) {
        this.id = id;
        this.name = name;
        this.def = def;
        this.seed = seed;
        Branch main = new Branch("b1", "main", new Engine(def, seed));
        branches.put(main.id, main);
        currentBranchId = main.id;
        branchCounter = 1;
    }

    public Branch current() { return branches.get(currentBranchId); }

    public Branch branch(String id) {
        Branch b = branches.get(id);
        if (b == null) throw new IllegalArgumentException("unknown branch: " + id);
        return b;
    }

    public int sourcePriorityOf(String source) {
        int idx = sourcePriorities.indexOf(source);
        if (idx < 0) {
            sourcePriorities.add(source);
            idx = sourcePriorities.size() - 1;
        }
        return idx;
    }

    /** Import external events into a branch. Rejects duplicate (source, seq) keys. */
    @SuppressWarnings("unchecked")
    public List<EventInstance> importEvents(Branch branch, List<Object> eventMaps) {
        List<EventInstance> added = new ArrayList<>();
        for (Object o : eventMaps) {
            Map<String, Object> m = (Map<String, Object>) o;
            String type = String.valueOf(m.get("type"));
            long time = m.get("time") instanceof Number n ? n.longValue() : 0;
            String source = m.get("source") == null ? "external" : m.get("source").toString();
            long seq = m.get("seq") instanceof Number n2 ? n2.longValue() : 0;
            for (EventInstance existing : branch.engine.externalLog) {
                if (existing.source.equals(source) && existing.seq == seq) {
                    throw new IllegalArgumentException(
                        "duplicate external event key source=" + source + " seq=" + seq);
                }
            }
            for (EventInstance pending : added) {
                if (pending.source.equals(source) && pending.seq == seq) {
                    throw new IllegalArgumentException(
                        "duplicate external event key source=" + source + " seq=" + seq);
                }
            }
            added.add(branch.engine.enqueueExternal(type, time, source, sourcePriorityOf(source), seq));
        }
        return added;
    }

    /** Fork a new branch from a checkpoint of the given branch. */
    public Branch fork(String fromBranchId, String checkpointId, String name) {
        Branch from = branch(fromBranchId);
        Checkpoint c = from.checkpoints.get(checkpointId);
        if (c == null) throw new IllegalArgumentException("unknown checkpoint: " + checkpointId);
        String currentFp = def.fingerprint();
        if (!c.defFingerprint.equals(currentFp)) {
            throw new IllegalStateException(
                "checkpoint " + c.id + " was created under a different definition version");
        }
        Engine engine = new Engine(def, seed);
        engine.restoreFromMap(c.engineSnapshot);
        Branch b = new Branch("b" + (++branchCounter),
            name == null || name.isEmpty() ? "branch-" + branchCounter : name, engine);
        b.forkedFrom = from.id;
        branches.put(b.id, b);
        return b;
    }

    /** Session fingerprint: locks definition, initial variables, seed and event content. */
    public String fingerprint() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definition", def.toMap());
        m.put("seed", seed);
        m.put("sourcePriorities", new ArrayList<>(sourcePriorities));
        List<Object> events = new ArrayList<>();
        for (EventInstance e : branches.get("b1").engine.externalLog) events.add(e.toMap());
        m.put("events", events);
        return Definition.sha256(Json.canonical(m));
    }

    // ---------- branch comparison & merge ----------

    private static List<StepRecord> externalAfter(List<StepRecord> trace, int from) {
        List<StepRecord> out = new ArrayList<>();
        for (int i = from; i < trace.size(); i++) {
            StepRecord r = trace.get(i);
            if (!r.internal) out.add(r);
        }
        return out;
    }

    private static String orderKey(StepRecord r) {
        return r.time + ":" + r.sourcePriority + ":" + r.seq;
    }

    public Map<String, Object> compare(String aId, String bId) {
        Branch a = branch(aId), b = branch(bId);
        List<StepRecord> ta = a.engine.trace, tb = b.engine.trace;
        int div = 0;
        while (div < ta.size() && div < tb.size()
            && ta.get(div).eventId.equals(tb.get(div).eventId)) div++;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", branchView(a));
        m.put("b", branchView(b));
        m.put("firstDivergence", div);
        m.put("statesEqual", a.engine.state.equals(b.engine.state)
            && a.engine.vars.equals(b.engine.vars));
        m.put("outputsEqual", a.engine.outputs.equals(b.engine.outputs));
        return m;
    }

    private static Map<String, Object> branchView(Branch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id);
        m.put("name", b.name);
        m.put("state", b.engine.state);
        m.put("vars", new LinkedHashMap<>(b.engine.vars));
        m.put("outputs", new ArrayList<>(b.engine.outputs));
        m.put("steps", b.engine.trace.size());
        m.put("traceHash", b.engine.traceHash());
        return m;
    }

    public static final class MergeConflict extends Exception {
        public final StepRecord aEvent;
        public final StepRecord bEvent;
        public MergeConflict(String msg, StepRecord a, StepRecord b) {
            super(msg);
            this.aEvent = a;
            this.bEvent = b;
        }
    }

    /**
     * Merge two branches. Never overwrites one final state with the other:
     * succeeds only when the external events applied since the common ancestor
     * are compatible and their relative order is unambiguous; the merged branch
     * is then re-replayed deterministically from the ancestor.
     */
    public Branch merge(String aId, String bId, String name) throws MergeConflict {
        Branch a = branch(aId), b = branch(bId);
        List<StepRecord> ta = a.engine.trace, tb = b.engine.trace;
        int p = 0;
        while (p < ta.size() && p < tb.size() && ta.get(p).eventId.equals(tb.get(p).eventId)) p++;
        List<StepRecord> ea = externalAfter(ta, p);
        List<StepRecord> eb = externalAfter(tb, p);

        for (StepRecord x : ea) {
            for (StepRecord y : eb) {
                if (x.eventId.equals(y.eventId)) {
                    throw new MergeConflict(
                        "同一外部事件在分叉后被两个分支分别应用: " + x.eventId, x, y);
                }
            }
        }
        for (StepRecord x : ea) {
            for (StepRecord y : eb) {
                if (orderKey(x).equals(orderKey(y))) {
                    throw new MergeConflict(
                        "事件排序键相同但内容不同，顺序无歧义无法保证: " + x.eventId + " vs " + y.eventId, x, y);
                }
            }
        }

        // Compatible: combined external log = common prefix + deterministic union.
        List<StepRecord> combined = new ArrayList<>();
        for (int i = 0; i < ta.size(); i++) {
            StepRecord r = ta.get(i);
            if (!r.internal) {
                if (indexOfEvent(combined, r.eventId) < 0 && i < p) combined.add(r);
            }
        }
        List<StepRecord> rest = new ArrayList<>();
        rest.addAll(ea);
        rest.addAll(eb);
        rest.sort((r1, r2) -> {
            int c = Long.compare(r1.time, r2.time);
            if (c != 0) return c;
            c = Integer.compare(r1.sourcePriority, r2.sourcePriority);
            if (c != 0) return c;
            return Long.compare(r1.seq, r2.seq);
        });
        combined.addAll(rest);

        Engine engine = new Engine(def, seed);
        for (StepRecord r : combined) {
            engine.enqueueExternal(r.eventType, r.time, r.source, r.sourcePriority, r.seq);
        }
        engine.run(1_000_000);
        Branch merged = new Branch("b" + (++branchCounter),
            name == null || name.isEmpty() ? "merge-" + a.name + "-" + b.name : name, engine);
        branches.put(merged.id, merged);
        return merged;
    }

    private static int indexOfEvent(List<StepRecord> list, String eventId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).eventId.equals(eventId)) return i;
        }
        return -1;
    }

    // ---------- export / import ----------

    public Map<String, Object> exportDoc() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "replay-session/1");
        m.put("name", name);
        m.put("seed", seed);
        m.put("sourcePriorities", new ArrayList<>(sourcePriorities));
        m.put("definition", def.toMap());
        m.put("fingerprint", fingerprint());
        List<Object> bs = new ArrayList<>();
        for (Branch b : branches.values()) {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("name", b.name);
            List<Object> events = new ArrayList<>();
            for (EventInstance e : b.engine.externalLog) events.add(e.toMap());
            bm.put("events", events);
            bm.put("steps", b.engine.trace.size());
            bm.put("traceHash", b.engine.traceHash());
            bs.add(bm);
        }
        m.put("branches", bs);
        return m;
    }

    /** Rebuild a session from an export document by deterministic re-replay. */
    @SuppressWarnings("unchecked")
    public static Session importDoc(Map<String, Object> doc, String newId, List<Boolean> hashMatches) {
        Definition def = Definition.fromMap((Map<String, Object>) doc.get("definition"));
        long seed = doc.get("seed") instanceof Number n ? n.longValue() : 0;
        Session s = new Session(newId, String.valueOf(doc.getOrDefault("name", "imported")), def, seed);
        if (doc.get("sourcePriorities") instanceof List) {
            for (Object o : (List<Object>) doc.get("sourcePriorities")) s.sourcePriorities.add(o.toString());
        }
        List<Object> branchSpecs = (List<Object>) doc.get("branches");
        boolean first = true;
        for (Object bo : branchSpecs) {
            Map<String, Object> bm = (Map<String, Object>) bo;
            Branch b;
            if (first) {
                b = s.branches.get("b1");
                b.name = String.valueOf(bm.getOrDefault("name", "main"));
                first = false;
            } else {
                b = new Branch("b" + (++s.branchCounter), String.valueOf(bm.get("name")),
                    new Engine(def, seed));
                s.branches.put(b.id, b);
            }
            s.importEvents(b, (List<Object>) bm.get("events"));
            int steps = bm.get("steps") instanceof Number n2 ? n2.intValue() : 0;
            b.engine.run(steps);
            hashMatches.add(b.engine.traceHash().equals(bm.get("traceHash")));
        }
        return s;
    }

    // ---------- persistence ----------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("seed", seed);
        m.put("sourcePriorities", new ArrayList<>(sourcePriorities));
        m.put("definition", def.toMap());
        m.put("currentBranchId", currentBranchId);
        m.put("branchCounter", branchCounter);
        Map<String, Object> bs = new LinkedHashMap<>();
        branches.forEach((k, v) -> bs.put(k, v.toMap()));
        m.put("branches", bs);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Session fromMap(Map<String, Object> m) {
        Definition def = Definition.fromMap((Map<String, Object>) m.get("definition"));
        long seed = m.get("seed") instanceof Number n ? n.longValue() : 0;
        Session s = new Session(EventInstance.str(m.get("id")), EventInstance.str(m.get("name")), def, seed);
        s.sourcePriorities = new ArrayList<>();
        for (Object o : (List<Object>) m.get("sourcePriorities")) s.sourcePriorities.add(o.toString());
        s.currentBranchId = EventInstance.str(m.get("currentBranchId"));
        s.branchCounter = EventInstance.num(m.get("branchCounter"));
        s.branches = new LinkedHashMap<>();
        ((Map<String, Object>) m.get("branches")).forEach((k, v) ->
            s.branches.put(k, Branch.fromMap((Map<String, Object>) v, def)));
        return s;
    }
}
