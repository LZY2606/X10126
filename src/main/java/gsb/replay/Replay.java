package gsb.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 回放室核心服务（无持久化 I/O，由 Store 包装）。
 *
 * 会话锁：定义（版本+指纹）、初始状态、随机种子、已导入外部事件。
 * 轨迹哈希链：H0 = sha256("seed"|canonical({definitionFingerprint,initialState,initialVars,seed}))
 *            Hn = sha256(H(n-1) | canonical(traceEntry))
 * 检查点指纹必须与当前定义指纹一致，否则拒绝恢复。
 */
public final class Replay {

    public static final class ConflictException extends RuntimeException {
        public final Map<String, Object> detail;

        public ConflictException(String message, Map<String, Object> detail) {
            super(message);
            this.detail = detail;
        }
    }

    // ---------------- 会话 / 定义 ----------------

    public Map<String, Object> createSession(Map<String, Object> definitionInput) {
        return createSession(definitionInput, 1);
    }

    public Map<String, Object> createSession(Map<String, Object> definitionInput, int version) {
        Map<String, Object> defRaw = new LinkedHashMap<>(definitionInput);
        defRaw.put("version", String.valueOf(version));
        Definition def = Definition.fromRaw(defRaw);

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", "sess");
        session.put("createdAtLogical", 0L);
        session.put("definition", def.raw);
        session.put("definitionFingerprint", def.fingerprint());
        session.put("branches", new LinkedHashMap<String, Object>());
        session.put("checkpoints", new LinkedHashMap<String, Object>());
        session.put("nextBranch", 1);
        session.put("nextCheckpoint", 1);
        session.put("importLog", new ArrayList<Object>());
        session.put("externalSeq", 0L);

        Map<String, Object> main = newBranch(session, "main", null, null, 0L);
        branches(session).put("main", main);
        return session;
    }

    public Definition definition(Map<String, Object> session) {
        return Definition.fromRaw(Json.obj(session.get("definition"), "definition"));
    }

