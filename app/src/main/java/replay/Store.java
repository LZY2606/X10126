package replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class Store {
    private static final String MAIN_BRANCH = "main";
    private static final String INITIAL_HASH = Hashes.sha256(Json.canonical(Map.of("format", Engine.VERSION, "marker", "initial")));

    private final Path file;
    private Map<String, Object> root;

    public Store(Path file) {
        this.file = file;
        load();
    }

    public synchronized Map<String, Object> state() {
        return Json.clone(root);
    }

    public synchronized Map<String, Object> createSession(String name, Map<String, Object> definition, long seed) {
        Engine.validateDefinition(definition);
        String id = uniqueId("session", sessions().keySet());
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", id);
        session.put("name", name == null || name.isBlank() ? "会话 " + (sessions().size() + 1) : name);
        session.put("createdAtLogical", sessions().size());
        session.put("definition", Json.clone(definition));
        session.put("definitionFingerprint", Engine.definitionFingerprint(definition));
        session.put("seed", seed);
        session.put("branches", new LinkedHashMap<String, Object>());
        session.put("checkpoints", new LinkedHashMap<String, Object>());
        Map<String, Object> main = newBranch(id, MAIN_BRANCH, null, 0,
                Engine.initialSnapshot(definition, seed, MAIN_BRANCH), List.of(), List.of(), INITIAL_HASH);
        Json.at(session, "branches").put(MAIN_BRANCH, main);
        sessions().put(id, session);
        persist();
        return Json.clone(session);
    }

    public synchronized Map<String, Object> importEvents(String sessionId, String branchId, List<Object> events) {
        Session session = requireSession(sessionId);
        Map<String, Object> branch = requireBranch(session, branchId);
        List<Map<String, Object>> pending = maps(branch.get("pending"));
        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> existingKeys = knownEventKeys(session.data, branch);
        long fallback = nextExternalSequence(pending);
        for (Object raw : events) {
            Map<String, Object> input = Json.object(raw);
            Map<String, Object> event = normalizeExternalEvent(input, pending.size() + normalized.size(), fallback++);
            String key = Engine.eventKey(event);
            if (!existingKeys.add(key)) {
                throw new EngineException("duplicate external event with same logical identity: " + key.substring(0, 12));
            }
            normalized.add(event);
        }
        pending.addAll(normalized);
        pending.sort(Engine.eventComparator());
        branch.put("pending", pending);
        persist();
        return branchView(session.data, branch, true);
    }

    public synchronized Map<String, Object> step(String sessionId, String branchId) {
        Session session = requireSession(sessionId);
        Map<String, Object> branch = requireBranch(session, branchId);
        verifyDefinition(session, branch);
        List<Map<String, Object>> pending = maps(branch.get("pending"));
        if (pending.isEmpty()) throw new EngineException("event queue is empty");
        pending.sort(Engine.eventComparator());
        Map<String, Object> event = pending.remove(0);
        long rootAbsolute = Json.longValue(branch, "rootAbsoluteIndex", 0);
        List<Map<String, Object>> steps = maps(branch.get("steps"));
        long index = rootAbsolute + steps.size();
        String previous = Json.optionalString(branch, "trajectoryHash", INITIAL_HASH);
        Engine.StepResult result = Engine.applyEvent(session.definition(), snapshot(branch), event, index, previous);
        steps.add(result.step);
        for (Map<String, Object> internal : result.internalEvents) {
            insertInternal(pending, internal);
        }
        branch.put("steps", steps);
        branch.put("pending", pending);
        branch.put("snapshot", result.snapshot);
        branch.put("trajectoryHash", Json.string(result.step, "hash"));
        persist();
        return branchView(session.data, branch, true);
    }

    public synchronized Map<String, Object> checkpoint(String sessionId, String branchId, String name) {
        Session session = requireSession(sessionId);
        Map<String, Object> branch = requireBranch(session, branchId);
        verifyDefinition(session, branch);
        long rootAbsolute = Json.longValue(branch, "rootAbsoluteIndex", 0);
        long absolute = rootAbsolute + maps(branch.get("steps")).size();
        String id = "cp-" + branchId + "-" + absolute + "-" + session.fingerprint.substring(0, 10);
        String rootCheckpointId = Json.optionalString(branch, "rootCheckpointId", "");
        Map<String, Object> rootSnapshot;
        String rootTrajectoryHash;
        if (rootCheckpointId.isBlank()) {
            rootSnapshot = Engine.initialSnapshot(session.definition, Json.longValue(session.data, "seed", 0), branchId);
            rootTrajectoryHash = INITIAL_HASH;
        } else {
            Map<String, Object> rootCheckpoint = requireCheckpoint(session, rootCheckpointId);
            rootSnapshot = Json.cloneObject(rootCheckpoint.get("snapshot"));
            rootTrajectoryHash = Json.string(rootCheckpoint, "trajectoryHash");
        }
        Object existingRaw = Json.at(session.data, "checkpoints").get(id);
        Map<String, Object> existing = existingRaw == null ? null : Json.object(existingRaw);
        if (existing != null && !Json.optionalString(existing, "trajectoryHash", "")
                .equals(Json.optionalString(branch, "trajectoryHash", ""))) {
            throw new EngineException("checkpoint id collision with different trajectory");
        }
        if (existing == null) {
            Map<String, Object> checkpoint = new LinkedHashMap<>();
            checkpoint.put("id", id);
            checkpoint.put("name", name == null || name.isBlank() ? "检查点 " + absolute : name);
            checkpoint.put("sessionId", sessionId);
            checkpoint.put("branchId", branchId);
            checkpoint.put("rootAbsoluteIndex", rootAbsolute);
            checkpoint.put("absoluteIndex", absolute);
            checkpoint.put("definitionFingerprint", session.fingerprint);
            checkpoint.put("snapshot", snapshot(branch));
            checkpoint.put("rootSnapshot", rootSnapshot);
            checkpoint.put("pending", Json.clone(branch.get("pending")));
            checkpoint.put("steps", Json.clone(branch.get("steps")));
            checkpoint.put("trajectoryHash", branch.get("trajectoryHash"));
            checkpoint.put("rootTrajectoryHash", rootTrajectoryHash);
            Json.at(session.data, "checkpoints").put(id, checkpoint);
        }
        persist();
        return Json.clone(Json.object(Json.at(session.data, "checkpoints").get(id)));
    }

    public synchronized Map<String, Object> fork(String sessionId, String checkpointId, String name) {
        Session session = requireSession(sessionId);
        Map<String, Object> checkpoint = requireCheckpoint(session, checkpointId);
        verifyCheckpointDefinition(session, checkpoint);
        String newId = uniqueBranchId(session.data, "branch");
        Map<String, Object> source = requireBranch(session, Json.string(checkpoint, "branchId"));
        Map<String, Object> branch = newBranch(sessionId, newId, Json.string(source, "id"),
                Json.longValue(checkpoint, "absoluteIndex", 0),
                Json.cloneObject(checkpoint.get("snapshot")), Json.clone(checkpoint.get("pending")),
                List.of(), Json.string(checkpoint, "trajectoryHash"));
        branch.put("rootCheckpointId", checkpointId);
        branch.put("name", name == null || name.isBlank() ? "分支 " + newId : name);
        Json.at(session.data, "branches").put(newId, branch);
        persist();
        return branchView(session.data, branch, true);
    }

    public synchronized Map<String, Object> restore(String sessionId, String checkpointId) {
        Session session = requireSession(sessionId);
        Map<String, Object> checkpoint = requireCheckpoint(session, checkpointId);
        verifyCheckpointDefinition(session, checkpoint);
        if (!Json.string(checkpoint, "branchId").equals(MAIN_BRANCH) && !findBranch(sessionId, Json.string(checkpoint, "branchId")).isPresent()) {
            throw new EngineException("checkpoint branch no longer exists");
        }
        Map<String, Object> branch = requireBranch(session, Json.string(checkpoint, "branchId"));
        branch.put("snapshot", Json.cloneObject(checkpoint.get("snapshot")));
        branch.put("pending", Json.clone(checkpoint.get("pending")));
        branch.put("steps", Json.clone(checkpoint.get("steps")));
        branch.put("trajectoryHash", checkpoint.get("trajectoryHash"));
        persist();
        return branchView(session.data, branch, true);
    }

    public synchronized Map<String, Object> compare(String sessionId, String leftId, String rightId) {
        Session session = requireSession(sessionId);
        Map<String, Object> left = requireBranch(session, leftId);
        Map<String, Object> right = requireBranch(session, rightId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("left", branchView(session.data, left, true));
        result.put("right", branchView(session.data, right, true));
        result.put("snapshotDiff", Engine.snapshotDiff(snapshot(left), snapshot(right)));
        result.put("outputCount", Map.of("left", countOutputs(left), "right", countOutputs(right)));
        result.put("failureCount", Map.of("left", countFailures(left), "right", countFailures(right)));
        result.put("sameTrajectoryHash", Objects.equals(left.get("trajectoryHash"), right.get("trajectoryHash")));
        return result;
    }

    public synchronized Map<String, Object> merge(String sessionId, String leftId, String rightId, String name, boolean create) {
        Session session = requireSession(sessionId);
        Map<String, Object> left = requireBranch(session, leftId);
        Map<String, Object> right = requireBranch(session, rightId);
        if (leftId.equals(rightId)) throw new EngineException("cannot merge a branch with itself");
        Node ancestor = lowestCommonAncestor(session.data, leftId, rightId);
        Map<String, Object> checkpoint = findCheckpointAt(session.data, ancestor.branchId, ancestor.absoluteIndex)
                .orElseThrow(() -> new EngineException("common ancestor checkpoint is unavailable"));
        verifyCheckpointDefinition(session, checkpoint);
        List<Map<String, Object>> leftEvents = externalEventsAfter(session.data, left, ancestor);
        List<Map<String, Object>> rightEvents = externalEventsAfter(session.data, right, ancestor);
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        addMergeEvents(groups, leftEvents, leftId);
        addMergeEvents(groups, rightEvents, rightId);
        List<Map<String, Object>> conflicts = conflicts(groups);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", conflicts.isEmpty());
        result.put("ancestor", Map.of("branchId", ancestor.branchId, "absoluteIndex", ancestor.absoluteIndex,
                "checkpointId", checkpoint.get("id")));
        result.put("conflicts", conflicts);
        result.put("leftEventCount", leftEvents.size());
        result.put("rightEventCount", rightEvents.size());
        if (!conflicts.isEmpty()) {
            result.put("firstConflict", conflicts.get(0));
            persist();
            return result;
        }
        Set<String> inherited = new LinkedHashSet<>();
        for (Map<String, Object> event : maps(checkpoint.get("pending"))) inherited.add(Engine.eventKey(event));
        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> mergedKeys = new LinkedHashSet<>();
        for (List<Map<String, Object>> group : groups.values()) {
            Map<String, Object> event = Engine.eventContent(group.get(0));
            String key = Engine.eventKey(event);
            if (inherited.contains(key) || !mergedKeys.add(key)) continue;
            event.put("id", "merge-" + key.substring(0, 12));
            event.put("source", event.get("source"));
            event.put("sourcePriority", event.get("sourcePriority"));
            event.put("originalSeq", event.get("originalSeq"));
            merged.add(event);
        }
        merged.sort(externalMergeComparator());
        Map<String, Object> replay = replayMerged(session, checkpoint, merged);
        result.put("mergedEventCount", merged.size());
        result.put("trajectoryHash", replay.get("trajectoryHash"));
        if (create) {
            String branchId = uniqueBranchId(session.data, "merged");
            Map<String, Object> branch = newBranch(sessionId, branchId, null,
                    Json.longValue(checkpoint, "absoluteIndex", 0),
                    Json.cloneObject(replay.get("snapshot")), Json.clone(replay.get("pending")),
                    Json.clone(replay.get("steps")), Json.string(replay, "trajectoryHash"));
            branch.put("rootCheckpointId", Json.string(checkpoint, "id"));
            branch.put("parents", List.of(leftId, rightId));
            branch.put("name", name == null || name.isBlank() ? "合并 " + leftId + " + " + rightId : name);
            Json.at(session.data, "branches").put(branchId, branch);
            result.put("branch", branchView(session.data, branch, true));
        }
        persist();
        return result;
    }

    public synchronized Map<String, Object> exportSession(String sessionId) {
        Session session = requireSession(sessionId);
        Map<String, Object> exported = new LinkedHashMap<>();
        exported.put("format", "state-machine-replay-room-session/v1");
        exported.put("exportedAtLogical", 0);
        exported.put("session", Json.clone(session.data));
        exported.put("sessionFingerprint", sessionFingerprint(session.data));
        return exported;
    }

    public synchronized Map<String, Object> importSession(Object payload) {
        Map<String, Object> input = Json.object(payload);
        Map<String, Object> original = Json.cloneObject(input.get("session"));
        String expected = Json.optionalString(input, "sessionFingerprint", sessionFingerprint(original));
        if (!expected.equals(sessionFingerprint(original))) {
            throw new EngineException("session fingerprint mismatch");
        }
        validateImportedSession(original);
        String oldId = Json.string(original, "id");
        String newId = uniqueId("session", sessions().keySet());
        original.put("id", newId);
        for (Object value : Json.at(original, "branches").values()) Json.object(value).put("sessionId", newId);
        for (Object value : Json.at(original, "checkpoints").values()) Json.object(value).put("sessionId", newId);
        sessions().put(newId, original);
        persist();
        return Json.clone(original);
    }

    private void load() {
        if (Files.exists(file)) {
            try {
                root = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                throw new EngineException("cannot load data file " + file + ": " + e.getMessage());
            }
        } else {
            root = new LinkedHashMap<>();
            root.put("format", "state-machine-replay-room-data/v1");
            root.put("sessions", new LinkedHashMap<String, Object>());
            persist();
        }
        for (Map<String, Object> session : sessionValues()) validateImportedSession(session);
    }

    private synchronized void persist() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "replay-room-", ".tmp");
            Files.writeString(temporary, Json.write(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new EngineException("cannot persist data: " + e.getMessage());
        }
    }

    private Map<String, Object> sessions() {
        return Json.object(root.get("sessions"));
    }

    private List<Map<String, Object>> sessionValues() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : sessions().values()) result.add(Json.object(value));
        return result;
    }

    private Session requireSession(String sessionId) {
        Map<String, Object> data = Json.object(sessions().get(sessionId));
        return new Session(data, Engine.definitionFingerprint(Json.at(data, "definition")),
                Json.cloneObject(data.get("definition")));
    }

    private Map<String, Object> requireBranch(Session session, String branchId) {
        return findBranch(session.data, branchId)
                .orElseThrow(() -> new EngineException("unknown branch " + branchId));
    }

    private Optional<Map<String, Object>> findBranch(String sessionId, String branchId) {
        return findBranch(Json.object(sessions().get(sessionId)), branchId);
    }

    private Optional<Map<String, Object>> findBranch(Map<String, Object> session, String branchId) {
        Object branch = Json.at(session, "branches").get(branchId);
        return Optional.ofNullable(branch).map(Json::object);
    }

    private Map<String, Object> requireCheckpoint(Session session, String checkpointId) {
        return findCheckpoint(session.data, checkpointId)
                .orElseThrow(() -> new EngineException("unknown checkpoint " + checkpointId));
    }

    private Map<String, Object> requireCheckpoint(Session session, String ignored, Map<String, Object> checkpoint) {
        return checkpoint;
    }

    private Optional<Map<String, Object>> findCheckpoint(Map<String, Object> session, String id) {
        Object checkpoint = Json.at(session, "checkpoints").get(id);
        return Optional.ofNullable(checkpoint).map(Json::object);
    }

    private Optional<Map<String, Object>> findCheckpointAt(Map<String, Object> session, String branchId, long absolute) {
        for (Object value : Json.at(session, "checkpoints").values()) {
            Map<String, Object> checkpoint = Json.object(value);
            if (branchId.equals(checkpoint.get("branchId")) && absolute == Json.longValue(checkpoint, "absoluteIndex", -1)) {
                return Optional.of(checkpoint);
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> newBranch(String sessionId, String id, String forkedFrom, long rootAbsolute,
                                          Map<String, Object> snapshot, Object pending, Object steps,
                                          String trajectoryHash) {
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("id", id);
        branch.put("name", id);
        branch.put("sessionId", sessionId);
        branch.put("forkedFrom", forkedFrom);
        branch.put("rootAbsoluteIndex", rootAbsolute);
        branch.put("snapshot", snapshot);
        branch.put("pending", pending);
        branch.put("steps", steps);
        branch.put("trajectoryHash", trajectoryHash);
        return branch;
    }

    private Map<String, Object> snapshot(Map<String, Object> branch) {
        return Json.cloneObject(branch.get("snapshot"));
    }

    private void verifyDefinition(Session session, Map<String, Object> branch) {
        String actual = Engine.definitionFingerprint(session.definition);
        if (!actual.equals(session.fingerprint)) {
            throw new EngineException("session definition fingerprint is unstable");
        }
        String rootCheckpoint = Json.optionalString(branch, "rootCheckpointId", "");
        if (!rootCheckpoint.isBlank()) {
            Map<String, Object> checkpoint = requireCheckpoint(session, rootCheckpoint);
            if (!actual.equals(checkpoint.get("definitionFingerprint"))) {
                throw new EngineException("checkpoint definition fingerprint does not match current definition");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : Json.list(value)) result.add((Map<String, Object>) item);
        return result;
    }

    private Map<String, Object> normalizeExternalEvent(Map<String, Object> input, int importedIndex, long fallbackSeq) {
        Map<String, Object> event = new LinkedHashMap<>();
        long time = Json.longValue(input, "logicalTime", 0);
        if (time < 0) throw new EngineException("logicalTime must not be negative");
        event.put("logicalTime", time);
        event.put("type", Json.string(input, "type"));
        event.put("payload", Json.clone(input.getOrDefault("payload", Map.of())));
        event.put("source", Json.optionalString(input, "source", "external"));
        long priority = Json.longValue(input, "sourcePriority", 100);
        event.put("sourcePriority", priority);
        long originalSeq = input.containsKey("originalSeq") ? Json.longValue(input, "originalSeq", 0) : fallbackSeq;
        event.put("originalSeq", originalSeq);
        event.put("internal", false);
        event.put("id", Json.optionalString(input, "id",
                "ext-" + event.get("source") + "-" + event.get("sourcePriority") + "-" + event.get("originalSeq")
                        + "-" + Engine.eventKey(event).substring(0, 10)));
        return event;
    }

    private Set<String> knownEventKeys(Map<String, Object> session, Map<String, Object> branch) {
        Set<String> keys = new LinkedHashSet<>();
        collectExternalKeys(session, Json.string(branch, "id"), new LinkedHashSet<>(), keys);
        return keys;
    }

    private void collectExternalKeys(Map<String, Object> session, String branchId, Set<String> visited, Set<String> keys) {
        if (!visited.add(branchId)) return;
        Optional<Map<String, Object>> found = findBranch(session, branchId);
        if (found.isEmpty()) return;
        Map<String, Object> branch = found.get();
        for (Map<String, Object> event : maps(branch.get("pending"))) if (!Boolean.TRUE.equals(event.get("internal"))) keys.add(Engine.eventKey(event));
        for (Map<String, Object> step : maps(branch.get("steps"))) {
            Map<String, Object> event = Json.object(step.get("event"));
            if (!Boolean.TRUE.equals(event.get("internal"))) keys.add(Engine.eventKey(event));
        }
        String rootCheckpoint = Json.optionalString(branch, "rootCheckpointId", "");
        if (!rootCheckpoint.isBlank()) {
            findCheckpoint(session, rootCheckpoint).ifPresent(checkpoint -> {
                for (Map<String, Object> event : maps(checkpoint.get("steps"))) {
                    Map<String, Object> eventContent = Json.object(event.get("event"));
                    if (!Boolean.TRUE.equals(eventContent.get("internal"))) keys.add(Engine.eventKey(eventContent));
                }
            });
        }
        String forkedFrom = Json.optionalString(branch, "forkedFrom", "");
        if (!forkedFrom.isBlank()) collectExternalKeys(session, forkedFrom, visited, keys);
        for (Object parent : Json.list(branch.getOrDefault("parents", List.of()))) collectExternalKeys(session, String.valueOf(parent), visited, keys);
    }

    private long nextExternalSequence(List<Map<String, Object>> events) {
        long value = 0;
        for (Map<String, Object> event : events) {
            value = Math.max(value, Json.longValue(event, "originalSeq", -1) + 1);
        }
        return value;
    }

    private void insertInternal(List<Map<String, Object>> pending, Map<String, Object> internal) {
        for (int i = 0; i < pending.size(); i++) {
            if (Engine.eventComparator().compare(internal, pending.get(i)) < 0) {
                pending.add(i, internal);
                return;
            }
        }
        pending.add(internal);
    }

    private Map<String, Object> branchView(Map<String, Object> session, Map<String, Object> branch, boolean includeSteps) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", branch.get("id"));
        view.put("name", branch.get("name"));
        view.put("sessionId", branch.get("sessionId"));
        view.put("forkedFrom", branch.get("forkedFrom"));
        view.put("rootCheckpointId", branch.get("rootCheckpointId"));
        view.put("rootAbsoluteIndex", branch.get("rootAbsoluteIndex"));
        view.put("absoluteIndex", Json.longValue(branch, "rootAbsoluteIndex", 0) + maps(branch.get("steps")).size());
        view.put("snapshot", branch.get("snapshot"));
        view.put("pending", branch.get("pending"));
        view.put("trajectoryHash", branch.get("trajectoryHash"));
        view.put("definitionFingerprint", Json.optionalString(branch, "definitionFingerprint",
                Engine.definitionFingerprint(Json.at(session, "definition"))));
        view.put("outputCount", countOutputs(branch));
        view.put("failureCount", countFailures(branch));
        if (includeSteps) view.put("steps", branch.get("steps"));
        return view;
    }

    private long countOutputs(Map<String, Object> branch) {
        return maps(branch.get("steps")).stream()
                .flatMap(step -> Json.list(Json.object(step).get("outputs")).stream())
                .count();
    }

    private long countFailures(Map<String, Object> branch) {
        return maps(branch.get("steps")).stream()
                .filter(step -> Json.object(step).get("failure") != null)
                .count();
    }

    private List<Map<String, Object>> externalEventsAfter(Map<String, Object> session, Map<String, Object> branch, Node ancestor) {
        List<Map<String, Object>> events = new ArrayList<>();
        collectExternalEvents(session, Json.string(branch, "id"), ancestor, new LinkedHashSet<>(), events);
        events.sort(Comparator.comparingLong(event -> Json.longValue(event, "absoluteIndex", 0)));
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> event : events) {
            if (seen.add(Engine.eventKey(event))) result.add(event);
        }
        return result;
    }

    private void collectExternalEvents(Map<String, Object> session, String branchId, Node ancestor,
                                       Set<String> visited, List<Map<String, Object>> events) {
        if (!visited.add(branchId)) return;
        Optional<Map<String, Object>> found = findBranch(session, branchId);
        if (found.isEmpty()) return;
        Map<String, Object> branch = found.get();
        long rootAbsolute = Json.longValue(branch, "rootAbsoluteIndex", 0);
        long cutoff = branchId.equals(ancestor.branchId) ? ancestor.absoluteIndex - rootAbsolute : -1;
        int stepCount = 0;
        for (Map<String, Object> step : maps(branch.get("steps"))) {
            long localIndex = stepCount++;
            if (localIndex <= cutoff) continue;
            Map<String, Object> event = Json.cloneObject(step.get("event"));
            if (!Boolean.TRUE.equals(event.get("internal"))) {
                event.put("absoluteIndex", rootAbsolute + localIndex);
                events.add(event);
            }
        }
        if (branchId.equals(ancestor.branchId)) {
            for (Map<String, Object> pending : maps(branch.get("pending"))) {
                if (!Boolean.TRUE.equals(pending.get("internal"))) {
                    Map<String, Object> copy = Json.clone(pending);
                    copy.put("absoluteIndex", Long.MAX_VALUE / 2);
                    events.add(copy);
                }
            }
            return;
        }
        String checkpointId = Json.optionalString(branch, "rootCheckpointId", "");
        if (checkpointId.isBlank()) return;
        Map<String, Object> checkpoint = findCheckpoint(session, checkpointId)
                .orElseThrow(() -> new EngineException("missing root checkpoint " + checkpointId));
        String parent = Json.string(checkpoint, "branchId");
        Map<String, Object> pseudo = new LinkedHashMap<>();
        pseudo.put("id", parent);
        pseudo.put("rootAbsoluteIndex", checkpoint.get("rootAbsoluteIndex"));
        pseudo.put("steps", checkpoint.get("steps"));
        pseudo.put("pending", checkpoint.get("pending"));
        long parentRoot = Json.longValue(checkpoint, "rootAbsoluteIndex", 0);
        long localCutoff = ancestor.branchId.equals(parent) ? ancestor.absoluteIndex - parentRoot : -1;
        int index = 0;
        for (Map<String, Object> step : maps(pseudo.get("steps"))) {
            long localIndex = index++;
            if (localIndex <= localCutoff) continue;
            Map<String, Object> event = Json.cloneObject(step.get("event"));
            if (!Boolean.TRUE.equals(event.get("internal"))) {
                event.put("absoluteIndex", parentRoot + localIndex);
                events.add(event);
            }
        }
        if (ancestor.branchId.equals(parent)) {
            return;
        }
        collectExternalEvents(session, parent, ancestor, visited, events);
    }

    private void addMergeEvents(Map<String, List<Map<String, Object>>> groups, List<Map<String, Object>> events, String branchId) {
        for (Map<String, Object> event : events) {
            String key = Engine.eventKey(event);
            List<Map<String, Object>> group = groups.computeIfAbsent(key, ignored -> new ArrayList<>());
            boolean duplicate = group.stream().anyMatch(existing -> Json.optionalString(existing, "id", "").equals(Json.optionalString(event, "id", "")));
            if (!duplicate) {
                Map<String, Object> copy = Json.clone(event);
                copy.put("branchId", branchId);
                group.add(copy);
            }
        }
    }

    private List<Map<String, Object>> conflicts(Map<String, List<Map<String, Object>>> groups) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (List<Map<String, Object>> group : groups.values()) {
            if (group.size() > 1 && !sameContent(group)) {
                Map<String, Object> conflict = new LinkedHashMap<>();
                conflict.put("type", "same_sort_identity_different_payload");
                conflict.put("events", group);
                conflicts.add(conflict);
            }
            if (group.size() > 2) {
                Map<String, Object> conflict = new LinkedHashMap<>();
                conflict.put("type", "duplicate_event_in_branch");
                conflict.put("events", group);
                conflicts.add(conflict);
            }
        }
        return conflicts;
    }

    private boolean sameContent(List<Map<String, Object>> events) {
        String first = Json.canonical(Engine.eventContent(events.get(0)));
        return events.stream().allMatch(event -> Json.canonical(Engine.eventContent(event)).equals(first));
    }

    private Comparator<Map<String, Object>> externalMergeComparator() {
        return Comparator
                .comparingLong((Map<String, Object> event) -> Json.longValue(event, "logicalTime", 0))
                .thenComparingLong(event -> Json.longValue(event, "sourcePriority", 0))
                .thenComparingLong(event -> Json.longValue(event, "originalSeq", 0))
                .thenComparing(event -> Json.optionalString(event, "source", ""))
                .thenComparing(Engine::eventKey);
    }

    private Map<String, Object> replayMerged(Session session, Map<String, Object> ancestorCheckpoint,
                                             List<Map<String, Object>> externalEvents) {
        Map<String, Object> snapshot = Json.cloneObject(ancestorCheckpoint.get("snapshot"));
        List<Map<String, Object>> pending = new ArrayList<>();
        pending.addAll(externalEvents);
        pending.sort(Engine.eventComparator());
        List<Map<String, Object>> steps = new ArrayList<>();
        long rootAbsolute = Json.longValue(ancestorCheckpoint, "absoluteIndex", 0);
        String previous = Json.string(ancestorCheckpoint, "trajectoryHash");
        while (!pending.isEmpty()) {
            pending.sort(Engine.eventComparator());
            Map<String, Object> event = pending.remove(0);
            long index = rootAbsolute + steps.size();
            Engine.StepResult result = Engine.applyEvent(session.definition, snapshot, event, index, previous);
            steps.add(result.step);
            for (Map<String, Object> internal : result.internalEvents) insertInternal(pending, internal);
            snapshot = result.snapshot;
            previous = Json.string(result.step, "hash");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshot", snapshot);
        result.put("pending", pending);
        result.put("steps", steps);
        result.put("trajectoryHash", previous);
        return result;
    }

    private Node lowestCommonAncestor(Map<String, Object> session, String leftId, String rightId) {
        List<Node> left = ancestors(session, leftId);
        List<Node> right = ancestors(session, rightId);
        for (Node candidate : left) {
            for (Node other : right) {
                if (candidate.equals(other)) return candidate;
            }
        }
        throw new EngineException("branches have no common ancestor");
    }

    private List<Node> ancestors(Map<String, Object> session, String branchId) {
        List<Node> result = new ArrayList<>();
        String current = branchId;
        while (current != null) {
            final String currentId = current;
            Map<String, Object> branch = findBranch(session, currentId)
                    .orElseThrow(() -> new EngineException("missing branch " + currentId));
            long tip = Json.longValue(branch, "rootAbsoluteIndex", 0) + maps(branch.get("steps")).size();
            addAncestorCheckpoints(session, current, tip, result);
            String rootCheckpoint = Json.optionalString(branch, "rootCheckpointId", "");
            if (rootCheckpoint.isBlank()) {
                result.add(new Node(MAIN_BRANCH, 0));
                break;
            }
            Map<String, Object> checkpoint = findCheckpoint(session, rootCheckpoint)
                    .orElseThrow(() -> new EngineException("missing checkpoint " + rootCheckpoint));
            current = Json.string(checkpoint, "branchId");
        }
        return result;
    }

    private void addAncestorCheckpoints(Map<String, Object> session, String branchId, long tip, List<Node> nodes) {
        List<Node> checkpoints = new ArrayList<>();
        for (Object value : Json.at(session, "checkpoints").values()) {
            Map<String, Object> checkpoint = Json.object(value);
            long absolute = Json.longValue(checkpoint, "absoluteIndex", -1);
            if (Json.string(checkpoint, "branchId").equals(branchId) && absolute <= tip) {
                checkpoints.add(new Node(branchId, absolute));
            }
        }
        checkpoints.sort(Comparator.comparingLong(node -> node.absoluteIndex));
        for (int i = checkpoints.size() - 1; i >= 0; i--) {
            if (nodes.stream().noneMatch(checkpoints.get(i)::equals)) nodes.add(checkpoints.get(i));
        }
    }

    private String uniqueId(String prefix, Set<String> used) {
        int index = 1;
        while (used.contains(prefix + "-" + index)) index++;
        return prefix + "-" + index;
    }

    private String uniqueBranchId(Map<String, Object> session, String prefix) {
        Set<String> ids = Json.at(session, "branches").keySet();
        int index = 1;
        while (ids.contains(prefix + "-" + index)) index++;
        return prefix + "-" + index;
    }

    private String sessionFingerprint(Map<String, Object> session) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("format", "state-machine-replay-room-session-lock/v1");
        material.put("definitionFingerprint", Json.string(session, "definitionFingerprint"));
        material.put("seed", session.get("seed"));
        material.put("branches", Json.at(session, "branches"));
        material.put("checkpoints", Json.at(session, "checkpoints"));
        return Hashes.sha256(Json.canonical(material));
    }

    private void validateImportedSession(Map<String, Object> session) {
        if (!Json.string(session, "id").startsWith("session-")) throw new EngineException("invalid session id");
        Map<String, Object> definition = Json.object(session.get("definition"));
        String definitionFingerprint = Engine.definitionFingerprint(definition);
        if (!definitionFingerprint.equals(Json.string(session, "definitionFingerprint"))) {
            throw new EngineException("session definition fingerprint mismatch");
        }
        for (Object value : Json.at(session, "branches").values()) {
            Map<String, Object> branch = Json.object(value);
            for (Map<String, Object> step : maps(branch.get("steps"))) {
                verifyStepHash(definition, branch, step);
            }
        }
        for (Object value : Json.at(session, "checkpoints").values()) {
            Map<String, Object> checkpoint = Json.object(value);
            if (!definitionFingerprint.equals(checkpoint.get("definitionFingerprint"))) {
                throw new EngineException("checkpoint was created by a different definition");
            }
            for (Map<String, Object> step : maps(checkpoint.get("steps"))) verifyStepHash(definition, checkpoint, step);
        }
    }

    private void verifyStepHash(Map<String, Object> definition, Map<String, Object> owner, Map<String, Object> step) {
        long index = Json.longValue(step, "index", -1);
        long rootAbsolute = Json.longValue(owner, "rootAbsoluteIndex", 0);
        List<Map<String, Object>> ownerSteps = maps(owner.get("steps"));
        int position = -1;
        for (int i = 0; i < ownerSteps.size(); i++) {
            if (Json.longValue(ownerSteps.get(i), "index", -2) == index) {
                position = i;
                break;
            }
        }
        String previous = position == 0
                ? (owner.containsKey("trajectoryHash") ? Json.string(owner, "trajectoryHash") : INITIAL_HASH)
                : Json.string(ownerSteps.get(position - 1), "hash");
        Map<String, Object> event = Json.object(step.get("event"));
        Map<String, Object> before = Json.object(position == 0 ? owner.get("snapshot") : ownerSteps.get(position - 1).get("after"));
        Engine.StepResult recomputed = Engine.applyEvent(definition, before, event, index, previous);
        if (!Json.string(recomputed.step, "hash").equals(Json.string(step, "hash"))) {
            throw new EngineException("trajectory hash mismatch at step " + index);
        }
    }

    private record Node(String branchId, long absoluteIndex) {
    }

    private record Session(Map<String, Object> data, String fingerprint, Map<String, Object> definition) {
    }
}
