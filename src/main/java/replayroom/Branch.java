package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个回放分支。记录当前状态、未处理事件（含内部事件队列）、轨迹、
 * 导入批次（外部事件在第几步之后进入队列），以及派生血缘用于寻找共同祖先。
 */
public final class Branch {
    public String id;
    public String name;
    public String parentBranchId;
    public int parentStepIndex = -1;

    public String state;
    public Map<String, Object> vars = new LinkedHashMap<>();
    public long rngState;
    public long birthCounter;

    public List<Event> pending = new ArrayList<>();
    public List<StepRecord> steps = new ArrayList<>();
    public List<ImportBatch> imports = new ArrayList<>();

    public static final class ImportBatch {
        public int afterStep;
        public List<Event> events = new ArrayList<>();

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("afterStep", afterStep);
            List<Object> es = new ArrayList<>();
            for (Event e : events) es.add(e.toJson());
            m.put("events", es);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static ImportBatch fromJson(Map<String, Object> m) {
            ImportBatch b = new ImportBatch();
            b.afterStep = ((Number) m.get("afterStep")).intValue();
            for (Object e : Json.list(m.get("events")))
                b.events.add(Event.fromJson(Json.obj(e)));
            return b;
        }
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("parentBranchId", parentBranchId);
        m.put("parentStepIndex", parentStepIndex);
        m.put("state", state);
        m.put("vars", vars);
        m.put("rngState", rngState);
        m.put("birthCounter", birthCounter);
        List<Object> pend = new ArrayList<>();
        for (Event e : pending) pend.add(e.toJson());
        m.put("pending", pend);
        List<Object> st = new ArrayList<>();
        for (StepRecord s : steps) st.add(s.toJson());
        m.put("steps", st);
        List<Object> im = new ArrayList<>();
        for (ImportBatch b : imports) im.add(b.toJson());
        m.put("imports", im);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Branch fromJson(Map<String, Object> m) {
        Branch b = new Branch();
        b.id = (String) m.get("id");
        b.name = (String) m.get("name");
        b.parentBranchId = (String) m.get("parentBranchId");
        b.parentStepIndex = ((Number) m.getOrDefault("parentStepIndex", -1)).intValue();
        b.state = (String) m.get("state");
        b.vars = new LinkedHashMap<>(Json.obj(m.get("vars")));
        b.rngState = ((Number) m.getOrDefault("rngState", 0)).longValue();
        b.birthCounter = ((Number) m.getOrDefault("birthCounter", 0)).longValue();
        for (Object e : Json.list(m.get("pending")))
            b.pending.add(Event.fromJson(Json.obj(e)));
        for (Object s : Json.list(m.get("steps")))
            b.steps.add(StepRecord.fromJson(Json.obj(s)));
        for (Object x : Json.list(m.get("imports")))
            b.imports.add(ImportBatch.fromJson(Json.obj(x)));
        return b;
    }
}