    public String definitionFingerprint(Map<String, Object> session) {
        return (String) session.get("definitionFingerprint");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> branches(Map<String, Object> s) {
        return (Map<String, Object>) s.get("branches");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> checkpoints(Map<String, Object> s) {
        return (Map<String, Object>) s.get("checkpoints");
    }

    public Map<String, Object> branch(Map<String, Object> s, String id) {
        Object bo = branches(s).get(id);
        if (!(bo instanceof Map)) {
            throw new Json.JsonException("branch not found: " + id);
        }
        Map<String, Object> b = (Map<String, Object>) bo;
        if (b == null) {
            throw new Json.JsonException("branch not found: " + id);
        }
        return b;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> newBranch(Map<String, Object> session, String name,
                                          String parentBranch, String parentCheckpoint, long forkAt) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", name);
        b.put("name", name);
        b.put("parentBranch", parentBranch);
        b.put("parentCheckpoint", parentCheckpoint);
        b.put("forkAtStep", forkAt);
        b.put("trace", new ArrayList<Object>());
        b.put("traceHash", anchorHash(session));
        b.put("externalIds", new ArrayList<Object>());
        b.put("externalLog", new ArrayList<Object>());
        b.put("pendingExternals", new ArrayList<Object>());
        b.put("pendingInternals", new ArrayList<Object>());
        b.put("currentState", definition(session).initialState());
        b.put("state", definition(session).initialVars());
        b.put("rngState", definition(session).seed());
        b.put("stepCount", 0L);
        b.put("internalSeq", 0L);
        b.put("createdFromMerge", false);
        return b;
    }

    public String anchorHash(Map<String, Object> session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definitionFingerprint", definitionFingerprint(session));
        body.put("initialState", definition(session).initialState());
        body.put("initialVars", definition(session).initialVars());
        body.put("seed", definition(session).seed());
        return Hash.sha256Hex("seed|" + Json.canonical(body));
    }

    // ---------------- 外部事件导入 ----------------

    /** 向 main（或指定分支）导入一批外部事件；规范化、排序后追加到待处理队列。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> importEvents(Map<String, Object> session, String branchId,
                                                  List<Object> events) {
        Map<String, Object> b = branch(session, branchId == null ? "main" : branchId);
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (Object o : events) {
            Map<String, Object> e = Json.obj(o, "event");
            if (!(e.get("type") instanceof String)) {
                throw new Json.JsonException("event.type required");
            }
            Map<String, Object> norm = new LinkedHashMap<>(e);
            long seq = Json.asLong(norm.get("seq"), -1L);
            if (seq < 0) {
                seq = ((Number) session.get("externalSeq")).longValue() + 1;
                session.put("externalSeq", seq);
                norm.put("seq", seq);
            } else {
                long cur = ((Number) session.get("externalSeq")).longValue();
                if (seq > cur) {
                    session.put("externalSeq", seq);
                }
            }
            if (norm.get("id") == null) {
                norm.put("id", "ext-" + seq);
            }
            norm.put("time", Json.asLong(norm.get("time"), 0L));
            norm.put("priority", Json.asLong(norm.get("priority"), 0L));
            norm.put("seq", seq);
            norm.put("internal", false);
            if (norm.get("source") == null) {
                norm.put("source", "log");
            }
            accepted.add(norm);
        }
        @SuppressWarnings("unchecked")
        List<Object> pending = (List<Object>) b.get("pendingExternals");
        pending.addAll(accepted);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pendingMaps = (List) pending;
        pendingMaps.sort(Engine.EXTERNAL_ORDER);
        @SuppressWarnings("unchecked")
        List<Object> branchLog = (List<Object>) b.get("externalLog");
        branchLog.addAll(accepted);
        return accepted;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> allImported(Map<String, Object> session) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> l = (List) session.get("importLog");
        return l;
    }

    // ---------------- 单步回放 ----------------

    public boolean canStep(Map<String, Object> session, String branchId) {
        Map<String, Object> b = branch(session, branchId == null ? "main" : branchId);
        return Engine.peekNext(pendingInternal(b), pendingExternal(b)) != null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pendingInternal(Map<String, Object> b) {
        return castList(b.get("pendingInternals"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pendingExternal(Map<String, Object> b) {
        return castList(b.get("pendingExternals"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> traceOf(Map<String, Object> b) {
        return castList(b.get("trace"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> externalIds(Map<String, Object> b) {
        return (List<String>) (List<?>) b.get("externalIds");
    }

    public Map<String, Object> peek(Map<String, Object> session, String branchId) {
        Map<String, Object> b = branch(session, branchId == null ? "main" : branchId);
        return Engine.peekNext(pendingInternal(b), pendingExternal(b));
    }

    /** 单步执行；无事件时返回 null。 */
    public Map<String, Object> step(Map<String, Object> session, String branchId) {
        Map<String, Object> b = branch(session, branchId == null ? "main" : branchId);
        List<Map<String, Object>> internals = pendingInternal(b);
        List<Map<String, Object>> externals = pendingExternal(b);
        Map<String, Object> next = Engine.peekNext(internals, externals);
        if (next == null) {
            return null;
        }
        if (next.get("internal") == Boolean.TRUE) {
            internals.remove(0);
        } else {
            externals.remove(0);
            externalIds(b).add((String) next.get("id"));
        }

        long stepCount = ((Number) b.get("stepCount")).longValue();
        Map<String, Object> stepEvent = new LinkedHashMap<>(next);
        stepEvent.put("seq", stepCount); // 引擎内部用不到，轨迹展示保留逻辑序号
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("currentState", b.get("currentState"));
        runtime.put("state", Definition.deepCopy(Json.obj(b.get("state"), "state")));
        Rng rng = new Rng(((Number) b.get("rngState")).longValue());
        runtime.put("rngRef", rng);

        Map<String, Object> entry = Engine.step(definition(session), runtime, stepEvent);
        entry.put("seq", stepCount);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> emitted = (List<Map<String, Object>>) runtime.get("emitted");
        if (emitted == null) {
            emitted = new ArrayList<>();
        }
        long internalSeq = ((Number) b.get("internalSeq")).longValue();
        for (Map<String, Object> ie : emitted) {
            internalSeq++;
            ie.put("id", "int-" + stepCount + "-" + internalSeq);
            ie.put("priority", 0L);
            ie.put("seq", internalSeq);
            ie.put("internal", true);
            ie.put("source", "internal");
            internals.add(ie);
        }
        // 新内部事件带延迟时，重排 FIFO 中按时间非降序（延迟事件应排到后面）。
        internals.sort(java.util.Comparator.comparingLong((Map<String, Object> e) ->
                Json.asLong(e.get("time"), 0L)).thenComparingLong(e -> Json.asLong(e.get("seq"), 0L)));

        String prevHash = (String) b.get("traceHash");
        String h = Hash.chain(prevHash, fingerprintEntry(entry));
        entry.put("hash", h);

        b.put("currentState", runtime.get("currentState"));
        b.put("state", runtime.get("state"));
        b.put("rngState", rng.snapshot());
        b.put("stepCount", stepCount + 1);
        b.put("internalSeq", internalSeq);
        b.put("traceHash", h);
        traceOf(b).add(entry);
        return entry;
    }

    public int runTo(Map<String, Object> session, String branchId, long maxSteps) {
        int n = 0;
        while ((maxSteps <= 0 || n < maxSteps) && canStep(session, branchId)) {
            step(session, branchId);
            n++;
        }
        return n;
    }

    /** 指纹只包含轨迹的确定性内容，不含展示用 hash 字段本身。 */
    private Map<String, Object> fingerprintEntry(Map<String, Object> entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : new String[]{"seq", "event", "status", "from", "to", "transition", "outputs", "failure"}) {
            if (entry.containsKey(k)) {
                m.put(k, entry.get(k));
            }
        }
        return m;
    }

    // ---------------- 检查点 / 分叉 ----------------

    /** 建立检查点：深拷贝整个分支运行时，并锁定当前定义指纹。 */
    public Map<String, Object> checkpoint(Map<String, Object> session, String branchId, String label) {
        Map<String, Object> b = branch(session, branchId == null ? "main" : branchId);
        long n = ((Number) session.get("nextCheckpoint")).longValue();
        session.put("nextCheckpoint", n + 1);
        String id = "cp-" + n;

        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("id", id);
        cp.put("label", label == null ? id : label);
        cp.put("branch", b.get("id"));
        cp.put("step", b.get("stepCount"));
        cp.put("definitionFingerprint", definitionFingerprint(session));
        cp.put("definitionVersion", definition(session).version());
        cp.put("currentState", b.get("currentState"));
        cp.put("state", Definition.deepCopy(Json.obj(b.get("state"), "state")));
        cp.put("rngState", b.get("rngState"));
        cp.put("internalSeq", b.get("internalSeq"));
        cp.put("traceHash", b.get("traceHash"));
        cp.put("externalLog", deepList(b.get("externalLog")));
        cp.put("pendingExternals", deepList(b.get("pendingExternals")));
        cp.put("pendingInternals", deepList(b.get("pendingInternals")));
        cp.put("externalIds", new ArrayList<>(externalIds(b)));
        checkpoints(session).put(id, cp);
        return cp;
    }

    /**
     * 从检查点分叉。检查点必须通过定义指纹校验：旧定义下的检查点拒绝接到新定义。
     * playPending=true 表示把检查点处尚未处理的外部事件队列复制到新分支继续处理。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fork(Map<String, Object> session, String checkpointId,
                                    String name, boolean playPending) {
        Object cpo = checkpoints(session).get(checkpointId);
        if (!(cpo instanceof Map)) {
            throw new Json.JsonException("checkpoint not found: " + checkpointId);
        }
        Map<String, Object> cp = (Map<String, Object>) cpo;
        String fp = definitionFingerprint(session);
        if (!fp.equals(cp.get("definitionFingerprint"))) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", "definition fingerprint mismatch");
            detail.put("checkpoint", checkpointId);
            detail.put("checkpointFingerprint", cp.get("definitionFingerprint"));
            detail.put("checkpointVersion", cp.get("definitionVersion"));
            detail.put("currentFingerprint", fp);
            detail.put("currentVersion", definition(session).version());
            throw new ConflictException("检查点定义指纹不匹配，拒绝恢复", detail);
        }
        long n = ((Number) session.get("nextBranch")).longValue();
        session.put("nextBranch", n + 1);
        String id = name == null ? "branch-" + n : name;
        if (branches(session).containsKey(id)) {
            throw new Json.JsonException("branch name already exists: " + id);
        }

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", id);
        b.put("name", id);
        b.put("parentBranch", cp.get("branch"));
        b.put("parentCheckpoint", checkpointId);
        b.put("forkAtStep", cp.get("step"));
        b.put("trace", deepList(traceFromPrefix(session, (String) cp.get("branch"),
                ((Number) cp.get("step")).longValue())));
        b.put("traceHash", cp.get("traceHash"));
        b.put("externalIds", new ArrayList<>(Json.list(cp.get("externalIds"), "cp.externalIds")));
        b.put("externalLog", new ArrayList<>(Json.list(cp.get("externalLog"), "cp.externalLog")));
        b.put("pendingExternals", playPending
                ? deepList(cp.get("pendingExternals"))
                : new ArrayList<Object>());
        b.put("pendingInternals", new ArrayList<>(deepList(cp.get("pendingInternals"))));
        b.put("currentState", cp.get("currentState"));
        b.put("state", Definition.deepCopy(Json.obj(cp.get("state"), "cp.state")));
        b.put("rngState", cp.get("rngState"));
        b.put("stepCount", cp.get("step"));
        b.put("internalSeq", cp.get("internalSeq"));
        b.put("createdFromMerge", false);
        branches(session).put(id, b);
        return b;
    }

    @SuppressWarnings("unchecked")
    private List<Object> traceFromPrefix(Map<String, Object> session, String branchId, long steps) {
        List<Object> full = (List<Object>) branch(session, branchId).get("trace");
        return new ArrayList<>(full.subList(0, (int) Math.min(steps, full.size())));
    }

    // ---------------- 合并 ----------------

    /**
     * 合并规则：两边从共同祖先（检查点）开始的外部事件集合必须兼容，
     * 且规范顺序无歧义。集合不同 / 顺序冲突则拒绝并给出第一组冲突事件。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> mergePlan(Map<String, Object> session, String branchA, String branchB,
                                         String ancestorCheckpointId) {
        Map<String, Object> a = branch(session, branchA);
        Map<String, Object> b = branch(session, branchB);

        String ancestorId = ancestorCheckpointId;
        long ancestorStep = 0;
        if (ancestorId == null) {
            ancestorId = inferAncestor(session, a, b);
        }
        if (ancestorId != null) {
            Object acp = checkpoints(session).get(ancestorId);
            if (!(acp instanceof Map)) {
                throw new Json.JsonException("ancestor checkpoint not found: " + ancestorId);
            }
            Map<String, Object> cp = (Map<String, Object>) acp;
            ancestorStep = ((Number) cp.get("step")).longValue();
        }

        List<String> baseIds = ancestorId == null
                ? new ArrayList<>()
                : new ArrayList<>((List<String>) (List<?>) Json.obj(checkpoints(session).get(ancestorId), "cp").get("externalIds"));

        List<Map<String, Object>> aEvents = externalEventsSince(session, a, baseIds);
        List<Map<String, Object>> bEvents = externalEventsSince(session, b, baseIds);

        // 1) 集合兼容性（按外部事件 id）
        Map<String, Map<String, Object>> aById = indexById(aEvents);
        Map<String, Map<String, Object>> bById = indexById(bEvents);

        List<Map<String, Object>> onlyA = new ArrayList<>();
        for (String id : aById.keySet()) {
            if (!bById.containsKey(id)) {
                onlyA.add(aById.get(id));
            }
        }
        List<Map<String, Object>> onlyB = new ArrayList<>();
        for (String id : bById.keySet()) {
            if (!aById.containsKey(id)) {
                onlyB.add(bById.get(id));
            }
        }
        if (!onlyA.isEmpty() || !onlyB.isEmpty()) {
            onlyA.sort(Engine.EXTERNAL_ORDER);
            onlyB.sort(Engine.EXTERNAL_ORDER);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("reason", "external event sets differ since common ancestor");
            d.put("ancestor", ancestorId);
            d.put("firstConflictAt", firstTime(onlyA, onlyB));
            d.put("onlyOnA", publicList(onlyA));
            d.put("onlyOnB", publicList(onlyB));
            throw new ConflictException("两分支外部事件集合不兼容，拒绝合并", d);
        }

        // 2) 顺序无歧义：按规范顺序合并后必须与两边各自的相对顺序一致。
        List<Map<String, Object>> union = new ArrayList<>(aById.values());
        union.sort(Engine.EXTERNAL_ORDER);

        List<String> canonicalIds = idsOf(union);
        List<String> aOrder = idsOf(aEvents);
        List<String> bOrder = idsOf(bEvents);

        List<Map<String, Object>> filterCanonical = new ArrayList<>();
        {
            java.util.Set<String> aSet = new java.util.HashSet<>(aOrder);
            for (Map<String, Object> e : union) {
                if (aSet.contains(e.get("id"))) {
                    filterCanonical.add(e);
                }
            }
        }
        List<Map<String, Object>> firstConflictingGroup = orderConflictGroup(aEvents, bEvents, union);
        if (firstConflictingGroup != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("reason", "ambiguous event ordering since common ancestor");
            d.put("ancestor", ancestorId);
            d.put("firstConflictAt", firstConflictingGroup.isEmpty() ? null
                    : firstConflictingGroup.get(0).get("time"));
            d.put("conflictGroup", publicList(firstConflictingGroup));
            throw new ConflictException("两分支事件顺序存在歧义，拒绝合并", d);
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("compatible", true);
        plan.put("ancestor", ancestorId);
        plan.put("ancestorStep", ancestorStep);
        plan.put("events", union);
        plan.put("branchAEndState", a.get("state"));
        plan.put("branchBEndState", b.get("state"));
        boolean sameEnd = Json.canonical(a.get("state")).equals(Json.canonical(b.get("state")))
                && a.get("currentState").equals(b.get("currentState"))
                && a.get("traceHash").equals(b.get("traceHash"));
        plan.put("identicalResults", sameEnd);
        return plan;
    }

    /** 执行合并：重放共同祖先上的规范事件序列，生成新分支并验证结果指纹。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> merge(Map<String, Object> session, String branchA, String branchB,
                                     String ancestorCheckpointId, String newBranchName) {
        Map<String, Object> plan = mergePlan(session, branchA, branchB, ancestorCheckpointId);
        String ancestorId = (String) plan.get("ancestor");
        List<Map<String, Object>> events = (List<Map<String, Object>>) (List<?>)  plan.get("events");

        Map<String, Object> child = fork(session, ancestorId == null ? firstCheckpointOf(session, branchA) : ancestorId,
                newBranchName == null ? "merge-" + branchA + "-" + branchB : newBranchName, false);
        for (Map<String, Object> e : events) {
            pendingExternal(child).add(Definition.deepCopy(Json.obj(e, "event")));
        }
        pendingExternal(child).sort(Engine.EXTERNAL_ORDER);
        runTo(session, (String) child.get("id"), 0);

        boolean verifiedA = child.get("traceHash").equals(branch(session, branchA).get("traceHash"));
        boolean verifiedB = child.get("traceHash").equals(branch(session, branchB).get("traceHash"));
        child.put("createdFromMerge", true);
        child.put("mergeParents", List.of(branchA, branchB));
        child.put("mergeVerified", verifiedA || verifiedB);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plan", plan);
        result.put("branch", child);
        result.put("traceHash", child.get("traceHash"));
        result.put("verified", verifiedA || verifiedB);
        return result;
    }

    private String firstCheckpointOf(Map<String, Object> session, String branchId) {
        String found = null;
        for (Map.Entry<String, Object> en : checkpoints(session).entrySet()) {
            Map<String, Object> cp = Json.obj(en.getValue(), "cp");
            if (branchId.equals(cp.get("branch")) && found == null) {
                found = en.getKey();
            }
        }
        if (found == null) {
            throw new Json.JsonException("no checkpoint available to anchor merge for branch " + branchId);
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private String inferAncestor(Map<String, Object> session, Map<String, Object> a, Map<String, Object> b) {
        // 沿 parentCheckpoint 链收集两个分支各自可追溯的检查点，取步数最大的公共点。
        java.util.Set<String> fromA = new java.util.HashSet<>();
        collectAncestorChain(session, a, fromA);
        java.util.Set<String> fromB = new java.util.HashSet<>();
        collectAncestorChain(session, b, fromB);
        String best = null;
        long bestStep = -1;
        for (String id : fromA) {
            if (fromB.contains(id)) {
                long step = ((Number) Json.obj(checkpoints(session).get(id), "cp").get("step")).longValue();
                if (step > bestStep) {
                    bestStep = step;
                    best = id;
                }
            }
        }
        return best;
    }

    private void collectAncestorChain(Map<String, Object> session, Map<String, Object> b,
                                      java.util.Set<String> out) {
        Map<String, Object> cur = b;
        java.util.Set<Object> seen = new java.util.HashSet<>();
        while (cur != null && seen.add(cur)) {
            String cpId = (String) cur.get("parentCheckpoint");
            if (cpId == null) {
                return;
            }
            out.add(cpId);
            Object ocp = checkpoints(session).get(cpId);
            if (!(ocp instanceof Map)) {
                return;
            }
            Map<String, Object> cp = (Map<String, Object>) ocp;
            String parentBranch = (String) cp.get("branch");
            if (parentBranch == null) {
                return;
            }
            Object nxt = branches(session).get(parentBranch);
            cur = nxt instanceof Map ? (Map<String, Object>) nxt : null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> externalEventsSince(Map<String, Object> session,
                                                          Map<String, Object> branch, List<String> baseIds) {
        java.util.Set<String> base = new java.util.HashSet<>(baseIds);
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        @SuppressWarnings("unchecked")
        List<Object> branchLog = (List<Object>) branch.getOrDefault("externalLog", new ArrayList<>());
        for (Object eo : branchLog) {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) eo;
            String id = (String) e.get("id");
            if (!base.contains(id) && seen.add(id)) {
                out.add(e);
            }
        }
        for (Object idObj : externalIds(branch)) {
            String id = (String) idObj;
            if (base.contains(id) || !seen.add(id)) {
                continue;
            }
            Map<String, Object> e = findImported(session, id);
            if (e != null) {
                out.add(e);
            }
        }
        // 尚未处理但已在队列里的外部事件也参与兼容性判断
        for (Map<String, Object> e : pendingExternal(branch)) {
            String id = (String) e.get("id");
            if (base.contains(id) || !seen.add(id)) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private Map<String, Object> findImported(Map<String, Object> session, String id) {
        for (Map<String, Object> e : allImported(session)) {
            if (id.equals(e.get("id"))) {
                return e;
            }
        }
        return null;
    }

    private Map<String, Map<String, Object>> indexById(List<Map<String, Object>> events) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (Map<String, Object> e : events) {
            m.put((String) e.get("id"), e);
        }
        return m;
    }

    private List<String> idsOf(List<Map<String, Object>> events) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> e : events) {
            ids.add((String) e.get("id"));
        }
        return ids;
    }

    private Long firstTime(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        Long ta = a.isEmpty() ? null : Json.asLong(a.get(0).get("time"), 0L);
        Long tb = b.isEmpty() ? null : Json.asLong(b.get(0).get("time"), 0L);
        if (ta == null) {
            return tb;
        }
        if (tb == null) {
            return ta;
        }
        return Math.min(ta, tb);
    }

    /**
     * 顺序冲突检测：按 (time) 分组，同组内两边的事件 id 子序列必须一致；
     * 返回第一个不一致的时间组（含两侧排序后的事件）。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> orderConflictGroup(List<Map<String, Object>> aEvents,
                                                         List<Map<String, Object>> bEvents,
                                                         List<Map<String, Object>> union) {
        TreeMap<Long, List<String>> ga = groups(aEvents);
        TreeMap<Long, List<String>> gb = groups(bEvents);
        for (Long t : new TreeSetKeys(ga, gb)) {
            List<String> la = ga.getOrDefault(t, new ArrayList<>());
            List<String> lb = gb.getOrDefault(t, new ArrayList<>());
            if (!la.equals(lb)) {
                List<Map<String, Object>> group = new ArrayList<>();
                for (Map<String, Object> e : union) {
                    if (Json.asLong(e.get("time"), 0L) == t) {
                        group.add(e);
                    }
                }
                return group;
            }
        }
        return null;
    }

    private TreeMap<Long, List<String>> groups(List<Map<String, Object>> events) {
        TreeMap<Long, List<String>> m = new TreeMap<>();
        for (Map<String, Object> e : events) {
            long t = Json.asLong(e.get("time"), 0L);
            m.computeIfAbsent(t, k -> new ArrayList<>()).add((String) e.get("id"));
        }
        return m;
    }

    private static final class TreeSetKeys implements Iterable<Long> {
        private final TreeMap<Long, ?> a;
        private final TreeMap<Long, ?> b;

        TreeSetKeys(TreeMap<Long, ?> a, TreeMap<Long, ?> b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public java.util.Iterator<Long> iterator() {
            java.util.TreeSet<Long> keys = new java.util.TreeSet<>(a.keySet());
            keys.addAll(b.keySet());
            return keys.iterator();
        }
    }

    private List<Object> publicList(List<Map<String, Object>> events) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> e : events) {
            Map<String, Object> v = new LinkedHashMap<>();
            for (String k : new String[]{"id", "type", "time", "priority", "seq", "source"}) {
                if (e.containsKey(k)) {
                    v.put(k, e.get(k));
                }
            }
            out.add(v);
        }
        return out;
    }

    // ---------------- 差异对比 ----------------

    /** 状态差异：added / removed / changed，路径为点分键。 */
    public Map<String, Object> diffState(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> flatBefore = new LinkedHashMap<>();
        Map<String, Object> flatAfter = new LinkedHashMap<>();
        flatten("", before, flatBefore);
        flatten("", after, flatAfter);

        List<Object> added = new ArrayList<>();
        List<Object> removed = new ArrayList<>();
        List<Object> changed = new ArrayList<>();
        for (String k : flatAfter.keySet()) {
            if (!flatBefore.containsKey(k)) {
                added.add(diffEntry(k, null, flatAfter.get(k)));
            } else if (!Json.canonical(flatBefore.get(k)).equals(Json.canonical(flatAfter.get(k)))) {
                changed.add(diffEntry(k, flatBefore.get(k), flatAfter.get(k)));
            }
        }
        for (String k : flatBefore.keySet()) {
            if (!flatAfter.containsKey(k)) {
                removed.add(diffEntry(k, flatBefore.get(k), null));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("added", added);
        out.put("removed", removed);
        out.put("changed", changed);
        return out;
    }

    private Map<String, Object> diffEntry(String path, Object before, Object after) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("path", path);
        e.put("before", before);
        e.put("after", after);
        return e;
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Object v, Map<String, Object> out) {
        if (v instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                flatten(key, e.getValue(), out);
            }
        } else {
            out.put(prefix, v);
        }
    }

    /** 分支轨迹差异：对齐相同序号，列出状态差异与状态机当前状态。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> compareBranches(Map<String, Object> session, String a, String b) {
        Map<String, Object> ba = branch(session, a);
        Map<String, Object> bb = branch(session, b);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branchA", branchSummary(session, ba));
        out.put("branchB", branchSummary(session, bb));
        out.put("stateDiff", diffState(Json.obj(ba.get("state"), "state"), Json.obj(bb.get("state"), "state")));
        out.put("sameTraceHash", ba.get("traceHash").equals(bb.get("traceHash")));
        return out;
    }

    public Map<String, Object> branchSummary(Map<String, Object> session, Map<String, Object> b) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", b.get("id"));
        out.put("currentState", b.get("currentState"));
        out.put("stepCount", b.get("stepCount"));
        out.put("traceHash", b.get("traceHash"));
        out.put("state", b.get("state"));
        out.put("pendingExternalCount", pendingExternal(b).size());
        out.put("pendingInternalCount", pendingInternal(b).size());
        out.put("parentCheckpoint", b.get("parentCheckpoint"));
        return out;
    }


    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        return (List<Map<String, Object>>) (List<?>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> deepList(Object o) {
        List<Object> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object e : (List<Object>) o) {
                out.add(Definition.deepCopyValue(e));
            }
        }
        return out;
    }

    // ---------------- 导出 / 导入重放验证 ----------------

    public Map<String, Object> exportSession(Map<String, Object> session) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("format", "state-machine-replay-room/v1");
        bundle.put("exportedAtLogical", 0L);
        bundle.put("definition", session.get("definition"));
        bundle.put("definitionFingerprint", session.get("definitionFingerprint"));
        bundle.put("importLog", session.get("importLog"));
        bundle.put("branches", session.get("branches"));
        bundle.put("checkpoints", session.get("checkpoints"));
        bundle.put("nextBranch", session.get("nextBranch"));
        bundle.put("nextCheckpoint", session.get("nextCheckpoint"));
        bundle.put("externalSeq", session.get("externalSeq"));
        bundle.put("sessionFingerprint", sessionFingerprint(session));
        return bundle;
    }

    /** 会话指纹：定义 + 全部外部事件 + main 末端轨迹哈希。 */
    public String sessionFingerprint(Map<String, Object> session) {
        Map<String, Object> logs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : branches(session).entrySet()) {
            logs.put(e.getKey(), Json.obj(e.getValue(), "branch").get("externalLog"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definitionFingerprint", definitionFingerprint(session));
        body.put("externalLogs", logs);
        body.put("mainTraceHash", branch(session, "main").get("traceHash"));
        body.put("mainStepCount", branch(session, "main").get("stepCount"));
        return Hash.sha256Hex(Json.canonical(body));
    }

    /**
     * 导入会话：先重建 main，逐条重放已记录的外部事件，校验轨迹哈希。
     * 其他分支（含合并分支）以快照形式恢复，但会重新校验其检查点定义指纹。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> importSession(Map<String, Object> bundle, boolean verify) {
        if (!"state-machine-replay-room/v1".equals(bundle.get("format"))) {
            throw new Json.JsonException("unsupported export format");
        }
        Map<String, Object> defRaw = Json.obj(bundle.get("definition"), "definition");
        Map<String, Object> fresh = createSession(defRaw);
        if (!fresh.get("definitionFingerprint").equals(bundle.get("definitionFingerprint"))) {
            throw new Json.JsonException("definition fingerprint mismatch on import");
        }

        Map<String, Object> storedBranches = Json.obj(bundle.get("branches"), "branches");
        Map<String, Object> storedMain = Json.obj(storedBranches.get("main"), "branches.main");
        long mainSteps = Json.asLong(storedMain.get("stepCount"), 0L);

        // 重放 main：只重放 main 分支自己导入过的外部事件（导入到分叉的事件不参与 main）。
        @SuppressWarnings("unchecked")
        List<Object> mainExternalLog = (List<Object>)
                (storedMain.get("externalLog") instanceof List ? storedMain.get("externalLog") : new ArrayList<>());
        if (mainExternalLog != null && !mainExternalLog.isEmpty()) {
            importEvents(fresh, "main", new ArrayList<>(mainExternalLog));
        }
        runTo(fresh, "main", 0);

        if (verify && !storedMain.get("traceHash").equals(branch(fresh, "main").get("traceHash"))) {
            throw new Json.JsonException("main trace hash mismatch: replay is not deterministic");
        }
        if (verify && Json.asLong(branch(fresh, "main").get("stepCount"), -1L) != mainSteps) {
            throw new Json.JsonException("main step count mismatch on replay");
        }

        // 恢复其他分支/检查点（定义指纹逐一校验）
        fresh.put("nextBranch", bundle.getOrDefault("nextBranch", fresh.get("nextBranch")));
        fresh.put("nextCheckpoint", bundle.getOrDefault("nextCheckpoint", fresh.get("nextCheckpoint")));
        fresh.put("externalSeq", bundle.getOrDefault("externalSeq", fresh.get("externalSeq")));

        Map<String, Object> storedCps = Json.obj(bundle.get("checkpoints"), "checkpoints");
        for (Map.Entry<String, Object> e : storedCps.entrySet()) {
            Map<String, Object> cp = Definition.deepCopy(Json.obj(e.getValue(), "checkpoint"));
            if (!definitionFingerprint(fresh).equals(cp.get("definitionFingerprint"))) {
                throw new ConflictException("检查点定义指纹不匹配，拒绝导入", Map.of(
                        "checkpoint", e.getKey(),
                        "checkpointFingerprint", cp.get("definitionFingerprint"),
                        "currentFingerprint", definitionFingerprint(fresh)));
            }
            checkpoints(fresh).put(e.getKey(), cp);
        }
        for (Map.Entry<String, Object> e : storedBranches.entrySet()) {
            if ("main".equals(e.getKey())) {
                continue;
            }
            Map<String, Object> sb = Definition.deepCopy(Json.obj(e.getValue(), "branch"));
            branches(fresh).put(e.getKey(), sb);
        }

        if (verify) {
            String expected = Json.str(bundle.get("sessionFingerprint"));
            if (expected != null && !expected.equals(sessionFingerprint(fresh))) {
                throw new Json.JsonException("session fingerprint mismatch on import");
            }
        }
        return fresh;
    }
}
