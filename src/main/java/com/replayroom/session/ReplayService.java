package com.replayroom.session;

import com.replayroom.engine.Definition;
import com.replayroom.engine.Engine;
import com.replayroom.engine.Frame;
import com.replayroom.engine.StepResult;
import com.replayroom.json.Json;
import com.replayroom.model.Events;
import com.replayroom.model.ModelAccess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application service owning all replay operations. Sessions live in memory
 * for fast access and are persisted after every mutating operation, so a
 * process restart resumes exactly where the user left off.
 */
public final class ReplayService {

    private final Store store;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Definition> definitions = new ConcurrentHashMap<>();

    public ReplayService(Store store) {
        this.store = store;
        for (Session session : store.loadAllSessions()) {
            sessions.put(session.id, session);
            definitions.computeIfAbsent(session.definitionFingerprint,
                    key -> Definition.parse(session.definition));
        }
    }

    public Store store() {
        return store;
    }

    // ------------------------------------------------------------------
    // Definitions
    // ------------------------------------------------------------------

    public Definition registerDefinition(Map<String, Object> document) {
        Definition definition = Definition.parse(document);
        definitions.put(definition.fingerprint(), definition);
        store.saveDefinition(definition.fingerprint(), document);
        return definition;
    }

    public Definition definition(String fingerprint) {
        Definition definition = definitions.get(fingerprint);
        if (definition == null) {
            throw new IllegalArgumentException("unknown definition " + fingerprint);
        }
        return definition;
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    public Session createSession(Map<String, Object> request) {
        Object definitionObject = request.get("definition");
        if (!(definitionObject instanceof Map)) {
            throw new IllegalArgumentException("createSession requires a definition object");
        }
        Definition definition = registerDefinition(ModelAccess.asObject(definitionObject));
        List<Map<String, Object>> events = validateEvents(
                ModelAccess.objectListField(ModelAccess.asObject(request), "events"));
        checkUniqueEventIds(events);

        Session session = new Session();
        session.id = idOrGenerate(request.get("id"), Ids::session);
        session.name = stringOrDefault(request.get("name"), "未命名会话");
        session.definition = definition.raw();
        session.definitionFingerprint = definition.fingerprint();
        session.lockedSeed = request.get("seed") instanceof Number number
                ? number.longValue() : definition.seed();
        session.createdAt = LogicalClock.now();
        session.updatedAt = session.createdAt;

        Branch main = new Branch();
        main.id = Ids.branch();
        main.name = "main";
        main.definitionFingerprint = definition.fingerprint();
        main.kind = "root";
        main.origin = null;
        main.externalPool = copyEvents(events);
        main.queue = copyEvents(events);
        main.queue.sort(Events.externalComparator(definition.sources()));
        main.currentState = definition.initialState();
        main.currentData = copyData(definition.initialData());
        main.rngState = session.lockedSeed;
        main.rootHash = Steps.rootHash(definition.fingerprint(),
                definition.initialState(), definition.initialData(), session.lockedSeed);
        main.traceHash = main.rootHash;
        main.internalCounter = 0;
        main.logicalClock = 0;
        main.finished = main.queue.isEmpty();
        session.branches.add(main);

        persist(session);
        return session;
    }

    public List<Session> listSessions() {
        return new ArrayList<>(sessions.values());
    }

    public Session requireSession(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        return session;
    }

    public Branch requireBranch(Session session, String branchId) {
        for (Branch branch : session.branches) {
            if (branch.id.equals(branchId)) {
                return branch;
            }
        }
        throw new NotFoundException("branch not found: " + branchId);
    }

    public Checkpoint requireCheckpoint(Session session, String checkpointId) {
        Checkpoint checkpoint = session.checkpoints.get(checkpointId);
        if (checkpoint == null) {
            throw new NotFoundException("checkpoint not found: " + checkpointId);
        }
        return checkpoint;
    }

    // ------------------------------------------------------------------
    // Stepping
    // ------------------------------------------------------------------

    public synchronized Map<String, Object> step(Session session, String branchId, int count) {
        Branch branch = requireBranch(session, branchId);
        Definition definition = definition(session.definitionFingerprint);
        Engine engine = new Engine(definition);
        Frame frame = new Frame(branch.currentState, branch.currentData, session.lockedSeed);
        frame.rng().restore(branch.rngState);

        int executed = 0;
        Map<String, Object> lastStep = null;
        while (executed < count && !branch.queue.isEmpty()) {
            Map<String, Object> event = branch.queue.remove(0);
            branch.internalCounter++;
            StepResult result = engine.step(frame, event, branch.internalCounter);

            List<Map<String, Object>> emitted = new ArrayList<>(result.emittedInternal);
            emitted.sort(internalEventComparator());
            // Actions' internal events can only run after the current event:
            // they are pushed to the head of the queue in deterministic order.
            for (int i = emitted.size() - 1; i >= 0; i--) {
                branch.queue.add(0, copyEvent(emitted.get(i)));
            }

            Map<String, Object> record = Steps.buildRecord(result, branch.traceHash);
            record.put("stepIndex", branch.steps.size());
            record.put("clock", ++branch.logicalClock);
            branch.steps.add(record);
            branch.traceHash = (String) record.get("hash");
            branch.currentState = frame.state();
            branch.currentData = copyData(frame.data());
            branch.rngState = frame.rng().state();
            branch.finished = branch.queue.isEmpty();

            lastStep = stepView(session, branch, record, definition);
            executed++;
        }

        autoCheckpoint(session, branch, executed > 0 ? "step-" + branch.steps.size() : null);
        persist(session);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("executed", executed);
        response.put("finished", branch.finished);
        response.put("pending", branch.queue.size());
        response.put("traceHash", branch.traceHash);
        response.put("lastStep", lastStep);
        return response;
    }

    private Comparator<Map<String, Object>> internalEventComparator() {
        return Comparator.comparingLong(Events::time)
                .thenComparing(event -> String.valueOf(event.get("type")))
                .thenComparingLong(Events::seq);
    }

    private Map<String, Object> stepView(Session session, Branch branch,
                                         Map<String, Object> record, Definition definition) {
        Map<String, Object> view = new LinkedHashMap<>(record);
        Object before = record.get("dataBefore");
        Object after = record.get("dataAfter");
        if (before instanceof Map && after instanceof Map) {
            view.put("diff", Diff.between(ModelAccess.asObject(before), ModelAccess.asObject(after)));
        }
        return view;
    }

    // ------------------------------------------------------------------
    // Checkpoints
    // ------------------------------------------------------------------

    public Checkpoint createCheckpoint(Session session, String branchId, String requestedName) {
        Branch branch = requireBranch(session, branchId);
        Checkpoint checkpoint = checkpointAt(session, branch, requestedName, true);
        persist(session);
        return checkpoint;
    }

    private Checkpoint autoCheckpoint(Session session, Branch branch, String name) {
        return checkpointAt(session, branch, name, false);
    }

    private Checkpoint checkpointAt(Session session, Branch branch, String requestedName,
                                    boolean explicit) {
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.id = Ids.checkpoint();
        checkpoint.name = requestedName == null
                ? "auto@" + branch.steps.size()
                : requestedName;
        checkpoint.branchId = branch.id;
        checkpoint.stepIndex = branch.steps.size();
        checkpoint.definitionFingerprint = session.definitionFingerprint;
        checkpoint.state = branch.currentState;
        checkpoint.data = copyData(branch.currentData);
        checkpoint.rngState = branch.rngState;
        checkpoint.traceHash = branch.traceHash;
        checkpoint.pending = copyEvents(branch.queue);
        Map<String, Object> frameMaterial = new LinkedHashMap<>();
        frameMaterial.put("state", checkpoint.state);
        frameMaterial.put("data", checkpoint.data);
        frameMaterial.put("rngState", checkpoint.rngState);
        checkpoint.frameHash = Json.fingerprint(frameMaterial);
        checkpoint.createdAt = LogicalClock.now();

        session.checkpoints.put(checkpoint.id, checkpoint);
        if (explicit) {
            branch.checkpointIds.add(checkpoint.id);
        }
        return checkpoint;
    }

    public synchronized Branch fork(Session session, String checkpointId,
                                    Map<String, Object> request) {
        Checkpoint checkpoint = requireCheckpoint(session, checkpointId);
        if (!checkpoint.definitionFingerprint.equals(session.definitionFingerprint)) {
            throw new VersionMismatchException(
                    "checkpoint " + checkpoint.id + " was created with definition "
                            + checkpoint.definitionFingerprint + " but session now uses "
                            + session.definitionFingerprint);
        }
        Branch originBranch = requireBranch(session, checkpoint.branchId);
        ensureCheckpointStillReachable(originBranch, checkpoint);

        Definition definition = definition(session.definitionFingerprint);
        List<Map<String, Object>> extraEvents = copyEvents(
                ModelAccess.objectListField(
                        request instanceof Map ? ModelAccess.asObject(request) : Map.of(),
                        "events"));
        checkUniqueEventIds(extraEvents);
        Set<String> existingIds = collectExternalIds(originBranch.externalPool);
        for (Map<String, Object> event : extraEvents) {
            if (!existingIds.add(Events.id(event))) {
                throw new IllegalArgumentException("event id '" + Events.id(event)
                        + "' already exists on the origin branch");
            }
        }

        Branch branch = new Branch();
        branch.id = Ids.branch();
        branch.name = request instanceof Map && request.get("name") instanceof String text
                ? text : "branch-" + session.branches.size();
        branch.definitionFingerprint = session.definitionFingerprint;
        branch.kind = "fork";
        branch.origin = originRef(checkpoint.branchId, checkpoint.stepIndex);

        List<Map<String, Object>> processedExternal = externalEventsFromSteps(
                originBranch.steps.subList(0, Math.min(checkpoint.stepIndex, originBranch.steps.size())));
        List<Map<String, Object>> pool = new ArrayList<>();
        pool.addAll(processedExternal);
        pool.addAll(copyEvents(checkpoint.pending));
        pool.addAll(extraEvents);
        checkUniqueEventIds(pool);
        pool.sort(Events.externalComparator(definition.sources()));
        branch.externalPool = pool;

        branch.queue = copyEvents(checkpoint.pending);
        branch.queue.addAll(extraEvents);
        branch.queue.sort(queueComparator(definition));

        branch.steps = copySteps(originBranch.steps.subList(0, checkpoint.stepIndex));
        branch.currentState = checkpoint.state;
        branch.currentData = copyData(checkpoint.data);
        branch.rngState = checkpoint.rngState;
        branch.traceHash = checkpoint.traceHash;
        branch.rootHash = originBranch.rootHash;
        branch.internalCounter = countInternalSteps(branch.steps);
        branch.logicalClock = branch.steps.size();
        branch.finished = branch.queue.isEmpty();
        session.branches.add(branch);

        persist(session);
        return branch;
    }

    public synchronized Branch reset(Session session, String branchId, String checkpointId) {
        Branch branch = requireBranch(session, branchId);
        Checkpoint checkpoint = requireCheckpoint(session, checkpointId);
        if (!checkpoint.branchId.equals(branch.id)) {
            throw new IllegalArgumentException(
                    "checkpoint " + checkpointId + " belongs to another branch");
        }
        if (!checkpoint.definitionFingerprint.equals(session.definitionFingerprint)) {
            throw new VersionMismatchException(
                    "checkpoint " + checkpointId + " was created with definition "
                            + checkpoint.definitionFingerprint + " but session now uses "
                            + session.definitionFingerprint);
        }
        if (checkpoint.stepIndex > branch.steps.size()) {
            throw new IllegalArgumentException("checkpoint is ahead of the branch tip");
        }
        for (Branch other : session.branches) {
            if (other == branch || other.origin == null) {
                continue;
            }
            String originBranchId = String.valueOf(other.origin.get("branchId"));
            int originStep = ((Number) other.origin.getOrDefault("stepIndex", 0)).intValue();
            if (originBranchId.equals(branch.id) && originStep > checkpoint.stepIndex) {
                throw new ConflictException(
                        "reset would remove steps still referenced by branch '" + other.name + "'",
                        Map.of("blockedBy", other.id, "blockedByName", other.name,
                                "requiredStepIndex", originStep,
                                "checkpointStepIndex", checkpoint.stepIndex));
            }
        }

        branch.steps = copySteps(branch.steps.subList(0, checkpoint.stepIndex));
        branch.queue = copyEvents(checkpoint.pending);
        branch.currentState = checkpoint.state;
        branch.currentData = copyData(checkpoint.data);
        branch.rngState = checkpoint.rngState;
        branch.traceHash = checkpoint.traceHash;
        branch.internalCounter = countInternalSteps(branch.steps);
        branch.logicalClock = branch.steps.size();
        branch.finished = branch.queue.isEmpty();

        List<String> retained = new ArrayList<>();
        for (String candidate : branch.checkpointIds) {
            Checkpoint item = session.checkpoints.get(candidate);
            if (item != null && item.stepIndex <= checkpoint.stepIndex) {
                retained.add(candidate);
            } else if (item != null) {
                session.checkpoints.remove(candidate);
            }
        }
        branch.checkpointIds = retained;

        persist(session);
        return branch;
    }

    private void ensureCheckpointStillReachable(Branch branch, Checkpoint checkpoint) {
        boolean reachable = checkpoint.stepIndex <= branch.steps.size();
        for (Map<String, Object> step : branch.steps) {
            if (((Number) step.getOrDefault("stepIndex", -1)).intValue() == checkpoint.stepIndex
                    && checkpoint.stepIndex == 0) {
                reachable = true;
            }
        }
        if (!reachable) {
            throw new ConflictException(
                    "checkpoint '" + checkpoint.name + "' has been truncated from its branch",
                    Map.of("checkpointId", checkpoint.id, "branchId", branch.id));
        }
    }

    // ------------------------------------------------------------------
    // Branch comparison and merging
    // ------------------------------------------------------------------

    public Map<String, Object> compare(Session session, String branchAId, String branchBId) {
        Branch branchA = requireBranch(session, branchAId);
        Branch branchB = requireBranch(session, branchBId);
        Map<String, Object> stateDiff = Diff.between(branchA.currentData, branchB.currentData);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branchA", summary(branchA));
        result.put("branchB", summary(branchB));
        result.put("stateChanged",
                !Json.canonical(branchA.currentData).equals(Json.canonical(branchB.currentData))
                        || !branchA.currentState.equals(branchB.currentState));
        result.put("stateDiff", stateDiff);
        result.put("outputsA", collectOutputs(branchA.steps));
        result.put("outputsB", collectOutputs(branchB.steps));
        result.put("traceHashA", branchA.traceHash);
        result.put("traceHashB", branchB.traceHash);
        return result;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> merge(Session session, String branchAId,
                                                  String branchBId, Map<String, Object> request) {
        Branch branchA = requireBranch(session, branchAId);
        Branch branchB = requireBranch(session, branchBId);
        if (branchA == branchB) {
            throw new IllegalArgumentException("cannot merge a branch with itself");
        }
        Definition definition = definition(session.definitionFingerprint);

        List<ChainNode> chainA = chainNodes(session, ancestorChain(session, branchA));
        List<ChainNode> chainB = chainNodes(session, ancestorChain(session, branchB));
        ChainNode lca = lowestCommonAncestor(chainA, chainB);

        Map<String, Map<String, Object>> externalA =
                suffixExternalEvents(session, chainA, lca);
        Map<String, Map<String, Object>> externalB =
                suffixExternalEvents(session, chainB, lca);

        MergePlan plan = planMerge(definition, externalA, externalB);

        Checkpoint checkpoint = checkpointAtNode(session, definition, lca);

        Branch merged = new Branch();
        merged.id = Ids.branch();
        merged.name = request != null && request.get("name") instanceof String text
                ? text : "merge(" + branchA.name + "," + branchB.name + ")";
        merged.definitionFingerprint = session.definitionFingerprint;
        merged.kind = "merged";
        merged.origin = lca == null ? null : originRef(lca.branchId, lca.stepIndex);
        merged.rootHash = (lca == null || lca.stepIndex == 0)
                && checkpoint.traceHash == null
                ? branchA.rootHash : (lca == null ? branchA.rootHash : rootHashOf(session, lca));

        List<Map<String, Object>> processedAtAncestor = lca == null ? List.of()
                : externalEventsFromSteps(requireBranch(session, lca.branchId).steps
                        .subList(0, Math.min(lca.stepIndex,
                                requireBranch(session, lca.branchId).steps.size())));
        List<Map<String, Object>> pool = copyEvents(processedAtAncestor);
        pool.addAll(copyEvents(plan.ordered));
        checkUniqueEventIds(pool);
        pool.sort(Events.externalComparator(definition.sources()));
        merged.externalPool = pool;

        merged.queue = copyEvents(plan.ordered);
        merged.queue.sort(queueComparator(definition));
        merged.steps = lca == null ? new ArrayList<>()
                : copySteps(requireBranch(session, lca.branchId).steps
                        .subList(0, Math.min(lca.stepIndex,
                                requireBranch(session, lca.branchId).steps.size())));
        merged.currentState = checkpoint.state;
        merged.currentData = copyData(checkpoint.data);
        merged.rngState = checkpoint.rngState;
        merged.traceHash = checkpoint.traceHash;
        merged.internalCounter = countInternalSteps(merged.steps);
        merged.logicalClock = merged.steps.size();
        merged.finished = merged.queue.isEmpty();
        session.branches.add(merged);

        Frame frame = new Frame(merged.currentState, merged.currentData, session.lockedSeed);
        frame.rng().restore(merged.rngState);
        Engine engine = new Engine(definition);
        while (!merged.queue.isEmpty()) {
            Map<String, Object> event = merged.queue.remove(0);
            merged.internalCounter++;
            StepResult result = engine.step(frame, event, merged.internalCounter);
            List<Map<String, Object>> emitted = new ArrayList<>(result.emittedInternal);
            emitted.sort(internalEventComparator());
            for (int i = emitted.size() - 1; i >= 0; i--) {
                merged.queue.add(0, copyEvent(emitted.get(i)));
            }
            Map<String, Object> record = Steps.buildRecord(result, merged.traceHash);
            record.put("stepIndex", merged.steps.size());
            record.put("clock", ++merged.logicalClock);
            merged.steps.add(record);
            merged.traceHash = (String) record.get("hash");
            merged.currentState = frame.state();
            merged.currentData = copyData(frame.data());
            merged.rngState = frame.rng().state();
        }
        merged.finished = true;
        autoCheckpoint(session, merged, "merge@" + merged.steps.size());
        persist(session);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mergedBranchId", merged.id);
        response.put("mergedBranchName", merged.name);
        response.put("ancestor", lca == null ? null : originRef(lca.branchId, lca.stepIndex));
        response.put("eventsFromA", idsOf(plan.fromA));
        response.put("eventsFromB", idsOf(plan.fromB));
        response.put("orderedEvents", summarizeEvents(plan.ordered));
        response.put("traceHash", merged.traceHash);
        response.put("state", merged.currentState);
        response.put("data", merged.currentData);
        response.put("outputs", collectOutputs(merged.steps));
        return response;
    }

    private String rootHashOf(Session session, ChainNode lca) {
        return requireBranch(session, lca.branchId).rootHash;
    }

    /**
     * Two-pointer LCA over interval segments: each chain node covers global
     * step indexes [segStart, stepIndex] on its branch. Equal (branchId,
     * stepIndex) nodes are the only valid common points.
     */
    private ChainNode lowestCommonAncestor(List<ChainNode> chainA, List<ChainNode> chainB) {
        int prefix = 0;
        int limit = Math.min(chainA.size(), chainB.size());
        while (prefix < limit && chainA.get(prefix).ref.equals(chainB.get(prefix).ref)) {
            prefix++;
        }
        if (prefix == 0) {
            return null;
        }
        ChainNode lastCommon = chainA.get(prefix - 1);

        // One side may continue inside a branch that is itself an ancestor of
        // the other side's next node (e.g. A advanced on main after B forked).
        // Walk each divergent tail backward; the first node found on the
        // other chain extends the common ancestry.
        if (prefix < chainA.size()) {
            for (int i = chainA.size() - 1; i >= prefix; i--) {
                ChainNode candidate = chainA.get(i);
                for (int k = prefix - 1; k < chainB.size(); k++) {
                    if (chainB.get(k).ref.equals(candidate.ref)) {
                        return candidate;
                    }
                }
            }
        }
        if (prefix < chainB.size()) {
            for (int i = chainB.size() - 1; i >= prefix; i--) {
                ChainNode candidate = chainB.get(i);
                for (int k = prefix - 1; k < chainA.size(); k++) {
                    if (chainA.get(k).ref.equals(candidate.ref)) {
                        return candidate;
                    }
                }
            }
        }
        return lastCommon;
    }

    private List<ChainNode> chainNodes(Session session, List<Map<String, Object>> refs) {
        List<ChainNode> nodes = new ArrayList<>();
        for (Map<String, Object> ref : refs) {
            ChainNode node = new ChainNode();
            node.branchId = String.valueOf(ref.get("branchId"));
            node.stepIndex = ((Number) ref.getOrDefault("stepIndex", 0)).intValue();
            node.ref = ref;
            nodes.add(node);
        }
        return nodes;
    }

    private static final class ChainNode {
        String branchId;
        int stepIndex;
        Map<String, Object> ref;
    }

    private Map<String, Map<String, Object>> suffixExternalEvents(
            Session session, List<ChainNode> chain, ChainNode lca) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        int lcaIndex = lca == null ? -1 : chain.indexOf(lca);
        for (int i = lcaIndex + 1; i < chain.size(); i++) {
            ChainNode node = chain.get(i);
            Branch branch = requireBranch(session, node.branchId);
            int start = 0;
            if (branch.origin != null
                    && String.valueOf(branch.origin.get("branchId"))
                            .equals(i > 0 ? chain.get(Math.max(0, i - 1)).branchId : "")) {
                start = ((Number) branch.origin.getOrDefault("stepIndex", 0)).intValue();
            } else if (i > lcaIndex + 1 && chain.get(i - 1).branchId.equals(node.branchId)) {
                start = chain.get(i - 1).stepIndex;
            } else if (branch.origin != null) {
                start = ((Number) branch.origin.getOrDefault("stepIndex", 0)).intValue();
            }
            int end = Math.min(node.stepIndex, branch.steps.size());
            for (Map<String, Object> event : externalEventsFromSteps(branch.steps.subList(start, end))) {
                byId.putIfAbsent(Events.id(event), event);
            }
            // Events still pending anywhere on this side remain part of the
            // external set that must be compatible across branches.
            for (Map<String, Object> event : branch.externalPool) {
                byId.putIfAbsent(Events.id(event), copyEvent(event));
            }
        }
        return byId;
    }

    private Checkpoint checkpointAtNode(Session session, Definition definition, ChainNode node) {
        if (node == null || node.stepIndex == 0) {
            Checkpoint checkpoint = new Checkpoint();
            checkpoint.definitionFingerprint = definition.fingerprint();
            checkpoint.state = definition.initialState();
            checkpoint.data = copyData(definition.initialData());
            checkpoint.rngState = session.lockedSeed;
            checkpoint.traceHash = Steps.rootHash(definition.fingerprint(),
                    definition.initialState(), definition.initialData(), session.lockedSeed);
            checkpoint.pending = List.of();
            return checkpoint;
        }
        Branch branch = requireBranch(session, node.branchId);
        Map<String, Object> record = branch.steps.get(node.stepIndex - 1);
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.definitionFingerprint = definition.fingerprint();
        checkpoint.state = String.valueOf(record.get("stateAfter"));
        checkpoint.data = copyData(ModelAccess.asObject(record.get("dataAfter")));
        checkpoint.rngState = ((Number) record.get("rngAfter")).longValue();
        checkpoint.traceHash = String.valueOf(record.get("hash"));
        checkpoint.pending = List.of();
        return checkpoint;
    }

    private MergePlan planMerge(Definition definition,
                                Map<String, Map<String, Object>> eventsA,
                                Map<String, Map<String, Object>> eventsB) {
        Set<String> sharedIds = new LinkedHashSet<>(eventsA.keySet());
        sharedIds.retainAll(eventsB.keySet());

        List<Map<String, Object>> firstConflict = new ArrayList<>();
        for (String id : sharedIds) {
            if (!Json.canonical(eventsA.get(id)).equals(Json.canonical(eventsB.get(id)))) {
                Map<String, Object> conflict = new LinkedHashMap<>();
                conflict.put("reason", "same_external_event_id_different_content");
                conflict.put("eventId", id);
                conflict.put("eventA", eventsA.get(id));
                conflict.put("eventB", eventsB.get(id));
                firstConflict.add(conflict);
            }
        }

        List<Map<String, Object>> union = new ArrayList<>();
        Set<String> addedIds = new LinkedHashSet<>();
        for (Map<String, Object> event : eventsA.values()) {
            union.add(copyEvent(event));
            addedIds.add(Events.id(event));
        }
        for (Map<String, Object> event : eventsB.values()) {
            String id = Events.id(event);
            if (addedIds.add(id)) {
                union.add(copyEvent(event));
            }
        }
        Map<String, Object> sources = definition.sources();
        union.sort((left, right) -> {
            long timeCompare = Long.compare(Events.time(left), Events.time(right));
            if (timeCompare != 0) {
                return Long.signum(timeCompare);
            }
            long priorityCompare = Long.compare(
                    Events.sourcePriority(Events.source(left), sources),
                    Events.sourcePriority(Events.source(right), sources));
            if (priorityCompare != 0) {
                return Long.signum(priorityCompare);
            }
            long seqCompare = Long.compare(Events.seq(left), Events.seq(right));
            if (seqCompare != 0) {
                return Long.signum(seqCompare);
            }
            // Ordering is only unambiguous when identical time/priority/seq
            // refers to the same event. Distinct ids form the first
            // ambiguous conflict group.
            if (!Events.id(left).equals(Events.id(right))) {
                throw new ConflictException("external event order is ambiguous at logical time "
                        + Events.time(left), Map.of(
                        "reason", "ambiguous_same_time_order",
                        "logicalTime", Events.time(left),
                        "conflictEvents", List.of(stripForConflict(left), stripForConflict(right))));
            }
            return 0;
        });

        if (!firstConflict.isEmpty()) {
            throw new ConflictException("external event sets disagree on shared event content",
                    Map.of("reason", "incompatible_external_event_sets",
                            "conflictEvents", List.of(firstConflict.get(0))));
        }

        MergePlan plan = new MergePlan();
        plan.ordered = union;
        for (Map<String, Object> event : union) {
            String id = Events.id(event);
            if (eventsA.containsKey(id)) {
                plan.fromA.add(event);
            }
            if (eventsB.containsKey(id)) {
                plan.fromB.add(event);
            }
        }
        return plan;
    }

    private Map<String, Object> stripForConflict(Map<String, Object> event) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("id", event.get("id"));
        compact.put("type", event.get("type"));
        compact.put("time", event.get("time"));
        compact.put("source", event.get("source"));
        compact.put("seq", event.get("seq"));
        compact.put("data", event.get("data"));
        return compact;
    }

