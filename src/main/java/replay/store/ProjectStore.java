package replay.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import replay.engine.Definition;
import replay.engine.ReplayEngine;
import replay.json.Json;
import replay.json.JsonUtil;

public final class ProjectStore {
    private final Path root;
    private final Path projectsFile;
    private Map<String, Object> database;

    public ProjectStore(Path root) {
        this.root = root;
        this.projectsFile = root.resolve("projects.json");
        load();
    }

    public synchronized Map<String, Object> createProject(String id, Map<String, Object> definition,
                                                          List<Map<String, Object>> events, String seedLogId) {
        Definition.validate(definition);
        ensureUniqueExternalIds(events);
        Map<String, Object> project = baseProject(id, definition, events);
        project.put("seedLogId", seedLogId == null ? "" : seedLogId);
        Map<String, Object> branch = newBranch(project, "main", "main", null, null, events);
        project.put("branches", mapOf(branch));
        map(database.get("projects")).put(id, project);
        save();
        return projectView(project, "main");
    }

    public synchronized Map<String, Object> updateDefinition(String projectId, Map<String, Object> definition) {
        Map<String, Object> project = requireProject(projectId);
        if (!project.get("branches").toString().equals("{}")) {
            throw new ConflictException("定义已由检查点和轨迹锁定，不能直接修改；请新建项目。");
        }
        Definition.validate(definition);
        project.put("definition", definition);
        project.put("definitionFingerprint", JsonUtil.fingerprint(definition));
        save();
        return projectView(project, "main");
    }

    public synchronized Map<String, Object> importEvents(String projectId, List<Map<String, Object>> events) {
        requireProject(projectId);
        ensureUniqueExternalIds(events);
        Map<String, Object> project = requireProject(projectId);
        Map<String, Object> external = eventsMap(project);
        for (Map<String, Object> event : events) {
            String id = String.valueOf(event.get("id"));
            if (external.containsKey(id)) {
                throw new BadRequestException("重复事件 ID: " + id);
            }
            external.put(id, normalizeInput(event));
        }
        ReplayEngine engine = branchEngine(project, "main");
        engine.enqueueExternal(events);
        updateRuntime(project, "main", engine);
        save();
        return projectView(project, "main");
    }

    public synchronized Map<String, Object> step(String projectId, String branchId, long count) {
        Map<String, Object> project = requireProject(projectId);
        ReplayEngine engine = branchEngine(project, branchId);
        List<Map<String, Object>> steps = new ArrayList<>();
        long target = count <= 0 ? Long.MAX_VALUE : count;
        while (target-- > 0 && engine.hasPending()) {
            steps.add(engine.step().record());
        }
        updateRuntime(project, branchId, engine);
        save();
        Map<String, Object> view = projectView(project, branchId);
        view.put("steps", steps);
        return view;
    }

    public synchronized Map<String, Object> checkpoint(String projectId, String branchId, String name) {
        requireSafeName(name);
        Map<String, Object> project = requireProject(projectId);
        Map<String, Object> branch = requireBranch(project, branchId);
        ReplayEngine engine = branchEngine(project, branchId);
        Map<String, Object> checkpoint = engine.checkpoint(name, branchId);
        checkpoint.put("createdAt", OffsetDateTime.now().toString());
        checkpoint.put("parentCheckpointId", branch.get("currentCheckpointId"));
        checkpoint.put("fingerprint", JsonUtil.fingerprint(checkpoint));
        checkpoints(project).put(String.valueOf(checkpoint.get("checkpointId")), checkpoint);
        branch.put("currentCheckpointId", checkpoint.get("checkpointId"));
        updateRuntime(project, branchId, engine);
        save();
        return projectView(project, branchId);
    }

