package replayroom.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import replayroom.util.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 确定性回放引擎。状态只由 (定义, 种子, 事件序列) 决定。 */
public class Engine {
    private static final int MAX_STEPS = 1_000_000;

    @JsonIgnore
    public Definition def;
    public long seed;
    public String state;
    public Map<String, Object> vars = new LinkedHashMap<>();
    public List<EventInstance> pending = new ArrayList<>();
    public List<TraceEntry> trace = new ArrayList<>();
    public List<EventInstance> appliedExternal = new ArrayList<>();
    public List<EventInstance> importedExternal = new ArrayList<>();
    public long internalSeq;
    public long enqueueCounter;
    public long rngState;

    public static Engine fresh(Definition def, long seed) {
        Engine engine = new Engine();
        engine.def = def;
        engine.seed = seed;
        engine.state = def.initialState;
        engine.vars = new LinkedHashMap<>(def.initialVariables);
        engine.rngState = seed;
        return engine;
    }

    public Comparator<EventInstance> order() {
        return Comparator.comparingLong((EventInstance e) -> e.time)
                .thenComparingInt(e -> def.sourcePriority(e.source))
                .thenComparingLong(e -> e.seq)
                .thenComparingLong(e -> e.enqueueOrder)
                .thenComparing(e -> e.id);
    }

    private void insertSorted(EventInstance e) {
        Comparator<EventInstance> cmp = order();
        int lo = 0;
        int hi = pending.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cmp.compare(pending.get(mid), e) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        pending.add(lo, e);
    }

    public void importEvents(List<EventInstance> events) {
        for (EventInstance e : events) {
            if (e.id == null || e.id.isBlank()) {
                e.id = "ext-" + (importedExternal.size() + 1);
            }
            if (e.source == null || e.source.isBlank()) {
                e.source = "external";
            }
            e.internal = false;
            e.enqueueOrder = enqueueCounter++;
            importedExternal.add(e);
            insertSorted(e);
        }
    }

    /** 合并后整体替换外部事件集合并重新排队未处理部分。 */
    public void loadMerged(List<EventInstance> union, List<EventInstance> alreadyApplied) {
        pending.clear();
        importedExternal = new ArrayList<>(union);
        java.util.Set<String> appliedIds = new java.util.HashSet<>();
        for (EventInstance e : alreadyApplied) {
            appliedIds.add(e.id);
        }
        List<EventInstance> rest = new ArrayList<>();
        for (EventInstance e : union) {
            if (!appliedIds.contains(e.id)) {
                rest.add(e);
            }
            if (e.enqueueOrder >= enqueueCounter) {
                enqueueCounter = e.enqueueOrder + 1;
            }
        }
        rest.sort(order());
        pending.addAll(rest);
    }

    public TraceEntry step() {
        if (pending.isEmpty()) {
            return null;
        }
        EventInstance ev = pending.remove(0);
        String beforeState = state;
        Map<String, Object> beforeVars = new LinkedHashMap<>(vars);
        long beforeRng = rngState;

        TraceEntry entry = new TraceEntry();
        entry.index = trace.size();
        entry.eventId = ev.id;
        entry.event = ev.event;
        entry.time = ev.time;
        entry.source = ev.source;
        entry.seq = ev.seq;
        entry.internal = ev.internal;
        entry.beforeState = beforeState;
        entry.beforeVars = beforeVars;

        int ruleIndex = -1;
        for (int i = 0; i < def.rules.size(); i++) {
            Rule r = def.rules.get(i);
            if (!r.event.equals(ev.event)) {
                continue;
            }
            if (r.from != null && !r.from.equals("*") && !r.from.equals(state)) {
                continue;
            }
            if (r.condition != null && !Exprs.condition(r.condition, vars)) {
                continue;
            }
            ruleIndex = i;
            break;
        }
        entry.ruleIndex = ruleIndex;

        if (ruleIndex < 0) {
            entry.result = "ignored";
        } else {
            Rule rule = def.rules.get(ruleIndex);
            List<EventInstance> emittedEvents = new ArrayList<>();
            try {
                for (Action action : rule.actions) {
                    apply(action, ev, emittedEvents, entry);
                }
                if (rule.to != null) {
                    state = rule.to;
                }
                entry.result = "applied";
            } catch (ActionFailure failure) {
                // 回滚：状态、变量、随机序列与派生内部事件一并撤销，失败记录仍入轨迹。
                state = beforeState;
                vars = new LinkedHashMap<>(beforeVars);
                rngState = beforeRng;
                pending.removeAll(emittedEvents);
                entry.result = "failed";
                entry.failure = failure.getMessage();
            }
        }
        entry.afterState = state;
        entry.afterVars = new LinkedHashMap<>(vars);
        trace.add(entry);
        if (!ev.internal) {
            appliedExternal.add(ev);
        }
        return entry;
    }