    // ------------------------------------------------------------------
    // Branch tree helpers
    // ------------------------------------------------------------------

    /**
     * Returns the node chain ending at the given branch, as a list of
     * {"branchId","stepIndex"} refs from the virtual root down. The last
     * element is the branch tip.
     */
    private List<Map<String, Object>> ancestorChain(Session session, Branch tip) {
        List<Map<String, Object>> reversed = new ArrayList<>();
        Branch current = tip;
        while (true) {
            reversed.add(originRef(current.id, current.steps.size()));
            if (current.origin == null) {
                break;
            }
            String parentId = String.valueOf(current.origin.get("branchId"));
            int forkStep = ((Number) current.origin.getOrDefault("stepIndex", 0)).intValue();
            Branch parent = requireBranch(session, parentId);
            reversed.add(originRef(parent.id, forkStep));
            if (forkStep == 0 && parent.origin == null) {
                break;
            }
            // Continue walking from the parent branch at its fork point:
            // treat it as a tip with exactly forkStep recorded steps.
            current = new BranchView(parent, forkStep);
        }
        java.util.Collections.reverse(reversed);
        List<Map<String, Object>> chain = new ArrayList<>();
        for (Map<String, Object> ref : reversed) {
            if (chain.isEmpty() || !chain.get(chain.size() - 1).equals(ref)) {
                chain.add(ref);
            }
        }
        return chain;
    }

