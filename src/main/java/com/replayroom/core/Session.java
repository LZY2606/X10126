package com.replayroom.core;

import com.replayroom.json.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deterministic replay session. Holds the machine definition, the external event
 * log, the live machine state, the pending internal-event queue and the full trace.
 * Every replay of the same (definition, seed, initial state, events) yields the
 * same chained trace hash.
 */
public final class Session {
    public String id;
    public String name;
    public MachineDefinition def;
    public long seed;
    public DeterministicRandom rng;
    public final List<Event> log = new ArrayList<>();
    public int cursor;
    public String state;
    public LinkedHashMap<String, Object> vars = new LinkedHashMap<>();
    public final Deque<Event> pending = new ArrayDeque<>();
    public final List<TraceEntry> trace = new ArrayList<>();
    public final List<Checkpoint> checkpoints = new ArrayList<>();
    public final List<Event> appliedExternal = new ArrayList<>();
    public String baseSessionId;
    public String baseCheckpointId;
    public String baseTraceHash;
    public long internalSeq;
    public String baseHash;
    public String traceHash;
    public long createdAt;
    public long updatedAt;

    private Session() {}

    public static Session create(String id, String name, MachineDefinition def, long seed, Clock clock) {
        Session s = new Session();
        s.id = id;
        s.name = name;
        s.def = def;
        s.seed = seed;
        s.rng = new DeterministicRandom(seed);
        s.state = def.initialState;
        s.vars.putAll(def.initialVars);
        s.baseHash = computeBaseHash(def, seed);
        s.traceHash = s.baseHash;
        s.createdAt = s.updatedAt = clock.nowMillis();
        return s;
    }

    /** Locks definition fingerprint, seed and initial state together. */
    public static String computeBaseHash(MachineDefinition def, long seed) {
        Map<String, Object> init = new LinkedHashMap<>();
        init.put("state", def.initialState);
        init.put("vars", def.initialVars);
        return MachineDefinition.sha256(
                "def:" + def.fingerprint + "|seed:" + seed + "|init:" + Json.canonical(init));
    }

    /** Stable fingerprint: base hash plus the full external event log content. */
    public String fingerprint() {
        List<Object> events = new ArrayList<>();
        for (Event e : log) events.add(e.toMap());
        return MachineDefinition.sha256(baseHash + "|events:" + Json.canonical(events));
    }

    public Comparator<Event> eventOrder() {
        return Comparator.comparingLong((Event e) -> e.time)
                .thenComparingLong(e -> def.sourcePriority(e.source))
                .thenComparingLong(e -> e.seq)
                .thenComparing(e -> e.id);
    }

    // ---------------- event log ----------------

    public synchronized void importEvents(List<Event> events) {
        List<Event> fresh = new ArrayList<>();
        for (Event e : events) {
            boolean dup = log.stream().anyMatch(x -> x.id.equals(e.id));
            if (dup) continue;
            if (cursor > 0) {
                Event lastConsumed = log.get(cursor - 1);
                if (eventOrder().compare(e, lastConsumed) <= 0) {
                    throw new IllegalArgumentException("event " + e.id
                            + " orders at or before already-replayed event " + lastConsumed.id
                            + " (time=" + e.time + ", seq=" + e.seq + ")");
                }
            }
            fresh.add(e);
        }
        log.addAll(fresh);
        log.sort(eventOrder());
    }

    // ---------------- replay ----------------

    public boolean hasNext() {
        return !pending.isEmpty() || cursor < log.size();
    }

    /** Replay exactly one event (internal queue first, then the external log). */
    public synchronized TraceEntry step() {
        Event ev;
        if (!pending.isEmpty()) {
            ev = pending.poll();
        } else if (cursor < log.size()) {
            ev = log.get(cursor++);
            appliedExternal.add(ev);
        } else {
            return null;
        }
        TraceEntry entry = process(ev);
        trace.add(entry);
        traceHash = MachineDefinition.sha256(traceHash + "|" + Json.canonical(entry.toMap()));
        return entry;
    }

    /** Replay up to max events; returns the produced trace entries. */
    public List<TraceEntry> run(int max) {
        List<TraceEntry> out = new ArrayList<>();
        while (out.size() < max) {
            TraceEntry e = step();
            if (e == null) break;
            out.add(e);
        }
        return out;
    }