    public synchronized Map<String, Object> reset(String projectId, String branchId, String checkpointId) {
        Map<String, Object> project = requireProject(projectId);
        Map<String, Object> branch = requireBranch(project, branchId);
        Map<String, Object> checkpoint = requireCheckpoint(project, checkpointId);
        if (!checkpoint.get("branchId").equals(branchId) && !isAncestor(project, branchId, checkpointId)) {
            throw new ConflictException("检查点不属于该分支祖先链");
        }
        branch.put("currentCheckpointId", checkpointId);
        restoreRuntime(project, branchId);
        save();
        return projectView(project, branchId);
    }

    public synchronized Map<String, Object> fork(String projectId, String sourceBranchId, String checkpointId,
                                                 String newBranchId, String name) {
        requireSafeName(newBranchId);
        Map<String, Object> project = requireProject(projectId);
        requireBranch(project, sourceBranchId);
        requireCheckpoint(project, checkpointId);
        if (branches(project).containsKey(newBranchId)) {
            throw new ConflictException("分支已存在: " + newBranchId);
        }
        Map<String, Object> source = requireBranch(project, sourceBranchId);
        List<Map<String, Object>> available = sourceBranchEvents(project, source, checkpointId);
        Map<String, Object> child = newBranch(project, newBranchId, name, sourceBranchId, checkpointId, available);
        branches(project).put(newBranchId, child);
        save();
        return projectView(project, newBranchId);
    }

    public synchronized Map<String, Object> appendEvents(String projectId, String branchId,
                                                         List<Map<String, Object>> events) {
        ensureUniqueExternalIds(events);
        Map<String, Object> project = requireProject(projectId);
        Map<String, Object> branch = requireBranch(project, branchId);
        Map<String, Object> external = eventsMap(project);
        for (Map<String, Object> event : events) {
            String id = String.valueOf(event.get("id"));
            if (external.containsKey(id)) {
                throw new BadRequestException("重复事件 ID: " + id);
            }
            external.put(id, normalizeInput(event));
            branchExternalIds(branch).add(id);
        }
        ReplayEngine engine = branchEngine(project, branchId);
        engine.enqueueExternal(events);
        updateRuntime(project, branchId, engine);
        save();
        return projectView(project, branchId);
    }

    public synchronized Map<String, Object> merge(String projectId, String targetBranchId, String sourceBranchId,
                                                  String targetCheckpointId, String sourceCheckpointId,
                                                  String mergedBranchId, String name) {
        requireSafeName(mergedBranchId);
        Map<String, Object> project = requireProject(projectId);
        Map<String, Object> target = requireBranch(project, targetBranchId);
        Map<String, Object> source = requireBranch(project, sourceBranchId);
        if (branches(project).containsKey(mergedBranchId)) {
            throw new ConflictException("分支已存在: " + mergedBranchId);
        }
        String targetCpId = checkpointIdOrDefault(target, targetCheckpointId);
        String sourceCpId = checkpointIdOrDefault(source, sourceCheckpointId);
        String lcaId = commonAncestor(project, targetCpId, sourceCpId);
        if (lcaId == null) {
            throw new ConflictException("两个分支没有共同祖先检查点");
        }
        Map<String, Object> lca = requireCheckpoint(project, lcaId);
        Map<String, Object> external = eventsMap(project);
        Map<String, Object> merged = mergeEvents(external, stringList(lca.get("processedExternalIds")),
                stringList(requireCheckpoint(project, targetCpId).get("processedExternalIds")),
                stringList(requireCheckpoint(project, sourceCpId).get("processedExternalIds")),
                definition(project));
        if (merged.containsKey("conflict")) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", false);
            response.put("conflict", merged.get("conflict"));
            return response;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ordered = (List<Map<String, Object>>) merged.get("events");
        Map<String, Object> branch = newBranch(project, mergedBranchId, name,
                targetBranchId + "+" + sourceBranchId, lcaId, ordered);
        branches(project).put(mergedBranchId, branch);
        ReplayEngine engine = ReplayEngine.fromCheckpoint(definition(project), lca);
        engine.enqueueExternal(ordered);
        while (engine.hasPending()) {
            engine.step();
        }
        updateRuntime(project, mergedBranchId, engine);
        save();
        Map<String, Object> view = projectView(project, mergedBranchId);
        view.put("mergeBaseCheckpointId", lcaId);
        view.put("ok", true);
        return view;
    }