    /** Read-only branch projected to an earlier step count for tree walking. */
    private static final class BranchView extends Branch {
        BranchView(Branch delegate, int projectedSteps) {
            this.id = delegate.id;
            this.name = delegate.name;
            this.definitionFingerprint = delegate.definitionFingerprint;
            this.origin = delegate.origin;
            this.kind = delegate.kind;
            this.externalPool = delegate.externalPool;
            this.queue = delegate.queue;
            this.steps = delegate.steps.subList(0, Math.min(projectedSteps, delegate.steps.size()));
            this.currentState = delegate.currentState;
            this.currentData = delegate.currentData;
            this.rngState = delegate.rngState;
            this.traceHash = delegate.traceHash;
            this.rootHash = delegate.rootHash;
            this.internalCounter = delegate.internalCounter;
            this.logicalClock = delegate.logicalClock;
            this.finished = delegate.finished;
            this.checkpointIds = delegate.checkpointIds;
        }
    }

    // ------------------------------------------------------------------
    // Export / import
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> exportSession(String sessionId) {
        Session session = requireSession(sessionId);
        session.updatedAt = LogicalClock.now();
        Map<String, Object> bundle = session.toMap();
        bundle.remove("exportHash");
        String exportHash = Json.fingerprint(bundle);
        bundle.put("exportHash", exportHash);
        session.exportHash = exportHash;
        persist(session);
        return bundle;
    }

