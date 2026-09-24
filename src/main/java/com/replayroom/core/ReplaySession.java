package com.replayroom.core;

import com.replayroom.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * A replay session locks the definition version, initial state, imported
 * event log and random seed into one stable fingerprint. It owns branches
 * and checkpoints and implements checkpoint validation and branch merging.
 */
public class ReplaySession {
    public String id;
    public String name;
    public MachineDefinition definition;
    /** Locks definition + initial state + event content + seed. */
    public String fingerprint;
    /** Locks the definition only; checkpoints are validated against it. */
    public String definitionFingerprint;
    public List<LogEvent> externalLog = new ArrayList<>();
    public Map<String, LogEvent> eventRegistry = new LinkedHashMap<>();
    public Map<String, Branch> branches = new LinkedHashMap<>();
    public String currentBranchId;
    public Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();
    public long eventCounter;
    public long branchCounter;
    public long checkpointCounter;

    public static ReplaySession create(String id, String name, MachineDefinition definition,
                                       List<LogEvent> events) {
        ReplaySession session = new ReplaySession();
        session.id = id;
        session.name = name == null || name.isBlank() ? "session" : name;
        session.definition = definition;
        session.definitionFingerprint = Json.fingerprint(definition);
        session.externalLog = new ArrayList<>();
        for (LogEvent event : events) {
            session.registerEvent(event);
            session.externalLog.add(event);
        }
        Engine engine = new Engine(definition);
        session.externalLog.sort(engine.externalOrder());
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("definition", definition);
        lock.put("eventLog", session.externalLog);
        session.fingerprint = Json.fingerprint(lock);

        Branch main = new Branch();
        main.id = "b1";
        main.name = "main";
        main.parentBranchId = null;
        main.forkStep = 0;
        main.snapshot = initialSnapshot(definition);
        main.pendingExternal = session.externalLog.stream().map(LogEvent::copy).toList();
        session.branchCounter = 1;
        session.branches.put(main.id, main);
        session.currentBranchId = main.id;
        return session;
    }

    public static StateSnapshot initialSnapshot(MachineDefinition definition) {
        StateSnapshot snapshot = new StateSnapshot();
        snapshot.state = definition.initialState;
        snapshot.variables = new LinkedHashMap<>(definition.initialVariables);
        snapshot.rngState = definition.seed;
        return snapshot;
    }

    private void registerEvent(LogEvent event) {
        if (event.id == null || event.id.isBlank()) {
            event.id = "E" + (++eventCounter);
        } else {
            try {
                eventCounter = Math.max(eventCounter, Long.parseLong(event.id.replaceAll("\\D", "")));
            } catch (NumberFormatException ignored) {
                // non-numeric id, counter untouched
            }
        }
        event.internal = false;
        if (event.args == null) {
            event.args = new LinkedHashMap<>();
        }
        eventRegistry.put(event.id, event);
    }

    public Engine engine() {
        return new Engine(definition);
    }

    public Branch currentBranch() {
        return branches.get(currentBranchId);
    }

    public Branch branch(String id) {
        Branch branch = branches.get(id);
        if (branch == null) {
            throw new IllegalArgumentException("unknown branch: " + id);
        }
        return branch;
    }

    /** Append external events to one branch's pending queue (default: current). */
    public List<LogEvent> addEvents(List<LogEvent> events, String branchId) {
        Branch branch = branchId == null ? currentBranch() : branch(branchId);
        List<LogEvent> added = new ArrayList<>();
        for (LogEvent event : events) {
            LogEvent copy = event.copy();
            registerEvent(copy);
            externalLog.add(copy);
            added.add(copy);
            branch.pendingExternal.add(copy);
        }
        branch.pendingExternal.sort(engine().externalOrder());
        return added;
    }

    public TraceRecord step(String branchId) {
        Branch branch = branchId == null ? currentBranch() : branch(branchId);
        return engine().step(branch);
    }

    public List<TraceRecord> runToEnd(String branchId, long maxSteps) {
        Branch branch = branchId == null ? currentBranch() : branch(branchId);
        return engine().runToEnd(branch, maxSteps);
    }

    public String traceHash(Branch branch) {
        return Json.fingerprint(branch.trace);
    }

