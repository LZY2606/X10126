package replayroom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public final class Service {
    private final Store store;
    private Workspace workspace;
    private final ReentrantLock lock = new ReentrantLock();

    public Service(Store store) {
        this.store = store;
        this.workspace = store.load().orElseGet(Samples::workspace);
        validateWorkspace(this.workspace);
        save();
    }

    public Workspace workspace() {
        lock.lock();
        try {
            return workspace;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> state() {
        lock.lock();
        try {
            Map<String, Object> state = workspace.toMap();
            state.put("summary", summary());
            return state;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> updateDefinition(Object raw) {
        lock.lock();
        try {
            Models.Definition updated = Models.Definition.fromMap(raw);
            updated.validate();
            if (!workspace.branches().isEmpty()) {
                boolean onlyGenesis = workspace.branches().size() == 1
                    && workspace.branches().get(0).steps().isEmpty()
                    && workspace.checkpoints().size() <= 1;
                if (!onlyGenesis) {
                    throw new ApiException(409, "definition_locked", "Create a new session before changing a definition that has replay history");
                }
            }
            Branch initial = initialBranch(updated);
            Checkpoint genesis = genesisCheckpoint(updated, initial);
            workspace = new Workspace(
                workspace.id(),
                workspace.formatVersion(),
                updated,
                List.of(initial),
                List.of(genesis),
                initial.id(),
                workspace.createdAt(),
                null
            );
            save();
            return state();
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> importEvents(Object raw, boolean replace) {
        lock.lock();
        try {
            List<Models.EventEnvelope> imported = parseExternalEvents(raw);
            List<Branch> branches = new ArrayList<>();
            for (Branch branch : workspace.branches()) {
                List<Models.EventEnvelope> events;
                int nextExternal;
                if (replace) {
                    if (!branch.steps().isEmpty()) {
                        throw new ApiException(409, "events_locked", "Cannot replace event logs after replay has started");
                    }
                    events = filterByBase(imported, branch);
                    nextExternal = 0;
                } else {
                    events = new ArrayList<>(branch.externalEvents());
                    nextExternal = branch.nextExternal();
                    for (Models.EventEnvelope event : filterByBase(imported, branch)) {
                        if (event.time() < lastImportedTime(events)) {
                            throw new ApiException(400, "event_order", "Appended event time must not be earlier than existing events");
                        }
                        events.add(event);
                    }
                }
                List<Models.EventEnvelope> sorted = sortedExternal(events);
                branches.add(new Branch(
                    branch.id(), branch.name(), branch.definitionFingerprint(), branch.baseCheckpointId(),
                    branch.forkedFromBranchId(), branch.parentCheckpointId(), branch.currentState(), branch.vars(),
                    branch.rngState(), sorted, branch.pendingInternal(), nextExternal, branch.steps(),
                    branch.processedExternalIds(), branch.lastTraceHash(), branch.merged()
                ));
            }
            replaceInPlace(branches, workspace.checkpoints(), workspace.activeBranchId(), null);
            return state();
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> step(String branchId, long count) {
        lock.lock();
        try {
            List<Branch> branches = new ArrayList<>(workspace.branches());
            int index = findBranchIndex(branchId);
            Branch branch = branches.get(index);
            long target = count <= 0 ? 1 : Math.min(count, Integer.MAX_VALUE);
            for (long i = 0; i < target && Engine.canStep(branch); i++) {
                branch = Engine.step(branch, workspace.definition());
            }
            branches.set(index, branch);
            replaceInPlace(branches, workspace.checkpoints(), branch.id(), null);
            return branchState(branch.id());
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> checkpoint(String branchId, Object raw) {
        lock.lock();
        try {
            Branch branch = requireBranch(branchId);
            String requestedName = raw == null ? null : Json.string(Json.object(raw), "name");
            String name = requestedName == null || requestedName.isBlank() ? "检查点 " + (workspace.checkpoints().size() + 1) : requestedName;
            Checkpoint checkpoint = checkpointFromBranch(branch, name, nextCheckpointId());
            List<Checkpoint> checkpoints = new ArrayList<>(workspace.checkpoints());
            checkpoints.add(checkpoint);
            replaceInPlace(workspace.branches(), checkpoints, workspace.activeBranchId(), null);
            return Map.of("checkpoint", checkpoint.toMap());
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> fork(Object raw) {
        lock.lock();
        try {
            Map<String, Object> request = Json.object(raw);
            String checkpointId = Json.requireString(request, "checkpointId");
            Checkpoint checkpoint = findCheckpoint(checkpointId);
            if (!checkpoint.definitionFingerprint().equals(workspace.definition().fingerprint())) {
                throw new ApiException(409, "checkpoint_definition_mismatch",
                    "Checkpoint definition " + checkpoint.definitionFingerprint() + " does not match current definition " + workspace.definition().fingerprint());
            }
            String name = Json.string(request, "name");
            String id = nextBranchId();
            Branch branch = branchFromCheckpoint(checkpoint, id, name == null || name.isBlank() ? "分支 " + (workspace.branches().size() + 1) : name);
            List<Branch> branches = new ArrayList<>(workspace.branches());
            branches.add(branch);
            replaceInPlace(branches, workspace.checkpoints(), id, null);
            return branchState(id);
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> merge(Object raw) {
        lock.lock();
        try {
            Map<String, Object> request = Json.object(raw);
            Branch left = requireBranch(Json.requireString(request, "leftBranchId"));
            Branch right = requireBranch(Json.requireString(request, "rightBranchId"));
            Checkpoint base = commonBase(left, right);
            if (!left.pendingInternal().isEmpty() || !right.pendingInternal().isEmpty()) {
                throw new ApiException(409, "pending_internal", "Drain queued internal events before merging");
            }
            List<Models.EventEnvelope> leftEvents = eventsAfter(left, base.stepIndex());
            List<Models.EventEnvelope> rightEvents = eventsAfter(right, base.stepIndex());
            MergePlan plan = mergePlan(leftEvents, rightEvents);
            Branch prefixOwner = base.branchId().equals(left.id()) ? left : right;
            List<Step> prefixSteps = prefixSteps(prefixOwner, base.stepIndex());
            String id = nextBranchId();
            String name = Json.string(request, "name");
            Branch merged = new Branch(
                id,
                name == null || name.isBlank() ? "合并 " + (workspace.branches().size() + 1) : name,
                workspace.definition().fingerprint(),
                base.id(),
                null,
                base.id(),
                base.currentState(),
                new LinkedHashMap<>(base.vars()),
                base.rngState(),
                plan.events(),
                new ArrayList<>(),
                0,
                prefixSteps,
                new ArrayList<>(base.processedExternalIds()),
                base.traceHash(),
                true
            );
            List<Branch> branches = new ArrayList<>(workspace.branches());
            branches.add(merged);
            replaceInPlace(branches, workspace.checkpoints(), id, null);
            Map<String, Object> response = branchState(id);
            response.put("baseCheckpoint", base.toMap());
            return response;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> compare(String leftId, String rightId) {
        lock.lock();
        try {
            Branch left = requireBranch(leftId);
            Branch right = requireBranch(rightId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("left", branchSummary(left));
            result.put("right", branchSummary(right));
            result.put("stateDiff", diff(left, right));
            result.put("leftTraceHash", left.lastTraceHash());
            result.put("rightTraceHash", right.lastTraceHash());
            return result;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> branchState(String branchId) {
        lock.lock();
        try {
            Branch branch = requireBranch(branchId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("branch", branch.toMap());
            result.put("definitionFingerprint", workspace.definition().fingerprint());
            result.put("canStep", Engine.canStep(branch));
            return result;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> exportSession() {
        lock.lock();
        try {
            Map<String, Object> exported = workspace.toMap();
            String fingerprint = Hashing.sha256(Json.canonical(exported));
            exported.put("exportFingerprint", fingerprint);
            replaceInPlace(workspace.branches(), workspace.checkpoints(), workspace.activeBranchId(), fingerprint);
            return exported;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> importSession(Object raw) {
        lock.lock();
        try {
            Map<String, Object> map = Json.object(raw);
            String expected = Json.string(map, "exportFingerprint");
            Map<String, Object> withoutFingerprint = new LinkedHashMap<>(map);
            withoutFingerprint.put("exportFingerprint", null);
            if (expected != null) {
                String actual = Hashing.sha256(Json.canonical(withoutFingerprint));
                if (!expected.equals(actual)) {
                    throw new ApiException(400, "bad_export_fingerprint", "Export payload has been modified");
                }
            }
            Workspace imported = Workspace.fromMap(raw);
            validateWorkspace(imported);
            workspace = imported;
            save();
            return state();
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> reset() {
        lock.lock();
        try {
            Workspace fresh = Samples.workspace();
            workspace = fresh;
            save();
            return state();
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Object> branchSummaries = new ArrayList<>();
        for (Branch branch : workspace.branches()) {
            branchSummaries.add(branchSummary(branch));
        }
        summary.put("branches", branchSummaries);
        summary.put("checkpointCount", workspace.checkpoints().size());
        return summary;
    }

    private Map<String, Object> branchSummary(Branch branch) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", branch.id());
        map.put("name", branch.name());
        map.put("currentState", branch.currentState());
        map.put("vars", branch.vars());
        map.put("stepCount", branch.steps().size());
        map.put("lastTraceHash", branch.lastTraceHash());
        map.put("done", !Engine.canStep(branch));
        map.put("merged", branch.merged());
        return map;
    }

    private Map<String, Object> diff(Branch left, Branch right) {
        Map<String, Object> changes = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>();
        keys.addAll(left.vars().keySet());
        keys.addAll(right.vars().keySet());
        keys.add("state");
        for (String key : keys.stream().sorted().toList()) {
            Object leftValue = key.equals("state") ? left.currentState() : left.vars().get(key);
            Object rightValue = key.equals("state") ? right.currentState() : right.vars().get(key);
            if (!java.util.Objects.equals(leftValue, rightValue)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("left", leftValue);
                change.put("right", rightValue);
                changes.put(key, change);
            }
        }
        return changes;
    }

    private List<Models.EventEnvelope> parseExternalEvents(Object raw) {
        List<Object> values = raw instanceof List<?> ? Json.list(raw) : List.of(raw);
        List<Models.EventEnvelope> events = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        long order = 0;
        for (Object value : values) {
            Map<String, Object> map = Json.object(value);
            if (Boolean.TRUE.equals(map.get("internal"))) {
                throw new ApiException(400, "internal_event_import", "Imported event log may only contain external events");
            }
            String id = Json.requireString(map, "id");
            if (!ids.add(id)) {
                throw new ApiException(400, "duplicate_event_id", "Duplicate external event id " + id);
            }
            String source = Json.string(map, "source");
            String resolvedSource = source == null || source.isBlank() ? "external" : source;
            int priority = workspace.definition().priorities().getOrDefault(resolvedSource, 100);
            events.add(new Models.EventEnvelope(
                id,
                Json.requireString(map, "type"),
                Json.integer(map, "time", 0L),
                resolvedSource,
                Json.integer(map, "seq", order),
                Json.objectField(map, "payload"),
                false,
                null,
                0L,
                priority,
                order++
            ));
        }
        return rejectAmbiguous(sortedExternal(events));
    }

    private List<Models.EventEnvelope> rejectAmbiguous(List<Models.EventEnvelope> events) {
        for (int i = 1; i < events.size(); i++) {
            Models.EventEnvelope previous = events.get(i - 1);
            Models.EventEnvelope current = events.get(i);
            if (previous.time() == current.time()
                && previous.priority() == current.priority()
                && previous.seq() == current.seq()
                && !previous.id().equals(current.id())) {
                throw new ApiException(400, "ambiguous_event_order",
                    "Events have identical logical time, source priority and seq: " + previous.id() + ", " + current.id());
            }
        }
        return events;
    }

    private List<Models.EventEnvelope> sortedExternal(List<Models.EventEnvelope> events) {
        List<Models.EventEnvelope> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }

    private List<Models.EventEnvelope> filterByBase(List<Models.EventEnvelope> imported, Branch branch) {
        if (branch.baseCheckpointId() == null) {
            return imported;
        }
        Checkpoint base = findCheckpoint(branch.baseCheckpointId());
        Set<String> already = new HashSet<>(base.processedExternalIds());
        List<Models.EventEnvelope> result = new ArrayList<>();
        for (Models.EventEnvelope event : imported) {
            if (!already.contains(event.id())) {
                result.add(event);
            }
        }
        return result;
    }

    private long lastImportedTime(List<Models.EventEnvelope> events) {
        return events.isEmpty() ? Long.MIN_VALUE : events.get(events.size() - 1).time();
    }

    private Branch requireBranch(String id) {
        return workspace.branches().stream()
            .filter(branch -> branch.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new ApiException(404, "branch_not_found", "Unknown branch " + id));
    }

    private int findBranchIndex(String id) {
        for (int i = 0; i < workspace.branches().size(); i++) {
            if (workspace.branches().get(i).id().equals(id)) return i;
        }
        throw new ApiException(404, "branch_not_found", "Unknown branch " + id);
    }

    private Checkpoint findCheckpoint(String id) {
        return workspace.checkpoints().stream()
            .filter(checkpoint -> checkpoint.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new ApiException(404, "checkpoint_not_found", "Unknown checkpoint " + id));
    }

    private Checkpoint latestCheckpoint(Branch branch) {
        Checkpoint result = genesisFor(branch);
        for (Checkpoint checkpoint : workspace.checkpoints()) {
            if (checkpoint.branchId().equals(branch.id()) && checkpoint.stepIndex() >= result.stepIndex()
                && checkpoint.stepIndex() <= branch.steps().size()) {
                result = checkpoint;
            }
        }
        return result;
    }

    private Checkpoint genesisFor(Branch branch) {
        String root = branch.parentCheckpointId();
        Checkpoint checkpoint = findCheckpoint(root == null ? "genesis" : root);
        while (checkpoint.parentCheckpointId() != null) {
            checkpoint = findCheckpoint(checkpoint.parentCheckpointId());
        }
        return checkpoint;
    }

    private Checkpoint checkpointFromBranch(Branch branch, String name, String id) {
        Checkpoint parent = latestCheckpoint(branch);
        Step step = branch.steps().get(branch.steps().size() - 1);
        String traceHash = step.stepHash();
        Map<String, Object> snapshot = new LinkedHashMap<>(step.afterState());
        Map<String, Object> vars = new LinkedHashMap<>(branch.vars());
        return new Checkpoint(id, name, branch.id(), branch.steps().size(),
            branch.definitionFingerprint(), traceHash, snapshot, branch.currentState(), vars,
            branch.rngState(), branch.nextExternal(), new ArrayList<>(branch.pendingInternal()),
            new ArrayList<>(branch.processedExternalIds()), parent.id());
    }

    private Branch branchFromCheckpoint(Checkpoint checkpoint, String id, String name) {
        Branch source = requireBranch(checkpoint.branchId());
        List<Step> prefix = new ArrayList<>(source.steps().subList(0, (int) checkpoint.stepIndex()));
        return new Branch(
            id, name, checkpoint.definitionFingerprint(), checkpoint.id(), checkpoint.branchId(),
            checkpoint.id(), checkpoint.currentState(), new LinkedHashMap<>(checkpoint.vars()),
            checkpoint.rngState(), tailEvents(source, checkpoint), new ArrayList<>(checkpoint.pendingInternal()),
            checkpoint.nextExternal() - countBaseProcessed(checkpoint), prefix,
            new ArrayList<>(checkpoint.processedExternalIds()), checkpoint.traceHash(), false
        );
    }

    private List<Models.EventEnvelope> tailEvents(Branch source, Checkpoint checkpoint) {
        List<Models.EventEnvelope> all = source.externalEvents();
        Set<String> baseProcessed = new HashSet<>(checkpoint.processedExternalIds());
        List<Models.EventEnvelope> result = new ArrayList<>();
        for (Models.EventEnvelope event : all) {
            if (!baseProcessed.contains(event.id())) result.add(event);
        }
        return result;
    }

    private long countBaseProcessed(Checkpoint checkpoint) {
        return checkpoint.processedExternalIds().stream().filter(id -> id.endsWith(".i0") || !id.contains(".i")).count();
    }

    private Checkpoint commonBase(Branch left, Branch right) {
        Set<String> leftCheckpoints = reachableCheckpointIds(left);
        Set<String> rightCheckpoints = reachableCheckpointIds(right);
        return workspace.checkpoints().stream()
            .filter(checkpoint -> leftCheckpoints.contains(checkpoint.id()) && rightCheckpoints.contains(checkpoint.id()))
            .max(Comparator.comparingLong(Checkpoint::stepIndex))
            .orElseThrow(() -> new ApiException(409, "no_common_base", "Branches do not share a checkpoint ancestor"));
    }

    private Set<String> reachableCheckpointIds(Branch branch) {
        Set<String> result = new HashSet<>();
        addCheckpointChain(branch.parentCheckpointId() == null ? "genesis" : branch.parentCheckpointId(), result);
        for (Checkpoint checkpoint : workspace.checkpoints()) {
            if (checkpoint.branchId().equals(branch.id()) && checkpoint.stepIndex() <= branch.steps().size()) {
                addCheckpointChain(checkpoint.id(), result);
            }
        }
        return result;
    }

    private void addCheckpointChain(String id, Set<String> target) {
        if (id == null || !target.add(id)) return;
        Checkpoint checkpoint = findCheckpoint(id);
        if (checkpoint.parentCheckpointId() != null) {
            addCheckpointChain(checkpoint.parentCheckpointId(), target);
        }
    }

    private List<Models.EventEnvelope> eventsAfter(Branch branch, long baseStepIndex) {
        List<Models.EventEnvelope> result = new ArrayList<>();
        for (Step step : branch.steps()) {
            if (step.index() <= baseStepIndex || step.failed()) continue;
            Object internal = step.event().get("internal");
            if (!Boolean.TRUE.equals(internal)) {
                result.add(Models.EventEnvelope.fromMap(step.event()));
            }
        }
        return result;
    }

    private List<Step> prefixSteps(Branch owner, long baseStepIndex) {
        return new ArrayList<>(owner.steps().subList(0, (int) baseStepIndex));
    }

    private record MergePlan(List<Models.EventEnvelope> events) {
    }

    private MergePlan mergePlan(List<Models.EventEnvelope> leftEvents, List<Models.EventEnvelope> rightEvents) {
        Map<String, Models.EventEnvelope> leftById = new LinkedHashMap<>();
        Map<String, Models.EventEnvelope> rightById = new LinkedHashMap<>();
        leftEvents.forEach(event -> leftById.put(event.id(), event));
        rightEvents.forEach(event -> rightById.put(event.id(), event));
        for (String id : leftById.keySet()) {
            Models.EventEnvelope right = rightById.get(id);
            if (right != null && !Json.canonical(leftById.get(id).toMap()).equals(Json.canonical(right.toMap()))) {
                throw conflict("same_id_different_event", List.of(leftById.get(id), right));
            }
        }
        List<String> shared = leftById.keySet().stream().filter(rightById::containsKey).toList();
        int leftPosition = 0;
        int rightPosition = 0;
        for (String id : shared) {
            int newLeft = indexOfId(leftEvents, id);
            int newRight = indexOfId(rightEvents, id);
            if (newLeft < leftPosition || newRight < rightPosition) {
                throw conflict("shared_event_order", List.of(leftEvents.get(newLeft), rightEvents.get(newRight)));
            }
            leftPosition = newLeft;
            rightPosition = newRight;
        }
        List<Models.EventEnvelope> combined = new ArrayList<>(leftEvents);
        Set<String> existing = new HashSet<>(leftById.keySet());
        for (Models.EventEnvelope event : rightEvents) {
            if (!existing.contains(event.id())) combined.add(event);
        }
        List<Models.EventEnvelope> sorted = new ArrayList<>(combined);
        sorted.sort(Comparator.comparingLong(Models.EventEnvelope::time)
            .thenComparingInt(Models.EventEnvelope::priority)
            .thenComparingLong(Models.EventEnvelope::seq)
            .thenComparing(Models.EventEnvelope::source)
            .thenComparing(Models.EventEnvelope::id));
        for (int i = 1; i < sorted.size(); i++) {
            Models.EventEnvelope previous = sorted.get(i - 1);
            Models.EventEnvelope current = sorted.get(i);
            if (previous.time() == current.time() && previous.priority() == current.priority()
                && previous.seq() == current.seq() && !previous.id().equals(current.id())) {
                throw conflict("cross_branch_order_ambiguous", List.of(previous, current));
            }
        }
        if (!preservesSharedOrder(sorted, shared) || !preservesRelativeOrder(sorted, leftEvents) || !preservesRelativeOrder(sorted, rightEvents)) {
            throw conflict("ordering_ambiguous", List.of(leftEvents.get(0), rightEvents.get(0)));
        }
        List<Models.EventEnvelope> normalized = new ArrayList<>();
        for (long i = 0; i < sorted.size(); i++) {
            Models.EventEnvelope event = sorted.get((int) i);
            normalized.add(new Models.EventEnvelope(event.id(), event.type(), event.time(), event.source(),
                event.seq(), event.payload(), event.internal(), event.parentEventId(), event.generation(),
                event.priority(), i));
        }
        return new MergePlan(normalized);
    }

    private ApiException conflict(String code, List<Models.EventEnvelope> events) {
        List<Object> group = new ArrayList<>();
        events.forEach(event -> group.add(event.toMap()));
        ApiException exception = new ApiException(409, code, "Branches are not merge-compatible: " + code);
        exception.details().put("firstConflictEvents", group);
        return exception;
    }

    private int indexOfId(List<Models.EventEnvelope> events, String id) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private boolean preservesSharedOrder(List<Models.EventEnvelope> merged, List<String> shared) {
        int position = -1;
        for (String id : shared) {
            int next = indexOfId(merged, id);
            if (next <= position) return false;
            position = next;
        }
        return true;
    }

    private boolean preservesRelativeOrder(List<Models.EventEnvelope> merged, List<Models.EventEnvelope> side) {
        int position = -1;
        for (Models.EventEnvelope event : side) {
            int next = indexOfId(merged, event.id());
            if (next <= position) return false;
            position = next;
        }
        return true;
    }

    private Branch initialBranch(Models.Definition definition) {
        return new Branch(
            "branch-main", "主干", definition.fingerprint(), null, null, null,
            definition.initialState(), new LinkedHashMap<>(definition.initialVars()),
            definition.seed(), new ArrayList<>(), new ArrayList<>(), 0,
            new ArrayList<>(), new ArrayList<>(), Engine.genesisHash(definition), false
        );
    }

    private Checkpoint genesisCheckpoint(Models.Definition definition, Branch branch) {
        return new Checkpoint(
            "genesis", "初始检查点", branch.id(), 0L, definition.fingerprint(), branch.lastTraceHash(),
            Engine.snapshot(definition.initialState(), definition.initialVars()), definition.initialState(),
            new LinkedHashMap<>(definition.initialVars()), definition.seed(), 0, new ArrayList<>(),
            new ArrayList<>(), null
        );
    }

    private String nextBranchId() {
        return "branch-" + (workspace.branches().size() + 1);
    }

    private String nextCheckpointId() {
        long max = 0;
        for (Checkpoint checkpoint : workspace.checkpoints()) {
            if (checkpoint.id().startsWith("checkpoint-")) {
                try {
                    max = Math.max(max, Long.parseLong(checkpoint.id().substring("checkpoint-".length())));
                } catch (NumberFormatException ignored) {
                    // generated ids are numeric
                }
            }
        }
        return "checkpoint-" + (max + 1);
    }

    private void replaceInPlace(List<Branch> branches, List<Checkpoint> checkpoints, String activeBranchId, String exportFingerprint) {
        workspace = new Workspace(
            workspace.id(), workspace.formatVersion(), workspace.definition(),
            List.copyOf(branches), List.copyOf(checkpoints),
            activeBranchId == null ? workspace.activeBranchId() : activeBranchId,
            workspace.createdAt(), exportFingerprint
        );
        validateWorkspace(workspace);
        save();
    }

    private void save() {
        store.save(workspace);
    }

    private void validateWorkspace(Workspace candidate) {
        candidate.definition().validate();
        String definitionFingerprint = candidate.definition().fingerprint();
        for (Branch branch : candidate.branches()) {
            if (!branch.definitionFingerprint().equals(definitionFingerprint)) {
                throw new ApiException(400, "branch_definition_mismatch", "Branch " + branch.id() + " does not match definition fingerprint");
            }
            verifyReplay(branch, candidate.definition());
        }
        for (Checkpoint checkpoint : candidate.checkpoints()) {
            if (!checkpoint.definitionFingerprint().equals(definitionFingerprint)) {
                throw new ApiException(400, "checkpoint_definition_mismatch", "Checkpoint " + checkpoint.id() + " does not match definition fingerprint");
            }
        }
    }

    private void verifyReplay(Branch branch, Models.Definition definition) {
        Branch rebuilt = new Branch(
            branch.id(), branch.name(), branch.definitionFingerprint(), branch.baseCheckpointId(),
            branch.forkedFromBranchId(), branch.parentCheckpointId(), definition.initialState(),
            new LinkedHashMap<>(definition.initialVars()), definition.seed(), branch.externalEvents(),
            new ArrayList<>(), 0, new ArrayList<>(), new ArrayList<>(), Engine.genesisHash(definition), branch.merged()
        );
        if (branch.parentCheckpointId() != null) {
            Checkpoint root = findCheckpointForValidation(branch.parentCheckpointId());
            rebuilt = new Branch(
                rebuilt.id(), rebuilt.name(), rebuilt.definitionFingerprint(), rebuilt.baseCheckpointId(),
                rebuilt.forkedFromBranchId(), rebuilt.parentCheckpointId(), root.currentState(),
                new LinkedHashMap<>(root.vars()), root.rngState(), rebuilt.externalEvents(),
                new ArrayList<>(root.pendingInternal()), root.nextExternal() - root.processedExternalIds().size(),
                new ArrayList<>(branch.steps().subList(0, (int) root.stepIndex())),
                new ArrayList<>(root.processedExternalIds()), root.traceHash(), rebuilt.merged()
            );
        }
        for (Step expected : branch.steps()) {
            if (rebuilt.steps().size() >= (branch.parentCheckpointId() == null ? 0 : findCheckpointForValidation(branch.parentCheckpointId()).stepIndex())
                && !Engine.canStep(rebuilt) && rebuilt.steps().size() < expected.index()) {
                throw new ApiException(400, "trace_replay_failed", "Cannot replay step " + expected.index());
            }
            rebuilt = Engine.step(rebuilt, definition);
            if (!rebuilt.lastTraceHash().equals(expected.stepHash())) {
                throw new ApiException(400, "trace_replay_failed", "Step " + expected.index() + " produced a different trace hash");
            }
        }
        if (!rebuilt.currentState().equals(branch.currentState()) || !rebuilt.vars().equals(branch.vars())
            || rebuilt.rngState() != branch.rngState() || rebuilt.nextExternal() != branch.nextExternal()) {
            throw new ApiException(400, "state_replay_failed", "Replayed final state does not match stored branch");
        }
    }

    private Checkpoint findCheckpointForValidation(String id) {
        return workspace == null ? null : workspace.checkpoints().stream()
            .filter(checkpoint -> checkpoint.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new ApiException(400, "checkpoint_not_found", "Missing checkpoint " + id));
    }
}