    /**
     * Imports a previously exported session. The definition fingerprint,
     * every chained step hash and the final export hash are verified, and a
     * fresh deterministic replay of each branch proves the trajectory hash
     * is reproducible from (definition, initial state, events, seed).
     */
    public synchronized Session importSession(Map<String, Object> bundle, String requestedId) {
        Map<String, Object> material = new LinkedHashMap<>(bundle);
        String claimedExportHash = (String) material.remove("exportHash");
        if (claimedExportHash == null) {
            throw new IllegalArgumentException("export bundle is missing exportHash");
        }
        String recomputedExportHash = Json.fingerprint(material);
        if (!claimedExportHash.equals(recomputedExportHash)) {
            throw new IllegalArgumentException("export hash mismatch: bundle content was modified");
        }

        Session imported = Session.fromMap(bundle);
        Definition definition = registerDefinition(imported.definition);
        if (!definition.fingerprint().equals(imported.definitionFingerprint)) {
            throw new VersionMismatchException(
                    "embedded definition fingerprint does not match its content");
        }
        verifyTrajectoryChains(imported, definition);
        replayVerify(imported, definition);

        imported.id = requestedId == null ? imported.id : requestedId;
        if (sessions.containsKey(imported.id)) {
            throw new IllegalArgumentException("session id " + imported.id + " already exists");
        }
        sessions.put(imported.id, imported);
        persist(imported);
        return imported;
    }