    public synchronized Map<String, Object> compare(String projectId, String leftBranchId, String rightBranchId) {
        Map<String, Object> project = requireProject(projectId);
        Map<String, Object> left = branchView(project, leftBranchId);
        Map<String, Object> right = branchView(project, rightBranchId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("left", left);
        response.put("right", right);
        response.put("stateDifference", stateDifference(left, right));
        response.put("outputDifference", outputDifference(project, leftBranchId, rightBranchId));
        return response;
    }

    public synchronized Map<String, Object> project(String projectId, String branchId) {
        Map<String, Object> project = requireProject(projectId);
        return projectView(project, branchId == null || branchId.isBlank() ? "main" : branchId);
    }

    public synchronized Map<String, Object> exportSession(String projectId) {
        Map<String, Object> project = requireProject(projectId);
        Map<String, Object> exported = new LinkedHashMap<>();
        exported.put("sessionVersion", 1);
        exported.put("projectId", project.get("id"));
        exported.put("definition", project.get("definition"));
        exported.put("definitionFingerprint", project.get("definitionFingerprint"));
        exported.put("seedLogId", project.get("seedLogId"));
        exported.put("externalEvents", project.get("externalEvents"));
        List<Object> branchExports = new ArrayList<>();
        for (Map.Entry<String, Object> entry : map(project.get("branches")).entrySet()) {
            Map<String, Object> branch = JsonUtil.object(entry.getValue(), "branch");
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("branchId", branch.get("id"));
            copy.put("name", branch.get("name"));
            copy.put("parentBranchId", branch.get("parentBranchId"));
            copy.put("parentCheckpointId", branch.get("parentCheckpointId"));
            copy.put("externalEventIds", branch.get("externalEventIds"));
            copy.put("currentCheckpointId", branch.get("currentCheckpointId"));
            copy.put("runtime", branch.get("runtime"));
            branchExports.add(copy);
        }
        exported.put("branches", branchExports);
        exported.put("checkpoints", project.get("checkpoints"));
        exported.put("sessionFingerprint", JsonUtil.fingerprint(exported));
        return exported;
    }

    public synchronized Map<String, Object> importSession(Map<String, Object> session, String newId) {
        String projectId = newId == null || newId.isBlank()
                ? JsonUtil.string(JsonUtil.object(session, "session"), "projectId") : newId;
        if (map(database.get("projects")).containsKey(projectId)) {
            throw new ConflictException("项目 ID 已存在，请使用新 ID 导入");
        }
        Map<String, Object> definition = JsonUtil.object(session.get("definition"), "definition");
        Definition.validate(definition);
        String expectedDefinition = JsonUtil.fingerprint(definition);
        if (!expectedDefinition.equals(session.get("definitionFingerprint"))) {
            throw new ConflictException("定义指纹与导出内容不一致");
        }
        Map<String, Object> project = baseProject(projectId, definition, List.of());
        project.put("seedLogId", session.get("seedLogId"));
        Map<String, Object> exportedEvents = JsonUtil.object(session.get("externalEvents"), "externalEvents");
        for (Map.Entry<String, Object> entry : exportedEvents.entrySet()) {
            eventsMap(project).put(entry.getKey(), JsonUtil.deepCopy(entry.getValue()));
        }
        for (Object checkpointObject : JsonUtil.list(session.get("checkpoints"), "checkpoints")) {
            checkpoints(project).put(String.valueOf(JsonUtil.object(checkpointObject, "checkpoint").get("checkpointId")),
                    JsonUtil.deepCopy(checkpointObject));
        }
        for (Object branchObject : JsonUtil.list(session.get("branches"), "branches")) {
            Map<String, Object> exportedBranch = JsonUtil.object(branchObject, "exported branch");
            String branchId = JsonUtil.string(exportedBranch, "branchId");
            Map<String, Object> branch = newBranch(project, branchId,
                    JsonUtil.optionalString(exportedBranch, "name", branchId),
                    JsonUtil.optionalString(exportedBranch, "parentBranchId", null),
                    JsonUtil.optionalString(exportedBranch, "parentCheckpointId", null), List.of());
            branch.put("externalEventIds", new ArrayList<>(JsonUtil.list(exportedBranch.get("externalEventIds"), "externalEventIds")));
            branch.put("currentCheckpointId", exportedBranch.get("currentCheckpointId"));
            Map<String, Object> runtime = JsonUtil.object(exportedBranch.get("runtime"), "runtime");
            ReplayEngine engine = ReplayEngine.fromCheckpoint(definition,
                    requireCheckpoint(project, String.valueOf(branch.get("parentCheckpointId"))));
            engine.loadRuntime(runtime);
            updateRuntime(project, branchId, engine);
            branches(project).put(branchId, branch);
        }
        map(database.get("projects")).put(projectId, project);
        save();
        Map<String, Object> reimported = exportSession(projectId);
        if (!session.get("sessionFingerprint").equals(reimported.get("sessionFingerprint"))) {
            throw new ConflictException("导入重放指纹不一致");
        }
        return projectView(project, "main");
    }

    private Map<String, Object> mergeEvents(Map<String, Object> external, List<String> baseIds,
                                            List<String> targetIds, List<String> sourceIds,
                                            Map<String, Object> definition) {
        Set<String> base = new LinkedHashSet<>(baseIds);
        LinkedHashSet<String> targetUnique = new LinkedHashSet<>(targetIds);
        LinkedHashSet<String> sourceUnique = new LinkedHashSet<>(sourceIds);
        targetUnique.removeAll(base);
        sourceUnique.removeAll(base);
        List<String> all = new ArrayList<>(targetUnique);
        for (String id : sourceUnique) {
            if (!all.contains(id)) {
                all.add(id);
            }
        }
        for (String targetId : targetUnique) {
            if (sourceUnique.contains(targetId)) {
                Map<String, Object> targetEvent = eventForMerge(definition, external, targetId);
                Map<String, Object> sourceEvent = eventForMerge(definition, external, targetId);
                if (!Json.canonical(targetEvent).equals(Json.canonical(sourceEvent))) {
                    return Map.of("events", List.of(), "conflict", conflict(targetEvent, sourceEvent, targetUnique, sourceUnique));
                }
            }
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Map<String, Object> a = eventForMerge(definition, external, all.get(i));
                Map<String, Object> b = eventForMerge(definition, external, all.get(j));
                if (orderingConflict(a, b)) {
                    return Map.of("events", List.of(), "conflict", conflict(a, b, targetUnique, sourceUnique));
                }
            }
        }
        List<Map<String, Object>> ordered = all.stream().map(id -> eventForMerge(definition, external, id))
                .sorted(mergeComparator(definition)).toList();
        return Map.of("events", ordered);
    }

