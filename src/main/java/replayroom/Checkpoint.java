package replayroom;

import java.util.LinkedHashMap;
import java.util.Map;

/** 检查点：冻结某分支某步的完整状态，并锁定定义指纹与轨迹哈希。 */
public final class Checkpoint {
    public String id;
    public String label;
    public String defFp;
    public String configFp;
    public String branchId;
    public int stepIndex;
    public String stepHash;
    public String traceHash;
    public long createdAt;

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("defFp", defFp);
        m.put("configFp", configFp);
        m.put("branchId", branchId);
        m.put("stepIndex", stepIndex);
        m.put("stepHash", stepHash);
        m.put("traceHash", traceHash);
        m.put("createdAt", createdAt);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Checkpoint fromJson(Map<String, Object> m) {
        Checkpoint c = new Checkpoint();
        c.id = (String) m.get("id");
        c.label = (String) m.get("label");
        c.defFp = (String) m.get("defFp");
        c.configFp = (String) m.get("configFp");
        c.branchId = (String) m.get("branchId");
        c.stepIndex = ((Number) m.get("stepIndex")).intValue();
        c.stepHash = (String) m.get("stepHash");
        c.traceHash = (String) m.get("traceHash");
        c.createdAt = ((Number) m.getOrDefault("createdAt", 0)).longValue();
        return c;
    }
}
