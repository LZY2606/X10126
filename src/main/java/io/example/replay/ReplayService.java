package io.example.replay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ReplayService {
    private final Store store;
    private AppState app;

    public ReplayService(Store store) {
        this.store = store;
        this.app = store.load();
    }

    public synchronized MachineDefinition definition() {
        return app.definition;
    }

    public synchronized MachineDefinition updateDefinition(Object raw) {
        MachineDefinition parsed = MachineDefinition.parse(raw);
        app.definition = parsed;
        persist();
        return parsed;
    }

    public synchronized List<Session> sessions() {
        return new ArrayList<>(app.sessions.values());
    }

    public synchronized Session session(String sessionId) {
        return requireSession(sessionId);
    }

    public synchronized Session createSession(Object request) {
        Map<String, Object> body = Json.object(request);
        MachineDefinition definition = body.get("definition") == null
                ? app.definition : MachineDefinition.parse(body.get("definition"));
        if (definition == null) {
            throw new IllegalArgumentException("Define a state machine first");
        }
        if (body.get("definition") != null) {
            app.definition = definition;
        }
        long seed = Json.longValue(body, "seed", 1L);
        List<StoredEvent> events = parseEvents(body.get("events"));
        Session session = new Session();
        session.id = id(body, "id", "s");
        session.name = body.get("name") == null ? "Session " + session.id : String.valueOf(body.get("name"));
        session.definition = definition;
        session.seed = seed;
        session.initialState = initialStateJson(definition, seed, events);
        session.inputFingerprint = fingerprintInput(definition, definition.initialData(), seed, events);
        Branch main = new Branch();
        main.id = "main";
        main.name = "main";
        main.rootCheckpointId = null;
        ReplayEngine engine = new ReplayEngine(definition, seed);
        main.state = engine.replayState();
        main.pendingExternal = events;
        session.branches.put(main.id, main);
        app.sessions.put(session.id, session);
        persist();
        return session;
    }

    public synchronized Map<String, Object> appendEvents(String sessionId, Object request) {
        Session session = requireSession(sessionId);
        Map<String, Object> body = Json.object(request);
        String branchId = body.get("branchId") == null ? "main" : String.valueOf(body.get("branchId"));
        Branch branch = requireBranch(session, branchId);
        List<StoredEvent> parsed = parseEvents(body.get("events"));
        long base = branch.externalHistory.size() + branch.pendingExternal.size() + 1L;
        for (int i = 0; i < parsed.size(); i++) {
            StoredEvent event = parsed.get(i);
            if (event.originalSeq() == 0L) {
                event = new StoredEvent(event.id(), event.type(), event.time(), event.source(),
                        event.priority(), base + i, event.internal(), event.enqueueSeq(), event.payload());
            }
            branch.pendingExternal.add(event);
        }
        branch.pendingExternal.sort(externalComparator());
        persist();
        return branchView(session, branch);
    }

    public synchronized ReplayEngine.StepResult step(String sessionId, String branchId) {
        Session session = requireSession(sessionId);
        Branch branch = requireBranch(session, branchId);
        ReplayEngine engine = engineFor(session, branch);
        ReplayEngine.StepResult result = engine.step();
        if (result == null) {
            throw new IllegalArgumentException("No queued event");
        }
        if (!result.event().internal()) {
            branch.externalHistory.add(result.event());
        }
        branch.state = engine.replayState();
        branch.pendingExternal = new ArrayList<>(engine.remainingExternalEvents());
        persist();
        return result;
    }

    public synchronized Checkpoint checkpoint(String sessionId, String branchId, String label) {
        Session session = requireSession(sessionId);
        Branch branch = requireBranch(session, branchId);
        String id = "cp-" + UUID.randomUUID();
        Checkpoint checkpoint = new Checkpoint(id, branch.id, session.definition.fingerprint(),
                branch.state.trace.size(), branch.state.copy(),
                branch.pendingExternal.stream().map(StoredEvent::copy).toList(),
                branch.state.trace.isEmpty() ? null : branch.state.trace.get(branch.state.trace.size() - 1).traceHash(),
                label);
        branch.checkpoints.put(id, checkpoint);
        persist();
        return checkpoint;
    }

    public synchronized Checkpoint restoreCheckpoint(String sessionId, String branchId, String checkpointId) {
        Session session = requireSession(sessionId);
        Branch branch = requireBranch(session, branchId);
        Checkpoint checkpoint = requireCheckpoint(branch, checkpointId);
        validateCheckpoint(session, checkpoint);
        branch.state = checkpoint.state().copy();
        branch.pendingExternal = new ArrayList<>(checkpoint.remainingExternal().stream().map(StoredEvent::copy).toList());
        branch.externalHistory = new ArrayList<>(consumedUntilCheckpoint(branch, checkpoint));
        persist();
        return checkpoint;
    }

    public synchronized Branch fork(String sessionId, Object request) {
        Session session = requireSession(sessionId);
        Map<String, Object> body = Json.object(request);
        String sourceBranchId = body.get("sourceBranchId") == null ? "main" : String.valueOf(body.get("sourceBranchId"));
        String checkpointId = Json.string(body, "checkpointId");
        Branch source = requireBranch(session, sourceBranchId);
        Checkpoint checkpoint = checkpointId == null ? null : requireCheckpoint(source, checkpointId);
        if (checkpoint != null) {
            validateCheckpoint(session, checkpoint);
        }
        Branch forked = new Branch();
        forked.id = id(body, "branchId", "branch");
        if (session.branches.containsKey(forked.id)) {
            throw new IllegalArgumentException("Branch already exists: " + forked.id);
        }
        forked.name = body.get("name") == null ? forked.id : String.valueOf(body.get("name"));
        forked.parentBranchId = source.id;
        forked.parentCheckpointId = checkpoint == null ? null : checkpoint.id();
        forked.rootCheckpointId = checkpoint == null
                ? (source.rootCheckpointId == null ? null : source.rootCheckpointId)
                : checkpoint.id();
        if (checkpoint == null) {
            forked.state = source.state.copy();
            forked.externalHistory = new ArrayList<>(source.externalHistory.stream().map(StoredEvent::copy).toList());
            forked.pendingExternal = new ArrayList<>(source.pendingExternal.stream().map(StoredEvent::copy).toList());
        } else {
            forked.state = checkpoint.state().copy();
            forked.externalHistory = new ArrayList<>(consumedUntilCheckpoint(source, checkpoint));
            forked.pendingExternal = new ArrayList<>(checkpoint.remainingExternal().stream().map(StoredEvent::copy).toList());
        }
        forked.pendingExternal.addAll(parseEvents(body.get("events")));
        forked.pendingExternal.sort(externalComparator());
        session.branches.put(forked.id, forked);
        persist();
        return forked;
    }

    public synchronized MergeResult merge(String sessionId, Object request) {
        Session session = requireSession(sessionId);
        Map<String, Object> body = Json.object(request);
        Branch left = requireBranch(session, body.get("leftBranchId") == null ? "main" : String.valueOf(body.get("leftBranchId")));
        Branch right = requireBranch(session, Json.string(body, "rightBranchId"));
        Checkpoint ancestor = findCommonAncestor(session, left, right);
        List<StoredEvent> leftEvents = branchEventsAfterCheckpoint(left, ancestor);
        List<StoredEvent> rightEvents = branchEventsAfterCheckpoint(right, ancestor);
        MergeCompatibility compatibility = compatibleUnion(leftEvents, rightEvents);
        if (!compatibility.allowed) {
            return MergeResult.conflict(ancestor == null ? null : ancestor.id(),
                    compatibility.reason, compatibility.left, compatibility.right);
        }
        List<StoredEvent> mergedEvents = compatibility.events;
        Branch merged = new Branch();
        merged.id = id(body, "mergedBranchId", "merged");
        if (session.branches.containsKey(merged.id)) {
            merged.id = "merged-" + UUID.randomUUID();
        }
        merged.name = body.get("name") == null ? "merge-" + left.id + "-" + right.id : String.valueOf(body.get("name"));
        merged.parentBranchId = left.id;
        merged.parentCheckpointId = ancestor == null ? null : ancestor.id();
        merged.rootCheckpointId = ancestor == null ? null : ancestor.id();
        if (ancestor == null) {
            merged.state = new ReplayEngine(session.definition, session.seed).replayState();
            merged.pendingExternal = mergedEvents;
        } else {
            validateCheckpoint(session, ancestor);
            merged.state = ancestor.state().copy();
            merged.pendingExternal = mergedEvents;
        }
        replayAll(session, merged);
        merged.externalHistory = mergedEvents;
        session.branches.put(merged.id, merged);
        persist();
        return new MergeResult(true, merged.id, ancestor == null ? null : ancestor.id(), null, List.of());
    }

    public synchronized Map<String, Object> compare(String sessionId, String leftBranchId, String rightBranchId) {
        Session session = requireSession(sessionId);
        Branch left = requireBranch(session, leftBranchId);
        Branch right = requireBranch(session, rightBranchId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("left", branchView(session, left));
        result.put("right", branchView(session, right));
        result.put("stateEqual", Json.canonical(left.state.snapshotJson())
                .equals(Json.canonical(right.state.snapshotJson())));
        result.put("traceHashEqual", java.util.Objects.equals(traceHash(left), traceHash(right)));
        result.put("outputsLeft", outputs(left));
        result.put("outputsRight", outputs(right));
        result.put("outputsEqual", Json.canonical(outputs(left)).equals(Json.canonical(outputs(right))));
        Checkpoint ancestor = findCommonAncestor(session, left, right);
        result.put("commonAncestorCheckpointId", ancestor == null ? null : ancestor.id());
        return result;
    }

    public synchronized Map<String, Object> branchView(Session session, Branch branch) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", branch.id);
        result.put("name", branch.name);
        result.put("parentBranchId", branch.parentBranchId);
        result.put("parentCheckpointId", branch.parentCheckpointId);
        result.put("rootCheckpointId", branch.rootCheckpointId);
        result.put("current", branch.state.snapshotJson());
        result.put("traceHash", traceHash(branch));
        result.put("steps", branch.state.trace.size());
        result.put("pendingExternal", branch.pendingExternal.stream().map(StoredEvent::toJsonMutable).toList());
        result.put("internalQueue", branch.state.internalQueue.stream().map(StoredEvent::toJsonMutable).toList());
        result.put("externalHistory", branch.externalHistory.stream().map(StoredEvent::toJsonMutable).toList());
        result.put("trace", branch.state.trace.stream().map(TraceStep::toJson).toList());
        result.put("checkpoints", branch.checkpoints.values().stream().map(this::checkpointView).toList());
        result.put("definitionFingerprint", session.definition.fingerprint());
        result.put("inputFingerprint", session.inputFingerprint);
        return result;
    }

    public synchronized Map<String, Object> sessionView(Session session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", session.id);
        result.put("name", session.name);
        result.put("seed", session.seed);
        result.put("inputFingerprint", session.inputFingerprint);
        result.put("definition", session.definition.toJson());
        result.put("branches", session.branches.values().stream().map(branch -> branchView(session, branch)).toList());
        return result;
    }

    public synchronized String exportSession(String sessionId) {
        Session session = requireSession(sessionId);
        Session replayed = replaySession(session);
        return Json.write(exportObject(replayed));
    }

    public synchronized Session importSession(String jsonText) {
        Object parsed = Json.parse(jsonText);
        Object sessionJson = parsed;
        if (parsed instanceof Map<?, ?> map && map.containsKey("session")
                && "state-machine-replay-room/v1".equals(String.valueOf(map.get("exportFormat")))) {
            sessionJson = map.get("session");
        }
        Session imported = Session.fromJson(sessionJson);
        Session replayed = replaySession(imported);
        for (Branch branch : imported.branches.values()) {
            Branch actual = replayed.branches.get(branch.id);
            if (!java.util.Objects.equals(traceHash(branch), traceHash(actual))) {
                throw new IllegalArgumentException("Imported trace hash mismatch on branch " + branch.id);
            }
        }
        String id = imported.id;
        while (app.sessions.containsKey(id)) {
            id = imported.id + "-" + UUID.randomUUID();
        }
        replayed.id = id;
        app.sessions.put(id, replayed);
        if (app.definition == null) {
            app.definition = replayed.definition;
        }
        persist();
        return replayed;
    }

    private Map<String, Object> exportObject(Session session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exportFormat", "state-machine-replay-room/v1");
        result.put("exportedAtLogicalOnly", true);
        result.put("session", session.toJson());
        return result;
    }

    private Session replaySession(Session original) {
        Session result = new Session();
        result.id = original.id;
        result.name = original.name;
        result.definition = MachineDefinition.parse(original.definition.raw());
        result.seed = original.seed;
        result.initialState = Json.object(Json.deepCopy(original.initialState));
        result.inputFingerprint = original.inputFingerprint;
        for (Branch branch : original.branches.values()) {
            Branch rebuilt = new Branch();
            rebuilt.id = branch.id;
            rebuilt.name = branch.name;
            rebuilt.parentBranchId = branch.parentBranchId;
            rebuilt.parentCheckpointId = branch.parentCheckpointId;
            rebuilt.rootCheckpointId = branch.rootCheckpointId;
            Checkpoint anchor = branch.parentCheckpointId == null ? null
                    : findCheckpoint(original, branch.parentCheckpointId);
            int targetTraceSize = branch.state.trace.size();
            int startingTraceSize = anchor == null ? 0 : anchor.state().trace.size();
            if (anchor == null) {
                rebuilt.state = new ReplayEngine(result.definition, result.seed).replayState();
                rebuilt.pendingExternal = new ArrayList<>(branch.externalHistory.stream().map(StoredEvent::copy).toList());
            } else {
                rebuilt.state = anchor.state().copy();
                rebuilt.pendingExternal = new ArrayList<>(consumedEventsAfterCheckpoint(branch, anchor));
            }
            replayUntil(result, rebuilt, startingTraceSize, targetTraceSize);
            rebuilt.pendingExternal = new ArrayList<>(branch.pendingExternal);
            rebuilt.checkpoints.putAll(branch.checkpoints);
            result.branches.put(rebuilt.id, rebuilt);
        }
        return result;
    }

    private void replayAll(Session session, Branch branch) {
        ReplayEngine engine = engineFor(session, branch);
        while (engine.canStep()) {
            ReplayEngine.StepResult result = engine.step();
            if (!result.event().internal()) {
                branch.externalHistory.add(result.event());
            }
        }
        branch.state = engine.replayState();
        branch.pendingExternal = List.of();
    }

    private void replayUntil(Session session, Branch branch, int startingTraceSize, int targetTraceSize) {
        ReplayEngine engine = engineFor(session, branch);
        List<StoredEvent> consumed = new ArrayList<>();
        int replayedSteps = 0;
        while (replayedSteps < targetTraceSize - startingTraceSize && engine.canStep()) {
            ReplayEngine.StepResult result = engine.step();
            replayedSteps++;
            if (!result.event().internal()) {
                consumed.add(result.event());
            }
        }
        ReplayState replayedState = engine.replayState();
        if (replayedState.trace.size() != targetTraceSize) {
            throw new IllegalArgumentException("Replayed trace is shorter than imported trace");
        }
        branch.state = replayedState;
        branch.pendingExternal = new ArrayList<>(engine.remainingExternalEvents());
        if (branch.parentCheckpointId == null) {
            branch.externalHistory = consumed;
        } else {
            Checkpoint anchor = findCheckpoint(session, branch.parentCheckpointId);
            branch.externalHistory = new ArrayList<>(anchor == null
                    ? consumed : consumedUntilCheckpoint(session.branches.get(branch.parentBranchId), anchor));
            branch.externalHistory.addAll(consumed);
        }
    }

    private ReplayEngine engineFor(Session session, Branch branch) {
        ReplayEngine engine = new ReplayEngine(session.definition, session.seed);
        engine.restore(branch.state, branch.pendingExternal);
        return engine;
    }

    private Checkpoint findCommonAncestor(Session session, Branch left, Branch right) {
        Set<String> leftCheckpointIds = reachableCheckpointIds(session, left);
        if (left.rootCheckpointId == null && right.rootCheckpointId == null) {
            return null;
        }
        Deque<String> stack = new ArrayDeque<>();
        addCheckpointId(stack, right.rootCheckpointId);
        addCheckpointId(stack, right.parentCheckpointId);
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (!seen.add(id) || id == null) {
                continue;
            }
            if (leftCheckpointIds.contains(id)) {
                return findCheckpoint(session, id);
            }
            Checkpoint checkpoint = findCheckpoint(session, id);
            if (checkpoint != null) {
                Branch owner = requireBranch(session, checkpoint.branchId());
                addCheckpointId(stack, owner.parentCheckpointId);
            }
        }
        return null;
    }

    private Set<String> reachableCheckpointIds(Session session, Branch branch) {
        Set<String> result = new HashSet<>();
        Deque<Branch> branches = new ArrayDeque<>();
        branches.push(branch);
        Set<String> visitedBranches = new HashSet<>();
        while (!branches.isEmpty()) {
            Branch current = branches.pop();
            if (current == null || !visitedBranches.add(current.id)) {
                continue;
            }
            result.addAll(current.checkpoints.keySet());
            if (current.parentBranchId != null) {
                branches.push(session.branches.get(current.parentBranchId));
            }
        }
        return result;
    }

    private void addCheckpointId(Deque<String> stack, String id) {
        if (id != null) {
            stack.push(id);
        }
    }

    private Checkpoint findCheckpoint(Session session, String checkpointId) {
        for (Branch branch : session.branches.values()) {
            Checkpoint checkpoint = branch.checkpoints.get(checkpointId);
            if (checkpoint != null) {
                return checkpoint;
            }
        }
        return null;
    }

    private Checkpoint findCheckpoint(Session session, String branchId, String checkpointId) {
        if (branchId == null || checkpointId == null) {
            return null;
        }
        Branch branch = session.branches.get(branchId);
        return branch == null ? null : branch.checkpoints.get(checkpointId);
    }

    private List<StoredEvent> eventsAfterCheckpoint(Branch branch, Checkpoint checkpoint) {
        List<StoredEvent> events = new ArrayList<>(consumedEventsAfterCheckpoint(branch, checkpoint));
        events.addAll(branch.pendingExternal);
        return events.stream().sorted(externalComparator()).toList();
    }

    private List<StoredEvent> consumedEventsAfterCheckpoint(Branch branch, Checkpoint checkpoint) {
        List<StoredEvent> events = new ArrayList<>(branch.externalHistory);
        if (checkpoint == null) {
            return events.stream().sorted(externalComparator()).toList();
        }
        long step = checkpoint.stepIndex();
        List<StoredEvent> history = branch.externalHistory.stream()
                .filter(event -> consumedAfter(event, branch, step))
                .toList();
        events = new ArrayList<>(history);
        events.addAll(branch.pendingExternal);
        return events.stream()
                .sorted(externalComparator())
                .toList();
    }

    private List<StoredEvent> branchEventsAfterCheckpoint(Branch branch, Checkpoint checkpoint) {
        return eventsAfterCheckpoint(branch, checkpoint);
    }

    private boolean consumedAfter(StoredEvent event, Branch branch, long step) {
        for (TraceStep traceStep : branch.state.trace) {
            if (traceStep.eventId().equals(event.id()) && traceStep.index() >= step) {
                return true;
            }
        }
        return false;
    }

    private MergeCompatibility compatibleUnion(List<StoredEvent> left, List<StoredEvent> right) {
        List<StoredEvent> sortedLeft = left.stream().sorted(externalComparator()).toList();
        List<StoredEvent> sortedRight = right.stream().sorted(externalComparator()).toList();
        List<StoredEvent> merged = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < sortedLeft.size() || j < sortedRight.size()) {
            if (i >= sortedLeft.size()) {
                addDistinct(merged, sortedRight.get(j++));
            } else if (j >= sortedRight.size()) {
                addDistinct(merged, sortedLeft.get(i++));
            } else {
                StoredEvent a = sortedLeft.get(i);
                StoredEvent b = sortedRight.get(j);
                int cmp = ambiguityKey(a).compareTo(ambiguityKey(b));
                if (cmp == 0) {
                    if (!a.id().equals(b.id()) || !sameEvent(a, b)) {
                        return MergeCompatibility.conflict("Same logical ordering position contains different events", a, b);
                    }
                    addDistinct(merged, a);
                    i++;
                    j++;
                } else if (cmp < 0) {
                    StoredEvent duplicate = findById(sortedRight.subList(j, sortedRight.size()), a.id());
                    if (duplicate != null && !sameEvent(a, duplicate)) {
                        return MergeCompatibility.conflict("Event id appears at ambiguous order positions", a, duplicate);
                    }
                    addDistinct(merged, a);
                    i++;
                } else {
                    StoredEvent duplicate = findById(sortedLeft.subList(i, sortedLeft.size()), b.id());
                    if (duplicate != null && !sameEvent(b, duplicate)) {
                        return MergeCompatibility.conflict("Event id appears at ambiguous order positions", b, duplicate);
                    }
                    addDistinct(merged, b);
                    j++;
                }
            }
        }
        return MergeCompatibility.allowed(merged);
    }

    private void addDistinct(List<StoredEvent> events, StoredEvent event) {
        boolean duplicate = events.stream().anyMatch(existing -> sameEvent(existing, event));
        if (!duplicate) {
            events.add(event.copy());
        }
    }

    private StoredEvent findById(List<StoredEvent> events, String id) {
        return events.stream().filter(event -> event.id().equals(id)).findFirst().orElse(null);
    }

    private boolean sameEvent(StoredEvent left, StoredEvent right) {
        return Json.canonical(left.toJsonMutable()).equals(Json.canonical(right.toJsonMutable()));
    }

    private String sortKey(StoredEvent event) {
        return String.format("%020d|%010d|%s|%020d|%s",
                event.time(), event.priority(), event.source(), event.originalSeq(), event.id());
    }

    private String ambiguityKey(StoredEvent event) {
        return String.format("%020d|%010d|%s|%020d",
                event.time(), event.priority(), event.source(), event.originalSeq());
    }

    private List<StoredEvent> consumedUntilCheckpoint(Branch branch, Checkpoint checkpoint) {
        return branch.state.trace.stream()
                .filter(step -> !step.internal() && step.index() < checkpoint.stepIndex())
                .map(step -> branch.externalHistory.stream()
                        .filter(event -> event.id().equals(step.eventId()))
                        .findFirst().orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private List<Object> outputs(Branch branch) {
        List<Object> result = new ArrayList<>();
        for (TraceStep step : branch.state.trace) {
            result.addAll(Json.list(step.outputs()));
        }
        return result;
    }

    private String traceHash(Branch branch) {
        return branch.state.trace.isEmpty() ? null
                : branch.state.trace.get(branch.state.trace.size() - 1).traceHash();
    }

    private Map<String, Object> checkpointView(Checkpoint checkpoint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", checkpoint.id());
        result.put("branchId", checkpoint.branchId());
        result.put("stepIndex", checkpoint.stepIndex());
        result.put("traceHash", checkpoint.traceHash());
        result.put("label", checkpoint.label());
        result.put("definitionFingerprint", checkpoint.definitionFingerprint());
        return result;
    }

    private Map<String, Object> initialStateJson(MachineDefinition definition, long seed, List<StoredEvent> events) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", definition.initialState());
        result.put("data", definition.initialData());
        result.put("seed", seed);
        result.put("eventIds", events.stream().map(StoredEvent::id).toList());
        return result;
    }

    private String fingerprintInput(MachineDefinition definition, Map<String, Object> initialData,
                                    long seed, List<StoredEvent> events) {
        Map<String, Object> locked = new LinkedHashMap<>();
        locked.put("definitionFingerprint", definition.fingerprint());
        locked.put("initialState", definition.initialState());
        locked.put("initialData", initialData);
        locked.put("seed", seed);
        locked.put("events", events.stream().sorted(externalComparator())
                .map(StoredEvent::toJsonMutable).toList());
        return Hashing.sha256(Json.canonical(locked));
    }

    private List<StoredEvent> parseEvents(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        List<Object> list = Json.list(value);
        List<StoredEvent> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            result.add(StoredEvent.external(list.get(i), i + 1L));
        }
        return result;
    }

    private static Comparator<StoredEvent> externalComparator() {
        return Comparator.comparingLong(StoredEvent::time)
                .thenComparingInt(StoredEvent::priority)
                .thenComparing(StoredEvent::source, Comparator.nullsFirst(String::compareTo))
                .thenComparingLong(StoredEvent::originalSeq)
                .thenComparing(StoredEvent::id, Comparator.nullsFirst(String::compareTo));
    }

    private Session requireSession(String sessionId) {
        Session session = app.sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }
        return session;
    }

    private Branch requireBranch(Session session, String branchId) {
        Branch branch = session.branches.get(branchId);
        if (branch == null) {
            throw new IllegalArgumentException("Unknown branch: " + branchId);
        }
        return branch;
    }

    private Checkpoint requireCheckpoint(Branch branch, String checkpointId) {
        Checkpoint checkpoint = branch.checkpoints.get(checkpointId);
        if (checkpoint == null) {
            throw new IllegalArgumentException("Unknown checkpoint: " + checkpointId);
        }
        return checkpoint;
    }

    private void validateCheckpoint(Session session, Checkpoint checkpoint) {
        if (!checkpoint.definitionFingerprint().equals(session.definition.fingerprint())) {
            throw new DefinitionMismatchException(checkpoint.definitionFingerprint(),
                    session.definition.fingerprint());
        }
    }

    private String id(Map<String, Object> body, String key, String prefix) {
        String value = Json.string(body, key);
        return value == null || value.isBlank() ? prefix + "-" + UUID.randomUUID() : value;
    }

    private void persist() {
        store.save(app);
    }

    private record MergeCompatibility(boolean allowed, String reason, List<StoredEvent> events,
                                      StoredEvent left, StoredEvent right) {
        static MergeCompatibility allowed(List<StoredEvent> events) {
            return new MergeCompatibility(true, null, events, null, null);
        }

        static MergeCompatibility conflict(String reason, StoredEvent left, StoredEvent right) {
            return new MergeCompatibility(false, reason, List.of(), left, right);
        }
    }

    public static class DefinitionMismatchException extends RuntimeException {
        public DefinitionMismatchException(String checkpointFingerprint, String currentFingerprint) {
            super("Checkpoint definition fingerprint " + checkpointFingerprint
                    + " cannot be replayed with current definition " + currentFingerprint);
        }
    }
}
