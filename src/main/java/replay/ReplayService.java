package replay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReplayService {
    static final class RequestException extends RuntimeException {
        private final int status;

        RequestException(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    static final class MergeConflict extends RequestException {
        private final Map<String, Object> detail;

        MergeConflict(Map<String, Object> detail) {
            super(409, "branches contain incompatible external events");
            this.detail = detail;
        }

        Map<String, Object> detail() {
            return detail;
        }
    }

    private final Repository repository;
    private Store store;

    ReplayService(Repository repository) {
        this.repository = repository;
        Store loaded = repository.load();
        this.store = loaded == null ? initialize(Defaults.definition(), List.of()) : loaded;
        if (loaded == null) persist();
    }

    synchronized Map<String, Object> state() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("definition", store.definition().toMap());
        result.put("definitionFingerprint", store.definition().fingerprint());
        result.put("selectedBranchId", store.selectedBranchId());
        result.put("events", store.events().values().stream().sorted(this::compareEvents)
                .map(EventRecord::toMap).toList());
        result.put("branches", store.branches().values().stream().sorted((a, b) -> a.id().compareTo(b.id()))
                .map(this::branchView).toList());
        result.put("checkpoints", store.checkpoints().values().stream()
                .sorted((a, b) -> Long.compare(a.stepCount(), b.stepCount())).map(Checkpoint::toMap).toList());
        result.put("sessionHash", sessionHash());
        Branch branch = currentBranch();
        result.put("selectedBranch", branchView(branch));
        Checkpoint head = requireCheckpoint(branch.headCheckpointId());
        result.put("head", head.toMap());
        result.put("pending", head.pending().stream().map(EventRecord::toMap).toList());
        result.put("outputs", head.outputs().stream().map(OutputRecord::toMap).toList());
        result.put("traces", head.traces().stream().map(TraceRecord::toMap).toList());
        result.put("lastTrace", head.traces().isEmpty() ? null : head.traces().get(head.traces().size() - 1).toMap());
        return result;
    }

    synchronized Map<String, Object> updateDefinition(Map<String, Object> request) {
        MachineDefinition updated = MachineDefinition.fromMap(request);
        MachineDefinition old = store.definition();
        if (!old.fingerprint().equals(updated.fingerprint())) {
            String branchId = uniqueBranchId("definition");
            Engine engine = Engine.initial(updated, branchId, List.of());
            Checkpoint checkpoint = engine.checkpoint("definition-initial", true);
            store.checkpoints().put(checkpoint.id(), checkpoint);
            Branch branch = new Branch(branchId, "定义 " + updated.version(), checkpoint.id(),
                    updated.fingerprint(), "", checkpoint.id());
            store.branches().put(branchId, branch);
            store = new Store(updated, store.events(), store.checkpoints(), store.branches(), branchId);
            persist();
        }
        return state();
    }

    synchronized Map<String, Object> importEvents(List<Object> rawEvents) {
        List<EventRecord> imported = normalizeExternalEvents(rawEvents);
        ensureUniqueIds(imported, true);
        for (EventRecord event : imported) {
            store.events().put(event.id(), event);
        }
        String branchId = uniqueBranchId("imported");
        Engine engine = Engine.initial(store.definition(), branchId, imported);
        Checkpoint checkpoint = engine.checkpoint("initial", true);
        store.checkpoints().put(checkpoint.id(), checkpoint);
        Branch branch = new Branch(branchId, "导入事件分支 " + imported.size(), checkpoint.id(),
                store.definition().fingerprint(), "", checkpoint.id());
        store.branches().put(branch.id(), branch);
        store = new Store(store.definition(), store.events(), store.checkpoints(), store.branches(), branchId);
        persist();
        return state();
    }

    synchronized Map<String, Object> appendExternal(String branchId, Map<String, Object> request) {
        Branch branch = requireBranch(branchId);
        requireCurrentDefinition(branch);
        EventRecord event = EventRecord.externalFromMap(request);
        if (store.events().containsKey(event.id())) {
            throw new RequestException(409, "event id already exists: " + event.id());
        }
        Engine engine = engineFor(branch);
        engine.appendExternal(event);
        Checkpoint checkpoint = engine.checkpoint("append-external", false);
        store.events().put(event.id(), event);
        store.checkpoints().put(checkpoint.id(), checkpoint);
        updateHead(branch, checkpoint);
        persist();
        return state();
    }

    synchronized Map<String, Object> step(String branchId, int count, String reason) {
        Branch branch = requireBranch(branchId);
        requireCurrentDefinition(branch);
        Engine engine = engineFor(branch);
        Checkpoint checkpoint = null;
        TraceRecord lastTrace = null;
        int safeCount = Math.max(1, Math.min(count, 10000));
        for (int i = 0; i < safeCount; i++) {
            if (!engine.hasPending()) break;
            Engine.StepResult result = engine.step(reason == null || reason.isBlank() ? "step" : reason);
            checkpoint = result.checkpoint();
            lastTrace = result.trace();
            store.checkpoints().put(checkpoint.id(), checkpoint);
        }
        if (checkpoint != null) updateHead(branch, checkpoint);
        persist();
        return state();
    }

    synchronized Map<String, Object> checkpoint(String branchId, String name) {
        Branch branch = requireBranch(branchId);
        requireCurrentDefinition(branch);
        Checkpoint existing = requireCheckpoint(branch.headCheckpointId());
        Engine engine = Engine.restore(store.definition(), existing);
        Checkpoint checkpoint = engine.checkpoint(name == null || name.isBlank() ? "manual" : name, true);
        if (store.checkpoints().containsKey(checkpoint.id()) && !checkpoint.id().equals(existing.id())) {
            throw new RequestException(409, "identical checkpoint already exists");
        }
        store.checkpoints().put(checkpoint.id(), checkpoint);
        updateHead(branch, checkpoint);
        persist();
        return state();
    }

    synchronized Map<String, Object> fork(String checkpointId, String name) {
        Checkpoint origin = requireCheckpoint(checkpointId);
        if (!store.definition().fingerprint().equals(origin.definitionFingerprint())) {
            throw new RequestException(409, "checkpoint definition fingerprint does not match current definition");
        }
        String branchId = uniqueBranchId("fork");
        Engine engine = Engine.restore(store.definition(), origin);
        engine.beginBranch(branchId, origin.id());
        Checkpoint start = engine.checkpoint("fork-start", true);
        store.checkpoints().put(start.id(), start);
        Branch source = findBranchWithHead(origin.id());
        String parentId = source == null ? "" : source.id();
        Branch branch = new Branch(branchId, name == null || name.isBlank() ? "分叉 " + branchId : name,
                start.id(), store.definition().fingerprint(), parentId, start.id());
        store.branches().put(branchId, branch);
        store = new Store(store.definition(), store.events(), store.checkpoints(), store.branches(), branchId);
        persist();
        return state();
    }

    synchronized Map<String, Object> exportSession() {
        return store.toMap();
    }

    synchronized String sessionHash() {
        return Hashes.sha256(exportSession());
    }

    synchronized Map<String, Object> importSession(Map<String, Object> request) {
        Store imported = Store.fromMap(request);
        String importedHash = Hashes.sha256(imported.toMap());
        this.store = imported;
        persist();
        String reloadedHash = sessionHash();
        if (!importedHash.equals(reloadedHash)) {
            throw new IllegalStateException("session import changed trajectory hash");
        }
        return state();
    }

    synchronized Map<String, Object> mergeBranches(String targetId, String sourceId) {
        Branch target = requireBranch(targetId);
        Branch source = requireBranch(sourceId);
        requireCurrentDefinition(target);
        requireCurrentDefinition(source);
        Checkpoint targetHead = requireCheckpoint(target.headCheckpointId());
        Checkpoint sourceHead = requireCheckpoint(source.headCheckpointId());
        Checkpoint ancestor = commonAncestor(targetHead, sourceHead);

        List<String> targetSuffix = suffixExternal(ancestor, targetHead);
        List<String> sourceSuffix = suffixExternal(ancestor, sourceHead);
        int limit = Math.min(targetSuffix.size(), sourceSuffix.size());
        for (int i = 0; i < limit; i++) {
            String targetEventId = targetSuffix.get(i);
            String sourceEventId = sourceSuffix.get(i);
            EventRecord targetEvent = requireEvent(targetEventId);
            EventRecord sourceEvent = requireEvent(sourceEventId);
            if (!targetEventId.equals(sourceEventId)
                    || !Hashes.sha256(eventMergeKey(targetEvent)).equals(
                            Hashes.sha256(eventMergeKey(sourceEvent)))) {
                throw new MergeConflict(conflict(i, targetEvent, sourceEvent));
            }
        }
        if (targetSuffix.size() != sourceSuffix.size()) {
            EventRecord targetEvent = targetSuffix.size() > limit
                    ? requireEvent(targetSuffix.get(limit)) : null;
            EventRecord sourceEvent = sourceSuffix.size() > limit
                    ? requireEvent(sourceSuffix.get(limit)) : null;
            throw new MergeConflict(conflict(limit, targetEvent, sourceEvent));
        }

        Engine merged = Engine.restore(store.definition(), ancestor);
        merged.beginBranch(target.id(), targetHead.id());
        Set<String> queuedExternal = new HashSet<>();
        merged.peek();
        for (EventRecord pending : ancestor.pending()) {
            if (!pending.internal()) queuedExternal.add(pending.id());
        }
        for (String eventId : targetSuffix) {
            EventRecord event = requireEvent(eventId);
            if (queuedExternal.add(eventId)) {
                merged.appendExternal(event);
            }
            while (merged.hasPending()) {
                EventRecord next = merged.peek();
                boolean beforeExternal = !next.internal();
                Engine.StepResult result = merged.step("merge-replay");
                store.checkpoints().put(result.checkpoint().id(), result.checkpoint());
                if (beforeExternal && next.id().equals(eventId)) break;
            }
        }
        while (merged.hasPending() && merged.peek().internal()) {
            Engine.StepResult result = merged.step("merge-close");
            store.checkpoints().put(result.checkpoint().id(), result.checkpoint());
        }
        Checkpoint mergedCheckpoint = merged.checkpoint("merge", true);
        store.checkpoints().put(mergedCheckpoint.id(), mergedCheckpoint);
        if (targetSuffix.equals(sourceSuffix) && !targetHead.id().equals(sourceHead.id())
                && !sameTrajectoryHash(targetHead, sourceHead)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("index", limit);
            detail.put("reason", "same external events but internal closure is not at an unambiguous quiescent point");
            detail.put("targetHead", targetHead.id());
            detail.put("sourceHead", sourceHead.id());
            throw new MergeConflict(detail);
        }
        updateHead(target, mergedCheckpoint);
        persist();
        return state();
    }

    private Store initialize(MachineDefinition definition, List<EventRecord> externalEvents) {
        String branchId = "main";
        Engine engine = Engine.initial(definition, branchId, externalEvents);
        Checkpoint checkpoint = engine.checkpoint("initial", true);
        Map<String, EventRecord> events = new LinkedHashMap<>();
        for (EventRecord event : externalEvents) events.put(event.id(), event);
        Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();
        checkpoints.put(checkpoint.id(), checkpoint);
        Map<String, Branch> branches = new LinkedHashMap<>();
        branches.put(branchId, new Branch(branchId, "主线", checkpoint.id(), definition.fingerprint(), "", checkpoint.id()));
        return new Store(definition, events, checkpoints, branches, branchId);
    }

    private void persist() {
        repository.save(store);
    }

    private Branch currentBranch() {
        return requireBranch(store.selectedBranchId());
    }

    private Branch requireBranch(String id) {
        String actual = id == null || id.isBlank() ? store.selectedBranchId() : id;
        Branch branch = store.branches().get(actual);
        if (branch == null) throw new RequestException(404, "branch not found: " + actual);
        return branch;
    }

    private Checkpoint requireCheckpoint(String id) {
        Checkpoint checkpoint = store.checkpoints().get(id);
        if (checkpoint == null) throw new RequestException(404, "checkpoint not found: " + id);
        return checkpoint;
    }

    private EventRecord requireEvent(String id) {
        EventRecord event = store.events().get(id);
        if (event == null) throw new RequestException(404, "event not found: " + id);
        return event;
    }

    private void requireCurrentDefinition(Branch branch) {
        if (!store.definition().fingerprint().equals(branch.definitionFingerprint())) {
            throw new RequestException(409, "branch was created under an older definition fingerprint");
        }
    }

    private Engine engineFor(Branch branch) {
        requireCurrentDefinition(branch);
        return Engine.restore(store.definition(), requireCheckpoint(branch.headCheckpointId()));
    }

    private void updateHead(Branch branch, Checkpoint checkpoint) {
        Branch updated = new Branch(branch.id(), branch.name(), checkpoint.id(),
                branch.definitionFingerprint(), branch.parentBranchId(), branch.startCheckpointId());
        store.branches().put(branch.id(), updated);
        store = new Store(store.definition(), store.events(), store.checkpoints(), store.branches(), branch.id());
    }

    private List<EventRecord> normalizeExternalEvents(List<Object> rawEvents) {
        List<EventRecord> events = new ArrayList<>();
        for (Object raw : rawEvents) {
            events.add(EventRecord.externalFromMap(Json.object(raw, "event")));
        }
        events.sort(this::compareEvents);
        return events;
    }

    private void ensureUniqueIds(List<EventRecord> events, boolean allowExistingSameContent) {
        Set<String> batch = new HashSet<>();
        for (EventRecord event : events) {
            if (!batch.add(event.id())) throw new RequestException(400, "duplicate event id: " + event.id());
            EventRecord existing = store.events().get(event.id());
            if (existing != null) {
                String incoming = Hashes.sha256(eventMergeKey(event));
                String current = Hashes.sha256(eventMergeKey(existing));
                if (!incoming.equals(current) || !allowExistingSameContent) {
                    throw new RequestException(409, "event id conflicts with existing event: " + event.id());
                }
            }
        }
    }

    private int compareEvents(EventRecord a, EventRecord b) {
        int result = Long.compare(a.tick(), b.tick());
        if (result != 0) return result;
        result = Integer.compare(a.sourcePriority(), b.sourcePriority());
        if (result != 0) return result;
        result = Long.compare(a.sourceSequence(), b.sourceSequence());
        if (result != 0) return result;
        return a.id().compareTo(b.id());
    }

    private Map<String, Object> branchView(Branch branch) {
        Map<String, Object> map = branch.toMap();
        Checkpoint head = requireCheckpoint(branch.headCheckpointId());
        map.put("state", head.state());
        map.put("data", head.data());
        map.put("stepCount", head.stepCount());
        map.put("pendingCount", head.pending().size());
        map.put("outputCount", head.outputs().size());
        map.put("currentDefinition", branch.definitionFingerprint().equals(store.definition().fingerprint()));
        return map;
    }

    private String uniqueBranchId(String prefix) {
        int counter = store.branches().size() + 1;
        while (true) {
            String id = prefix + "-" + counter;
            if (!store.branches().containsKey(id)) return id;
            counter++;
        }
    }

    private Branch findBranchWithHead(String checkpointId) {
        return store.branches().values().stream()
                .filter(branch -> branch.headCheckpointId().equals(checkpointId)).findFirst().orElse(null);
    }

    private Checkpoint commonAncestor(Checkpoint left, Checkpoint right) {
        Set<String> leftChain = new HashSet<>();
        Checkpoint current = left;
        while (current != null) {
            leftChain.add(current.id());
            current = current.parentId() == null || current.parentId().isBlank()
                    ? null : store.checkpoints().get(current.parentId());
        }
        current = right;
        while (current != null) {
            if (leftChain.contains(current.id())) return current;
            current = current.parentId() == null || current.parentId().isBlank()
                    ? null : store.checkpoints().get(current.parentId());
        }
        throw new RequestException(400, "branches do not share a checkpoint ancestor");
    }

    private List<String> suffixExternal(Checkpoint ancestor, Checkpoint head) {
        List<String> all = head.externalIds();
        List<String> prefix = ancestor.externalIds();
        if (all.size() < prefix.size() || !new java.util.ArrayList<>(all.subList(0, prefix.size())).equals(prefix)) {
            throw new RequestException(400, "ancestor external prefix is not contained in branch history");
        }
        return new ArrayList<>(all.subList(prefix.size(), all.size()));
    }

    private Map<String, Object> eventMergeKey(EventRecord event) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("event", event.event());
        key.put("tick", event.tick());
        key.put("sourcePriority", event.sourcePriority());
        key.put("sourceSequence", event.sourceSequence());
        key.put("payload", event.payload());
        return key;
    }

    private Map<String, Object> conflict(int index, EventRecord targetEvent, EventRecord sourceEvent) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("index", index);
        detail.put("reason", "first conflicting external event after common ancestor");
        detail.put("target", targetEvent == null ? null : targetEvent.toMap());
        detail.put("source", sourceEvent == null ? null : sourceEvent.toMap());
        return detail;
    }

    private boolean sameTrajectoryHash(Checkpoint left, Checkpoint right) {
        return Hashes.sha256(trajectoryKey(left)).equals(Hashes.sha256(trajectoryKey(right)));
    }

    private Map<String, Object> trajectoryKey(Checkpoint checkpoint) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("state", checkpoint.state());
        key.put("data", checkpoint.data());
        key.put("pending", checkpoint.pending().stream().map(EventRecord::toMap).toList());
        key.put("outputs", checkpoint.outputs().stream().map(OutputRecord::toMap).toList());
        key.put("externalIds", checkpoint.externalIds());
        key.put("emissionCount", checkpoint.emissionCount());
        return key;
    }
}
