package replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * File backed session store. The whole project is one JSON document written
 * atomically (temp file + move) after every mutation, so a restart resumes
 * every branch exactly where it stopped.
 *
 * Trace nodes never store live state. State is always reconstructed by
 * deterministically replaying the node chain from the root with the current
 * engine; the chained node hashes make tampering visible and checkpoints pin
 * the definition fingerprint so an old checkpoint cannot attach to a new
 * definition.
 */
public class ProjectStore {

    private static final int MAX_RUN_STEPS = 100_000;

    private final Path file;
    private Map<String, Object> project;

    public ProjectStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                project = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
                if (project.get("schemaVersion") == null) {
                    throw new IllegalStateException("project file is missing schemaVersion");
                }
                return;
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read project file: " + file, e);
        }
        project = DemoData.newProject();
        persist();
    }

    private void persist() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, Json.writePretty(project), StandardCharsets.UTF_8);
            Files.move(temp, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write project file: " + file, e);
        }
    }

    // ---- generic view ----

    public synchronized Map<String, Object> snapshot() {
        return Json.copyObj(project);
    }

    public synchronized Models.Definition definition() {
        return new Models.Definition(Json.obj(project, "definition"));
    }

    public synchronized String sessionFingerprint() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("definition", Json.obj(project, "definition"));
        payload.put("initial", Json.obj(project, "initial"));
        payload.put("events", Json.arr(project, "events"));
        return Hashes.sha256(Json.writeCanonical(payload));
    }

    private Map<String, Object> nodes() {
        return Json.obj(project, "nodes");
    }

    private Map<String, Object> branches() {
        return Json.obj(project, "branches");
    }

    private Map<String, Object> checkpoints() {
        return Json.obj(project, "checkpoints");
    }

    private Map<String, Object> requireBranch(String name) {
        Object found = branches().get(name);
        if (found == null) {
            throw new NotFoundException("branch not found: " + name);
        }
        return Json.asObj(found, "branch");
    }

    public synchronized List<Models.Event> externalEvents() {
        List<Models.Event> events = new ArrayList<>();
        for (Object item : Json.arr(project, "events")) {
            events.add(Models.Event.fromMap(Json.asObj(item, "event")));
        }
        return DeterministicEngine.sortExternal(events);
    }

    // ---- playback ----

    private static final class Replay {
        final List<String> chain = new ArrayList<>();
        final List<String> hashes = new ArrayList<>();
        Map<String, Object> state;
    }

    /** Replay a node chain from the root, verifying every chained hash. */
    private Replay replayTo(String nodeId) {
        Models.Definition definition = definition();
        Replay replay = new Replay();
        replay.state = DeterministicEngine.initialState(definition);
        List<String> chain = new ArrayList<>();
        String current = nodeId;
        while (current != null) {
            chain.add(0, current);
            Object nodeObject = nodes().get(current);
            if (nodeObject == null) {
                throw new NotFoundException("trace node missing: " + current);
            }
            Map<String, Object> node = Json.asObj(nodeObject, "node");
            String def = Json.str(node, "definition");
            if (def != null && !def.equals(definition.fingerprint)) {
                throw new DefinitionMismatchException(
                        "node " + current + " was recorded under a different definition "
                                + "(node " + abbreviate(def) + ", current "
                                + abbreviate(definition.fingerprint) + ")");
            }
            current = Json.str(node, "parent");
        }
        String prevHash = "";
        for (String id : chain) {
            Map<String, Object> node = Json.asObj(nodes().get(id), "node");
            replay.chain.add(id);
            String kind = Json.str(node, "kind");
            if ("root".equals(kind)) {
                replay.hashes.add(Json.str(node, "hash"));
                prevHash = Json.str(node, "hash");
                continue;
            }
            String stored = Json.str(node, "hash");
            String recomputed = DeterministicEngine.hashNode(node, prevHash);
            if (!stored.equals(recomputed)) {
                throw new DefinitionMismatchException(
                        "trace node " + id + " hash does not match its contents");
            }
            replay.hashes.add(stored);
            prevHash = stored;
            if ("step".equals(kind)) {
                Models.Event event = Models.Event.fromMap(Json.obj(node, "event"));
                long stepIndex = ((Number) node.getOrDefault("step", 0L)).longValue();
                DeterministicEngine.StepResult result =
                        DeterministicEngine.step(definition, replay.state, event, stepIndex, prevHash);
                verifyNode(node, result.node);
                replay.state = result.stateAfter;
            } else if ("dropped".equals(kind)) {
                Models.Event event = Models.Event.fromMap(Json.obj(node, "event"));
                List<Object> consumed = new ArrayList<>(Json.arr(replay.state, "consumed"));
                consumed.add(event.id);
                replay.state.put("consumed", consumed);
            }
        }
        return replay;
    }

    private void verifyNode(Map<String, Object> stored, Map<String, Object> recomputed) {
        for (String key : new String[] {"stateBefore", "stateAfter", "matched", "internal",
                "outputs", "spawned", "failure", "rolledBack", "diff"}) {
            if (!java.util.Objects.equals(stored.get(key), recomputed.get(key))) {
                throw new DefinitionMismatchException(
                        "replay divergence at step " + stored.get("step") + " field " + key);
            }
        }
    }

    private static String abbreviate(String hash) {
        return hash == null ? "null" : hash.substring(0, Math.min(10, hash.length()));
    }

    private Map<String, Object> branchStatus(String name) {
        Map<String, Object> branch = requireBranch(name);
        String head = Json.requireStr(branch, "head");
        Replay replay = replayTo(head);
        List<Models.Event> external = externalEvents();
        @SuppressWarnings("unchecked")
        List<String> consumed = (List<String>) replay.state.getOrDefault("consumed",
                new ArrayList<>());
        Set<String> droppedIds = droppedIds(head);
        Models.Event next = DeterministicEngine.nextEvent(replay.state, external, droppedIds);
        boolean complete = next == null;

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("name", name);
        status.put("head", head);
        status.put("definition", definition().fingerprint);
        status.put("stateName", replay.state.get("state"));
        status.put("vars", Json.copyObj(Json.obj(replay.state, "vars")));
        status.put("internalQueue", Json.arr(replay.state, "internalQueue").size());
        status.put("consumed", new ArrayList<>(consumed));
        status.put("dropped", new ArrayList<>(droppedIds));
        status.put("complete", complete);
        status.put("nextEvent", next == null ? null : next.toMap());
        status.put("stateHash", DeterministicEngine.stateHash(replay.state));
        status.put("traceHash", DeterministicEngine.traceHash(definition().fingerprint,
                replay.hashes.subList(1, replay.hashes.size())));
        return status;
    }

    private Set<String> droppedIds(String head) {
        Set<String> ids = new LinkedHashSet<>();
        String current = head;
        while (current != null) {
            Map<String, Object> node = Json.asObj(nodes().get(current), "node");
            if ("dropped".equals(Json.str(node, "kind"))) {
                ids.add(Json.requireStr(Json.obj(node, "event"), "id"));
            }
            current = Json.str(node, "parent");
        }
        return ids;
    }

    public synchronized Map<String, Object> overview() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("schemaVersion", project.get("schemaVersion"));
        view.put("sessionFingerprint", sessionFingerprint());
        view.put("definition", Json.obj(project, "definition"));
        view.put("definitionFingerprint", definition().fingerprint);
        view.put("initial", Json.obj(project, "initial"));
        view.put("events", Json.arr(project, "events"));
        List<Object> branchViews = new ArrayList<>();
        List<String> names = new ArrayList<>(branches().keySet());
        for (String name : names) {
            try {
                branchViews.add(branchStatus(name));
            } catch (DefinitionMismatchException e) {
                Map<String, Object> stale = new LinkedHashMap<>();
                stale.put("name", name);
                stale.put("head", Json.str(requireBranch(name), "head"));
                stale.put("stale", true);
                stale.put("error", e.getMessage());
                branchViews.add(stale);
            }
        }
        view.put("branches", branchViews);
        List<Object> checkpointViews = new ArrayList<>();
        for (Map.Entry<String, Object> entry : checkpoints().entrySet()) {
            checkpointViews.add(checkpointView(entry.getKey(),
                    Json.asObj(entry.getValue(), "checkpoint")));
        }
        view.put("checkpoints", checkpointViews);
        return view;
    }

    private Map<String, Object> checkpointView(String name, Map<String, Object> checkpoint) {
        Map<String, Object> view = new LinkedHashMap<>(checkpoint);
        view.put("name", name);
        view.put("compatible",
                definition().fingerprint.equals(checkpoint.get("definition")));
        return view;
    }

    // ---- mutations ----

    public synchronized Map<String, Object> updateDefinition(Map<String, Object> raw) {
        Models.Definition candidate = new Models.Definition(raw);
        validateDefinition(candidate);
        project.put("definition", candidate.raw);
        project.put("initial", DeterministicEngine.initialState(candidate));
        // New definition gets a fresh root; old branches/checkpoints stay on disk
        // and are reported stale rather than silently attached to the new definition.
        String rootId = "root-" + ((Number) project.getOrDefault("nextNodeSeq", 1L)).longValue();
        project.put("nextNodeSeq", ((Number) project.getOrDefault("nextNodeSeq", 1L)).longValue() + 1);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", "root");
        root.put("definition", candidate.fingerprint);
        root.put("initial", Json.copyObj(Json.obj(project, "initial")));
        root.put("hash", Hashes.sha256("root:" + candidate.fingerprint));
        nodes().put(rootId, root);
        project.put("root", rootId);
        Map<String, Object> main = new LinkedHashMap<>();
        main.put("head", rootId);
        branches().put("main", main);
        persist();
        return overview();
    }

    private void validateDefinition(Models.Definition definition) {
        if (definition.initialState() == null || definition.initialState().isEmpty()) {
            throw new BadRequestException("definition.initial is required");
        }
        List<String> stateNames = definition.stateNames();
        if (!stateNames.isEmpty() && !stateNames.contains(definition.initialState())) {
            throw new BadRequestException("initial state '" + definition.initialState()
                    + "' is not listed in states");
        }
        for (Map<String, Object> transition : definition.transitions()) {
            String from = Json.str(transition, "from");
            String to = Json.str(transition, "to");
            if (from != null && !"*".equals(from)
                    && !stateNames.isEmpty() && !stateNames.contains(from)) {
                throw new BadRequestException("transition from unknown state: " + from);
            }
            if (to != null && !stateNames.isEmpty() && !stateNames.contains(to)) {
                throw new BadRequestException("transition to unknown state: " + to);
            }
            if (Json.str(transition, "on") == null) {
                throw new BadRequestException("every transition needs an 'on' event type");
            }
        }
    }

    /** Replace the locked event log. Any history becomes invalid, so reset everything. */
    public synchronized Map<String, Object> importEvents(List<Object> eventList, String mode) {
        List<Models.Event> parsed = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        long autoSeq = 0L;
        for (Object item : eventList) {
            Map<String, Object> raw = Json.asObj(item, "event");
            String id = Json.str(raw, "id");
            if (id == null || id.isEmpty()) {
                do {
                    id = "e" + autoSeq++;
                } while (ids.contains(id));
                raw = Json.copyObj(raw);
                raw.put("id", id);
            }
            if (!ids.add(id)) {
                throw new BadRequestException("duplicate event id: " + id);
            }
            long seq = raw.containsKey("seq")
                    ? ((Number) raw.get("seq")).longValue() : autoSeq;
            if (!raw.containsKey("seq")) {
                autoSeq++;
            }
            raw = Json.copyObj(raw);
            raw.put("id", id);
            raw.put("seq", seq);
            raw.putIfAbsent("time", 0L);
            raw.putIfAbsent("priority", 0L);
            raw.putIfAbsent("payload", new LinkedHashMap<>());
            raw.put("internal", false);
            Models.Event event = Models.Event.fromMap(raw);
            parsed.add(event);
        }
        List<Object> stored = new ArrayList<>();
        for (Models.Event event : parsed) {
            stored.add(event.toMap());
        }
        project.put("events", stored);
        if (!"keep".equals(mode)) {
            rebuildHistory();
        }
        persist();
        return overview();
    }

    /** Reset all branches/checkpoints to a fresh root under the current definition. */
    private void rebuildHistory() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", "root");
        root.put("definition", definition().fingerprint);
        root.put("initial", Json.copyObj(Json.obj(project, "initial")));
        root.put("hash", Hashes.sha256("root:" + definition().fingerprint));
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("root", root);
        project.put("nodes", nodes);

        Map<String, Object> main = new LinkedHashMap<>();
        main.put("head", "root");
        Map<String, Object> branches = new LinkedHashMap<>();
        branches.put("main", main);
        project.put("branches", branches);
        project.put("checkpoints", new LinkedHashMap<>());
        project.put("root", "root");
        project.put("initial", DeterministicEngine.initialState(definition()));
        project.put("nextNodeSeq", 1L);
    }

    private String addNode(Map<String, Object> node, String parent) {
        long seq = ((Number) project.getOrDefault("nextNodeSeq", 1L)).longValue();
        project.put("nextNodeSeq", seq + 1);
        String id = "n" + seq;
        node.put("parent", parent);
        nodes().put(id, node);
        return id;
    }

    public synchronized Map<String, Object> reset(String branchName) {
        requireBranch(branchName);
        rebuildHistory();
        branches().clear();
        Map<String, Object> main = new LinkedHashMap<>();
        main.put("head", "root");
        branches().put(branchName == null ? "main" : branchName, main);
        persist();
        return overview();
    }

    public synchronized Map<String, Object> step(String branchName) {
        Map<String, Object> branch = requireBranch(branchName);
        String head = Json.requireStr(branch, "head");
        Replay replay = replayTo(head);
        Models.Event event = DeterministicEngine.nextEvent(replay.state,
                externalEvents(), droppedIds(head));
        if (event == null) {
            throw new BadRequestException("no events left to replay on branch " + branchName);
        }
        long stepIndex = countSteps(head) + 1;
        String prevHash = Json.str(Json.asObj(nodes().get(head), "head node"), "hash");
        DeterministicEngine.StepResult result =
                DeterministicEngine.step(definition(), replay.state, event, stepIndex, prevHash);
        String id = addNode(result.node, head);
        branch.put("head", id);
        persist();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("nodeId", id);
        response.put("node", result.node);
        response.put("branch", branchStatus(branchName));
        return response;
    }

    public synchronized Map<String, Object> run(String branchName, Integer limit) {
        int max = limit == null ? MAX_RUN_STEPS : Math.min(limit, MAX_RUN_STEPS);
        List<Object> executed = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            Map<String, Object> branch = requireBranch(branchName);
            String head = Json.requireStr(branch, "head");
            Replay replay = replayTo(head);
            Models.Event event = DeterministicEngine.nextEvent(replay.state,
                    externalEvents(), droppedIds(head));
            if (event == null) {
                break;
            }
            long stepIndex = countSteps(head) + 1;
            String prevHash = Json.str(Json.asObj(nodes().get(head), "head node"), "hash");
            DeterministicEngine.StepResult result =
                    DeterministicEngine.step(definition(), replay.state, event, stepIndex, prevHash);
            String id = addNode(result.node, head);
            branch.put("head", id);
            executed.add(result.node);
        }
        persist();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("executed", executed.size());
        response.put("branch", branchStatus(branchName));
        return response;
    }

    public synchronized Map<String, Object> drop(String branchName, String eventId) {
        Map<String, Object> branch = requireBranch(branchName);
        String head = Json.requireStr(branch, "head");
        Replay replay = replayTo(head);
        Models.Event target = null;
        for (Models.Event event : externalEvents()) {
            if (event.id.equals(eventId)) {
                target = event;
            }
        }
        if (target == null) {
            throw new NotFoundException("event not found: " + eventId);
        }
        Models.Event next = DeterministicEngine.nextEvent(replay.state,
                externalEvents(), droppedIds(head));
        if (next == null || !next.id.equals(eventId)) {
            throw new BadRequestException("only the current next event can be dropped (next is "
                    + (next == null ? "none" : next.id) + ")");
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("kind", "dropped");
        node.put("step", countSteps(head) + 1);
        node.put("event", target.toMap());
        node.put("hash", DeterministicEngine.hashNode(node,
                Json.str(Json.asObj(nodes().get(head), "head node"), "hash")));
        String id = addNode(node, head);
        branch.put("head", id);
        persist();
        return branchStatus(branchName);
    }

    private long countSteps(String head) {
        long count = 0;
        String current = head;
        while (current != null) {
            Map<String, Object> node = Json.asObj(nodes().get(current), "node");
            if (!"root".equals(Json.str(node, "kind"))) {
                count++;
            }
            current = Json.str(node, "parent");
        }
        return count;
    }

    // ---- checkpoints & forks ----

    public synchronized Map<String, Object> createCheckpoint(String branchName,
                                                             String checkpointName) {
        requireBranch(branchName);
        String head = Json.requireStr(requireBranch(branchName), "head");
        replayTo(head);
        if (checkpointName == null || checkpointName.isEmpty()) {
            throw new BadRequestException("checkpoint name is required");
        }
        if (checkpoints().containsKey(checkpointName)) {
            throw new BadRequestException("checkpoint already exists: " + checkpointName);
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("node", head);
        checkpoint.put("branch", branchName);
        checkpoint.put("definition", definition().fingerprint);
        checkpoint.put("stateHash",
                DeterministicEngine.stateHash(replayTo(head).state));
        checkpoint.put("createdStep", countSteps(head));
        checkpoints().put(checkpointName, checkpoint);
        persist();
        return checkpointView(checkpointName, checkpoint);
    }

    public synchronized Map<String, Object> fork(String branchName, String newBranchName,
                                                 String checkpointName) {
        requireBranch(branchName);
        if (newBranchName == null || newBranchName.isEmpty()) {
            throw new BadRequestException("new branch name is required");
        }
        if (branches().containsKey(newBranchName)) {
            throw new BadRequestException("branch already exists: " + newBranchName);
        }
        String head;
        if (checkpointName != null && !checkpointName.isEmpty()) {
            Object checkpointObject = checkpoints().get(checkpointName);
            if (checkpointObject == null) {
                throw new NotFoundException("checkpoint not found: " + checkpointName);
            }
            Map<String, Object> checkpoint = Json.asObj(checkpointObject, "checkpoint");
            String pinned = String.valueOf(checkpoint.get("definition"));
            if (!pinned.equals(definition().fingerprint)) {
                throw new DefinitionMismatchException(
                        "checkpoint '" + checkpointName + "' was created with definition "
                                + abbreviate(pinned) + " but current definition is "
                                + abbreviate(definition().fingerprint)
                                + ". Refusing to attach an old checkpoint to a new definition.");
            }
            head = String.valueOf(checkpoint.get("node"));
        } else {
            head = Json.requireStr(requireBranch(branchName), "head");
        }
        replayTo(head);
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("head", head);
        branch.put("forkedFrom", branchName);
        branches().put(newBranchName, branch);
        persist();
        return branchStatus(newBranchName);
    }

    // ---- introspection ----

    public synchronized Map<String, Object> trace(String branchName) {
        requireBranch(branchName);
        String head = Json.requireStr(requireBranch(branchName), "head");
        Replay replay = replayTo(head);
        Map<String, Object> response = new LinkedHashMap<>();
        List<Object> nodes = new ArrayList<>();
        for (String id : replay.chain) {
            Map<String, Object> node = Json.asObj(this.nodes().get(id), "node");
            Map<String, Object> view = new LinkedHashMap<>(node);
            view.put("id", id);
            nodes.add(view);
        }
        response.put("nodes", nodes);
        response.put("traceHash", DeterministicEngine.traceHash(definition().fingerprint,
                replay.hashes.subList(1, replay.hashes.size())));
        response.put("definitionFingerprint", definition().fingerprint);
        response.put("sessionFingerprint", sessionFingerprint());
        return response;
    }

    private List<String> chainOf(String head) {
        List<String> chain = new ArrayList<>();
        String current = head;
        while (current != null) {
            chain.add(current);
            Map<String, Object> node = Json.asObj(nodes().get(current), "node");
            current = Json.str(node, "parent");
        }
        return chain;
    }

    private String commonAncestor(String headA, String headB) {
        Set<String> ancestors = new LinkedHashSet<>(chainOf(headA));
        for (String id : chainOf(headB)) {
            if (ancestors.contains(id)) {
                return id;
            }
        }
        return rootIdRaw();
    }

    private String rootIdRaw() {
        return Json.requireStr(project, "root");
    }

    /**
     * External events actually processed on a branch since (and excluding) the
     * ancestor. Explicitly dropped events stay out: choosing not to ingest an
     * event means it is not part of that side's incorporated event set.
     */
    private List<Models.Event> externalSince(String head, String ancestor) {
        List<Models.Event> events = new ArrayList<>();
        String current = head;
        while (current != null && !current.equals(ancestor)) {
            Map<String, Object> node = Json.asObj(nodes().get(current), "node");
            String kind = Json.str(node, "kind");
            if ("step".equals(kind)) {
                Models.Event event = Models.Event.fromMap(Json.obj(node, "event"));
                if (!event.internal) {
                    events.add(event);
                }
            }
            current = Json.str(node, "parent");
        }
        events.sort(DeterministicEngine.EVENT_ORDER);
        return events;
    }

    public synchronized Map<String, Object> compare(String branchA, String branchB) {
        requireBranch(branchA);
        requireBranch(branchB);
        String headA = Json.requireStr(requireBranch(branchA), "head");
        String headB = Json.requireStr(requireBranch(branchB), "head");
        String ancestor = commonAncestor(headA, headB);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("a", branchStatus(branchA));
        response.put("b", branchStatus(branchB));
        response.put("ancestor", ancestor);
        Replay ancestorReplay = replayTo(ancestor);
        Map<String, Object> ancestorView = new LinkedHashMap<>();
        ancestorView.put("node", ancestor);
        ancestorView.put("stateName", ancestorReplay.state.get("state"));
        ancestorView.put("vars", Json.copyObj(Json.obj(ancestorReplay.state, "vars")));
        ancestorView.put("stateHash", DeterministicEngine.stateHash(ancestorReplay.state));
        response.put("ancestorState", ancestorView);

        response.put("eventsA", mapsOf(externalSince(headA, ancestor)));
        response.put("eventsB", mapsOf(externalSince(headB, ancestor)));

        List<Map<String, Object>> diffs = new ArrayList<>();
        Map<String, Object> varsA = Json.obj(replayTo(headA).state, "vars");
        Map<String, Object> varsB = Json.obj(replayTo(headB).state, "vars");
        Set<String> keys = new LinkedHashSet<>(varsA.keySet());
        keys.addAll(varsB.keySet());
        for (String key : keys) {
            Object a = varsA.get(key);
            Object b = varsB.get(key);
            if (!java.util.Objects.equals(a, b)) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("path", key);
                diff.put("a", a);
                diff.put("b", b);
                diffs.add(diff);
            }
        }
        response.put("varDiffs", diffs);
        response.put("stateA", replayTo(headA).state.get("state"));
        response.put("stateB", replayTo(headB).state.get("state"));
        return response;
    }

    private static List<Object> mapsOf(List<Models.Event> events) {
        List<Object> result = new ArrayList<>();
        for (Models.Event event : events) {
            result.add(event.toMap());
        }
        return result;
    }

    // ---- three-way merge ----

    /**
     * Merge is allowed only when the sets of external events seen on each side
     * since the common ancestor are compatible and their order is unambiguous:
     * shared events must appear on both sides with the same predecessor set,
     * and same-time events unique to either side create an ordering conflict.
     * Outputs compared at merge time come from replaying both chains; when
     * compatible both chains describe the same external history and therefore
     * the same deterministic result.
     */
    public synchronized Map<String, Object> merge(String branchA, String branchB,
                                                  String mergedName) {
        requireBranch(branchA);
        requireBranch(branchB);
        if (mergedName == null || mergedName.isEmpty()) {
            throw new BadRequestException("merged branch name is required");
        }
        if (branches().containsKey(mergedName)) {
            throw new BadRequestException("branch already exists: " + mergedName);
        }
        String headA = Json.requireStr(requireBranch(branchA), "head");
        String headB = Json.requireStr(requireBranch(branchB), "head");
        String ancestor = commonAncestor(headA, headB);

        List<Models.Event> a = externalSince(headA, ancestor);
        List<Models.Event> b = externalSince(headB, ancestor);

        // Compatibility scan over the global canonical ordering. The two branches
        // are compatible exactly when no logical-time group contains events
        // belonging to different sides without the other side also being at that
        // time (unique same-time events on both sides make the order ambiguous).
        Map<String, Models.Event> byId = new LinkedHashMap<>();
        Set<String> idsA = new LinkedHashSet<>();
        Set<String> idsB = new LinkedHashSet<>();
        for (Models.Event event : a) {
            byId.put(event.id, event);
            idsA.add(event.id);
        }
        for (Models.Event event : b) {
            byId.put(event.id, event);
            idsB.add(event.id);
        }
        List<Models.Event> union = new ArrayList<>(byId.values());
        union.sort(DeterministicEngine.EVENT_ORDER);
        Set<String> processedA = new LinkedHashSet<>();
        Set<String> processedB = new LinkedHashSet<>();
        List<String> mergedIds = new ArrayList<>();
        int i = 0;
        while (i < union.size()) {
            long time = union.get(i).time;
            List<Models.Event> group = new ArrayList<>();
            while (i < union.size() && union.get(i).time == time) {
                group.add(union.get(i));
                i++;
            }
            boolean anyA = false;
            boolean anyB = false;
            boolean uniqueA = false;
            boolean uniqueB = false;
            for (Models.Event event : group) {
                boolean inA = idsA.contains(event.id);
                boolean inB = idsB.contains(event.id);
                anyA |= inA;
                anyB |= inB;
                uniqueA |= inA && !inB;
                uniqueB |= inB && !inA;
            }
            if (uniqueA && uniqueB) {
                throw conflict(branchA, branchB, ancestor,
                        firstAt(group, idsA), firstAt(group, idsB),
                        "events unique to each branch share logical time " + time
                                + "; their relative order is ambiguous", a, b);
            }
            // A shared event must be reached at the same position on both sides:
            // all earlier groups must contain exactly the same shared/side flags.
            for (Models.Event event : group) {
                if (idsA.contains(event.id) && idsB.contains(event.id)) {
                    if (!processedA.equals(processedB)) {
                        throw conflict(branchA, branchB, ancestor, event, event,
                                "shared event " + event.id
                                        + " is reached after different predecessor events",
                                a, b);
                    }
                }
            }
            for (Models.Event event : group) {
                mergedIds.add(event.id);
                if (idsA.contains(event.id)) {
                    processedA.add(event.id);
                }
                if (idsB.contains(event.id)) {
                    processedB.add(event.id);
                }
            }
        }

        // Replay the unambiguous union from the ancestor state, creating a
        // fresh node chain. Events dropped on either side stay out of that side
        // and therefore only enter the union when both sides saw them.
        Replay ancestorReplay = replayTo(ancestor);
        Set<String> mergedSet = new LinkedHashSet<>(mergedIds);
        Map<String, Object> state = Json.copyObj(ancestorReplay.state);
        state.put("internalQueue", new ArrayList<>());
        List<Object> consumed = new ArrayList<>(Json.arr(state, "consumed"));
        for (Models.Event event : externalEvents()) {
            if (!mergedSet.contains(event.id) && !consumed.contains(event.id)) {
                consumed.add(event.id);
            }
        }
        state.put("consumed", consumed);
        String parent = ancestor;
        String prevHash = Json.str(Json.asObj(nodes().get(ancestor), "ancestor node"), "hash");
        long stepBase = countSteps(ancestor);
        String newHead = ancestor;
        for (;;) {
            Models.Event event = DeterministicEngine.nextEvent(state, externalEvents(), Set.of());
            if (event == null) {
                break;
            }
            DeterministicEngine.StepResult result = DeterministicEngine.step(
                    definition(), state, event, stepBase + 1, prevHash);
            stepBase++;
            String id = addNode(result.node, parent);
            parent = id;
            newHead = id;
            state = result.stateAfter;
            prevHash = String.valueOf(result.node.get("hash"));
        }

        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("head", newHead);
        branch.put("mergedFrom", branchA + "," + branchB);
        branches().put(mergedName, branch);
        persist();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("merged", true);
        response.put("ancestor", ancestor);
        response.put("externalOrder", mergedIds);
        response.put("branch", branchStatus(mergedName));
        return response;
    }

    private static Models.Event firstAt(List<Models.Event> group, Set<String> ids) {
        for (Models.Event event : group) {
            if (ids.contains(event.id)) {
                return event;
            }
        }
        return null;
    }

    private Map<String, Object> conflictMap(String branchA, String branchB, String ancestor,
                                            String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        detail.put("ancestor", ancestor);
        detail.put("branchA", branchA);
        detail.put("branchB", branchB);
        return detail;
    }

    private static int indexOf(List<Models.Event> events, String id, int from) {
        for (int i = from; i < events.size(); i++) {
            if (events.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private ConflictException conflict(String branchA, String branchB, String ancestor,
                                       Models.Event ea, Models.Event eb, String reason,
                                       List<Models.Event> allA, List<Models.Event> allB) {
        long time = ea != null ? ea.time : eb.time;
        List<Object> groupA = new ArrayList<>();
        List<Object> groupB = new ArrayList<>();
        for (Models.Event event : allA) {
            if (event.time == time) {
                groupA.add(event.toMap());
            }
        }
        for (Models.Event event : allB) {
            if (event.time == time) {
                groupB.add(event.toMap());
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        detail.put("ancestor", ancestor);
        detail.put("logicalTime", time);
        detail.put("branchA", branchA);
        detail.put("branchB", branchB);
        detail.put("eventsA", groupA);
        detail.put("eventsB", groupB);
        if (ea != null) {
            detail.put("nextA", ea.toMap());
        }
        if (eb != null) {
            detail.put("nextB", eb.toMap());
        }
        return new ConflictException(detail);
    }

    // ---- export / import ----

    public synchronized Map<String, Object> exportSession() {
        return Json.copyObj(project);
    }

    /**
     * Import a previously exported session document and verify that every
     * branch replays to the same state and trace hash recorded in the file.
     */
    public synchronized Map<String, Object> importSession(Map<String, Object> imported) {
        for (String key : new String[] {"schemaVersion", "definition", "nodes",
                "branches", "root"}) {
            if (!imported.containsKey(key)) {
                throw new BadRequestException("imported session is missing: " + key);
            }
        }
        Map<String, Object> previous = project;
        project = Json.copyObj(imported);
        try {
            for (String branchName : branches().keySet()) {
                Map<String, Object> status = branchStatus(branchName);
                if (status.containsKey("error")) {
                    throw new BadRequestException("imported branch " + branchName
                            + " fails verification: " + status.get("error"));
                }
            }
            for (Map.Entry<String, Object> entry : checkpoints().entrySet()) {
                Map<String, Object> checkpoint = Json.asObj(entry.getValue(), "checkpoint");
                String nodeId = String.valueOf(checkpoint.get("node"));
                replayTo(nodeId);
            }
            persist();
            return overview();
        } catch (RuntimeException e) {
            project = previous;
            throw e;
        }
    }

    // ---- errors ----

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    public static class DefinitionMismatchException extends RuntimeException {
        public DefinitionMismatchException(String message) {
            super(message);
        }
    }

    public static class ConflictException extends RuntimeException {
        public final Map<String, Object> detail;

        public ConflictException(Map<String, Object> detail) {
            super(String.valueOf(detail.get("reason")));
            this.detail = detail;
        }
    }
}
