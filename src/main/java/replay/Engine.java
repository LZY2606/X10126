package replay;

import replay.Model.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Deterministic replay engine. All time comes from the logical timestamps of
 * the imported events; the engine never reads a wall clock.
 */
public class Engine {

    public static final String INTERNAL_SOURCE = "@internal";
    private static final int MAX_INTERNAL_PER_STEP_CHAIN = 1000;

    private final Session session;

    public Engine(Session session) {
        this.session = session;
    }

    // ---------- session construction ----------

    public static Session newSession(String name, Definition definition, List<Ev> events) {
        Session s = new Session();
        s.id = UUID.randomUUID().toString().substring(0, 8);
        s.name = name == null || name.isBlank() ? definition.name : name;
        s.definition = definition;
        s.defFingerprint = Json.fingerprint(definition);
        s.events = sortEvents(definition, events);
        s.sessionFingerprint = sessionFingerprint(s);
        Line main = new Line();
        main.id = "main";
        main.name = "main";
        main.queue = new ArrayList<>(s.events);
        main.snapshot = initialSnapshot(definition);
        s.lines.put("main", main);
        s.activeLineId = "main";
        return s;
    }

    public static Snapshot initialSnapshot(Definition def) {
        Snapshot snap = new Snapshot();
        snap.state = def.initialState;
        snap.rng = def.seed;
        return snap;
    }

    public static String sessionFingerprint(Session s) {
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("definition", s.defFingerprint);
        lock.put("initialState", s.definition.initialState);
        lock.put("seed", s.definition.seed);
        lock.put("events", s.events);
        return Json.fingerprint(lock);
    }

    public static List<Ev> sortEvents(Definition def, List<Ev> events) {
        List<Ev> sorted = new ArrayList<>(events == null ? List.of() : events);
        sorted.sort(eventComparator(def));
        return sorted;
    }

    public static Comparator<Ev> eventComparator(Definition def) {
        return Comparator
                .comparingLong((Ev e) -> e.time)
                .thenComparingInt(e -> def.sourcePriority.getOrDefault(e.source, 1_000_000))
                .thenComparingLong(e -> e.seq)
                .thenComparing(e -> e.name == null ? "" : e.name);
    }

    // ---------- replay ----------

    /** Advance the given line by exactly one event (internal events first). */
    public StepRec step(Line line) {
        Ev ev;
        boolean internal;
        if (!line.internalQueue.isEmpty()) {
            ev = line.internalQueue.remove(0);
            internal = true;
        } else if (line.cursor < line.queue.size()) {
            ev = line.queue.get(line.cursor++);
            internal = false;
        } else {
            return null;
        }

        StepRec rec = new StepRec();
        rec.index = line.trace.size();
        rec.event = ev;
        rec.internal = internal;
        rec.before = line.snapshot.copy();

        Map<String, Object> view = new HashMap<>(rec.before.vars);
        if (ev.payload != null) view.putAll(ev.payload);

        Transition transition = findTransition(rec.before.state, ev.name, view);
        if (transition == null) {
            rec.transition = null;
            rec.after = line.snapshot.copy();
        } else {
            rec.transition = transition.describe();
            Map<String, Object> workVars = new TreeMap<>(rec.before.vars);
            long[] rng = {rec.before.rng};
            List<String> outputs = new ArrayList<>();
            List<Ev> emitted = new ArrayList<>();
            try {
                for (Map<String, Object> action : transition.actions) {
                    runAction(action, view, workVars, rng, outputs, emitted, ev, line);
                }
                if (line.internalQueue.size() + emitted.size() > MAX_INTERNAL_PER_STEP_CHAIN) {
                    throw new ActionFailure("internal event chain too deep");
                }
                line.snapshot.state = transition.to != null ? transition.to : rec.before.state;
                line.snapshot.vars = workVars;
                line.snapshot.rng = rng[0];
                line.internalQueue.addAll(emitted);
                rec.outputs = outputs;
            } catch (RuntimeException failure) {
                // Roll back: snapshot, derived internal events and outputs are discarded,
                // but the failure itself is recorded in the trace.
                rec.failed = true;
                rec.error = failure.getMessage();
            }
            rec.after = line.snapshot.copy();
        }
        line.trace.add(rec);
        return rec;
    }