    private TraceEntry process(Event ev) {
        TraceEntry entry = new TraceEntry(trace.size(), ev, state, new LinkedHashMap<>(vars));
        MachineDefinition.Rule rule = findRule(ev);
        if (rule == null) {
            entry.status = TraceEntry.Status.UNHANDLED;
            entry.finish(state, new LinkedHashMap<>(vars));
            return entry;
        }
        if (rule.condition != null) {
            boolean ok;
            try {
                ok = Expr.bool(Expr.evaluate(rule.condition, ctx(ev)), "condition");
            } catch (EvalException ex) {
                entry.status = TraceEntry.Status.FAILED;
                entry.error = "condition error: " + ex.getMessage();
                entry.finish(state, new LinkedHashMap<>(vars));
                return entry;
            }
            if (!ok) {
                entry.status = TraceEntry.Status.SKIPPED;
                entry.finish(state, new LinkedHashMap<>(vars));
                return entry;
            }
        }
        long rngMark = rng.state();
        int pendingMark = pending.size();
        try {
            for (Map<String, Object> action : rule.actions) {
                applyAction(action, ev, entry);
            }
            entry.status = TraceEntry.Status.OK;
        } catch (ActionFailure | EvalException ex) {
            // roll back state, variables, rng and any internal events derived by this rule
            state = entry.beforeState;
            vars = new LinkedHashMap<>(entry.beforeVars);
            rng.restore(rngMark);
            while (pending.size() > pendingMark) pending.pollLast();
            entry.status = TraceEntry.Status.FAILED;
            entry.error = ex.getMessage();
        }
        entry.finish(state, new LinkedHashMap<>(vars));
        return entry;
    }

    private MachineDefinition.Rule findRule(Event ev) {
        for (MachineDefinition.Rule r : def.rules) {
            if (!r.on.equals(ev.type)) continue;
            if (r.from != null && !r.from.equals(state)) continue;
            return r;
        }
        return null;
    }

    private EvalContext ctx(Event ev) {
        return new EvalContext(vars, state, ev.payload, ev.time, rng);
    }

