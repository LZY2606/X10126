package replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import replay.json.Json;

/**
 * Deterministic replay engine. All time is logical; the engine only advances
 * when step()/run() is called, so tests never depend on wall-clock sleeps.
 */
public final class Engine {
    public static final int INTERNAL_PRIORITY = Integer.MAX_VALUE;

    public Definition def;
    public String state;
    public LinkedHashMap<String, Object> vars;
    public PriorityQueue<EventInstance> queue = new PriorityQueue<>();
    public long insertionCounter;
    public long eventCounter;
    public List<StepRecord> trace = new ArrayList<>();
    public List<String> outputs = new ArrayList<>();
    public long rngState;
    /** External events ever enqueued into this engine, in enqueue order (for export/replay). */
    public List<EventInstance> externalLog = new ArrayList<>();

    public Engine(Definition def, long seed) {
        this.def = def;
        this.state = def.initial;
        this.vars = new LinkedHashMap<>(def.variables);
        this.rngState = seed;
    }

    /** Mutable working frame used while applying one event's actions. */
    public static final class Frame {
        public String state;
        public LinkedHashMap<String, Object> vars;
    }

    public Expr.Context evalContext(Frame frame) {
        return new Expr.Context() {
            @Override public Object variable(String name) {
                if (name.equals("state")) return frame.state;
                return frame.vars.get(name);
            }
            @Override public long nextRandom(long bound) {
                return Engine.this.nextRandom(bound);
            }
        };
    }

    public long nextRandom(long bound) {
        // SplitMix64-style step: fully deterministic and serializable as a single long.
        rngState = rngState * 6364136223846793005L + 1442695040888963407L;
        long z = rngState;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return Math.floorMod(z, bound);
    }

    public EventInstance newInternalEvent(String type, long delayFromCurrent) {
        EventInstance e = new EventInstance();
        e.id = "e" + (++eventCounter);
        e.type = type;
        e.time = currentTime + delayFromCurrent;
        e.source = "internal";
        e.sourcePriority = INTERNAL_PRIORITY;
        e.seq = 0;
        e.internal = true;
        return e;
    }

    private long currentTime;

    /** Enqueue an external event. sourcePriority comes from the session's source list. */
    public EventInstance enqueueExternal(String type, long time, String source, int sourcePriority, long seq) {
        EventInstance e = new EventInstance();
        e.id = "e" + (++eventCounter);
        e.type = type;
        e.time = time;
        e.source = source;
        e.sourcePriority = sourcePriority;
        e.seq = seq;
        e.internal = false;
        e.insertion = ++insertionCounter;
        queue.add(e);
        externalLog.add(e);
        return e;
    }

    /** Process the next queued event, or return null when the queue is empty. */
    public StepRecord step() {
        EventInstance e = queue.poll();
        if (e == null) return null;
        currentTime = e.time;

        StepRecord rec = new StepRecord();
        rec.index = trace.size();
        rec.eventId = e.id;
        rec.eventType = e.type;
        rec.time = e.time;
        rec.source = e.source;
        rec.sourcePriority = e.sourcePriority;
        rec.seq = e.seq;
        rec.internal = e.internal;
        rec.beforeState = state;
        rec.beforeVars = new LinkedHashMap<>(vars);

        // Conditions are evaluated against the snapshot taken BEFORE this event.
        Frame snapshot = new Frame();
        snapshot.state = state;
        snapshot.vars = new LinkedHashMap<>(vars);
        Expr.Context snapshotCtx = evalContext(snapshot);

        List<Definition.Rule> matched = new ArrayList<>();
        for (Definition.Rule r : def.rules) {
            if (!r.event.equals(e.type)) continue;
            if (!r.from.equals("*") && !r.from.equals(snapshot.state)) continue;
            boolean ok;
            try {
                ok = Expr.truthy(Expr.eval(r.condition, snapshotCtx));
            } catch (Expr.EvalException ex) {
                ok = false;
            }
            if (ok) matched.add(r);
        }
        rec.matchedRules = matched.size();

        Frame work = new Frame();
        work.state = state;
        work.vars = new LinkedHashMap<>(vars);
        List<EventInstance> emitted = new ArrayList<>();
        List<String> stepOutputs = new ArrayList<>();
        String failure = null;
        try {
            for (Definition.Rule r : matched) {
                for (Action a : r.actions) {
                    a.apply(work, this, emitted, stepOutputs);
                }
            }
        } catch (Action.ActionFailure af) {
            failure = af.getMessage();
        }

        if (failure != null) {
            // Roll back: state/vars untouched, derived internal events discarded.
            // The failure itself is still recorded in the trace.
            rec.failure = failure;
            rec.afterState = rec.beforeState;
            rec.afterVars = new LinkedHashMap<>(rec.beforeVars);
            rec.outputs = new ArrayList<>();
            rec.emitted = new ArrayList<>();
        } else {
            state = work.state;
            vars = work.vars;
            outputs.addAll(stepOutputs);
            rec.outputs = stepOutputs;
            for (EventInstance ie : emitted) {
                ie.insertion = ++insertionCounter;
                queue.add(ie);
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("id", ie.id);
                em.put("type", ie.type);
                em.put("time", ie.time);
                rec.emitted.add(em);
            }
            rec.afterState = state;
            rec.afterVars = new LinkedHashMap<>(vars);
        }
        trace.add(rec);
        return rec;
    }

    /** Step until the queue drains or maxSteps is reached. Returns steps executed. */
    public int run(int maxSteps) {
        int n = 0;
        while (n < maxSteps && !queue.isEmpty()) {
            step();
            n++;
        }
        return n;
    }

    public boolean idle() { return queue.isEmpty(); }

    public String traceHash() {
        List<Object> entries = new ArrayList<>();
        for (StepRecord r : trace) entries.add(r.toMap());
        return Definition.sha256(Json.canonical(entries));
    }

    public Map<String, Object> snapshotToMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("vars", new LinkedHashMap<>(vars));
        List<Object> q = new ArrayList<>();
        for (EventInstance e : queue) q.add(e.toMap());
        m.put("queue", q);
        m.put("insertionCounter", insertionCounter);
        m.put("eventCounter", eventCounter);
        m.put("rngState", rngState);
        List<Object> t = new ArrayList<>();
        for (StepRecord r : trace) t.add(r.toMap());
        m.put("trace", t);
        m.put("outputs", new ArrayList<>(outputs));
        List<Object> ext = new ArrayList<>();
        for (EventInstance e : externalLog) ext.add(e.toMap());
        m.put("externalLog", ext);
        return m;
    }

    @SuppressWarnings("unchecked")
    public void restoreFromMap(Map<String, Object> m) {
        state = EventInstance.str(m.get("state"));
        vars = new LinkedHashMap<>((Map<String, Object>) m.get("vars"));
        queue = new PriorityQueue<>();
        for (Object o : (List<Object>) m.get("queue")) queue.add(EventInstance.fromMap((Map<String, Object>) o));
        insertionCounter = EventInstance.num(m.get("insertionCounter"));
        eventCounter = EventInstance.num(m.get("eventCounter"));
        rngState = EventInstance.num(m.get("rngState"));
        trace = new ArrayList<>();
        for (Object o : (List<Object>) m.get("trace")) trace.add(StepRecord.fromMap((Map<String, Object>) o));
        outputs = new ArrayList<>();
        for (Object o : (List<Object>) m.get("outputs")) outputs.add(o.toString());
        externalLog = new ArrayList<>();
        for (Object o : (List<Object>) m.get("externalLog")) externalLog.add(EventInstance.fromMap((Map<String, Object>) o));
    }
}
