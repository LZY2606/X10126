package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次回放会话：锁定机器定义版本、初始状态、随机种子；持有所有分支与检查点。 */
public final class Session {
    public String id;
    public String name;
    public long createdAt;
    public MachineDef machine;
    public long seed;
    public String defFp;
    public String configFp;
    public Map<String, Branch> branches = new LinkedHashMap<>();
    public List<Checkpoint> checkpoints = new ArrayList<>();
    public long idCounter;

    public String rootBranchId() {
        return branches.keySet().iterator().next();
    }

    public Map<String, Object> exportJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "replay-room-session");
        m.put("formatVersion", 1);
        m.put("id", id);
        m.put("name", name);
        m.put("createdAt", createdAt);
        m.put("machine", machine.canonical());
        m.put("seed", seed);
        m.put("defFp", defFp);
        m.put("configFp", configFp);
        m.put("idCounter", idCounter);
        List<Object> bs = new ArrayList<>();
        for (Branch b : branches.values()) bs.add(b.toJson());
        m.put("branches", bs);
        List<Object> cs = new ArrayList<>();
        for (Checkpoint c : checkpoints) cs.add(c.toJson());
        m.put("checkpoints", cs);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Session fromJson(Map<String, Object> m) {
        Session s = new Session();
        s.id = (String) m.get("id");
        s.name = (String) m.get("name");
        s.createdAt = ((Number) m.getOrDefault("createdAt", 0)).longValue();
        s.machine = MachineDef.fromJson(Json.obj(m.get("machine")));
        s.seed = ((Number) m.getOrDefault("seed", 0)).longValue();
        s.defFp = (String) m.get("defFp");
        s.configFp = (String) m.get("configFp");
        s.idCounter = ((Number) m.getOrDefault("idCounter", 0)).longValue();
        for (Object b : Json.list(m.get("branches"))) {
            Branch br = Branch.fromJson(Json.obj(b));
            s.branches.put(br.id, br);
        }
        for (Object c : Json.list(m.get("checkpoints")))
            s.checkpoints.add(Checkpoint.fromJson(Json.obj(c)));
        return s;
    }
}