    public Map<String, String> traceHashes() {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Map.Entry<String, Branch> entry : branches.entrySet()) {
            hashes.put(entry.getKey(), traceHash(entry.getValue()));
        }
        return hashes;
    }

    // ---------------- checkpoints ----------------

    public Checkpoint checkpoint(String name, String branchId) {
        Branch branch = branchId == null ? currentBranch() : branch(branchId);
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.id = "c" + (++checkpointCounter);
        checkpoint.name = name == null || name.isBlank() ? checkpoint.id : name;
        checkpoint.branchId = branch.id;
        checkpoint.step = branch.trace.size();
        checkpoint.definitionFingerprint = definitionFingerprint;
        checkpoint.snapshot = branch.snapshot.copy();
        checkpoint.internalQueue = branch.internalQueue.stream().map(LogEvent::copy).toList();
        checkpoint.pendingExternal = branch.pendingExternal.stream().map(LogEvent::copy).toList();
        checkpoint.outputs = new ArrayList<>(branch.outputs);
        checkpoint.traceHash = traceHash(branch);
        checkpoints.put(checkpoint.id, checkpoint);
        return checkpoint;
    }

    /** Fork a new branch from a checkpoint. Rejects checkpoints created under another definition. */
    public Branch fork(String checkpointId, String name) {
        Checkpoint checkpoint = checkpoints.get(checkpointId);
        if (checkpoint == null) {
            throw new IllegalArgumentException("unknown checkpoint: " + checkpointId);
        }
        if (!checkpoint.definitionFingerprint.equals(definitionFingerprint)) {
            throw new CheckpointMismatchException(
                    "checkpoint " + checkpoint.id + " was created under definition fingerprint "
                            + checkpoint.definitionFingerprint + " but the session definition is now "
                            + definitionFingerprint);
        }
        Branch fork = new Branch();
        fork.id = "b" + (++branchCounter);
        fork.name = name == null || name.isBlank() ? fork.id : name;
        fork.parentBranchId = checkpoint.branchId;
        fork.forkStep = checkpoint.step;
        Branch parent = branches.get(checkpoint.branchId);
        if (parent != null) {
            fork.trace = new ArrayList<>(parent.trace.subList(0, (int) checkpoint.step));
        }
        fork.snapshot = checkpoint.snapshot.copy();
        fork.internalQueue = checkpoint.internalQueue.stream().map(LogEvent::copy).toList();
        fork.pendingExternal = checkpoint.pendingExternal.stream().map(LogEvent::copy).toList();
        fork.outputs = new ArrayList<>(checkpoint.outputs);
        branches.put(fork.id, fork);
        currentBranchId = fork.id;
        return fork;
    }

    /** Replace the definition; recomputes fingerprints so old checkpoints stop validating. */
    public void updateDefinition(MachineDefinition newDefinition) {
        this.definition = newDefinition;
        this.definitionFingerprint = Json.fingerprint(newDefinition);
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("definition", newDefinition);
        lock.put("eventLog", externalLog);
        this.fingerprint = Json.fingerprint(lock);
    }

    // ---------------- merge ----------------

    /**
     * Merge {@code sourceId} into a new branch based on {@code targetId}.
     * The final state is never copied across: the merged branch is re-replayed
     * from the initial snapshot with the union of both sides' external events.
     * Allowed only when the external events both sides consumed since their
     * common ancestor are compatible and unambiguously ordered.
     */
    public MergeResult merge(String sourceId, String targetId) {
        Branch source = branch(sourceId);
        Branch target = branch(targetId);

        List<LogEvent> sourceSeq = externalSequence(source);
        List<LogEvent> targetSeq = externalSequence(target);

        int divergence = 0;
        while (divergence < sourceSeq.size() && divergence < targetSeq.size()
                && sourceSeq.get(divergence).id.equals(targetSeq.get(divergence).id)) {
            divergence++;
        }
        List<LogEvent> sourceOnly = sourceSeq.subList(divergence, sourceSeq.size());
        List<LogEvent> targetOnly = targetSeq.subList(divergence, targetSeq.size());

        // 1) Same event id with different content on the two sides.
        Map<String, LogEvent> sourceById = new LinkedHashMap<>();
        for (LogEvent e : sourceOnly) {
            sourceById.put(e.id, e);
        }
        for (LogEvent t : targetOnly) {
            LogEvent s = sourceById.get(t.id);
            if (s != null && !Json.canonical(s).equals(Json.canonical(t))) {
                return MergeResult.conflict("same event id with different content", s, t);
            }
        }

        // 2) Common events must appear in the same relative order.
        List<String> commonInSource = new ArrayList<>();
        for (LogEvent e : sourceOnly) {
            commonInSource.add(e.id);
        }
        List<String> commonInTarget = new ArrayList<>();
        for (LogEvent e : targetOnly) {
            if (sourceById.containsKey(e.id)) {
                commonInTarget.add(e.id);
            }
        }
        commonInSource.retainAll(new LinkedHashSet<>(commonInTarget));
        if (!commonInSource.equals(commonInTarget)) {
            LogEvent first = null;
            LogEvent second = null;
            outer:
            for (int i = 0; i < commonInSource.size(); i++) {
                for (int j = i + 1; j < commonInSource.size(); j++) {
                    String a = commonInSource.get(i);
                    String b = commonInSource.get(j);
                    int ia = commonInTarget.indexOf(a);
                    int ib = commonInTarget.indexOf(b);
                    if (ia > ib) {
                        first = sourceById.get(a);
                        second = sourceById.get(b);
                        break outer;
                    }
                }
            }
            return MergeResult.conflict("common events applied in different relative order", first, second);
        }

        // 3) Events unique to each side must not share an identical ordering key.
        Engine engine = engine();
        var comparator = engine.externalOrder();
        for (LogEvent t : targetOnly) {
            if (sourceById.containsKey(t.id)) {
                continue;
            }
            for (LogEvent s : sourceOnly) {
                if (s.id.equals(t.id)) {
                    continue;
                }
                if (s.time == t.time && definition.priorityOf(s.source) == definition.priorityOf(t.source)
                        && s.source.equals(t.source) && s.seq == t.seq) {
                    return MergeResult.conflict(
                            "events with identical ordering key (time, source priority, seq)", s, t);
                }
            }
        }

        // Compatible: union of both full external sequences, deterministically ordered.
        Map<String, LogEvent> union = new LinkedHashMap<>();
        for (LogEvent e : targetSeq) {
            union.put(e.id, e);
        }
        for (LogEvent e : sourceSeq) {
            union.putIfAbsent(e.id, e);
        }
        List<LogEvent> mergedEvents = new ArrayList<>(union.values());
        mergedEvents.sort(comparator);

        Branch merged = new Branch();
        merged.id = "b" + (++branchCounter);
        merged.name = "merge:" + source.name + "->" + target.name;
        merged.parentBranchId = target.id;
        merged.forkStep = divergence;
        merged.snapshot = initialSnapshot(definition);
        merged.pendingExternal = mergedEvents.stream().map(LogEvent::copy).toList();
        engine().runToEnd(merged, Engine.MAX_STEPS);
        branches.put(merged.id, merged);
        return MergeResult.merged(merged, divergence);
    }

    /** External events consumed by the branch (from its trace) plus the ones still pending. */
    public List<LogEvent> externalSequence(Branch branch) {
        List<LogEvent> sequence = new ArrayList<>();
        for (TraceRecord record : branch.trace) {
            if (!record.internal) {
                LogEvent event = eventRegistry.get(record.eventId);
                if (event != null) {
                    sequence.add(event);
                }
            }
        }
        sequence.addAll(branch.pendingExternal);
        return sequence;
    }

    public static class MergeResult {
        public boolean ok;
        public String reason;
        public LogEvent conflictA;
        public LogEvent conflictB;
        public Branch branch;
        public long divergenceStep;

        static MergeResult conflict(String reason, LogEvent a, LogEvent b) {
            MergeResult result = new MergeResult();
            result.ok = false;
            result.reason = reason;
            result.conflictA = a;
            result.conflictB = b;
            return result;
        }

        static MergeResult merged(Branch branch, long divergenceStep) {
            MergeResult result = new MergeResult();
            result.ok = true;
            result.branch = branch;
            result.divergenceStep = divergenceStep;
            return result;
        }
    }

    public static class CheckpointMismatchException extends RuntimeException {
        public CheckpointMismatchException(String message) {
            super(message);
        }
    }
}
