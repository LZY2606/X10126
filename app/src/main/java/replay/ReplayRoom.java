package replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReplayRoom {
    private static final String ROOT = "checkpoint-root";

    private String sessionId = "session";
    private Models.Definition definition;
    private String definitionFingerprint;
    private final Map<String, Models.Checkpoint> checkpoints = new LinkedHashMap<>();
    private final Map<String, Models.Branch> branches = new LinkedHashMap<>();
    private final List<String> branchOrder = new ArrayList<>();
    private int branchSequence = 1;
    private int checkpointSequence = 1;

    public ReplayRoom(Models.Definition definition) {
        this.definition = definition;
        this.definitionFingerprint = DefinitionCodec.fingerprint(definition);
        createRoot();
        Models.Branch main = new Models.Branch();
        main.id = "main";
        main.name = "main";
        main.baseCheckpointId = ROOT;
        main.headCheckpointId = ROOT;
        branches.put(main.id, main);
        branchOrder.add(main.id);
    }

    public synchronized Map<String, Object> state() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("sessionId", sessionId);
        json.put("definition", DefinitionCodec.write(definition));
        json.put("definitionFingerprint", definitionFingerprint);
        json.put("branches", branchOrder.stream().map(this::branchSummary).toList());
        json.put("checkpoints", checkpoints.values().stream().map(checkpoint -> checkpointSummary(checkpoint.id)).toList());
        return json;
    }

    public synchronized Map<String, Object> branchState(String branchId) {
        Models.Branch branch = requireBranch(branchId);
        Map<String, Object> json = branchSummary(branchId);
        json.put("events", branch.events.stream().map(EventCodec::write).toList());
        json.put("trace", branch.trace.stream().map(step -> StepCodec.write(step, false)).toList());
        return json;
    }

    public synchronized void importEvents(String branchId, Object eventsJson, boolean replace) {
        Models.Branch branch = requireBranch(branchId);
        List<Models.EventEnvelope> imported = EventCodec.parseExternals(eventsJson);
        List<Models.EventEnvelope> target;
        if (replace) {
            target = new ArrayList<>(imported);
        } else {
            target = new ArrayList<>(branch.events);
            target.addAll(imported);
        }
        validateExternalEvents(target, processedExternalIds(branch));
        branch.events = StateMachine.sortExternal(target, definition.sourcePriority);
        persistHook();
    }

    public synchronized Map<String, Object> step(String branchId, int count) {
        Models.Branch branch = requireBranch(branchId);
        if (count <= 0) count = 1;
        for (int i = 0; i < count; i++) {
            Models.EventEnvelope event = nextEvent(branch);
            if (event == null) break;
            execute(branch, event);
        }
        persistHook();
        return branchState(branchId);
    }

    public synchronized Map<String, Object> checkpoint(String branchId, String name) {
        Models.Branch branch = requireBranch(branchId);
        Models.Checkpoint checkpoint = new Models.Checkpoint();
        checkpoint.id = "checkpoint-" + checkpointSequence++;
        checkpoint.branchId = branchId;
        checkpoint.step = branch.cursor;
        checkpoint.parentCheckpointId = branch.headCheckpointId;
        checkpoint.definitionFingerprint = definitionFingerprint;
        checkpoint.stateHash = currentStateHash(branch.id);
        checkpoint.traceHash = currentTraceHash(branch.id);
        checkpoint.state = currentState(branch.id);
        checkpoint.variables = currentVariables(branch.id);
        checkpoint.randomState = currentRandomState(branch.id);
        checkpoint.internalQueue = currentInternalQueue(branch.id);
        checkpoints.put(checkpoint.id, checkpoint);
        branch.headCheckpointId = checkpoint.id;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", checkpoint.id);
        metadata.put("name", name == null || name.isBlank() ? checkpoint.id : name);
        persistHook();
        return checkpointSummary(checkpoint.id);
    }

    public synchronized Map<String, Object> fork(String checkpointId, String name) {
        Models.Checkpoint base = requireCheckpoint(checkpointId);
        verifyDefinition(base);
        Models.Branch source = requireBranch(base.branchId);
        Models.Branch branch = new Models.Branch();
        branch.id = "branch-" + branchSequence++;
        branch.name = name == null || name.isBlank() ? branch.id : name;
        branch.baseCheckpointId = checkpointId;
        branch.headCheckpointId = checkpointId;
        branch.events = processedEventsAt(source, base.step);
        branch.cursor = base.step;
        branch.trace = new ArrayList<>(source.trace.subList(0, Math.min(base.step, source.trace.size())));
        branches.put(branch.id, branch);
        branchOrder.add(branch.id);
        persistHook();
        return branchSummary(branch.id);
    }

    public synchronized Map<String, Object> compare(String leftId, String rightId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("left", branchSummary(leftId));
        result.put("right", branchSummary(rightId));
        result.put("commonAncestor", commonAncestorSummary(leftId, rightId));
        result.put("stateDiff", diff(currentStateView(leftId), currentStateView(rightId)));
        result.put("leftOutputs", outputs(leftId));
        result.put("rightOutputs", outputs(rightId));
        return result;
    }

    public synchronized Map<String, Object> merge(String leftId, String rightId, String name) {
        Models.Checkpoint ancestor = requireCheckpoint(commonAncestor(leftId, rightId));
        verifyDefinition(ancestor);
        Models.Branch left = requireBranch(leftId);
        Models.Branch right = requireBranch(rightId);
        int ancestorStep = ancestor.step;

        Map<String, Models.EventEnvelope> leftEvents = externalAfter(left, ancestorStep);
        Map<String, Models.EventEnvelope> rightEvents = externalAfter(right, ancestorStep);
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(leftEvents.keySet());
        ids.addAll(rightEvents.keySet());
        List<Models.EventEnvelope> union = new ArrayList<>();
        for (String id : ids) {
            Models.EventEnvelope a = leftEvents.get(id);
            Models.EventEnvelope b = rightEvents.get(id);
            if (a != null && b != null && !sameExternal(a, b)) {
                throw conflict("id", a, b, "事件 ID 相同但内容不同");
            }
            union.add(a != null ? a : b);
        }
        union = StateMachine.sortExternal(union, definition.sourcePriority);
        for (int i = 1; i < union.size(); i++) {
            Models.EventEnvelope a = union.get(i - 1);
            Models.EventEnvelope b = union.get(i);
            if (sameOrderKey(a, b) && !a.id.equals(b.id)) {
                throw conflict("order", a, b, "第一组同逻辑时刻、同来源优先级且同序号的事件顺序有歧义");
            }
        }

        Models.Branch merged = new Models.Branch();
        merged.id = "branch-" + branchSequence++;
        merged.name = name == null || name.isBlank() ? "merge-" + left.id + "-" + right.id : name;
        merged.baseCheckpointId = ancestor.id;
        merged.headCheckpointId = ancestor.id;
        merged.events = processedEventsAt(left, ancestorStep);
        merged.events.addAll(union);
        merged.events = StateMachine.sortExternal(merged.events, definition.sourcePriority);
        merged.cursor = ancestorStep;
        merged.trace = new ArrayList<>(left.trace.subList(0, Math.min(ancestorStep, left.trace.size())));
        branches.put(merged.id, merged);
        branchOrder.add(merged.id);
        while (nextEvent(merged) != null) execute(merged, nextEvent(merged));
        persistHook();
        return branchSummary(merged.id);
    }

    public synchronized Map<String, Object> exportSession() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("format", "deterministic-state-replay-session/v1");
        json.put("sessionId", sessionId);
        json.put("definition", DefinitionCodec.write(definition));
        json.put("definitionFingerprint", definitionFingerprint);
        json.put("branchSequence", branchSequence);
        json.put("checkpointSequence", checkpointSequence);
        json.put("branches", branchOrder.stream().map(id -> writeBranch(requireBranch(id))).toList());
        json.put("checkpoints", checkpoints.values().stream().map(this::writeCheckpoint).toList());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("session", json);
        envelope.put("sessionFingerprint", Models.sha256(Json.writeCanonical(json)));
        return envelope;
    }

    public synchronized String exportJson() {
        return Json.write(exportSession());
    }

    public static ReplayRoom importSession(Object jsonObject) {
        Map<String, Object> envelope = Json.object(jsonObject);
        Map<String, Object> session = Json.object(Json.required(envelope, "session"));
        String fingerprint = Json.optionalString(envelope, "sessionFingerprint", "");
        if (!fingerprint.isBlank() && !fingerprint.equals(Models.sha256(Json.writeCanonical(session)))) {
            throw new IllegalArgumentException("session fingerprint mismatch");
        }
        Models.Definition parsedDefinition = DefinitionCodec.parse(Json.object(Json.required(session, "definition")));
        ReplayRoom room = new ReplayRoom(parsedDefinition);
        if (!DefinitionCodec.fingerprint(parsedDefinition).equals(Json.optionalString(session, "definitionFingerprint", ""))) {
            throw new IllegalArgumentException("definition fingerprint mismatch");
        }
        room.sessionId = Json.optionalString(session, "sessionId", "session");
        room.branchSequence = (int) Json.optionalLong(session, "branchSequence", 1L);
        room.checkpointSequence = (int) Json.optionalLong(session, "checkpointSequence", 1L);
        room.checkpoints.clear();
        room.branches.clear();
        room.branchOrder.clear();
        for (Object checkpoint : Json.list(Json.required(session, "checkpoints"))) {
            Models.Checkpoint parsed = CheckpointCodec.read(Json.object(checkpoint));
            room.checkpoints.put(parsed.id, parsed);
        }
        for (Object branch : Json.list(Json.required(session, "branches"))) {
            Models.Branch parsed = BranchCodec.read(Json.object(branch));
            room.branches.put(parsed.id, parsed);
            room.branchOrder.add(parsed.id);
        }
        for (Models.Checkpoint checkpoint : room.checkpoints.values()) {
            if (!room.definitionFingerprint.equals(checkpoint.definitionFingerprint)) {
                throw new VersionException(checkpoint.id, checkpoint.definitionFingerprint, room.definitionFingerprint);
            }
        }
        room.verifyLoaded();
        return room;
    }

    synchronized void replaceDefinition(Models.Definition updated) {
        String newFingerprint = DefinitionCodec.fingerprint(updated);
        if (!newFingerprint.equals(definitionFingerprint) && (branches.size() > 1 || !branches.get("main").trace.isEmpty())) {
            throw new IllegalArgumentException("定义已有回放历史；请创建新会话以避免旧轨迹连接新定义");
        }
        this.definition = updated;
        this.definitionFingerprint = newFingerprint;
        checkpoints.clear();
        createRoot();
        Models.Branch main = branches.get("main");
        main.baseCheckpointId = ROOT;
        main.headCheckpointId = ROOT;
        main.events = new ArrayList<>();
        main.cursor = 0;
        main.trace = new ArrayList<>();
        persistHook();
    }

    private void execute(Models.Branch branch, Models.EventEnvelope event) {
        Models.Checkpoint head = checkpoints.get(branch.headCheckpointId);
        StateMachine.StepInput input = new StateMachine.StepInput();
        input.stepNumber = branch.cursor + 1;
        input.state = head.state;
        input.variables = head.variables;
        input.randomState = head.randomState;
        input.event = event;
        input.internalOrderBase = branch.trace.isEmpty() ? 0 : branch.trace.get(branch.trace.size() - 1).step * 100000;
        StateMachine.StepOutcome outcome = new StateMachine(definition).step(input);

        List<Models.EventEnvelope> queue = new ArrayList<>(head.internalQueue);
        if (event.internal) {
            removeFirstInternal(queue, event);
        }
        queue.addAll(outcome.emitted);
        queue.sort((a, b) -> Long.compare(a.time, b.time) == 0 ? Integer.compare(a.internalOrder, b.internalOrder) : Long.compare(a.time, b.time));

        Models.Checkpoint newHead = new Models.Checkpoint();
        newHead.id = "step-" + branch.id + "-" + outcome.record.step;
        newHead.branchId = branch.id;
        newHead.step = outcome.record.step;
        newHead.parentCheckpointId = branch.headCheckpointId;
        newHead.definitionFingerprint = definitionFingerprint;
        newHead.state = outcome.state;
        newHead.variables = outcome.variables;
        newHead.randomState = outcome.randomState;
        newHead.internalQueue = queue;
        newHead.stateHash = StateMachine.stateHash(outcome.state, outcome.variables);
        newHead.traceHash = chainHash(branch, outcome.record, newHead.stateHash);
        outcome.record.traceHash = newHead.traceHash;
        outcome.record.stateHash = newHead.stateHash;
        if (checkpoints.containsKey(newHead.id)) throw new IllegalStateException("duplicate transient checkpoint " + newHead.id);
        checkpoints.put(newHead.id, newHead);
        branch.headCheckpointId = newHead.id;
        branch.cursor = outcome.record.step;
        branch.trace.add(outcome.record);
    }

    private Models.EventEnvelope nextEvent(Models.Branch branch) {
        Models.Checkpoint head = checkpoints.get(branch.headCheckpointId);
        Models.EventEnvelope internal = head.internalQueue.isEmpty() ? null : head.internalQueue.get(0);
        Models.EventEnvelope external = null;
        Set<String> processed = processedExternalIds(branch);
        for (Models.EventEnvelope candidate : branch.events) {
            if (!processed.contains(candidate.id)) {
                external = candidate;
                break;
            }
        }
        if (internal == null) return external;
        if (external == null) return internal;
        return eventOrder(internal, external) <= 0 ? internal : external;
    }

    private int eventOrder(Models.EventEnvelope left, Models.EventEnvelope right) {
        int byTime = Long.compare(left.time, right.time);
        if (byTime != 0) return byTime;
        int byPriority = Integer.compare(
                definition.sourcePriority.getOrDefault(left.source, 0),
                definition.sourcePriority.getOrDefault(right.source, 0));
        if (byPriority != 0) return -byPriority;
        int bySequence = Long.compare(left.sequence, right.sequence);
        if (bySequence != 0) return bySequence;
        return left.id.compareTo(right.id);
    }

    private void removeFirstInternal(List<Models.EventEnvelope> queue, Models.EventEnvelope event) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).id.equals(event.id)) {
                queue.remove(i);
                return;
            }
        }
        throw new IllegalStateException("internal event missing from deterministic queue");
    }

    private void validateExternalEvents(List<Models.EventEnvelope> events, Set<String> processedIds) {
        Set<String> ids = new HashSet<>();
        for (Models.EventEnvelope event : events) {
            if (event.internal) throw new IllegalArgumentException("imported events must be external");
            if (!ids.add(event.id)) throw new IllegalArgumentException("duplicate event id: " + event.id);
        }
        List<Models.EventEnvelope> sorted = StateMachine.sortExternal(events, definition.sourcePriority);
        List<String> processedOrder = new ArrayList<>();
        for (Models.EventEnvelope event : sorted) {
            if (processedIds.contains(event.id)) processedOrder.add(event.id);
        }
        int index = 0;
        for (Models.EventEnvelope event : sorted) {
            if (index < processedOrder.size() && !event.id.equals(processedOrder.get(index))) {
                throw new IllegalArgumentException("不能改变已经处理事件的稳定顺序，冲突事件: " + event.id + " / " + processedOrder.get(index));
            }
            if (processedIds.contains(event.id)) index++;
        }
    }

    private Set<String> processedExternalIds(Models.Branch branch) {
        Set<String> result = new LinkedHashSet<>();
        for (Models.StepRecord step : branch.trace) {
            if (!step.event.internal) result.add(step.event.id);
        }
        return result;
    }

    private List<Models.EventEnvelope> processedEventsAt(Models.Branch source, int step) {
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(step, source.trace.size()); i++) {
            Models.EventEnvelope event = source.trace.get(i).event;
            if (!event.internal) ids.add(event.id);
        }
        List<Models.EventEnvelope> result = new ArrayList<>();
        for (Models.EventEnvelope event : source.events) {
            if (ids.contains(event.id)) result.add(event);
        }
        return result;
    }

    private void createRoot() {
        Models.Checkpoint root = new Models.Checkpoint();
        root.id = ROOT;
        root.branchId = "main";
        root.step = 0;
        root.parentCheckpointId = null;
        root.definitionFingerprint = definitionFingerprint;
        root.state = definition.initialState;
        root.variables = Models.cloneJson(definition.initialVariables);
        root.randomState = definition.seed;
        root.stateHash = StateMachine.stateHash(root.state, root.variables);
        root.traceHash = Models.sha256("deterministic-state-replay-room:v1");
        checkpoints.put(root.id, root);
    }

    private Models.Branch requireBranch(String id) {
        Models.Branch branch = branches.get(id);
        if (branch == null) throw new IllegalArgumentException("branch not found: " + id);
        return branch;
    }

    private Models.Checkpoint requireCheckpoint(String id) {
        Models.Checkpoint checkpoint = checkpoints.get(id);
        if (checkpoint == null) throw new IllegalArgumentException("checkpoint not found: " + id);
        return checkpoint;
    }

    private void verifyDefinition(Models.Checkpoint checkpoint) {
        if (!definitionFingerprint.equals(checkpoint.definitionFingerprint)) {
            throw new VersionException(checkpoint.id, checkpoint.definitionFingerprint, definitionFingerprint);
        }
    }

    private String currentState(String branchId) {
        return checkpoints.get(requireBranch(branchId).headCheckpointId).state;
    }

    private Map<String, Object> currentVariables(String branchId) {
        return checkpoints.get(requireBranch(branchId).headCheckpointId).variables;
    }

    private long currentRandomState(String branchId) {
        return checkpoints.get(requireBranch(branchId).headCheckpointId).randomState;
    }

    private String currentStateHash(String branchId) {
        return checkpoints.get(requireBranch(branchId).headCheckpointId).stateHash;
    }

    private String currentTraceHash(String branchId) {
        return checkpoints.get(requireBranch(branchId).headCheckpointId).traceHash;
    }

    private List<Models.EventEnvelope> currentInternalQueue(String branchId) {
        return checkpoints.get(requireBranch(branchId).headCheckpointId).internalQueue;
    }

    private Map<String, Object> currentStateView(String branchId) {
        Models.Checkpoint head = checkpoints.get(requireBranch(branchId).headCheckpointId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("state", head.state);
        view.put("vars", head.variables);
        return view;
    }

    private List<Map<String, Object>> outputs(String branchId) {
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (Models.StepRecord step : requireBranch(branchId).trace) outputs.addAll(step.outputs);
        return outputs;
    }

    private Map<String, Models.EventEnvelope> externalAfter(Models.Branch branch, int step) {
        Set<String> processedAtAncestor = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(step, branch.trace.size()); i++) {
            Models.EventEnvelope event = branch.trace.get(i).event;
            if (!event.internal) processedAtAncestor.add(event.id);
        }
        Map<String, Models.EventEnvelope> result = new LinkedHashMap<>();
        for (Models.EventEnvelope event : branch.events) {
            if (!processedAtAncestor.contains(event.id)) result.put(event.id, event);
        }
        return result;
    }

    private boolean sameExternal(Models.EventEnvelope left, Models.EventEnvelope right) {
        return Json.writeCanonical(EventCodec.external(left))
                .equals(Json.writeCanonical(EventCodec.external(right)));
    }

    private boolean sameOrderKey(Models.EventEnvelope left, Models.EventEnvelope right) {
        return left.time == right.time
                && definition.sourcePriority.getOrDefault(left.source, 0) == definition.sourcePriority.getOrDefault(right.source, 0)
                && left.sequence == right.sequence;
    }

    private IllegalArgumentException conflict(String kind, Models.EventEnvelope left, Models.EventEnvelope right, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", kind);
        details.put("message", message);
        details.put("left", EventCodec.write(left));
        details.put("right", EventCodec.write(right));
        return new MergeConflictException(message, details);
    }

    private String commonAncestor(String leftId, String rightId) {
        Set<String> leftAncestors = ancestors(requireBranch(leftId).baseCheckpointId);
        String current = requireBranch(rightId).baseCheckpointId;
        while (!leftAncestors.contains(current)) {
            Models.Checkpoint checkpoint = requireCheckpoint(current);
            if (checkpoint.parentCheckpointId == null) break;
            current = checkpoint.parentCheckpointId;
        }
        if (!leftAncestors.contains(current)) throw new IllegalArgumentException("branches have no common ancestor");
        return current;
    }

    private Set<String> ancestors(String checkpointId) {
        Set<String> result = new LinkedHashSet<>();
        String current = checkpointId;
        while (current != null) {
            result.add(current);
            current = requireCheckpoint(current).parentCheckpointId;
        }
        return result;
    }

    private Map<String, Object> commonAncestorSummary(String leftId, String rightId) {
        return checkpointSummary(commonAncestor(leftId, rightId));
    }

    private Map<String, Object> branchSummary(String branchId) {
        Models.Branch branch = requireBranch(branchId);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", branch.id);
        json.put("name", branch.name);
        json.put("baseCheckpointId", branch.baseCheckpointId);
        json.put("headCheckpointId", branch.headCheckpointId);
        json.put("cursor", branch.cursor);
        json.put("eventCount", branch.events.size());
        Models.Checkpoint head = checkpoints.get(branch.headCheckpointId);
        json.put("state", head.state);
        json.put("vars", head.variables);
        json.put("stateHash", head.stateHash);
        json.put("traceHash", head.traceHash);
        json.put("pendingInternal", head.internalQueue.size());
        return json;
    }

    private Map<String, Object> checkpointSummary(String checkpointId) {
        Models.Checkpoint checkpoint = requireCheckpoint(checkpointId);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", checkpoint.id);
        json.put("branchId", checkpoint.branchId);
        json.put("step", checkpoint.step);
        json.put("parentCheckpointId", checkpoint.parentCheckpointId);
        json.put("definitionFingerprint", checkpoint.definitionFingerprint);
        json.put("state", checkpoint.state);
        json.put("stateHash", checkpoint.stateHash);
        json.put("traceHash", checkpoint.traceHash);
        json.put("pendingInternal", checkpoint.internalQueue.size());
        return json;
    }

    private List<Map<String, Object>> diff(Map<String, Object> left, Map<String, Object> right) {
        List<Map<String, Object>> changes = new ArrayList<>();
        diff("$", left, right, changes);
        return changes;
    }

    private void diff(String path, Object before, Object after, List<Map<String, Object>> changes) {
        if (Json.writeCanonical(before).equals(Json.writeCanonical(after))) return;
        if (before instanceof Map<?, ?> beforeMap && after instanceof Map<?, ?> afterMap) {
            for (Object key : beforeMap.keySet()) {
                if (afterMap.containsKey(key)) diff(path + "." + key, beforeMap.get(key), afterMap.get(key), changes);
                else addChange(path + "." + key, beforeMap.get(key), null, changes);
            }
            for (Object key : afterMap.keySet()) {
                if (!beforeMap.containsKey(key)) addChange(path + "." + key, null, afterMap.get(key), changes);
            }
        } else {
            addChange(path, before, after, changes);
        }
    }

    private void addChange(String path, Object before, Object after, List<Map<String, Object>> changes) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("path", path);
        change.put("before", before);
        change.put("after", after);
        changes.add(change);
    }

    private String chainHash(Models.Branch branch, Models.StepRecord step, String stateHash) {
        String previous = checkpoints.get(branch.headCheckpointId).traceHash;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previous", previous);
        payload.put("step", step.step);
        payload.put("event", step.event.toIdentityJson());
        payload.put("matched", step.matched);
        payload.put("transition", step.transition);
        payload.put("fromState", step.fromState);
        payload.put("toState", step.toState);
        payload.put("failed", step.failed);
        payload.put("error", step.error);
        payload.put("after", step.afterSnapshot);
        payload.put("outputs", step.outputs);
        payload.put("stateHash", stateHash);
        return Models.sha256(Json.writeCanonical(payload));
    }

    private Map<String, Object> writeBranch(Models.Branch branch) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", branch.id);
        json.put("name", branch.name);
        json.put("baseCheckpointId", branch.baseCheckpointId);
        json.put("headCheckpointId", branch.headCheckpointId);
        json.put("cursor", branch.cursor);
        json.put("events", branch.events.stream().map(EventCodec::write).toList());
        json.put("trace", branch.trace.stream().map(step -> StepCodec.write(step, true)).toList());
        return json;
    }

    private Map<String, Object> writeCheckpoint(Models.Checkpoint checkpoint) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", checkpoint.id);
        json.put("branchId", checkpoint.branchId);
        json.put("step", checkpoint.step);
        json.put("parentCheckpointId", checkpoint.parentCheckpointId);
        json.put("definitionFingerprint", checkpoint.definitionFingerprint);
        json.put("stateHash", checkpoint.stateHash);
        json.put("traceHash", checkpoint.traceHash);
        json.put("state", checkpoint.state);
        json.put("vars", checkpoint.variables);
        json.put("randomState", checkpoint.randomState);
        json.put("internalQueue", checkpoint.internalQueue.stream().map(EventCodec::write).toList());
        return json;
    }

    private void verifyLoaded() {
        for (Models.Branch branch : branches.values()) {
            if (!checkpoints.containsKey(branch.baseCheckpointId) || !checkpoints.containsKey(branch.headCheckpointId)) {
                throw new IllegalArgumentException("branch references missing checkpoint");
            }
            String hash = Models.sha256("deterministic-state-replay-room:v1");
            for (Models.StepRecord step : branch.trace) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("previous", hash);
                payload.put("step", step.step);
                payload.put("event", step.event.toIdentityJson());
                payload.put("matched", step.matched);
                payload.put("transition", step.transition);
                payload.put("fromState", step.fromState);
                payload.put("toState", step.toState);
                payload.put("failed", step.failed);
                payload.put("error", step.error);
                payload.put("after", step.afterSnapshot);
                payload.put("outputs", step.outputs);
                payload.put("stateHash", step.stateHash);
                hash = Models.sha256(Json.writeCanonical(payload));
            }
            if (!hash.equals(checkpoints.get(branch.headCheckpointId).traceHash)) {
                throw new IllegalArgumentException("imported trace hash mismatch on " + branch.id);
            }
        }
    }

    private void persistHook() {
        Persistence.saveIfConfigured(this);
    }

    static final class VersionException extends IllegalArgumentException {
        final String checkpointId;
        final String checkpointFingerprint;
        final String currentFingerprint;

        VersionException(String checkpointId, String checkpointFingerprint, String currentFingerprint) {
            super("checkpoint definition fingerprint mismatch");
            this.checkpointId = checkpointId;
            this.checkpointFingerprint = checkpointFingerprint;
            this.currentFingerprint = currentFingerprint;
        }
    }

    static final class MergeConflictException extends IllegalArgumentException {
        final Map<String, Object> details;

        MergeConflictException(String message, Map<String, Object> details) {
            super(message);
            this.details = details;
        }
    }
}
