package replay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Deterministic replay engine. External events are processed strictly one at a time in
 * (logicalTime, sourcePriority, seq) order. Conditions see the pre-event snapshot;
 * internal events emitted by actions cascade right after the current event (before any
 * later external event); a failed action rolls back the whole event including derived
 * internal events, while the failure itself stays in the trace.
 */
public final class Engine {
    public static final String INTERNAL_SOURCE = "@internal";

    private final Definition def;
    private String state;
    private final LinkedHashMap<String, Object> vars;
    private final PriorityQueue<EventInstance> queue;          // external events
    private final Deque<EventInstance> internalQueue;          // cascade of derived events
    private final List<TraceEntry> trace;
    private final Lcg rng;
    private long clock;
    private long internalSeq;

    public Engine(Definition def, String initialState, Map<String, Object> initialVars, long seed) {
        this.def = def;
        if (!def.states.contains(initialState))
            throw new IllegalArgumentException("initial state '" + initialState + "' is not in definition states");
        this.state = initialState;
        this.vars = new LinkedHashMap<>(initialVars);
        this.queue = new PriorityQueue<>((a, b) -> a.compareToWith(b, def::sourcePriority));
        this.internalQueue = new ArrayDeque<>();
        this.trace = new ArrayList<>();
        this.rng = new Lcg(seed);
        this.clock = Long.MIN_VALUE;
        this.internalSeq = 0;
    }

    public Definition definition() { return def; }
    public String state() { return state; }
    public Map<String, Object> vars() { return java.util.Collections.unmodifiableMap(vars); }
    public List<TraceEntry> trace() { return java.util.Collections.unmodifiableList(trace); }
    public long clock() { return clock; }
    public int pending() { return queue.size() + internalQueue.size(); }

    public List<EventInstance> queuedEvents() {
        List<EventInstance> all = new ArrayList<>(queue);
        all.addAll(internalQueue);
        return all;
    }

    public List<EventInstance> internalQueueSnapshot() { return new ArrayList<>(internalQueue); }

    public void enqueue(EventInstance e) { queue.add(e); }

    /** Process exactly one event (internal cascade first). Returns null when nothing is pending. */
    public TraceEntry step() {
        EventInstance event = internalQueue.poll();
        if (event == null) event = queue.poll();
        if (event == null) return null;

        Map<String, Object> beforeVars = deepCopyVars();
        String beforeState = state;
        List<EventInstance> emitted = new ArrayList<>();
        List<Object> outputs = new ArrayList<>();
        TraceEntry entry = new TraceEntry(trace.size(), event, snapshotOf(beforeState, beforeVars));

        Definition.EventDef eventDef = def.events.get(event.name);
        try {
            if (eventDef == null) throw new ActionFailure("unknown event '" + event.name + "'");
            Expr.EvalContext ctx = evalContext(event);
            if (eventDef.condition != null && !eventDef.condition.evalBoolean(ctx)) {
                entry.finishSkipped(snapshotOf(state, vars), outputs);
                commit(event, entry);
                return entry;
            }
            for (Definition.Action action : eventDef.actions) apply(action, event, ctx, emitted, outputs);
            entry.finishApplied(snapshotOf(state, vars), outputs);
        } catch (RuntimeException ex) {
            // Roll back: restore state/vars, drop derived internal events.
            state = beforeState;
            vars.clear();
            vars.putAll(beforeVars);
            internalQueue.removeAll(emitted);
            entry.finishFailed(snapshotOf(state, vars), outputs, ex.getMessage());
        }
        commit(event, entry);
        return entry;
    }

    private void commit(EventInstance event, TraceEntry entry) {
        if (event.time > clock) clock = event.time;
        trace.add(entry);
    }