    private void verifyTrajectoryChains(Session session, Definition definition) {
        for (Branch branch : session.branches) {
            if (!branch.definitionFingerprint.equals(definition.fingerprint())) {
                throw new VersionMismatchException("branch " + branch.id
                        + " references a different definition fingerprint");
            }
            String previousHash = branch.rootHash;
            for (Map<String, Object> record : branch.steps) {
                String claimedPrev = String.valueOf(record.get("prevHash"));
                if (!claimedPrev.equals(previousHash)) {
                    throw new IllegalArgumentException(
                            "trace chain broken in branch " + branch.id + " at step "
                                    + record.get("stepIndex"));
                }
                Map<String, Object> material = new LinkedHashMap<>(record);
                material.remove("hash");
                material.remove("stepIndex");
                material.remove("clock");
                String recomputed = Json.fingerprint(material);
                if (!recomputed.equals(record.get("hash"))) {
                    throw new IllegalArgumentException(
                            "trace step hash mismatch in branch " + branch.id + " at step "
                                    + record.get("stepIndex"));
                }
                previousHash = String.valueOf(record.get("hash"));
            }
            if (!previousHash.equals(branch.traceHash)) {
                throw new IllegalArgumentException(
                        "final trace hash mismatch in branch " + branch.id);
            }
        }
    }