    /** Step until the line is finished or {@code maxSteps} is reached. */
    public int run(Line line, int maxSteps) {
        int count = 0;
        while (count < maxSteps && step(line) != null) count++;
        return count;
    }

    private Transition findTransition(String state, String eventName, Map<String, Object> view) {
        for (Transition t : session.definition.transitions) {
            if (t.event == null || !t.event.equals(eventName)) continue;
            if (t.from != null && !t.from.isEmpty() && !t.from.contains(state)) continue;
            if (t.condition != null && !t.condition.isBlank()
                    && !Expr.truthy(Expr.eval(t.condition, view))) continue;
            return t;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void runAction(Map<String, Object> action, Map<String, Object> view,
                           Map<String, Object> workVars, long[] rng,
                           List<String> outputs, List<Ev> emitted, Ev current, Line line) {
        Object typeObj = action.get("type");
        String type = typeObj == null ? "" : typeObj.toString();
        Map<String, Object> combined = new HashMap<>(workVars);
        if (current.payload != null) combined.putAll(current.payload);
        switch (type) {
            case "set" -> {
                String var = required(action, "var");
                workVars.put(var, Expr.eval(required(action, "value"), combined));
            }
            case "output" -> outputs.add(String.valueOf(Expr.eval(required(action, "value"), combined)));
            case "emit" -> {
                String name = required(action, "name");
                Map<String, Object> payload = action.get("payload") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m : null;
                emitted.add(new Ev(current.time, INTERNAL_SOURCE, line.internalSeq++, name, payload));
            }
            case "random" -> {
                String var = required(action, "var");
                long min = number(action.get("min"), 0);
                long max = number(action.get("max"), 100);
                rng[0] = lcg(rng[0]);
                long value = min + Math.floorMod(rng[0] >>> 16, max - min + 1);
                workVars.put(var, value);
            }
            case "fail" -> {
                Object cond = action.get("if");
                boolean fire = cond == null || Expr.truthy(Expr.eval(cond.toString(), combined));
                if (fire) {
                    Object msg = action.get("message");
                    throw new ActionFailure(msg != null ? msg.toString() : "action failed");
                }
            }
            default -> throw new ActionFailure("unknown action type: '" + type + "'");
        }
    }

    private static String required(Map<String, Object> action, String key) {
        Object v = action.get(key);
        if (v == null) throw new ActionFailure("action '" + action.get("type") + "' missing '" + key + "'");
        return v.toString();
    }

    private static long number(Object v, long fallback) {
        return v instanceof Number n ? n.longValue() : fallback;
    }

    /** Splitmix-style LCG so the RNG state is a single persistable long. */
    static long lcg(long state) {
        return state * 6364136223846793005L + 1442695040888963407L;
    }

    // ---------- checkpoints ----------

    public Checkpoint checkpoint(Line line, String name) {
        Checkpoint cp = new Checkpoint();
        cp.id = UUID.randomUUID().toString().substring(0, 8);
        cp.name = name == null || name.isBlank() ? "cp-" + cp.id : name;
        cp.lineId = line.id;
        cp.stepIndex = line.trace.size();
        cp.cursor = line.cursor;
        cp.internalSeq = line.internalSeq;
        cp.snapshot = line.snapshot.copy();
        cp.defFingerprint = session.defFingerprint;
        session.checkpoints.add(cp);
        return cp;
    }

    private Checkpoint checkedCheckpoint(String checkpointId) {
        Checkpoint cp = session.checkpoints.stream()
                .filter(c -> c.id.equals(checkpointId)).findFirst()
                .orElseThrow(() -> new ApiException(404, "no such checkpoint: " + checkpointId));
        if (!cp.defFingerprint.equals(session.defFingerprint)) {
            throw new CheckpointVersionException(session.defFingerprint, cp.defFingerprint);
        }
        return cp;
    }

    /** Restore the checkpoint's own line to the checkpoint. */
    public Line restore(String checkpointId) {
        Checkpoint cp = checkedCheckpoint(checkpointId);
        Line line = session.line(cp.lineId);
        line.snapshot = cp.snapshot.copy();
        line.cursor = cp.cursor;
        line.internalSeq = cp.internalSeq;
        line.internalQueue = new ArrayList<>();
        while (line.trace.size() > cp.stepIndex) line.trace.remove(line.trace.size() - 1);
        session.activeLineId = line.id;
        return line;
    }

    /** Fork a new branch from a checkpoint. */
    public Line fork(String checkpointId, String name) {
        Checkpoint cp = checkedCheckpoint(checkpointId);
        Line parent = session.line(cp.lineId);
        Line branch = new Line();
        branch.id = "b" + UUID.randomUUID().toString().substring(0, 6);
        branch.name = name == null || name.isBlank() ? branch.id : name;
        branch.baseLineId = parent.id;
        branch.baseStepIndex = cp.stepIndex;
        branch.queue = new ArrayList<>(parent.queue.subList(cp.cursor, parent.queue.size()));
        branch.snapshot = cp.snapshot.copy();
        branch.internalSeq = cp.internalSeq;
        session.lines.put(branch.id, branch);
        session.activeLineId = branch.id;
        return branch;
    }

    /** Add external events to a branch queue (kept in stable sorted order). */
    public void addEvents(Line line, List<Ev> extra) {
        line.queue.addAll(extra);
        line.queue.sort(eventComparator(session.definition));
    }

    // ---------- merge ----------

    public record MergeResult(String status, Integer conflictIndex, Ev sourceEvent, Ev targetEvent,
                              String traceHash, String message) {}

    private record Ancestor(String lineId, int stepIndex) {}

    private List<Ancestor> ancestry(Line line) {
        List<Ancestor> out = new ArrayList<>();
        Line cur = line;
        while (true) {
            out.add(new Ancestor(cur.id, cur.trace.size()));
            if (cur.baseLineId == null) return out;
            out.add(new Ancestor(cur.baseLineId, cur.baseStepIndex));
            cur = session.line(cur.baseLineId);
        }
    }

    /** External events applied on {@code line} since the given ancestor point. */
    private List<Ev> externalEventsSince(Line line, String ancestorLineId, int ancestorStep) {
        List<Ev> result = new ArrayList<>();
        Line cur = line;
        int upto = cur.trace.size();
        while (true) {
            int from = cur.id.equals(ancestorLineId) ? ancestorStep : 0;
            List<Ev> segment = new ArrayList<>();
            for (int i = from; i < upto; i++) {
                StepRec rec = cur.trace.get(i);
                if (!rec.internal) segment.add(rec.event);
            }
            result.addAll(0, segment);
            if (cur.id.equals(ancestorLineId)) return result;
            upto = cur.baseStepIndex;
            cur = session.line(cur.baseLineId);
        }
    }

    /**
     * Merge {@code sourceId} into {@code targetId}. Never overwrites state:
     * the merge is allowed only when the external event sequences applied on
     * both lines since their common ancestor are compatible (one is a prefix
     * of the other) and unambiguously ordered. The first conflicting pair of
     * events is reported on rejection.
     */
    public MergeResult merge(String sourceId, String targetId) {
        Line source = session.line(sourceId);
        Line target = session.line(targetId);

        List<Ancestor> srcAnc = ancestry(source);
        List<Ancestor> dstAnc = ancestry(target);
        Ancestor common = null;
        int bestDepth = -1;
        for (Ancestor a : srcAnc) {
            for (Ancestor b : dstAnc) {
                if (!a.lineId.equals(b.lineId)) continue;
                int step = Math.min(a.stepIndex, b.stepIndex);
                int depth = depthOf(a.lineId);
                if (depth > bestDepth || (depth == bestDepth && common != null && step > common.stepIndex)) {
                    bestDepth = depth;
                    common = new Ancestor(a.lineId, step);
                }
            }
        }
        if (common == null) throw new ApiException(400, "lines share no common ancestor");

        List<Ev> srcSeq = externalEventsSince(source, common.lineId, common.stepIndex);
        List<Ev> dstSeq = externalEventsSince(target, common.lineId, common.stepIndex);

        int shared = Math.min(srcSeq.size(), dstSeq.size());
        for (int i = 0; i < shared; i++) {
            if (!srcSeq.get(i).sameIdentity(dstSeq.get(i))) {
                return new MergeResult("conflict", i, srcSeq.get(i), dstSeq.get(i), null,
                        "first conflicting events at position " + i);
            }
        }

        if (srcSeq.size() == dstSeq.size()) {
            return new MergeResult("identical", null, null, null, traceHash(target),
                    "both lines applied the same external events");
        }

        List<Ev> missing;
        if (srcSeq.size() > dstSeq.size()) {
            missing = new ArrayList<>(srcSeq.subList(dstSeq.size(), srcSeq.size()));
        } else {
            // target is already ahead of source; nothing to bring over.
            return new MergeResult("merged", null, null, null, traceHash(target),
                    "target already contains the source sequence");
        }

        // Ambiguity check: no two distinct events may share the same ordering key.
        Comparator<Ev> cmp = eventComparator(session.definition);
        List<Ev> combined = new ArrayList<>(target.queue.subList(target.cursor, target.queue.size()));
        combined.addAll(missing);
        combined.sort(cmp);
        for (int i = 1; i < combined.size(); i++) {
            Ev a = combined.get(i - 1);
            Ev b = combined.get(i);
            if (cmp.compare(a, b) == 0 && !a.sameIdentity(b)) {
                return new MergeResult("conflict", null, a, b, null,
                        "ambiguous ordering between " + a.label() + " and " + b.label());
            }
        }

        addEvents(target, missing);
        run(target, 1_000_000);
        return new MergeResult("merged", null, null, null, traceHash(target),
                "appended " + missing.size() + " event(s) from " + source.name);
    }

    private int depthOf(String lineId) {
        int depth = 0;
        Line cur = session.line(lineId);
        while (cur.baseLineId != null) {
            depth++;
            cur = session.line(cur.baseLineId);
        }
        return depth;
    }

    // ---------- fingerprints / export ----------

    public static String traceHash(Line line) {
        return Json.fingerprint(line.trace);
    }

    public Map<String, Object> exportSession() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "replay-session/1");
        out.put("session", session);
        out.put("mainTraceHash", traceHash(session.main()));
        return out;
    }

    /**
     * Import an exported session: the main line is replayed from scratch and
     * must reproduce the exported trace hash exactly.
     */
    public static Session importSession(Map<String, Object> exported) {
        Object fmt = exported.get("format");
        if (!"replay-session/1".equals(fmt)) throw new ApiException(400, "unsupported export format: " + fmt);
        Session imported;
        try {
            imported = Json.M.convertValue(exported.get("session"), Session.class);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "corrupt export: " + e.getMessage());
        }
        String expectedHash = String.valueOf(exported.get("mainTraceHash"));

        Session fresh = newSession(imported.name, imported.definition, imported.events);
        Engine engine = new Engine(fresh);
        int steps = imported.main().trace.size();
        engine.run(fresh.main(), steps);
        String recomputed = traceHash(fresh.main());
        if (!recomputed.equals(expectedHash)) {
            throw new ApiException(400, "replay mismatch: exported=" + expectedHash + " recomputed=" + recomputed);
        }
        // Verified: keep the full imported data (branches, checkpoints) under a fresh id.
        imported.id = UUID.randomUUID().toString().substring(0, 8);
        imported.sessionFingerprint = sessionFingerprint(imported);
        imported.defFingerprint = Json.fingerprint(imported.definition);
        return imported;
    }
}