    @SuppressWarnings("unchecked")
    private void applyAction(Map<String, Object> action, Event ev, TraceEntry entry) throws ActionFailure {
        EvalContext ctx = ctx(ev);
        if (action.containsKey("fail")) {
            throw new ActionFailure(String.valueOf(action.get("fail")));
        }
        if (action.containsKey("set")) {
            String name = String.valueOf(action.get("set"));
            Object value = Expr.evaluate(String.valueOf(action.get("to")), ctx);
            vars.put(name, value);
            entry.outputs.add("set " + name + " = " + Expr.display(value));
            return;
        }
        if (action.containsKey("goto")) {
            String target = String.valueOf(action.get("goto"));
            if (!def.states.contains(target)) {
                throw new ActionFailure("goto target is not a known state: " + target);
            }
            state = target;
            entry.outputs.add("goto " + target);
            return;
        }
        if (action.containsKey("emit")) {
            String type = String.valueOf(action.get("emit"));
            Map<String, Object> payload = new LinkedHashMap<>();
            Object p = action.get("payload");
            if (p instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) p).entrySet()) {
                    Object v = e.getValue();
                    payload.put(e.getKey(), v instanceof String ? Expr.evaluate((String) v, ctx) : v);
                }
            }
            long seq = internalSeq++;
            Event internal = new Event("i" + seq, ev.time, "internal", seq, type, payload, true);
            pending.addLast(internal);
            entry.outputs.add("emit " + type + " -> " + internal.id);
            return;
        }
        if (action.containsKey("out")) {
            Object v = Expr.evaluate(String.valueOf(action.get("out")), ctx);
            entry.outputs.add(Expr.display(v));
            return;
        }
        throw new ActionFailure("unknown action: " + Json.canonical(action));
    }

    // ---------------- checkpoints & branches ----------------

    public synchronized Checkpoint checkpoint(String id, String label) {
        Checkpoint cp = new Checkpoint(id, label, def.fingerprint, cursor, state,
                new LinkedHashMap<>(vars), new ArrayList<>(pending), trace.size(),
                appliedExternal.size(), rng.state(), internalSeq, traceHash);
        checkpoints.add(cp);
        return cp;
    }

    public Checkpoint findCheckpoint(String checkpointId) {
        for (Checkpoint cp : checkpoints) {
            if (cp.id.equals(checkpointId)) return cp;
        }
        throw new IllegalArgumentException("unknown checkpoint: " + checkpointId);
    }

    private void verifyCheckpoint(Checkpoint cp) {
        if (!cp.defFingerprint.equals(def.fingerprint)) {
            throw new IllegalStateException("checkpoint " + cp.id + " was taken under definition "
                    + cp.defFingerprint.substring(0, 12) + "… but this session runs "
                    + def.fingerprint.substring(0, 12) + "…; refusing to attach");
        }
    }

    /** Rewind this session to a checkpoint taken under the same definition. */
    public synchronized void restore(String checkpointId) {
        Checkpoint cp = findCheckpoint(checkpointId);
        verifyCheckpoint(cp);
        applySnapshot(cp);
    }

    private void applySnapshot(Checkpoint cp) {
        cursor = cp.cursor;
        state = cp.state;
        vars = new LinkedHashMap<>(cp.vars);
        pending.clear();
        pending.addAll(cp.pending);
        trace.subList(cp.traceLength, trace.size()).clear();
        appliedExternal.subList(cp.appliedLength, appliedExternal.size()).clear();
        rng.restore(cp.rngState);
        internalSeq = cp.internalSeq;
        traceHash = cp.traceHash;
    }

    /** Fork a new branch session from a checkpoint. */
    public synchronized Session fork(String newId, String checkpointId, String name, Clock clock) {
        Checkpoint cp = findCheckpoint(checkpointId);
        verifyCheckpoint(cp);
        Session s = new Session();
        s.id = newId;
        s.name = name;
        s.def = def;
        s.seed = seed;
        s.rng = new DeterministicRandom(seed);
        s.log.addAll(log);
        s.checkpoints.addAll(checkpoints);
        s.trace.addAll(trace);
        s.appliedExternal.addAll(appliedExternal);
        s.cursor = cursor;
        s.state = state;
        s.vars = new LinkedHashMap<>(vars);
        s.pending.addAll(pending);
        s.internalSeq = internalSeq;
        s.baseHash = baseHash;
        s.traceHash = traceHash;
        s.baseSessionId = id;
        s.baseCheckpointId = cp.id;
        s.baseTraceHash = cp.traceHash;
        s.createdAt = s.updatedAt = clock.nowMillis();
        s.applySnapshot(cp);
        return s;
    }

    // ---------------- serialization ----------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("definition", def.raw);
        m.put("seed", seed);
        m.put("cursor", cursor);
        m.put("state", state);
        m.put("vars", vars);
        List<Object> logList = new ArrayList<>();
        for (Event e : log) logList.add(e.toMap());
        m.put("log", logList);
        List<Object> pendingList = new ArrayList<>();
        for (Event e : pending) pendingList.add(e.toMap());
        m.put("pending", pendingList);
        List<Object> traceList = new ArrayList<>();
        for (TraceEntry t : trace) traceList.add(t.toMap());
        m.put("trace", traceList);
        List<Object> cpList = new ArrayList<>();
        for (Checkpoint c : checkpoints) cpList.add(c.toMap());
        m.put("checkpoints", cpList);
        List<Object> appliedList = new ArrayList<>();
        for (Event e : appliedExternal) appliedList.add(e.toMap());
        m.put("appliedExternal", appliedList);
        if (baseSessionId != null) m.put("baseSessionId", baseSessionId);
        if (baseCheckpointId != null) m.put("baseCheckpointId", baseCheckpointId);
        if (baseTraceHash != null) m.put("baseTraceHash", baseTraceHash);
        m.put("internalSeq", internalSeq);
        m.put("rngState", rng.state());
        m.put("baseHash", baseHash);
        m.put("traceHash", traceHash);
        m.put("createdAt", createdAt);
        m.put("updatedAt", updatedAt);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Session fromMap(Map<String, Object> m) {
        Session s = new Session();
        s.id = String.valueOf(m.get("id"));
        s.name = String.valueOf(m.get("name"));
        s.def = MachineDefinition.parse((Map<String, Object>) m.get("definition"));
        s.seed = ((Number) m.get("seed")).longValue();
        s.cursor = ((Number) m.get("cursor")).intValue();
        s.state = String.valueOf(m.get("state"));
        s.vars = new LinkedHashMap<>((Map<String, Object>) m.get("vars"));
        for (Object e : (List<Object>) m.get("log")) s.log.add(Event.fromMap((Map<String, Object>) e));
        Object pend = m.get("pending");
        if (pend instanceof List) {
            for (Object e : (List<Object>) pend) s.pending.add(Event.fromMap((Map<String, Object>) e));
        }
        for (Object t : (List<Object>) m.get("trace")) s.trace.add(TraceEntry.fromMap((Map<String, Object>) t));
        Object cps = m.get("checkpoints");
        if (cps instanceof List) {
            for (Object c : (List<Object>) cps) s.checkpoints.add(Checkpoint.fromMap((Map<String, Object>) c));
        }
        Object applied = m.get("appliedExternal");
        if (applied instanceof List) {
            for (Object e : (List<Object>) applied) s.appliedExternal.add(Event.fromMap((Map<String, Object>) e));
        }
        s.baseSessionId = (String) m.get("baseSessionId");
        s.baseCheckpointId = (String) m.get("baseCheckpointId");
        s.baseTraceHash = (String) m.get("baseTraceHash");
        s.internalSeq = ((Number) m.getOrDefault("internalSeq", 0L)).longValue();
        s.rng = new DeterministicRandom(s.seed);
        s.rng.restore(((Number) m.getOrDefault("rngState", s.seed)).longValue());
        s.baseHash = String.valueOf(m.get("baseHash"));
        s.traceHash = String.valueOf(m.get("traceHash"));
        s.createdAt = ((Number) m.getOrDefault("createdAt", 0L)).longValue();
        s.updatedAt = ((Number) m.getOrDefault("updatedAt", 0L)).longValue();
        return s;
    }
}