    private void apply(Action action, EventInstance ev, List<EventInstance> emitted, TraceEntry entry) {
        switch (action.type) {
            case "set":
                vars.put(action.var, Exprs.expr(action.expr, vars, this::nextRandom));
                break;
            case "emit": {
                EventInstance ie = new EventInstance();
                ie.id = "int-" + (++internalSeq);
                ie.time = ev.time;
                ie.source = "@internal";
                ie.seq = internalSeq;
                ie.event = action.event;
                ie.data = action.data != null ? new LinkedHashMap<>(action.data) : new LinkedHashMap<>();
                ie.internal = true;
                ie.enqueueOrder = enqueueCounter++;
                insertSorted(ie);
                emitted.add(ie);
                entry.emitted.add(ie.event + "(" + ie.id + ")");
                break;
            }
            case "assert":
                if (!Exprs.condition(action.condition, vars)) {
                    throw new ActionFailure("断言失败: " + action.condition);
                }
                break;
            default:
                throw new ActionFailure("未知动作类型: " + action.type);
        }
    }

    /** splitmix64：确定性伪随机序列，只取决于种子与调用次数。 */
    private long nextRandom() {
        long z = (rngState += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public List<TraceEntry> runLimit(int limit, Long untilTime) {
        List<TraceEntry> out = new ArrayList<>();
        while (out.size() < limit) {
            if (pending.isEmpty()) {
                break;
            }
            if (untilTime != null && pending.get(0).time > untilTime) {
                break;
            }
            if (trace.size() >= MAX_STEPS) {
                throw new IllegalStateException("超过最大步数限制，可能存在内部事件自循环");
            }
            out.add(step());
        }
        return out;
    }

    public List<TraceEntry> runAll() {
        return runLimit(Integer.MAX_VALUE, null);
    }

    public ObjectNode snapshot() {
        ObjectNode n = Json.M.createObjectNode();
        n.put("state", state);
        n.set("vars", Json.M.valueToTree(vars));
        n.set("pending", Json.M.valueToTree(pending));
        n.set("trace", Json.M.valueToTree(trace));
        n.set("appliedExternal", Json.M.valueToTree(appliedExternal));
        n.set("importedExternal", Json.M.valueToTree(importedExternal));
        n.put("internalSeq", internalSeq);
        n.put("enqueueCounter", enqueueCounter);
        n.put("rngState", rngState);
        return n;
    }

    public void restore(JsonNode n) {
        state = n.get("state").asText();
        vars = Json.M.convertValue(n.get("vars"), new TypeReference<LinkedHashMap<String, Object>>() {
        });
        pending = new ArrayList<>(Json.M.convertValue(n.get("pending"),
                new TypeReference<List<EventInstance>>() {
                }));
        trace = new ArrayList<>(Json.M.convertValue(n.get("trace"),
                new TypeReference<List<TraceEntry>>() {
                }));
        appliedExternal = new ArrayList<>(Json.M.convertValue(n.get("appliedExternal"),
                new TypeReference<List<EventInstance>>() {
                }));
        importedExternal = new ArrayList<>(Json.M.convertValue(n.get("importedExternal"),
                new TypeReference<List<EventInstance>>() {
                }));
        internalSeq = n.get("internalSeq").asLong();
        enqueueCounter = n.get("enqueueCounter").asLong();
        rngState = n.get("rngState").asLong();
    }

    public String traceHash() {
        return Json.sha256(Json.canonicalString(Json.M.valueToTree(trace)));
    }
}
