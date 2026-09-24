package replayroom;

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

public class SessionService {
    private final Path file;
    private Map<String, Object> session;
    private long checkpointCounter;
    private long branchCounter;

    public SessionService(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            session = Json.object(Json.parse(readFile(file)), "session");
            checkpointCounter = Json.integer(session.getOrDefault("checkpointCounter", 1L), "checkpointCounter");
            branchCounter = Json.integer(session.getOrDefault("branchCounter", 1L), "branchCounter");
            validateLoadedSession();
        } else {
            session = newSession(Engine.defaultMachine(), "idle", Engine.defaultInitialData(), 20260924L, sampleEvents());
            save();
        }
    }

    public synchronized Map<String, Object> state() {
        Map<String, Object> result = Json.cloneObject(session);
        result.put("events", Json.cloneList(session.get("events")));
        result.put("checkpoints", Json.cloneObject(session.get("checkpoints")));
        return result;
    }

    public synchronized Map<String, Object> updateDefinition(Map<String, Object> request) {
        Map<String, Object> machine = Json.object(request.get("machine"), "machine");
        String initialState = Json.optionalString(request, "initialState", "idle");
        Map<String, Object> initialData = Json.cloneObject(request.getOrDefault("initialData", Map.of()));
        long seed = Json.integer(request.getOrDefault("seed", 1L), "seed");
        MachineValidator.validate(machine, initialState);
        session = newSession(machine, initialState, initialData, seed, Json.cloneList(session.get("events")));
        save();
        return state();
    }

    public synchronized Map<String, Object> importEvents(List<Object> events) {
        List<Object> normalized = normalizeEvents(events, sources());
        session = newSession(machine(), initialState(), initialData(), seed(), normalized);
        save();
        return state();
    }

    public synchronized Map<String, Object> step(String branchName) {
        Map<String, Object> branch = branch(branchName);
        List<Map<String, Object>> steps = typedList(branch.get("steps"));
        List<Map<String, Object>> queue = Json.cloneList(branch.get("queue"));
        if (queue.isEmpty()) {
            throw new BadRequestException("No events remain on branch " + branchName);
        }
        String state = initialState();
        Map<String, Object> data = initialData();
        long internalSeq = 0L;
        Long rng = null;
        String previousHash = manifestFingerprint();
        if (!steps.isEmpty()) {
            Map<String, Object> last = steps.get(steps.size() - 1);
            state = Json.string(last.get("stateAfter"), "step.stateAfter");
            data = Json.cloneObject(last.get("dataAfter"));
            internalSeq = Json.integer(last.get("nextInternalSeq"), "step.nextInternalSeq");
            rng = Json.integer(last.get("rngAfter"), "step.rngAfter");
            previousHash = Json.string(branch.get("trajectoryHash"), "trajectoryHash");
        }
        Map<String, Object> outcome = Engine.step(machine(), initialState(), initialData(), seed(),
                state, data, queue, internalSeq, rng, previousHash, steps.size());
        steps.add(Json.object(outcome.get("record"), "record"));
        branch.put("steps", steps);
        branch.put("state", outcome.get("state"));
        branch.put("data", outcome.get("data"));
        branch.put("queue", outcome.get("queue"));
        branch.put("trajectoryHash", outcome.get("hash"));
        save();
        return state();
    }

    public synchronized Map<String, Object> runToEnd(String branchName) {
        Map<String, Object> branch = branch(branchName);
        int guard = 0;
        while (!Json.cloneList(branch.get("queue")).isEmpty()) {
            if (guard++ > 100000) throw new IllegalStateException("Internal event runaway limit reached");
            step(branchName);
        }
        return state();
    }

    private Map<String, Object> newSession(Map<String, Object> machine, String initialState,
                                           Map<String, Object> initialData, long seed, List<Object> events) {
        Map<String, Object> normalizedEvents = normalizeEvents(events, machineSources(machine));
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("version", 1);
        created.put("machine", machine);
        created.put("initialState", initialState);
        created.put("initialData", initialData);
        created.put("seed", seed);
        created.put("events", normalizedEvents);
        created.put("definitionFingerprint", fingerprintMachine(machine));
        created.put("manifestFingerprint", fingerprintManifest(machine, initialState, initialData, seed, events));
        created.put("checkpointCounter", 1L);
        created.put("branchCounter", 1L);
        created.put("checkpoints", new LinkedHashMap<String, Object>());
        Map<String, Object> branches = new LinkedHashMap<>();
        branches.put("main", newBranch("main", "root", initialState, initialData, Json.cloneList(events),
                List.of("root"), 0L, null));
        created.put("branches", branches);
        checkpointCounter = 1L;
        branchCounter = 1L;
        saveRootCheckpoint(created);
        return created;
    }

    private void saveRootCheckpoint(Map<String, Object> created) {
        Map<String, Object> checkpoints = Json.object(created.get("checkpoints"), "checkpoints");
        checkpoints.put("root", checkpointRecord("root", null, "main", initialState(created),
                initialData(created), Json.cloneList(created.get("events")), Json.cloneList(created.get("events")),
                created.get("manifestFingerprint"), created.get("definitionFingerprint"),
                null, 0L, seed(created), 0));
    }

    private Map<String, Object> newBranch(String name, String checkpointId, String state,
                                          Map<String, Object> data, List<Object> queue,
                                          List<String> lineage, long internalSeq, Long rngAfter,
                                          List<Object> appendedEvents) {
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("name", name);
        branch.put("checkpoint", checkpointId);
        branch.put("state", state);
        branch.put("data", data);
        branch.put("queue", queue);
        branch.put("steps", new ArrayList<>());
        branch.put("trajectoryHash", manifestFingerprint());
        branch.put("lineage", lineage);
        branch.put("internalSeq", internalSeq);
        branch.put("rngAfter", rngAfter);
        branch.put("appendedEvents", appendedEvents);
        return branch;
    }

    public Map<String, Object> machine() {
        return Json.object(session.get("machine"), "machine");
    }

    public String initialState() {
        return initialState(session);
    }

    public static String initialState(Map<String, Object> source) {
        return Json.string(source.get("initialState"), "initialState");
    }

    public Map<String, Object> initialData() {
        return initialData(session);
    }

    public static Map<String, Object> initialData(Map<String, Object> source) {
        return Json.cloneObject(source.get("initialData"));
    }

    public long seed() {
        return seed(session);
    }

    public static long seed(Map<String, Object> source) {
        return Json.integer(source.get("seed"), "seed");
    }

    public String definitionFingerprint() {
        return Json.string(session.get("definitionFingerprint"), "definitionFingerprint");
    }

    public String manifestFingerprint() {
        return Json.string(session.get("manifestFingerprint"), "manifestFingerprint");
    }

    private Map<String, Object> sources() {
        Object value = machine().get("sources");
        return value == null ? new LinkedHashMap<>() : Json.object(value, "machine.sources");
    }

    private static Map<String, Object> machineSources(Map<String, Object> machine) {
        Object value = machine.get("sources");
        return value == null ? new LinkedHashMap<>() : Json.object(value, "machine.sources");
    }

    public static String fingerprintMachine(Map<String, Object> machine) {
        return Hashing.sha256(machine);
    }

    public static String fingerprintManifest(Map<String, Object> machine, String initialState,
                                             Map<String, Object> initialData, long seed, List<Object> events) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("definitionFingerprint", fingerprintMachine(machine));
        manifest.put("initialState", initialState);
        manifest.put("initialData", initialData);
        manifest.put("seed", seed);
        manifest.put("events", events);
        return Hashing.sha256(manifest);
    }

    private synchronized void save() {
        session.put("checkpointCounter", checkpointCounter);
        session.put("branchCounter", branchCounter);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, Json.write(session), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist session", e);
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read session", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> typedList(Object value) {
        return (List<Map<String, Object>>) (List<?>) Json.list(value, "list");
    }

    private synchronized Map<String, Object> branch(String name) {
        Map<String, Object> branches = Json.object(session.get("branches"), "branches");
        Map<String, Object> branch = Json.object(branches.get(name), "branch");
        return branch;
    }

    private static List<Object> sampleEvents() {
        return List.of(
                event("e1", 10, "operator", 1, "activate", Map.of()),
                event("e2", 20, "sensor", 2, "tick", Map.of("value", 7)),
                event("e3", 20, "operator", 1, "danger", Map.of("why", "demo"))
        );
    }

    public synchronized Map<String, Object> checkpoint(String branchName, String requestedId) {
        Map<String, Object> branch = branch(branchName);
        String parentId = Json.string(branch.get("checkpoint"), "branch.checkpoint");
        String id = requestedId == null || requestedId.isBlank()
                ? "checkpoint-" + (++checkpointCounter)
                : requestedId;
        Map<String, Object> checkpoints = Json.object(session.get("checkpoints"), "checkpoints");
        if (checkpoints.containsKey(id) || id.equals("root")) {
            throw new BadRequestException("Checkpoint id already exists: " + id);
        }
        List<Map<String, Object>> steps = typedList(branch.get("steps"));
        Map<String, Object> last = steps.isEmpty() ? null : steps.get(steps.size() - 1);
        long internalSeq = last == null ? 0L : Json.integer(last.get("nextInternalSeq"), "nextInternalSeq");
        Long rngAfter = last == null ? null : Json.integer(last.get("rngAfter"), "rngAfter");
        Map<String, Object> checkpoint = checkpointRecord(id, parentId, branchName,
                Json.string(branch.get("state"), "state"), Json.cloneObject(branch.get("data")),
                fullExternalEvents(branch), Json.cloneList(branch.get("queue")),
                manifestFingerprint(), definitionFingerprint(),
                Json.string(branch.get("trajectoryHash"), "trajectoryHash"),
                internalSeq, rngAfter, steps.size());
        checkpoint.put("steps", Json.cloneList(branch.get("steps")));
        checkpoints.put(id, checkpoint);
        branch.put("checkpoint", id);
        List<String> lineage = Json.list(branch.get("lineage"), "lineage").stream().map(String::valueOf).collect(Collectors.toList());
        lineage.add(id);
        branch.put("lineage", lineage);
        save();
        return state();
    }

    public synchronized Map<String, Object> fork(String checkpointId, String requestedName) {
        Map<String, Object> checkpoint = requireCurrentCheckpoint(checkpointId);
        Map<String, Object> branches = Json.object(session.get("branches"), "branches");
        String parentName = findBranchWithCheckpoint(checkpointId)
                .orElseThrow(() -> new BadRequestException("Checkpoint is not reachable from a live branch: " + checkpointId));
        Map<String, Object> parentBranch = branch(parentName);
        int stepCount = (int) Json.integer(checkpoint.get("stepCount"), "checkpoint.stepCount");
        List<Object> prefixSteps = Json.cloneList(checkpoint.get("steps"));
        if (prefixSteps.isEmpty() && stepCount != 0) {
            throw new BadRequestException("Checkpoint is missing replay prefix: " + checkpointId);
        }
        if (prefixSteps.size() != stepCount && !checkpointId.equals("root")) {
            throw new BadRequestException("Checkpoint prefix length is corrupt: " + checkpointId);
        }
        String name = requestedName == null || requestedName.isBlank()
                ? "branch-" + (++branchCounter)
                : requestedName;
        if (branches.containsKey(name) || name.equals("main")) {
            throw new BadRequestException("Branch already exists: " + name);
        }
        Map<String, Object> child = newBranch(name, checkpointId,
                Json.string(checkpoint.get("state"), "state"),
                Json.cloneObject(checkpoint.get("data")),
                Json.cloneList(checkpoint.get("queue")),
                new ArrayList<>(List.of(checkpointId)),
                Json.integer(checkpoint.get("internalSeq"), "internalSeq"),
                checkpoint.get("rngAfter") == null ? null : Json.integer(checkpoint.get("rngAfter"), "rngAfter"),
                new ArrayList<>());
        child.put("steps", prefixSteps);
        child.put("trajectoryHash", checkpoint.get("trajectoryHash"));
        branches.put(name, child);
        save();
        return state();
    }

    public synchronized Map<String, Object> appendExternalEvent(String branchName, Map<String, Object> event) {
        Map<String, Object> branch = branch(branchName);
        List<Object> fullEvents = fullExternalEvents(branch);
        List<Object> normalized = normalizeEvents(List.of(event), sources());
        Map<String, Object> normalizedEvent = Json.object(normalized.get(0), "event");
        List<Map<String, Object>> sortedExisting = sortedExternal(fullEvents);
        if (!sortedExisting.isEmpty()) {
            Map<String, Object> last = sortedExisting.get(sortedExisting.size() - 1);
            if (orderCompare(normalizedEvent, last) <= 0) {
                throw new BadRequestException("Appended event must order after the branch's last external event");
            }
        }
        for (Object existing : fullEvents) {
            String existingId = Json.string(Json.object(existing, "event").get("id"), "event.id");
            if (existingId.equals(normalizedEvent.get("id"))) {
                throw new BadRequestException("Event id already exists: " + existingId);
            }
        }
        Json.list(branch.get("appendedEvents"), "appendedEvents").add(normalizedEvent);
        Json.list(branch.get("queue"), "queue").add(normalizedEvent);
        save();
        return state();
    }

    public synchronized Map<String, Object> compareBranches(String leftName, String rightName) {
        Map<String, Object> left = branch(leftName);
        Map<String, Object> right = branch(rightName);
        String ancestorId = commonAncestor(
                Json.string(left.get("checkpoint"), "checkpoint"),
                Json.string(right.get("checkpoint"), "checkpoint"));
        Map<String, Object> ancestor = checkpoint(ancestorId);
        int ancestorSteps = (int) Json.integer(ancestor.get("stepCount"), "stepCount");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("left", leftName);
        result.put("right", rightName);
        result.put("commonAncestor", checkpointSummary(ancestorId));
        result.put("stateDiff", Map.of(
                "left", left.get("state"),
                "right", right.get("state"),
                "equal", Objects.equals(left.get("state"), right.get("state"))));
        result.put("dataDiff", Diff.maps(Json.cloneObject(left.get("data")), Json.cloneObject(right.get("data"))));
        result.put("leftEventsAfterAncestor", eventsAfterAncestor(left, ancestor));
        result.put("rightEventsAfterAncestor", eventsAfterAncestor(right, ancestor));
        result.put("leftOutputsAfterAncestor", outputsAfter(typedList(left.get("steps")), ancestorSteps));
        result.put("rightOutputsAfterAncestor", outputsAfter(typedList(right.get("steps")), ancestorSteps));
        result.put("leftTrajectoryHash", left.get("trajectoryHash"));
        result.put("rightTrajectoryHash", right.get("trajectoryHash"));
        result.put("equal", Objects.equals(left.get("state"), right.get("state"))
                && Json.canonical(left.get("data")).equals(Json.canonical(right.get("data")))
                && Json.canonical(result.get("leftOutputsAfterAncestor")).equals(Json.canonical(result.get("rightOutputsAfterAncestor"))));
        return result;
    }

    public synchronized Map<String, Object> mergeBranches(String leftName, String rightName, String requestedName) {
        Map<String, Object> left = branch(leftName);
        Map<String, Object> right = branch(rightName);
        String ancestorId = commonAncestor(
                Json.string(left.get("checkpoint"), "checkpoint"),
                Json.string(right.get("checkpoint"), "checkpoint"));
        Map<String, Object> ancestor = checkpoint(ancestorId);
        List<Map<String, Object>> leftPost = eventsAfterAncestor(left, ancestor);
        List<Map<String, Object>> rightPost = eventsAfterAncestor(right, ancestor);
        Map<String, Object> conflict = firstConflict(leftPost, rightPost);
        if (conflict != null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "branch-conflict");
            error.put("message", "Branches have incompatible external events");
            error.put("conflict", conflict);
            throw new ConflictException(Json.write(error));
        }
        Set<String> sharedIds = leftPost.stream().map(event -> Json.string(event.get("id"), "id")).collect(Collectors.toSet());
        sharedIds.retainAll(rightPost.stream().map(event -> Json.string(event.get("id"), "id")).collect(Collectors.toSet()));
        List<Map<String, Object>> union = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();
        for (Map<String, Object> event : leftPost) addUnionEvent(union, added, event);
        for (Map<String, Object> event : rightPost) if (!sharedIds.contains(Json.string(event.get("id"), "id"))) addUnionEvent(union, added, event);
        union.sort(SessionService::orderCompare);

        Map<String, Object> branches = Json.object(session.get("branches"), "branches");
        String name = requestedName == null || requestedName.isBlank()
                ? "branch-" + (++branchCounter)
                : requestedName;
        if (branches.containsKey(name) || name.equals("main")) {
            throw new BadRequestException("Branch already exists: " + name);
        }
        List<Object> queue = Json.cloneList(ancestor.get("queue"));
        queue.addAll(union);
        Map<String, Object> merged = newBranch(name, ancestorId,
                Json.string(ancestor.get("state"), "state"),
                Json.cloneObject(ancestor.get("data")),
                queue,
                new ArrayList<>(List.of(ancestorId)),
                Json.integer(ancestor.get("internalSeq"), "internalSeq"),
                ancestor.get("rngAfter") == null ? null : Json.integer(ancestor.get("rngAfter"), "rngAfter"),
                new ArrayList<>(union));
        merged.put("steps", Json.cloneList(ancestor.get("steps")));
        merged.put("trajectoryHash", ancestor.get("trajectoryHash"));
        branches.put(name, merged);
        runToEnd(name);
        save();
        return state();
    }

    public synchronized String exportSession() {
        return Json.write(state());
    }

    public synchronized Map<String, Object> importSession(String exportedJson) {
        Map<String, Object> imported = Json.object(Json.parse(exportedJson), "session");
        Map<String, Object> importedMachine = Json.object(imported.get("machine"), "machine");
        String importedInitialState = initialState(imported);
        Map<String, Object> importedInitialData = initialData(imported);
        long importedSeed = seed(imported);
        List<Object> importedEvents = normalizeEvents(Json.list(imported.get("events"), "events"), machineSources(importedMachine));
        MachineValidator.validate(importedMachine, importedInitialState);
        String expectedManifest = fingerprintManifest(importedMachine, importedInitialState,
                importedInitialData, importedSeed, importedEvents);
        if (!expectedManifest.equals(Json.string(imported.get("manifestFingerprint"), "manifestFingerprint"))) {
            throw new BadRequestException("Manifest fingerprint does not match locked definition, initial state, events, and seed");
        }
        if (!fingerprintMachine(importedMachine).equals(Json.string(imported.get("definitionFingerprint"), "definitionFingerprint"))) {
            throw new BadRequestException("Definition fingerprint does not match machine");
        }
        long importedCheckpointCounter = Json.integer(imported.getOrDefault("checkpointCounter", 1L), "checkpointCounter");
        long importedBranchCounter = Json.integer(imported.getOrDefault("branchCounter", 1L), "branchCounter");
        Map<String, Object> branches = Json.object(imported.get("branches"), "branches");
        for (Object value : branches.values()) {
            validateImportedBranch(Json.object(value, "branch"), imported, importedEvents);
        }
        session = imported;
        session.put("events", importedEvents);
        checkpointCounter = importedCheckpointCounter;
        branchCounter = importedBranchCounter;
        save();
        return state();
    }

    private Map<String, Object> checkpoint(String id) {
        return Json.object(Json.object(session.get("checkpoints"), "checkpoints").get(id),
                "checkpoint " + id);
    }

    private Map<String, Object> checkpointSummary(String id) {
        Map<String, Object> checkpoint = checkpoint(id);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", id);
        summary.put("branch", checkpoint.get("branch"));
        summary.put("state", checkpoint.get("state"));
        summary.put("data", checkpoint.get("data"));
        summary.put("stepCount", checkpoint.get("stepCount"));
        summary.put("manifestFingerprint", checkpoint.get("manifestFingerprint"));
        summary.put("definitionFingerprint", checkpoint.get("definitionFingerprint"));
        summary.put("trajectoryHash", checkpoint.get("trajectoryHash"));
        return summary;
    }

    private Map<String, Object> checkpointRecord(String id, String parentId, String branchName,
                                                 String state, Map<String, Object> data,
                                                 List<Object> fullEvents, List<Object> queue,
                                                 Object manifestFingerprint, Object definitionFingerprint,
                                                 Object trajectoryHash, long internalSeq, Long rngAfter,
                                                 int stepCount) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("id", id);
        checkpoint.put("parentId", parentId);
        checkpoint.put("branch", branchName);
        checkpoint.put("state", state);
        checkpoint.put("data", data);
        checkpoint.put("events", fullEvents);
        checkpoint.put("queue", queue);
        checkpoint.put("manifestFingerprint", manifestFingerprint);
        checkpoint.put("definitionFingerprint", definitionFingerprint);
        checkpoint.put("trajectoryHash", trajectoryHash);
        checkpoint.put("internalSeq", internalSeq);
        checkpoint.put("rngAfter", rngAfter);
        checkpoint.put("stepCount", stepCount);
        return checkpoint;
    }

    private Map<String, Object> requireCurrentCheckpoint(String id) {
        Map<String, Object> checkpoint = checkpoint(id);
        Object checkpointDefinition = checkpoint.get("definitionFingerprint");
        if (!Objects.equals(checkpointDefinition, definitionFingerprint())) {
            throw new ConflictException(Json.write(Map.of(
                    "error", "checkpoint-definition-mismatch",
                    "message", "Checkpoint was created under a different state machine definition",
                    "checkpointId", id,
                    "checkpointDefinitionFingerprint", checkpointDefinition,
                    "currentDefinitionFingerprint", definitionFingerprint())));
        }
        Object checkpointManifest = checkpoint.get("manifestFingerprint");
        if (!Objects.equals(checkpointManifest, manifestFingerprint())) {
            throw new ConflictException(Json.write(Map.of(
                    "error", "checkpoint-manifest-mismatch",
                    "message", "Checkpoint was created with different initial state, events, or seed",
                    "checkpointId", id,
                    "checkpointManifestFingerprint", checkpointManifest,
                    "currentManifestFingerprint", manifestFingerprint())));
        }
        return checkpoint;
    }

    private Optional<String> findBranchWithCheckpoint(String checkpointId) {
        Map<String, Object> branches = Json.object(session.get("branches"), "branches");
        return branches.keySet().stream()
                .filter(name -> lineageContains(branch(name), checkpointId))
                .findFirst();
    }

    private boolean lineageContains(Map<String, Object> branch, String checkpointId) {
        return Json.list(branch.get("lineage"), "lineage").stream()
                .map(String::valueOf)
                .anyMatch(checkpointId::equals);
    }

    private List<Object> fullExternalEvents(Map<String, Object> branch) {
        String checkpointId = Json.string(branch.get("checkpoint"), "branch.checkpoint");
        Map<String, Object> anchor = checkpoint(checkpointId);
        List<Object> events = Json.cloneList(anchor.get("events"));
        events.addAll(Json.cloneList(branch.get("appendedEvents")));
        return events;
    }

    private List<Map<String, Object>> eventsAfterAncestor(Map<String, Object> branch, Map<String, Object> ancestor) {
        List<Object> ancestorEvents = Json.list(ancestor.get("events"), "ancestor.events");
        Set<String> ancestorIds = ancestorEvents.stream()
                .map(value -> Json.string(Json.object(value, "event").get("id"), "id"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return fullExternalEvents(branch).stream()
                .map(value -> Json.object(value, "event"))
                .filter(event -> !ancestorIds.contains(Json.string(event.get("id"), "id")))
                .sorted(SessionService::orderCompare)
                .toList();
    }

    private String commonAncestor(String leftHead, String rightHead) {
        List<String> leftPath = checkpointPath(leftHead);
        Set<String> rightPath = new LinkedHashSet<>(checkpointPath(rightHead));
        for (String checkpointId : leftPath) {
            if (rightPath.contains(checkpointId)) return checkpointId;
        }
        throw new BadRequestException("Branches have no common ancestor");
    }

    private List<String> checkpointPath(String head) {
        List<String> path = new ArrayList<>();
        String current = head;
        Set<String> guard = new LinkedHashSet<>();
        while (current != null && guard.add(current)) {
            path.add(current);
            Map<String, Object> checkpoint = checkpoint(current);
            Object parent = checkpoint.get("parentId");
            current = parent == null ? null : Json.string(parent, "parentId");
        }
        return path;
    }

    private List<Map<String, Object>> sortedExternal(List<?> events) {
        return events.stream()
                .map(value -> Json.object(value, "event"))
                .sorted(SessionService::orderCompare)
                .toList();
    }

    private static int orderCompare(Map<String, Object> left, Map<String, Object> right) {
        int result = Long.compare(Json.integer(left.get("time"), "time"), Json.integer(right.get("time"), "time"));
        if (result != 0) return result;
        result = Long.compare(Json.integer(right.get("priority"), "priority"), Json.integer(left.get("priority"), "priority"));
        if (result != 0) return result;
        result = Long.compare(Json.integer(left.get("seq"), "seq"), Json.integer(right.get("seq"), "seq"));
        if (result != 0) return result;
        result = Json.optionalString(left, "source", "").compareTo(Json.optionalString(right, "source", ""));
        if (result != 0) return result;
        return Json.optionalString(left, "id", "").compareTo(Json.optionalString(right, "id", ""));
    }

    private List<Object> normalizeEvents(List<Object> rawEvents, Map<String, Object> sourcePriorities) {
        List<Map<String, Object>> events = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < rawEvents.size(); i++) {
            Map<String, Object> source = Json.object(rawEvents.get(i), "events[" + i + "]");
            Map<String, Object> event = new LinkedHashMap<>();
            String id = Json.optionalString(source, "id", "event-" + (i + 1));
            if (!ids.add(id)) throw new BadRequestException("Duplicate event id: " + id);
            String sourceName = Json.optionalString(source, "source", "default");
            event.put("id", id);
            event.put("time", Json.integer(source.get("time"), "event.time"));
            event.put("source", sourceName);
            event.put("seq", Json.integer(source.getOrDefault("seq", i + 1L), "event.seq"));
            event.put("event", Json.string(source.get("event"), "event.event"));
            event.put("priority", source.containsKey("priority")
                    ? Json.integer(source.get("priority"), "event.priority")
                    : sourcePriorities.getOrDefault(sourceName, 0L));
            event.put("internal", Boolean.FALSE);
            event.put("payload", source.getOrDefault("payload", new LinkedHashMap<>()));
            events.add(event);
        }
        events.sort(SessionService::orderCompare);
        for (int i = 1; i < events.size(); i++) {
            Map<String, Object> previous = events.get(i - 1);
            Map<String, Object> current = events.get(i);
            boolean sameOrder = Json.integer(previous.get("time"), "time") == Json.integer(current.get("time"), "time")
                    && Json.integer(previous.get("priority"), "priority") == Json.integer(current.get("priority"), "priority")
                    && Json.integer(previous.get("seq"), "seq") == Json.integer(current.get("seq"), "seq")
                    && Json.string(previous.get("source"), "source").equals(Json.string(current.get("source"), "source"));
            if (sameOrder) {
                throw new BadRequestException("Events have an ambiguous identical ordering key: "
                        + current.get("id") + " conflicts with " + previous.get("id"));
            }
        }
        return new ArrayList<>(events);
    }

    private Map<String, Object> firstConflict(List<Map<String, Object>> leftEvents,
                                              List<Map<String, Object>> rightEvents) {
        Map<String, Map<String, Object>> leftById = leftEvents.stream()
                .collect(Collectors.toMap(event -> Json.string(event.get("id"), "id"), event -> event,
                        (first, second) -> first, LinkedHashMap::new));
        Map<String, Map<String, Object>> rightById = rightEvents.stream()
                .collect(Collectors.toMap(event -> Json.string(event.get("id"), "id"), event -> event,
                        (first, second) -> first, LinkedHashMap::new));
        for (String id : leftById.keySet()) {
            if (rightById.containsKey(id)
                    && !Json.canonical(leftById.get(id)).equals(Json.canonical(rightById.get(id)))) {
                return conflict("same-id-different-content", id, List.of(leftById.get(id), rightById.get(id)));
            }
        }
        List<Map<String, Object>> sorted = new ArrayList<>();
        sorted.addAll(leftEvents);
        sorted.addAll(rightEvents);
        sorted.sort(SessionService::orderCompare);
        for (int i = 1; i < sorted.size(); i++) {
            Map<String, Object> previous = sorted.get(i - 1);
            Map<String, Object> current = sorted.get(i);
            String previousId = Json.string(previous.get("id"), "id");
            String currentId = Json.string(current.get("id"), "id");
            if (previousId.equals(currentId)) continue;
            if (Objects.equals(previous.get("time"), current.get("time"))
                    && Objects.equals(previous.get("priority"), current.get("priority"))
                    && Objects.equals(previous.get("seq"), current.get("seq"))
                    && Objects.equals(previous.get("source"), current.get("source"))) {
                return conflict("identical-ordering-key", "time=" + current.get("time")
                        + ",source=" + current.get("source") + ",seq=" + current.get("seq"),
                        List.of(previous, current));
            }
        }
        return null;
    }

    private Map<String, Object> conflict(String kind, String key, List<Map<String, Object>> events) {
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("kind", kind);
        conflict.put("key", key);
        conflict.put("events", events);
        return conflict;
    }

    private void addUnionEvent(List<Map<String, Object>> union, Set<String> ids, Map<String, Object> event) {
        String id = Json.string(event.get("id"), "id");
        if (ids.add(id)) union.add(event);
    }

    private List<Map<String, Object>> outputsAfter(List<Map<String, Object>> steps, int ancestorSteps) {
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (int i = ancestorSteps; i < steps.size(); i++) {
            for (Object output : Json.list(steps.get(i).get("outputs"), "outputs")) {
                outputs.add(Json.object(output, "output"));
            }
        }
        return outputs;
    }

    private void validateLoadedSession() {
        Map<String, Object> machine = machine();
        MachineValidator.validate(machine, initialState());
        List<Object> normalizedEvents = normalizeEvents(Json.list(session.get("events"), "events"), machineSources(machine));
        String manifest = fingerprintManifest(machine, initialState(), initialData(), seed(), normalizedEvents);
        if (!manifest.equals(manifestFingerprint())) {
            throw new IllegalStateException("Persisted session manifest fingerprint is invalid");
        }
        if (!fingerprintMachine(machine).equals(definitionFingerprint())) {
            throw new IllegalStateException("Persisted session definition fingerprint is invalid");
        }
        Map<String, Object> branches = Json.object(session.get("branches"), "branches");
        for (Object value : branches.values()) {
            validateImportedBranch(Json.object(value, "branch"), session, normalizedEvents);
        }
    }

    private void validateImportedBranch(Map<String, Object> branch, Map<String, Object> source,
                                        List<Object> normalizedEvents) {
        String checkpointId = Json.string(branch.get("checkpoint"), "branch.checkpoint");
        Map<String, Object> checkpoints = Json.object(source.get("checkpoints"), "checkpoints");
        Map<String, Object> anchor = Json.object(checkpoints.get(checkpointId), "checkpoint " + checkpointId);
        List<Object> fullEvents = Json.cloneList(anchor.get("events"));
        fullEvents.addAll(Json.cloneList(branch.get("appendedEvents")));
        ReplayResult replay = replay(source, anchor, fullEvents);
        List<Map<String, Object>> storedSteps = typedList(branch.get("steps"));
        if (storedSteps.size() > replay.steps.size()) {
            throw new BadRequestException("Branch has more stored steps than deterministic replay");
        }
        String hash = Json.string(anchor.get("trajectoryHash"), "checkpoint.trajectoryHash");
        for (int i = 0; i < storedSteps.size(); i++) {
            Map<String, Object> stored = storedSteps.get(i);
            Map<String, Object> expected = replay.steps.get(i);
            if (!Json.canonical(stored).equals(Json.canonical(expected))) {
                throw new BadRequestException("Branch step " + i + " does not match deterministic replay");
            }
            hash = replay.hashes.get(i);
        }
        if (!hash.equals(Json.string(branch.get("trajectoryHash"), "trajectoryHash"))) {
            throw new BadRequestException("Branch trajectory hash does not match replay");
        }
        if (!Json.canonical(branch.get("state")).equals(Json.canonical(replay.stateAt(storedSteps.size()))
                || !Json.canonical(branch.get("data")).equals(Json.canonical(replay.dataAt(storedSteps.size())))
                || !Json.canonical(branch.get("queue")).equals(Json.canonical(replay.queueAt(storedSteps.size())))) {
            throw new BadRequestException("Branch current position does not match deterministic replay");
        }
    }

    private ReplayResult replay(Map<String, Object> source, Map<String, Object> anchor, List<Object> fullExternalEvents) {
        Map<String, Object> machine = Json.object(source.get("machine"), "machine");
        String initialState = initialState(source);
        Map<String, Object> initialData = initialData(source);
        long seed = seed(source);
        String state = Json.string(anchor.get("state"), "anchor.state");
        Map<String, Object> data = Json.cloneObject(anchor.get("data"));
        List<Object> queue = Json.cloneList(anchor.get("queue"));
        long internalSeq = Json.integer(anchor.get("internalSeq"), "anchor.internalSeq");
        Long rngAfter = anchor.get("rngAfter") == null ? null : Json.integer(anchor.get("rngAfter"), "rngAfter");
        String previousHash = Json.string(anchor.get("trajectoryHash"), "anchor.trajectoryHash");
        List<Map<String, Object>> steps = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        int index = (int) Json.integer(anchor.get("stepCount"), "anchor.stepCount");
        int guard = 0;
        while (!Json.cloneList(queue).isEmpty()) {
            if (guard++ > 100000) throw new BadRequestException("Internal event runaway limit reached");
            Map<String, Object> outcome = Engine.step(machine, initialState, initialData, seed,
                    state, data, Json.cloneList(queue), internalSeq, rngAfter, previousHash, index);
            steps.add(Json.object(outcome.get("record"), "record"));
            hashes.add(Json.string(outcome.get("hash"), "hash"));
            state = Json.string(outcome.get("state"), "state");
            data = Json.cloneObject(outcome.get("data"));
            queue = Json.cloneList(outcome.get("queue"));
            internalSeq = Json.integer(outcome.get("internalSeq"), "internalSeq");
            rngAfter = Json.integer(outcome.get("rngAfter"), "rngAfter");
            previousHash = hashes.get(hashes.size() - 1);
            index++;
        }
        return new ReplayResult(steps, hashes, state, data, queue);
    }

    private record ReplayResult(List<Map<String, Object>> steps, List<String> hashes,
                                String finalState, Map<String, Object> finalData, List<Object> finalQueue) {
        String stateAt(int count) {
            return count == 0 ? finalState : Json.string(steps.get(count - 1).get("stateAfter"), "stateAfter");
        }

        Map<String, Object> dataAt(int count) {
            return count == 0 ? finalData : Json.cloneObject(steps.get(count - 1).get("dataAfter"));
        }

        List<Object> queueAt(int count) {
            if (count == 0) return finalQueue;
            return Json.cloneList(steps.get(count - 1).get("queueAfter"));
        }
    }

    private static Map<String, Object> event(String id, long time, String source, long seq,
                                             String name, Object payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("time", time);
        event.put("source", source);
        event.put("seq", seq);
        event.put("event", name);
        event.put("payload", payload);
        return event;
    }
}
