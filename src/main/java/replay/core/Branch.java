package replay.core;

import replay.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 回放分支：独立的运行状态、轨迹、检查点与待处理事件队列。 */
public final class Branch {
    String name;
    String state;
    Map<String, Object> vars = new LinkedHashMap<>();
    List<Event> pending = new ArrayList<>();
    List<Event> internalQueue = new ArrayList<>();
    Rng rng;
    String rollingHash = Hashes.GENESIS;
    boolean paused;
    final List<TraceEntry> entries = new ArrayList<>();
    final List<Checkpoint> checkpoints = new ArrayList<>();

    boolean finished() {
        return pending.isEmpty() && internalQueue.isEmpty();
    }

    String traceHash() {
        return rollingHash;
    }

    List<String> allOutputs() {
        List<String> out = new ArrayList<>();
        for (TraceEntry e : entries) out.addAll(e.outputs);
        return out;
    }

    Checkpoint snapshot(String checkpointName, String defFingerprint) {
        Checkpoint c = new Checkpoint();
        c.name = checkpointName;
        c.step = entries.size();
        c.defFingerprint = defFingerprint;
        c.state = state;
        c.vars = new LinkedHashMap<>(vars);
        c.pending = new ArrayList<>(pending);
        c.internalQueue = new ArrayList<>(internalQueue);
        c.rngState = rng.state();
        c.rollingHash = rollingHash;
        return c;
    }

    Map<String, Object> toJson(boolean withTrace) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("state", state);
        m.put("vars", vars);
        List<Object> p = new ArrayList<>();
        for (Event e : pending) p.add(e.toJson());
        m.put("pending", p);
        List<Object> q = new ArrayList<>();
        for (Event e : internalQueue) q.add(e.toJson());
        m.put("internalQueue", q);
        m.put("rngState", Long.toUnsignedString(rng.state()));
        m.put("rollingHash", rollingHash);
        m.put("paused", paused);
        m.put("finished", finished());
        m.put("steps", (long) entries.size());
        List<Object> cps = new ArrayList<>();
        for (Checkpoint c : checkpoints) cps.add(c.toJson());
        m.put("checkpoints", cps);
        if (withTrace) {
            List<Object> es = new ArrayList<>();
            for (TraceEntry e : entries) es.add(e.toJson());
            m.put("trace", es);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    static Branch fromJson(Map<String, Object> m) {
        Branch b = new Branch();
        b.name = Json.asString(m.get("name"), "branch.name");
        b.state = Json.asString(m.get("state"), "branch.state");
        b.vars = new LinkedHashMap<>((Map<String, Object>) Json.asMap(m.get("vars"), "branch.vars"));
        for (Object e : Json.asList(m.get("pending"), "branch.pending")) {
            b.pending.add(Event.fromJson(Json.asMap(e, "event")));
        }
        if (m.containsKey("internalQueue")) {
            for (Object e : Json.asList(m.get("internalQueue"), "branch.internalQueue")) {
                b.internalQueue.add(Event.fromJson(Json.asMap(e, "event")));
            }
        }
        b.rng = new Rng(Long.parseUnsignedLong(Json.asString(m.get("rngState"), "branch.rngState")));
        b.rollingHash = Json.asString(m.get("rollingHash"), "branch.rollingHash");
        b.paused = Boolean.TRUE.equals(m.get("paused"));
        if (m.containsKey("trace")) {
            for (Object e : Json.asList(m.get("trace"), "branch.trace")) {
                b.entries.add(TraceEntry.fromJson(Json.asMap(e, "entry")));
            }
        }
        if (m.containsKey("checkpoints")) {
            for (Object c : Json.asList(m.get("checkpoints"), "branch.checkpoints")) {
                b.checkpoints.add(Checkpoint.fromJson(Json.asMap(c, "checkpoint")));
            }
        }
        return b;
    }
}
