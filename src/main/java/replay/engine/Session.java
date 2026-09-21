package replay.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import replay.Json;

/**
 * A locked, replayable session. The lock binds definition fingerprint,
 * initial state/vars, full external-event content and the RNG seed.
 */
public final class Session {

    public String id;
    public String name;
    public Definition definition;
    public EventLog eventLog;
    public String lockFingerprint;

    public final Map<String, Branch> branches = new LinkedHashMap<>();
    public final List<Map<String, Object>> mergeRecords = new ArrayList<>();

    private long idCounter = 1;

    public static Session create(String id, String name, Definition definition,
                                 List<Object> rawEvents) {
        Session s = new Session();
        s.id = id;
        s.name = name == null ? "session" : name;
        s.definition = definition;
        s.eventLog = EventLog.fromMaps(rawEvents);
        s.lockFingerprint = lock(definition, s.eventLog);

        Branch main = new Branch();
        main.id = "br-main";
        main.name = "main";
        main.baseCheckpointId = null;
        main.parentBranchId = null;
        main.externalEvents.addAll(s.eventLog.events);
        main.engine = ReplayEngine.fresh(definition, s.eventLog);
        Branch.Checkpoint root = new Branch.Checkpoint();
        root.id = "cp-root";
        root.label = "root";
        root.stepIndex = 0;
        root.traceHash = main.engine.getTraceHash();
        root.definitionFingerprint = definition.fingerprint();
        root.definitionVersion = String.valueOf(definition.version);
        root.parentCheckpointId = null;
        root.snapshot = main.engine.snapshot();
        main.checkpoints.add(root);
        main.baseCheckpointId = root.id;
        s.branches.put(main.id, main);
        return s;
    }

    public static String lock(Definition definition, EventLog log) {
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("definitionFingerprint", definition.fingerprint());
        lock.put("definitionVersion", definition.version);
        lock.put("initialState", definition.initialState);
        lock.put("initialVars", definition.initialVars);
        lock.put("seed", definition.seed);
        lock.put("eventsFingerprint", log.fingerprint());
        return Hashes.fingerprint(lock);
    }

    public String nextId(String prefix) {
        return prefix + "-" + Long.toString(idCounter++, 36);
    }

    public Branch requireBranch(String branchId) {
        Branch b = branches.get(branchId);
        if (b == null) {
            throw new ApiException(404, "branch not found: " + branchId);
        }
        return b;
    }

    public Branch.Checkpoint requireCheckpoint(String checkpointId) {
        for (Branch b : branches.values()) {
            for (Branch.Checkpoint cp : b.checkpoints) {
                if (cp.id.equals(checkpointId)) {
                    return cp;
                }
            }
        }
        throw new ApiException(404, "checkpoint not found: " + checkpointId);
    }

    // ---------- playback ----------

