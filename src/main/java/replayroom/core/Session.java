package replayroom.core;

import com.fasterxml.jackson.databind.JsonNode;
import replayroom.util.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 回放会话：锁定 (定义, 初始状态, 种子)，管理检查点、分支与合并。 */
public class Session {
    public String id;
    public String name;
    public String kind = "root";
    public String parentId;
    public String forkCheckpointId;
    public Definition def;
    public Engine engine;
    public List<Checkpoint> checkpoints = new ArrayList<>();
    public JsonNode ancestorSnapshot;
    public List<String> mergeLog = new ArrayList<>();
    public String fingerprint;
    public int checkpointSeq;

    public static Session create(String id, String name, Definition def, long seed) {
        Session s = new Session();
        s.id = id;
        s.name = name;
        s.def = def;
        s.engine = Engine.fresh(def, seed);
        s.fingerprint = Json.sha256(Json.canonicalString(def.raw) + "|" + seed);
        return s;
    }

    public void reattach() {
        engine.def = def;
    }

    public Checkpoint checkpoint(String name) {
        Checkpoint cp = new Checkpoint();
        cp.id = "cp-" + (++checkpointSeq);
        cp.name = name == null || name.isBlank() ? cp.id : name;
        cp.definitionFingerprint = def.fingerprint;
        cp.snapshot = engine.snapshot();
        checkpoints.add(cp);
        return cp;
    }

    public Checkpoint findCheckpoint(String checkpointId) {
        for (Checkpoint cp : checkpoints) {
            if (cp.id.equals(checkpointId)) {
                return cp;
            }
        }
        throw new IllegalArgumentException("检查点不存在: " + checkpointId);
    }

    public void restore(String checkpointId) {
        Checkpoint cp = findCheckpoint(checkpointId);
        if (!cp.definitionFingerprint.equals(def.fingerprint)) {
            throw new VersionMismatch("检查点 " + cp.id + " 创建于定义 " + cp.definitionFingerprint
                    + "，当前定义为 " + def.fingerprint + "，拒绝跨定义版本恢复");
        }
        engine.restore(cp.snapshot.deepCopy());
    }

    /** 替换定义会重置引擎到初始状态；旧检查点因指纹不一致而无法再恢复。 */
    public void replaceDefinition(Definition newDef) {
        long seed = engine.seed;
        this.def = newDef;
        this.engine = Engine.fresh(newDef, seed);
        this.fingerprint = Json.sha256(Json.canonicalString(newDef.raw) + "|" + seed);
    }

    public Session fork(String newId, String checkpointId, String name) {
        Checkpoint cp = findCheckpoint(checkpointId);
        if (!cp.definitionFingerprint.equals(def.fingerprint)) {
            throw new VersionMismatch("检查点 " + cp.id + " 的定义指纹与当前定义不一致，拒绝分叉");
        }
        Session branch = Session.create(newId, name, def, engine.seed);
        branch.kind = "branch";
        branch.parentId = this.id;
        branch.forkCheckpointId = cp.id;
        branch.engine.restore(cp.snapshot.deepCopy());
        branch.ancestorSnapshot = cp.snapshot.deepCopy();
        return branch;
    }

    /** 合并分支：仅当双方自共同祖先起的外部事件集合兼容且顺序无歧义时允许。
     *  成功时回到祖先快照，按确定性顺序重放合并后的事件集合，而不是覆盖最终状态。 */
    public MergeResult merge(Session branch) {
        if (!this.id.equals(branch.parentId)) {
            throw new IllegalArgumentException("分支 " + branch.id + " 不是当前会话的直接分支");
        }
        if (branch.ancestorSnapshot == null) {
            throw new IllegalArgumentException("分支缺少共同祖先快照");
        }
        if (!branch.def.fingerprint.equals(this.def.fingerprint)) {
            throw new VersionMismatch("分支定义指纹与当前会话不一致，拒绝合并");
        }

        Engine anc = Engine.fresh(def, engine.seed);
        anc.restore(branch.ancestorSnapshot.deepCopy());
        List<EventInstance> ancApplied = anc.appliedExternal;
        Set<String> ancIds = new HashSet<>();
        for (EventInstance e : ancApplied) {
            ancIds.add(e.id);
        }

        List<EventInstance> aSide = minusById(engine.appliedExternal, ancIds);
        List<EventInstance> bSide = minusById(branch.engine.appliedExternal, ancIds);

        Map<String, EventInstance> aById = byId(aSide);
        Map<String, EventInstance> bById = byId(bSide);
        List<MergeConflict.Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, EventInstance> e : aById.entrySet()) {
            EventInstance other = bById.get(e.getKey());
            if (other != null && !Json.canonicalString(Json.M.valueToTree(e.getValue()))
                    .equals(Json.canonicalString(Json.M.valueToTree(other)))) {
                conflicts.add(new MergeConflict.Conflict("同一事件 id 在两分支内容不一致", e.getValue(), other));
            }
        }
        Map<String, EventInstance> aPositions = new HashMap<>();
        for (EventInstance e : aSide) {
            aPositions.put(e.positionKey(), e);
        }
        for (EventInstance eb : bSide) {
            EventInstance ea = aPositions.get(eb.positionKey());
            if (ea != null && !ea.id.equals(eb.id)) {
                conflicts.add(new MergeConflict.Conflict(
                        "同一逻辑位置 (时间+来源+序号) 存在两个不同事件，顺序有歧义", ea, eb));
            }
        }
        if (!conflicts.isEmpty()) {
            Comparator<EventInstance> order = engine.order();
            conflicts.sort((c1, c2) -> {
                EventInstance e1 = order.compare(c1.a, c1.b) <= 0 ? c1.a : c1.b;
                EventInstance e2 = order.compare(c2.a, c2.b) <= 0 ? c2.a : c2.b;
                int cmp = order.compare(e1, e2);
                return cmp != 0 ? cmp : c1.reason.compareTo(c2.reason);
            });
            throw new MergeConflict(conflicts.get(0), conflicts.size());
        }

        Map<String, EventInstance> union = new LinkedHashMap<>();
        for (EventInstance e : ancApplied) {
            union.put(e.id, e);
        }
        for (EventInstance e : aSide) {
            union.put(e.id, e);
        }
        for (EventInstance e : bSide) {
            union.putIfAbsent(e.id, e);
        }
        List<EventInstance> merged = new ArrayList<>(union.values());

        engine.restore(branch.ancestorSnapshot.deepCopy());
        engine.loadMerged(merged, ancApplied);
        engine.runAll();
        mergeLog.add("合并分支 " + branch.id + " (" + branch.name + "): 共同祖先后外部事件 "
                + aSide.size() + " + " + bSide.size() + " 条，重放完成，轨迹哈希 " + engine.traceHash());
        MergeResult result = new MergeResult();
        result.mergedEvents = merged.size() - ancApplied.size();
        result.traceHash = engine.traceHash();
        result.state = engine.state;
        result.vars = new LinkedHashMap<>(engine.vars);
        return result;
    }

    private static List<EventInstance> minusById(List<EventInstance> events, Set<String> baseIds) {
        List<EventInstance> out = new ArrayList<>();
        for (EventInstance e : events) {
            if (!baseIds.contains(e.id)) {
                out.add(e);
            }
        }
        return out;
    }

    private static Map<String, EventInstance> byId(List<EventInstance> events) {
        Map<String, EventInstance> map = new HashMap<>();
        for (EventInstance e : events) {
            map.put(e.id, e);
        }
        return map;
    }

    public static class MergeResult {
        public int mergedEvents;
        public String traceHash;
        public String state;
        public Map<String, Object> vars;
    }
}