    private void apply(Definition.Action action, EventInstance event, Expr.EvalContext ctx,
                       List<EventInstance> emitted, List<Object> outputs) {
        if (action instanceof Definition.Action.Transition t) {
            if (!def.states.contains(t.state()))
                throw new ActionFailure("transition to undefined state '" + t.state() + "'");
            state = t.state();
        } else if (action instanceof Definition.Action.Set s) {
            vars.put(s.var(), s.expr().eval(ctx));
        } else if (action instanceof Definition.Action.Emit em) {
            if (!def.events.containsKey(em.event()))
                throw new ActionFailure("emit of undefined event '" + em.event() + "'");
            Map<String, Object> payload = new LinkedHashMap<>();
            em.payload().forEach((k, expr) -> payload.put(k, expr.eval(ctx)));
            EventInstance derived = new EventInstance(event.time, INTERNAL_SOURCE, internalSeq++, em.event(), payload, true);
            emitted.add(derived);
            internalQueue.add(derived);
        } else if (action instanceof Definition.Action.Output o) {
            outputs.add(o.expr().eval(ctx));
        }
    }

    private Expr.EvalContext evalContext(EventInstance event) {
        return new Expr.EvalContext() {
            public Object resolve(String name) {
                if (name.equals("state")) return state;
                if (name.startsWith("payload.")) return event.payload.get(name.substring("payload.".length()));
                if (name.equals("payload")) return event.payload;
                if (vars.containsKey(name)) return vars.get(name);
                throw new Expr.EvalException("unknown variable '" + name + "'");
            }
            public long randInt(long bound) { return rng.nextInt(bound); }
        };
    }

    private Map<String, Object> snapshotOf(String s, Map<String, Object> v) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("state", s);
        snap.put("vars", new LinkedHashMap<>(v));
        return snap;
    }

    private LinkedHashMap<String, Object> deepCopyVars() {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        vars.forEach((k, v) -> copy.put(k, deepCopy(v)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    static Object deepCopy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) v).forEach((k, x) -> out.put(k, deepCopy(x)));
            return out;
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object x : (List<Object>) v) out.add(deepCopy(x));
            return out;
        }
        return v;
    }

    // ---- checkpoint snapshot / restore ----

    public Snapshot snapshot() {
        return new Snapshot(state, deepCopyVars(), new ArrayList<>(queue), new ArrayList<>(internalQueue),
                clock, rng.state(), internalSeq, new ArrayList<>(trace));
    }

    public void restore(Snapshot snap) {
        this.state = snap.state;
        vars.clear();
        snap.vars.forEach((k, v) -> vars.put(k, deepCopy(v)));
        queue.clear();
        queue.addAll(snap.queue);
        internalQueue.clear();
        internalQueue.addAll(snap.internalQueue);
        clock = snap.clock;
        rng.restore(snap.rngState);
        internalSeq = snap.internalSeq;
        trace.clear();
        trace.addAll(snap.tracePrefix);
    }

    /** Rebuild engine state from a persisted session (no re-execution needed). */
    public void loadPersisted(String state, Map<String, Object> persistedVars, List<EventInstance> persistedQueue,
                              List<EventInstance> persistedInternal, long clock, long rngState, long internalSeq,
                              List<TraceEntry> persistedTrace) {
        this.state = state;
        vars.clear();
        persistedVars.forEach((k, v) -> this.vars.put(k, deepCopy(v)));
        queue.clear();
        queue.addAll(persistedQueue);
        internalQueue.clear();
        internalQueue.addAll(persistedInternal);
        this.clock = clock;
        this.rng.restore(rngState);
        this.internalSeq = internalSeq;
        this.trace.clear();
        this.trace.addAll(persistedTrace);
    }

    public static final class Snapshot {
        public final String state;
        public final LinkedHashMap<String, Object> vars;
        public final List<EventInstance> queue;
        public final List<EventInstance> internalQueue;
        public final long clock;
        public final long rngState;
        public final long internalSeq;
        public final List<TraceEntry> tracePrefix;

        Snapshot(String state, LinkedHashMap<String, Object> vars, List<EventInstance> queue,
                 List<EventInstance> internalQueue, long clock, long rngState, long internalSeq,
                 List<TraceEntry> tracePrefix) {
            this.state = state;
            this.vars = vars;
            this.queue = queue;
            this.internalQueue = internalQueue;
            this.clock = clock;
            this.rngState = rngState;
            this.internalSeq = internalSeq;
            this.tracePrefix = tracePrefix;
        }

        public int traceLength() { return tracePrefix.size(); }
    }

    public static final class ActionFailure extends RuntimeException {
        public ActionFailure(String msg) { super(msg); }
    }
}