    private boolean orderingConflict(Map<String, Object> a, Map<String, Object> b) {
        return longValue(a, "logicalTime") == longValue(b, "logicalTime")
                && longValue(a, "priority") == longValue(b, "priority")
                && longValue(a, "originalSeq") == longValue(b, "originalSeq")
                && !String.valueOf(a.get("id")).equals(String.valueOf(b.get("id")));
    }

    private Map<String, Object> conflict(Map<String, Object> a, Map<String, Object> b,
                                         Set<String> targetUnique, Set<String> sourceUnique) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reason", "同时刻、同来源优先级、同原始序号的不同外部事件，跨分支顺序无歧义规则拒绝合并");
        result.put("firstConflictEvents", List.of(a, b));
        result.put("targetOnlyIds", targetUnique);
        result.put("sourceOnlyIds", sourceUnique);
        return result;
    }

    private Map<String, Object> eventForMerge(Map<String, Object> definition, Map<String, Object> external, String id) {
        Object input = external.get(id);
        if (input == null) {
            throw new ConflictException("合并缺少外部事件: " + id);
        }
        return ReplayEngine.normalizeExternal(definition, JsonUtil.object(input, "external event"));
    }

    private Comparator<Map<String, Object>> mergeComparator(Map<String, Object> definition) {
        return Comparator
                .comparingLong((Map<String, Object> event) -> longValue(event, "logicalTime"))
                .thenComparingLong(event -> -ReplayEngine.sourcePriority(definition, String.valueOf(event.get("source"))))
                .thenComparingLong(event -> longValue(event, "originalSeq"))
                .thenComparing(event -> String.valueOf(event.get("source")))
                .thenComparing(event -> String.valueOf(event.get("id")));
    }

    private Map<String, Object> projectView(Map<String, Object> project, String branchId) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", project.get("id"));
        view.put("definition", project.get("definition"));
        view.put("definitionFingerprint", project.get("definitionFingerprint"));
        view.put("seedLogId", project.get("seedLogId"));
        view.put("externalEvents", project.get("externalEvents"));
        view.put("branches", branches(project).keySet().stream().map(id -> branchView(project, id)).toList());
        view.put("currentBranchId", branchId);
        view.put("currentBranch", branchView(project, branchId));
        return view;
    }

    private Map<String, Object> branchView(Map<String, Object> project, String branchId) {
        Map<String, Object> branch = requireBranch(project, branchId);
        ReplayEngine engine = branchEngine(project, branchId);
        Map<String, Object> runtime = JsonUtil.object(branch.get("runtime"), "runtime");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", branch.get("id"));
        view.put("name", branch.get("name"));
        view.put("parentBranchId", branch.get("parentBranchId"));
        view.put("parentCheckpointId", branch.get("parentCheckpointId"));
        view.put("currentCheckpointId", branch.get("currentCheckpointId"));
        view.put("externalEventIds", branch.get("externalEventIds"));
        view.put("state", engine.stateView());
        view.put("steps", runtime.get("steps"));
        view.put("checkpoints", checkpoints(project).values().stream()
                .map(item -> JsonUtil.object(item, "checkpoint"))
                .filter(cp -> cp.get("branchId").equals(branchId) || isAncestor(project, branchId, String.valueOf(cp.get("checkpointId"))))
                .map(cp -> checkpointSummary(cp))
                .toList());
        return view;
    }

    private Map<String, Object> checkpointSummary(Map<String, Object> checkpoint) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("checkpointId", checkpoint.get("checkpointId"));
        view.put("name", checkpoint.get("name"));
        view.put("branchId", checkpoint.get("branchId"));
        view.put("stepIndex", checkpoint.get("stepIndex"));
        view.put("currentState", checkpoint.get("currentState"));
        view.put("definitionFingerprint", checkpoint.get("definitionFingerprint"));
        view.put("trajectoryHash", checkpoint.get("trajectoryHash"));
        view.put("parentCheckpointId", checkpoint.get("parentCheckpointId"));
        view.put("createdAt", checkpoint.get("createdAt"));
        return view;
    }

    private Map<String, Object> stateDifference(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> a = JsonUtil.object(left.get("state"), "state");
        Map<String, Object> b = JsonUtil.object(right.get("state"), "state");
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("stateChanged", !a.get("state").equals(b.get("state")));
        diff.put("leftHash", JsonUtil.fingerprint(a));
        diff.put("rightHash", JsonUtil.fingerprint(b));
        diff.put("data", compareValues(a.get("data"), b.get("data")));
        return diff;
    }

    private Map<String, Object> outputDifference(Map<String, Object> project, String leftId, String rightId) {
        List<Object> leftOutputs = outputs(project, leftId);
        List<Object> rightOutputs = outputs(project, rightId);
        int common = 0;
        while (common < Math.min(leftOutputs.size(), rightOutputs.size())
                && Json.canonical(leftOutputs.get(common)).equals(Json.canonical(rightOutputs.get(common)))) {
            common++;
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("commonPrefixLength", (long) common);
        diff.put("leftOnly", leftOutputs.subList(common, leftOutputs.size()));
        diff.put("rightOnly", rightOutputs.subList(common, rightOutputs.size()));
        return diff;
    }

    private List<Object> outputs(Map<String, Object> project, String branchId) {
        Map<String, Object> runtime = JsonUtil.object(requireBranch(project, branchId).get("runtime"), "runtime");
        List<Object> outputs = new ArrayList<>();
        for (Object stepObject : JsonUtil.list(runtime.get("steps"), "steps")) {
            outputs.addAll(JsonUtil.list(JsonUtil.object(stepObject, "step").get("outputs"), "outputs"));
        }
        return outputs;
    }

    private Map<String, Object> compareValues(Object left, Object right) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("equal", Json.canonical(left == null ? Map.of() : left)
                .equals(Json.canonical(right == null ? Map.of() : right)));
        result.put("left", left);
        result.put("right", right);
        return result;
    }

    private ReplayEngine branchEngine(Map<String, Object> project, String branchId) {
        Map<String, Object> branch = requireBranch(project, branchId);
        Map<String, Object> definition = definition(project);
        String checkpointId = String.valueOf(branch.get("currentCheckpointId"));
        ReplayEngine engine;
        if (checkpointId.isBlank() || "null".equals(checkpointId)) {
            engine = new ReplayEngine(definition);
        } else {
            engine = ReplayEngine.fromCheckpoint(definition, requireCheckpoint(project, checkpointId));
        }
        Object runtime = branch.get("runtime");
        if (runtime != null) {
            engine.loadRuntime(JsonUtil.object(runtime, "runtime"));
        }
        return engine;
    }

    private void restoreRuntime(Map<String, Object> project, String branchId) {
        Map<String, Object> branch = requireBranch(project, branchId);
        ReplayEngine engine = branchEngine(project, branchId);
        updateRuntime(project, branchId, engine);
    }

    private void updateRuntime(Map<String, Object> project, String branchId, ReplayEngine engine) {
        updateRuntime(requireBranch(project, branchId), engine);
    }

    private void updateRuntime(Map<String, Object> branch, ReplayEngine engine) {
        Map<String, Object> state = engine.stateView();
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("state", state.get("state"));
        runtime.put("data", state.get("data"));
        runtime.put("randomState", state.get("randomState"));
        runtime.put("nextInternalSequence", state.get("nextInternalSequence"));
        runtime.put("stepIndex", state.get("trajectoryLength"));
        runtime.put("trajectoryHash", state.get("trajectoryHash"));
        runtime.put("pending", state.get("nextEvent") == null ? List.of() : engine.pendingEvents());
        runtime.put("steps", engine.trajectoryRecords());
        branch.put("runtime", runtime);
    }

    private Map<String, Object> newBranch(Map<String, Object> project, String id, String name,
                                          String parentBranchId, String parentCheckpointId,
                                          List<Map<String, Object>> events) {
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("id", id);
        branch.put("name", name);
        branch.put("parentBranchId", parentBranchId == null ? "" : parentBranchId);
        branch.put("parentCheckpointId", parentCheckpointId == null ? "" : parentCheckpointId);
        branch.put("currentCheckpointId", parentCheckpointId == null ? "" : parentCheckpointId);
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> event : events) {
            ids.add(event.get("id"));
        }
        branch.put("externalEventIds", ids);
        ReplayEngine engine;
        if (parentCheckpointId == null || parentCheckpointId.isBlank()) {
            engine = new ReplayEngine(definition(project));
        } else {
            engine = ReplayEngine.fromCheckpoint(definition(project), requireCheckpoint(project, parentCheckpointId));
        }
        engine.enqueueExternal(events);
        updateRuntime(branch, engine);
        return branch;
    }

    private List<Map<String, Object>> sourceBranchEvents(Map<String, Object> project, Map<String, Object> branch,
                                                         String checkpointId) {
        requireCheckpoint(project, checkpointId);
        Map<String, Object> external = eventsMap(project);
        Set<String> allowed = new LinkedHashSet<>(stringList(requireCheckpoint(project, checkpointId).get("externalIds")));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object idObject : JsonUtil.list(branch.get("externalEventIds"), "externalEventIds")) {
            String id = String.valueOf(idObject);
            if (allowed.contains(id)) {
                result.add(JsonUtil.object(external.get(id), "event " + id));
            }
        }
        return result;
    }

    private String checkpointIdOrDefault(Map<String, Object> branch, String checkpointId) {
        if (checkpointId != null && !checkpointId.isBlank()) {
            return checkpointId;
        }
        return String.valueOf(branch.get("currentCheckpointId"));
    }

    private boolean isAncestor(Map<String, Object> project, String branchId, String checkpointId) {
        Map<String, Object> branch = requireBranch(project, branchId);
        String cursor = String.valueOf(branch.get("parentCheckpointId"));
        while (cursor != null && !cursor.isBlank() && !"null".equals(cursor)) {
            if (cursor.equals(checkpointId)) {
                return true;
            }
            Map<String, Object> checkpoint = requireCheckpoint(project, cursor);
            cursor = String.valueOf(checkpoint.get("parentCheckpointId"));
        }
        return false;
    }

    private String commonAncestor(Map<String, Object> project, String leftId, String rightId) {
        Set<String> leftChain = new LinkedHashSet<>();
        String cursor = leftId;
        while (cursor != null && !cursor.isBlank() && !"null".equals(cursor)) {
            requireCheckpoint(project, cursor);
            leftChain.add(cursor);
            cursor = String.valueOf(requireCheckpoint(project, cursor).get("parentCheckpointId"));
        }
        cursor = rightId;
        while (cursor != null && !cursor.isBlank() && !"null".equals(cursor)) {
            if (leftChain.contains(cursor)) {
                return cursor;
            }
            cursor = String.valueOf(requireCheckpoint(project, cursor).get("parentCheckpointId"));
        }
        return null;
    }

    private Map<String, Object> baseProject(String id, Map<String, Object> definition,
                                            List<Map<String, Object>> events) {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("id", id);
        project.put("definition", definition);
        project.put("definitionFingerprint", JsonUtil.fingerprint(definition));
        project.put("createdAt", OffsetDateTime.now().toString());
        Map<String, Object> external = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            external.put(JsonUtil.optionalString(event, "id", generatedId(event)), normalizeInput(event));
        }
        project.put("externalEvents", external);
        project.put("branches", new LinkedHashMap<String, Object>());
        project.put("checkpoints", new LinkedHashMap<String, Object>());
        return project;
    }

    private Map<String, Object> normalizeInput(Map<String, Object> event) {
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("id", JsonUtil.optionalString(event, "id", generatedId(event)));
        copy.put("event", JsonUtil.string(event, "event"));
        copy.put("logicalTime", JsonUtil.integer(event.getOrDefault("logicalTime", 0L), "logicalTime"));
        copy.put("source", JsonUtil.optionalString(event, "source", "default"));
        copy.put("originalSeq", JsonUtil.integer(event.get("originalSeq"), "originalSeq"));
        copy.put("data", JsonUtil.deepCopy(event.getOrDefault("data", Map.of())));
        return copy;
    }

    private String generatedId(Map<String, Object> event) {
        return JsonUtil.optionalString(event, "source", "default") + "-"
                + JsonUtil.integer(event.getOrDefault("logicalTime", 0L), "logicalTime") + "-"
                + JsonUtil.integer(event.get("originalSeq"), "originalSeq");
    }

    private void ensureUniqueExternalIds(List<Map<String, Object>> events) {
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> event : events) {
            String id = JsonUtil.optionalString(event, "id", generatedId(event));
            if (!seen.add(id)) {
                throw new BadRequestException("导入日志中存在重复事件 ID: " + id);
            }
        }
    }

    private List<String> stringList(Object value) {
        return JsonUtil.list(value, "string list").stream().map(String::valueOf).toList();
    }

    private List<Object> branchExternalIds(Map<String, Object> branch) {
        return JsonUtil.list(branch.get("externalEventIds"), "externalEventIds");
    }

    private long longValue(Map<String, Object> map, String key) {
        return JsonUtil.integer(map.get(key), key);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> branches(Map<String, Object> project) {
        return (Map<String, Object>) project.get("branches");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> checkpoints(Map<String, Object> project) {
        return (Map<String, Object>) project.get("checkpoints");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> eventsMap(Map<String, Object> project) {
        return (Map<String, Object>) project.get("externalEvents");
    }

    private Map<String, Object> mapOf(Map<String, Object> value) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put(String.valueOf(value.get("id")), value);
        return wrapper;
    }

    private Map<String, Object> definition(Map<String, Object> project) {
        return JsonUtil.object(project.get("definition"), "definition");
    }

    private Map<String, Object> requireProject(String projectId) {
        Object project = map(database.get("projects")).get(projectId);
        if (project == null) {
            throw new NotFoundException("项目不存在: " + projectId);
        }
        return JsonUtil.object(project, "project");
    }

    private Map<String, Object> requireProjectInFlight(String ignored) {
        return Map.of();
    }

    private Map<String, Object> requireBranch(Map<String, Object> project, String branchId) {
        Object branch = branches(project).get(branchId);
        if (branch == null) {
            throw new NotFoundException("分支不存在: " + branchId);
        }
        return JsonUtil.object(branch, "branch");
    }

    private Map<String, Object> requireCheckpoint(Map<String, Object> project, String checkpointId) {
        Object checkpointValue = checkpoints(project).get(checkpointId);
        if (checkpointValue == null) {
            throw new DefinitionVersionException("检查点不存在或已使用旧定义: " + checkpointId);
        }
        Map<String, Object> checkpoint = JsonUtil.object(checkpointValue, "checkpoint");
        String fingerprint = String.valueOf(checkpoint.get("fingerprint"));
        Object copy = JsonUtil.deepCopy(checkpoint);
        Map<String, Object> check = JsonUtil.object(copy, "checkpoint");
        check.remove("fingerprint");
        if (!fingerprint.equals(JsonUtil.fingerprint(check))) {
            throw new DefinitionVersionException("检查点自身指纹损坏");
        }
        String currentDefinition = JsonUtil.fingerprint(definition(project));
        if (!currentDefinition.equals(checkpoint.get("definitionFingerprint"))) {
            throw new DefinitionVersionException("检查点定义指纹不匹配，不能接到当前定义");
        }
        return checkpoint;
    }

    private void requireSafeName(String value) {
        if (value == null || value.isBlank() || value.contains("..") || value.contains("/") || value.contains("\\")) {
            throw new BadRequestException("名称不能为空且不能包含路径分隔符");
        }
    }

    private void load() {
        try {
            Files.createDirectories(root);
            if (Files.exists(projectsFile)) {
                database = JsonUtil.object(Json.parse(Files.readString(projectsFile, StandardCharsets.UTF_8)), "database");
                return;
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法读取持久化文件", e);
        }
        database = new LinkedHashMap<>();
        database.put("version", 1);
        database.put("projects", new LinkedHashMap<String, Object>());
        save();
    }

    private synchronized void save() {
        try {
            Files.createDirectories(root);
            Path temporary = root.resolve("projects.json.tmp");
            Files.writeString(temporary, Json.write(database), StandardCharsets.UTF_8);
            Files.move(temporary, projectsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("无法持久化项目", e);
        }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    public static class DefinitionVersionException extends RuntimeException {
        public DefinitionVersionException(String message) {
            super(message);
        }
    }
}