    public Map<String, Object> step(String branchId, int count) {
        Branch branch = requireBranch(branchId);
        List<Object> produced = new ArrayList<>();
        int applied = 0;
        while (applied < count && branch.engine.hasNext()) {
            produced.add(branch.engine.stepOnce());
            Map<String, Object> step = (Map<String, Object>) produced.get(produced.size() - 1);
            branch.steps.add(step);
            applied++;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("applied", applied);
        resp.put("finished", !branch.engine.hasNext());
        resp.put("steps", produced);
        resp.put("branch", branch.describe());
        resp.put("lockFingerprint", lockFingerprint);
        return resp;
    }

    public Map<String, Object> runToEnd(String branchId) {
        Branch branch = requireBranch(branchId);
        List<Object> produced = new ArrayList<>();
        while (branch.engine.hasNext()) {
            Map<String, Object> step = branch.engine.stepOnce();
            branch.steps.add(step);
            produced.add(step);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("applied", produced.size());
        resp.put("finished", true);
        resp.put("steps", produced);
        resp.put("branch", branch.describe());
        resp.put("traceHash", branch.engine.getTraceHash());
        resp.put("lockFingerprint", lockFingerprint);
        return resp;
    }

    // ---------- checkpoints ----------

    public Branch.Checkpoint checkpoint(String branchId, String label) {
        Branch branch = requireBranch(branchId);
        Branch.Checkpoint cp = new Branch.Checkpoint();
        cp.id = nextId("cp");
        cp.label = label == null ? ("checkpoint@" + branch.engine.getStepIndex()) : label;
        cp.stepIndex = branch.engine.getStepIndex();
        cp.traceHash = branch.engine.getTraceHash();
        cp.definitionFingerprint = definition.fingerprint();
        cp.definitionVersion = String.valueOf(definition.version);
        cp.snapshot = branch.engine.snapshot();
        cp.parentCheckpointId = branch.checkpoints.isEmpty()
                ? branch.baseCheckpointId
                : branch.checkpoints.get(branch.checkpoints.size() - 1).id;
        branch.checkpoints.add(cp);
        return cp;
    }

    /** Restore must reject checkpoints created under a different definition. */
    public void restoreCheckpoint(String branchId, String checkpointId) {
        Branch branch = requireBranch(branchId);
        Branch.Checkpoint cp = requireCheckpoint(checkpointId);
        verifyCheckpointDefinition(cp);
        long cpStep = cp.stepIndex;
        branch.engine = ReplayEngine.fresh(definition,
                EventLog.fromMaps(rawEvents(branch.externalEvents)));
        while (branch.engine.getStepIndex() < cpStep) {
            branch.engine.stepOnce();
        }
        branch.engine.restore(cp.snapshot);
        // truncate trajectory records to the checkpoint boundary
        while (branch.steps.size() > cpStep) {
            branch.steps.remove(branch.steps.size() - 1);
        }
        branch.checkpoints.removeIf(existing -> existing.stepIndex > cpStep);
    }

    public void verifyCheckpointDefinition(Branch.Checkpoint cp) {
        if (!cp.definitionFingerprint.equals(definition.fingerprint())) {
            throw new ApiException(409,
                    "checkpoint was created under definition version " + cp.definitionVersion
                            + " (fingerprint " + cp.definitionFingerprint.substring(0, 12)
                            + ") and cannot be replayed under the current definition version "
                            + definition.version + " (fingerprint "
                            + definition.fingerprint().substring(0, 12) + ")");
        }
    }

    // ---------- branches ----------

    public Branch fork(String branchId, String checkpointId, String name,
                       List<Object> additionalEvents) {
        Branch parent = requireBranch(branchId);
        Branch.Checkpoint cp = checkpointId == null
                ? latestCheckpointOrImplicit(parent)
                : requireCheckpoint(checkpointId);
        verifyCheckpointDefinition(cp);

        Branch child = new Branch();
        child.id = nextId("br");
        child.name = name == null ? parent.name + "-fork" : name;
        child.parentBranchId = parent.id;
        child.baseCheckpointId = cp.id;
        child.ancestorStepIndex = cp.stepIndex;

        // A fork stores only the external events introduced AFTER its anchor.
        if (additionalEvents != null) {
            EventLog extraLog = EventLog.fromMaps(additionalEvents);
            child.externalEvents.addAll(extraLog.events);
            child.externalEvents.sort(EventLog.ORDER);
        }

        EventLog branchLog = EventLog.fromMaps(rawEvents(child.externalEvents));
        child.engine = ReplayEngine.fresh(definition, branchLog);
        child.engine.restore(cp.snapshot);

        // Re-create the steps already represented by the ancestor snapshot so
        // the branch UI shows its prefix history.
        for (Map<String, Object> step : parent.steps) {
            if (Json.lng(step, "index", 0L) < cp.stepIndex) {
                child.steps.add(step);
            }
        }
        Branch.Checkpoint anchor = new Branch.Checkpoint();
        anchor.id = nextId("cp");
        anchor.label = "fork-anchor of " + cp.label;
        anchor.stepIndex = cp.stepIndex;
        anchor.traceHash = cp.traceHash;
        anchor.definitionFingerprint = cp.definitionFingerprint;
        anchor.definitionVersion = cp.definitionVersion;
        anchor.snapshot = cp.snapshot;
        anchor.parentCheckpointId = cp.id;
        child.checkpoints.add(anchor);
        child.baseCheckpointId = anchor.id;

        branches.put(child.id, child);
        return child;
    }

    private Branch.Checkpoint latestCheckpointOrImplicit(Branch branch) {
        if (!branch.checkpoints.isEmpty()) {
            return branch.checkpoints.get(branch.checkpoints.size() - 1);
        }
        Branch.Checkpoint implicit = new Branch.Checkpoint();
        implicit.id = "cp-implicit-" + branch.id;
        implicit.label = "implicit@current";
        implicit.stepIndex = branch.engine.getStepIndex();
        implicit.traceHash = branch.engine.getTraceHash();
        implicit.definitionFingerprint = definition.fingerprint();
        implicit.definitionVersion = String.valueOf(definition.version);
        implicit.snapshot = branch.engine.snapshot();
        implicit.parentCheckpointId = branch.baseCheckpointId;
        return implicit;
    }

    public Map<String, Object> appendExternal(String branchId, List<Object> newEvents) {
        Branch branch = requireBranch(branchId);
        EventLog extra = EventLog.fromMaps(newEvents);
        for (Map<String, Object> e : extra.events) {
            e.put("id", nextId("ext"));
        }
        extra.events.sort(EventLog.ORDER);
        branch.externalEvents.addAll(extra.events);
        branch.externalEvents.sort(EventLog.ORDER);
        // Rebuild engine, replay prior steps deterministically, keep their records.
        long stepsToKeep = branch.engine.getStepIndex();
        EventLog rebuilt = EventLog.fromMaps(rawEvents(branch.externalEvents));
        ReplayEngine engine = ReplayEngine.fresh(definition, rebuilt);
        for (int i = 0; i < stepsToKeep; i++) {
            Map<String, Object> oldStep = branch.steps.get(i);
            Map<String, Object> newStep = engine.stepOnce();
            if (!Json.str(newStep, "traceHash").equals(Json.str(oldStep, "traceHash"))) {
                throw new ApiException(409,
                        "appending an earlier-timestamped event altered history; "
                                + "replay diverged at step " + i);
            }
        }
        branch.engine = engine;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("added", extra.events.size());
        resp.put("branch", branch.describe());
        return resp;
    }

    // ---------- comparison and merge ----------

    /** Prefix steps recorded before the ancestor (shared history). */
    private List<Map<String, Object>> prefixSteps(Branch a, Branch b, Branch.Checkpoint ancestor) {
        List<Map<String, Object>> prefix = new ArrayList<>();
        for (Map<String, Object> step : a.steps) {
            if (Json.lng(step, "index", 0L) < ancestor.stepIndex) {
                prefix.add(step);
            }
        }
        if ((long) prefix.size() == ancestor.stepIndex) {
            return prefix;
        }
        prefix.clear();
        for (Map<String, Object> step : b.steps) {
            if (Json.lng(step, "index", 0L) < ancestor.stepIndex) {
                prefix.add(step);
            }
        }
        return prefix;
    }

    public Map<String, Object> compare(String leftId, String rightId) {
        Branch a = requireBranch(leftId);
        Branch b = requireBranch(rightId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("left", a.describe());
        resp.put("right", b.describe());
        resp.put("sameState", a.engine.getState().equals(b.engine.getState()));
        resp.put("sameVars", Hashes.canonical(Hashes.canonicalize(a.engine.getVars()))
                .equals(Hashes.canonical(Hashes.canonicalize(b.engine.getVars()))));
        resp.put("sameTraceHash", a.engine.getTraceHash().equals(b.engine.getTraceHash()));

        Map<String, Object> varDiff = new LinkedHashMap<>();
        java.util.Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(a.engine.getVars().keySet());
        keys.addAll(b.engine.getVars().keySet());
        for (String key : keys) {
            Object av = a.engine.getVars().get(key);
            Object bv = b.engine.getVars().get(key);
            if (!Hashes.canonical(Hashes.canonicalize(av))
                    .equals(Hashes.canonical(Hashes.canonicalize(bv)))) {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("left", av);
                pair.put("right", bv);
                varDiff.put(key, pair);
            }
        }
        Map<String, Object> statePair = new LinkedHashMap<>();
        statePair.put("left", a.engine.getState());
        statePair.put("right", b.engine.getState());
        resp.put("state", statePair);
        Map<String, Object> outputsPair = new LinkedHashMap<>();
        outputsPair.put("leftCount", outputCount(a));
        outputsPair.put("rightCount", outputCount(b));
        resp.put("outputs", outputsPair);
        resp.put("varsDiff", varDiff);
        return resp;
    }

    private static long outputCount(Branch b) {
        long n = 0;
        for (Map<String, Object> step : b.steps) {
            n += Json.list(step, "outputs").size();
        }
        return n;
    }

    /**
     * External events a branch introduces after the merge ancestor. Forks
     * already store only relative events; main and deep-lineage branches
     * derive them from recorded steps.
     */
    private List<Map<String, Object>> relativeEvents(Branch b, Branch.Checkpoint ancestor) {
        if (b.baseCheckpointId != null && b.baseCheckpointId.equals(ancestor.id)) {
            return new ArrayList<>(b.externalEvents);
        }
        if (ancestor.id.equals("cp-root") && "br-main".equals(b.id)) {
            return new ArrayList<>(b.externalEvents);
        }
        return b.externalSince(ancestor);
    }

    public Map<String, Object> merge(String leftId, String rightId, String newName, boolean execute) {
        Branch a = requireBranch(leftId);
        Branch b = requireBranch(rightId);
        Branch.Checkpoint ancestor = commonAncestor(a, b);
        if (ancestor == null) {
            throw new ApiException(409,
                    "branches share no common ancestor checkpoint; merge rejected");
        }
        verifyCheckpointDefinition(ancestor);

        List<Map<String, Object>> aEvents = relativeEvents(a, ancestor);
        List<Map<String, Object>> bEvents = relativeEvents(b, ancestor);
        MergePlanner.MergeResult plan = MergePlanner.plan(aEvents, bEvents);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ancestor", Map.of(
                "checkpointId", ancestor.id,
                "label", ancestor.label,
                "stepIndex", ancestor.stepIndex));
        resp.put("compatible", plan.compatible);
        resp.put("summary", plan.summary);
        if (!plan.compatible) {
            resp.put("status", "rejected");
            resp.put("firstConflict", plan.firstConflict);
            resp.put("httpStatus", 409);
            return resp;
        }
        if (!execute) {
            resp.put("status", "compatible-preview");
            resp.put("mergedEvents", plan.mergedEvents.size());
            return resp;
        }

        Branch merged = new Branch();
        merged.id = nextId("br");
        merged.name = newName == null ? "merge(" + a.name + "," + b.name + ")" : newName;
        merged.parentBranchId = a.id;
        merged.baseCheckpointId = ancestor.id;
        merged.externalEvents.addAll(plan.mergedEvents);
        merged.ancestorStepIndex = ancestor.stepIndex;

        EventLog mergedLog = EventLog.fromMaps(rawEvents(plan.mergedEvents));
        merged.engine = ReplayEngine.fresh(definition, mergedLog);
        merged.engine.restore(ancestor.snapshot);
        merged.steps.addAll(prefixSteps(a, b, ancestor));
        while (merged.engine.hasNext()) {
            Map<String, Object> step = merged.engine.stepOnce();
            merged.steps.add(step);
        }

        Branch.Checkpoint anchor = new Branch.Checkpoint();
        anchor.id = nextId("cp");
        anchor.label = "merge-anchor of " + ancestor.label;
        anchor.stepIndex = ancestor.stepIndex;
        anchor.traceHash = ancestor.traceHash;
        anchor.definitionFingerprint = ancestor.definitionFingerprint;
        anchor.definitionVersion = ancestor.definitionVersion;
        anchor.snapshot = ancestor.snapshot;
        merged.checkpoints.add(anchor);
        merged.baseCheckpointId = anchor.id;

        branches.put(merged.id, merged);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", nextId("merge"));
        record.put("left", leftId);
        record.put("right", rightId);
        record.put("ancestor", ancestor.id);
        record.put("mergedBranch", merged.id);
        record.put("traceHash", merged.engine.getTraceHash());
        mergeRecords.add(record);

        resp.put("status", "merged");
        resp.put("mergedBranch", merged.describe());
        resp.put("traceHash", merged.engine.getTraceHash());
        return resp;
    }

    private Branch.Checkpoint commonAncestor(Branch a, Branch b) {
        java.util.Set<String> aChain = new java.util.HashSet<>();
        String cursor = a.baseCheckpointId;
        java.util.Map<String, Branch.Checkpoint> all = new LinkedHashMap<>();
        for (Branch br : branches.values()) {
            for (Branch.Checkpoint cp : br.checkpoints) {
                all.put(cp.id, cp);
            }
        }
        while (cursor != null) {
            aChain.add(cursor);
            Branch.Checkpoint cp = all.get(cursor);
            cursor = cp == null ? null : cp.parentCheckpointId;
        }
        cursor = b.baseCheckpointId;
        Branch.Checkpoint best = null;
        long bestStep = -1;
        while (cursor != null) {
            if (aChain.contains(cursor)) {
                Branch.Checkpoint cp = all.get(cursor);
                if (cp != null && cp.stepIndex >= bestStep) {
                    best = cp;
                    bestStep = cp.stepIndex;
                }
            }
            Branch.Checkpoint cp = all.get(cursor);
            cursor = cp == null ? null : cp.parentCheckpointId;
        }
        return best;
    }

    // ---------- views / serialization ----------

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("lockFingerprint", lockFingerprint);
        m.put("definitionFingerprint", definition.fingerprint());
        m.put("definitionVersion", definition.version);
        m.put("seed", definition.seed);
        m.put("eventCount", eventLog.events.size());
        m.put("eventsFingerprint", eventLog.fingerprint());
        List<Object> brs = new ArrayList<>();
        for (Branch b : branches.values()) {
            brs.add(b.describe());
        }
        m.put("branches", brs);
        m.put("merges", mergeRecords);
        return m;
    }