    private void replayVerify(Session session, Definition definition) {
        for (Branch branch : session.branches) {
            if (!"root".equals(branch.kind)) {
                continue;
            }
            Frame frame = new Frame(definition.initialState(), definition.initialData(),
                    session.lockedSeed);
            Engine engine = new Engine(definition);
            List<Map<String, Object>> queue = copyEvents(branch.externalPool);
            queue.sort(queueComparator(definition));
            long internalCounter = 0;
            long clock = 0;
            for (Map<String, Object> expected : branch.steps) {
                if (queue.isEmpty()) {
                    throw new IllegalArgumentException(
                            "replay ran out of events in branch " + branch.id);
                }
                Map<String, Object> event = queue.remove(0);
                internalCounter++;
                StepResult result = engine.step(frame, event, internalCounter);
                List<Map<String, Object>> emitted = new ArrayList<>(result.emittedInternal);
                emitted.sort(internalEventComparator());
                for (int i = emitted.size() - 1; i >= 0; i--) {
                    queue.add(0, copyEvent(emitted.get(i)));
                }
                Map<String, Object> record = Steps.buildRecord(result,
                        expected.get("prevHash") == null ? branch.rootHash
                                : String.valueOf(expected.get("prevHash")));
                if (!record.get("hash").equals(expected.get("hash"))) {
                    throw new IllegalArgumentException(
                            "deterministic replay hash mismatch in branch " + branch.id
                                    + " at step " + expected.get("stepIndex"));
                }
                clock++;
            }
            if (!frame.state().equals(branch.currentState)
                    || !Json.canonical(frame.data()).equals(Json.canonical(branch.currentData))) {
                throw new IllegalArgumentException(
                        "replayed final state differs for branch " + branch.id);
            }
        }
    }