    public Map<String, Object> branchView(String branchId, int offset, int limit) {
        Branch b = requireBranch(branchId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("branch", b.describe());
        int from = Math.max(0, offset);
        int to = limit <= 0 ? b.steps.size() : Math.min(b.steps.size(), from + limit);
        m.put("steps", new ArrayList<>(b.steps.subList(from, to)));
        m.put("offset", from);
        m.put("totalSteps", b.steps.size());
        return m;
    }

    /** Export package: sufficient to reproduce identical trajectory hashes. */
    public Map<String, Object> export() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "state-machine-replay-room/session.v1");
        m.put("id", id);
        m.put("name", name);
        m.put("idCounter", idCounter);
        m.put("lockFingerprint", lockFingerprint);
        m.put("definition", definition.toMap());
        m.put("events", eventLog.toMaps());
        List<Object> branchData = new ArrayList<>();
        for (Branch b : branches.values()) {
            Map<String, Object> bd = new LinkedHashMap<>();
            bd.put("id", b.id);
            bd.put("name", b.name);
            bd.put("parentBranchId", b.parentBranchId);
            bd.put("baseCheckpointId", b.baseCheckpointId);
            bd.put("ancestorStepIndex", b.ancestorStepIndex);
            bd.put("externalEvents", rawEvents(b.externalEvents));
            bd.put("steps", b.steps);
            List<Object> cps = new ArrayList<>();
            for (Branch.Checkpoint cp : b.checkpoints) {
                Map<String, Object> cpMap = cp.toMap();
                cpMap.put("parentCheckpointId", cp.parentCheckpointId);
                cps.add(cpMap);
            }
            bd.put("checkpoints", cps);
            branchData.add(bd);
        }
        m.put("branches", branchData);
        m.put("merges", mergeRecords);
        return m;
    }

    /**
     * Import: verify determinism by re-running every branch end to end and
     * comparing trajectory hashes before accepting the package.
     */
    public static Session importVerified(Map<String, Object> data) {
        String format = Json.str(data, "format");
        if (!"state-machine-replay-room/session.v1".equals(format)) {
            throw new ApiException(400, "unsupported export format");
        }
        Definition def = Definition.fromMap(Json.obj(data, "definition"));
        List<Object> events = Json.list(data, "events");
        String id = Json.str(data, "id", "imported");
        String name = Json.str(data, "name", "imported session");
        Session s = create(id, name, def, events);

        long counter = Json.lng(data, "idCounter", 1L);
        s.idCounter = Math.max(counter, s.idCounter);

        Map<String, Object> storedBranches = new LinkedHashMap<>();
        for (Object raw : Json.list(data, "branches")) {
            Map<String, Object> bd = (Map<String, Object>) raw;
            storedBranches.put(Json.str(bd, "id"), bd);
        }

        // Rebuild branches in declaration order; main first.
        for (Object raw : Json.list(data, "branches")) {
            Map<String, Object> bd = (Map<String, Object>) raw;
            String bid = Json.str(bd, "id");
            if ("br-main".equals(bid)) {
                rebuildBranch(s, s.branches.get("br-main"), bd);
            }
        }
        for (Object raw : Json.list(data, "branches")) {
            Map<String, Object> bd = (Map<String, Object>) raw;
            String bid = Json.str(bd, "id");
            if ("br-main".equals(bid)) {
                continue;
            }
            Branch created = new Branch();
            created.id = bid;
            created.name = Json.str(bd, "name", bid);
            created.parentBranchId = Json.str(bd, "parentBranchId");
            created.baseCheckpointId = Json.str(bd, "baseCheckpointId");
            created.ancestorStepIndex = Json.lng(bd, "ancestorStepIndex", 0L);
            for (Object e : Json.list(bd, "externalEvents")) {
                created.externalEvents.add((Map<String, Object>) e);
            }
            created.externalEvents.sort(EventLog.ORDER);
            EventLog log = EventLog.fromMaps(rawEvents(created.externalEvents));
            created.engine = ReplayEngine.fresh(def, log);
            Branch.Checkpoint anchor = findCheckpoint(s, created.baseCheckpointId);
            if (anchor != null) {
                created.engine.restore(anchor.snapshot);
            }
            s.branches.put(bid, created);
            rebuildBranch(s, created, bd);
        }

        String importedLock = Json.str(data, "lockFingerprint");
        if (importedLock != null && !importedLock.equals(s.lockFingerprint)) {
            throw new ApiException(400, "lock fingerprint mismatch on import");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static void rebuildBranch(Session s, Branch branch, Map<String, Object> stored) {
        List<Map<String, Object>> storedSteps = new ArrayList<>();
        for (Object rawStep : Json.list(stored, "steps")) {
            storedSteps.add((Map<String, Object>) rawStep);
        }
        branch.steps.clear();
        for (Map<String, Object> prefix : storedSteps) {
            if (Json.lng(prefix, "index", 0L) < branch.ancestorStepIndex) {
                branch.steps.add(prefix);
            }
        }
        while (branch.engine.hasNext()) {
            branch.steps.add(branch.engine.stepOnce());
        }
        String storedTrace = null;
        for (Object rawStep : Json.list(stored, "steps")) {
            Map<String, Object> st = (Map<String, Object>) rawStep;
            storedTrace = Json.str(st, "traceHash");
        }
        if (storedTrace != null && !storedTrace.equals(branch.engine.getTraceHash())) {
            throw new ApiException(400,
                    "import verification failed for branch " + branch.id
                            + ": replayed trajectory hash " + branch.engine.getTraceHash().substring(0, 12)
                            + " does not match exported hash " + storedTrace.substring(0, 12));
        }
        for (Object rawCp : Json.list(stored, "checkpoints")) {
            Map<String, Object> cpMap = (Map<String, Object>) rawCp;
            Branch.Checkpoint cp = new Branch.Checkpoint();
            cp.id = Json.str(cpMap, "id");
            cp.label = Json.str(cpMap, "label");
            cp.stepIndex = Json.lng(cpMap, "stepIndex", 0L);
            cp.traceHash = Json.str(cpMap, "traceHash");
            cp.definitionFingerprint = Json.str(cpMap, "definitionFingerprint");
            cp.definitionVersion = Json.str(cpMap, "definitionVersion");
            cp.parentCheckpointId = Json.str(cpMap, "parentCheckpointId");
            // Snapshot is rebuilt from engine at the recorded step boundary.
            Branch.Checkpoint existing = findCheckpoint(s, cp.id);
            if (existing != null) {
                continue;
            }
            long target = cp.stepIndex;
            ReplayEngine replay = ReplayEngine.fresh(s.definition,
                    EventLog.fromMaps(rawEvents(branch.externalEvents)));
            if (branch.ancestorStepIndex > 0) {
                Branch.Checkpoint ancestor = findCheckpoint(s, branch.baseCheckpointId);
                if (ancestor != null) {
                    replay.restore(ancestor.snapshot);
                }
            }
            while (replay.getStepIndex() < target && replay.hasNext()) {
                replay.stepOnce();
            }
            cp.snapshot = replay.snapshot();
            if (!cp.traceHash.equals(cp.snapshot.traceHash)) {
                throw new ApiException(400,
                        "checkpoint " + cp.id + " trajectory hash mismatch on import");
            }
            branch.checkpoints.add(cp);
        }
    }

    private static Branch.Checkpoint findCheckpoint(Session s, String id) {
        for (Branch b : s.branches.values()) {
            for (Branch.Checkpoint cp : b.checkpoints) {
                if (cp.id.equals(id)) {
                    return cp;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rawEvents(List<Map<String, Object>> events) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> e : events) {
            out.add(new LinkedHashMap<>(e));
        }
        return out;
    }

    public static final class ApiException extends RuntimeException {
        private final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