    // ------------------------------------------------------------------
    // Validation and small helpers
    // ------------------------------------------------------------------

    private List<Map<String, Object>> validateEvents(List<Map<String, Object>> events) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> event = events.get(i);
            if (!(event.get("id") instanceof String id) || id.isBlank()) {
                errors.add("events[" + i + "].id must be a non-empty string");
            }
            if (!(event.get("type") instanceof String type) || type.isBlank()) {
                errors.add("events[" + i + "].type must be a non-empty string");
            }
            Object time = event.get("time");
            if (!(time instanceof Number)) {
                errors.add("events[" + i + "].time must be an integer logical time");
            } else if (((Number) time).doubleValue() != ((Number) time).longValue()) {
                errors.add("events[" + i + "].time must be an integer logical time");
            }
            if (!(event.get("source") instanceof String) || String.valueOf(event.get("source")).isBlank()) {
                errors.add("events[" + i + "].source must name a source");
            }
            Object seq = event.get("seq");
            if (seq != null && !(seq instanceof Number)) {
                errors.add("events[" + i + "].seq must be numeric");
            }
            if (event.containsKey("data") && !(event.get("data") instanceof Map)) {
                errors.add("events[" + i + "].data must be an object");
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        return events;
    }

    private void checkUniqueEventIds(List<Map<String, Object>> events) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> event : events) {
            String id = Events.id(event);
            if (!id.isEmpty() && !ids.add(id)) {
                throw new IllegalArgumentException("duplicate external event id '" + id + "'");
            }
        }
    }

    private Set<String> collectExternalIds(List<Map<String, Object>> events) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> event : events) {
            if (!Events.internal(event)) {
                ids.add(Events.id(event));
            }
        }
        return ids;
    }

    private Comparator<Map<String, Object>> queueComparator(Definition definition) {
        return Comparator
                .comparingLong(Events::time)
                .thenComparing(event -> Events.internal(event) ? 0L : 1L)
                .thenComparingLong(event ->
                        Events.internal(event) ? 0L
                                : Events.sourcePriority(Events.source(event), definition.sources()))
                .thenComparingLong(event -> Events.internal(event) ? 0L : Events.seq(event))
                .thenComparing(Events::id)
                .thenComparingLong(event -> Events.internal(event) ? Events.seq(event) : 0L);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> externalEventsFromSteps(List<Map<String, Object>> steps) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            Object event = step.get("event");
            if (event instanceof Map<?, ?> map && !Events.internal((Map<String, Object>) map)) {
                events.add((Map<String, Object>) event);
            }
        }
        return events;
    }

    private long countInternalSteps(List<Map<String, Object>> steps) {
        long count = 0;
        for (Map<String, Object> step : steps) {
            Object event = step.get("event");
            if (event instanceof Map<?, ?> map && Events.internal(ModelAccess.asObject(map))) {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collectOutputs(List<Map<String, Object>> steps) {
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            Object stepOutputs = step.get("outputs");
            if (stepOutputs instanceof List<?> list) {
                for (Object output : list) {
                    Map<String, Object> copy = new LinkedHashMap<>(ModelAccess.asObject(output));
                    copy.put("stepIndex", step.get("stepIndex"));
                    outputs.add(copy);
                }
            }
        }
        return outputs;
    }

    private Map<String, Object> originRef(String branchId, int stepIndex) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("branchId", branchId);
        ref.put("stepIndex", stepIndex);
        return ref;
    }

    private List<String> idsOf(List<Map<String, Object>> events) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> event : events) {
            ids.add(Events.id(event));
        }
        return ids;
    }

    private List<Map<String, Object>> summarizeEvents(List<Map<String, Object>> events) {
        List<Map<String, Object>> summary = new ArrayList<>();
        for (Map<String, Object> event : events) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", event.get("id"));
            item.put("type", event.get("type"));
            item.put("time", event.get("time"));
            item.put("source", event.get("source"));
            item.put("seq", event.get("seq"));
            summary.add(item);
        }
        return summary;
    }

    public Map<String, Object> sessionSummary(Session session) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", session.id);
        summary.put("name", session.name);
        summary.put("definitionFingerprint", session.definitionFingerprint);
        summary.put("lockedSeed", session.lockedSeed);
        summary.put("createdAt", session.createdAt);
        summary.put("updatedAt", session.updatedAt);
        summary.put("exportHash", session.exportHash);
        List<Object> branchSummaries = new ArrayList<>();
        for (Branch branch : session.branches) {
            branchSummaries.add(summary(branch));
        }
        summary.put("branches", branchSummaries);
        summary.put("checkpointCount", session.checkpoints.size());
        return summary;
    }

    public Map<String, Object> summary(Branch branch) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", branch.id);
        summary.put("name", branch.name);
        summary.put("kind", branch.kind);
        summary.put("origin", branch.origin);
        summary.put("steps", branch.steps.size());
        summary.put("pending", branch.queue.size());
        summary.put("finished", branch.finished);
        summary.put("currentState", branch.currentState);
        summary.put("currentData", branch.currentData);
        summary.put("traceHash", branch.traceHash);
        return summary;
    }

    public Map<String, Object> branchDetail(Session session, Branch branch) {
        Map<String, Object> detail = summary(branch);
        Definition definition = definition(session.definitionFingerprint);
        detail.put("rootHash", branch.rootHash);
        detail.put("rngState", branch.rngState);
        detail.put("queue", summarizeQueue(branch.queue));
        List<Object> checkpoints = new ArrayList<>();
        for (String checkpointId : branch.checkpointIds) {
            Checkpoint checkpoint = session.checkpoints.get(checkpointId);
            if (checkpoint != null) {
                checkpoints.add(checkpoint.toMap());
            }
        }
        detail.put("checkpoints", checkpoints);
        List<Object> stepViews = new ArrayList<>();
        for (Map<String, Object> record : branch.steps) {
            stepViews.add(stepView(session, branch, record, definition));
        }
        detail.put("trace", stepViews);
        detail.put("outputs", collectOutputs(branch.steps));
        return detail;
    }

    private List<Map<String, Object>> summarizeQueue(List<Map<String, Object>> queue) {
        List<Map<String, Object>> summary = new ArrayList<>();
        for (Map<String, Object> event : queue) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", event.get("id"));
            item.put("type", event.get("type"));
            item.put("time", event.get("time"));
            item.put("source", event.get("source"));
            item.put("seq", event.get("seq"));
            item.put("internal", event.get("internal"));
            summary.add(item);
        }
        return summary;
    }

    private void persist(Session session) {
        session.updatedAt = LogicalClock.now();
        store.saveSession(session);
    }

    private String idOrGenerate(Object requested, java.util.function.Supplier<String> generator) {
        return requested instanceof String text && !text.isBlank() ? text : generator.get();
    }

    private String stringOrDefault(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyData(Map<String, Object> data) {
        return (Map<String, Object>) Json.deepCopy(data);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyEvent(Map<String, Object> event) {
        return (Map<String, Object>) Json.deepCopy(event);
    }

    private List<Map<String, Object>> copyEvents(List<Map<String, Object>> events) {
        List<Map<String, Object>> copy = new ArrayList<>(events.size());
        for (Map<String, Object> event : events) {
            copy.add(copyEvent(event));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> copySteps(List<Map<String, Object>> steps) {
        List<Map<String, Object>> copy = new ArrayList<>(steps.size());
        for (Map<String, Object> step : steps) {
            copy.add((Map<String, Object>) Json.deepCopy(step));
        }
        return copy;
    }

    private static final class MergePlan {
        List<Map<String, Object>> ordered = new ArrayList<>();
        List<Map<String, Object>> fromA = new ArrayList<>();
        List<Map<String, Object>> fromB = new ArrayList<>();
    }
}
