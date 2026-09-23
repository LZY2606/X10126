package replay.core;

import replay.expr.Expr;
import replay.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Runtime state of one replay branch. Fully deterministic: driven only by the
 * definition, the queued events (logical clock) and the branch RNG state.
 */
public final class Branch {
    public String id;
    public String name;
    public String ancestorCheckpointId; // null for the root branch
    public String state;
    public final LinkedHashMap<String, Object> vars = new LinkedHashMap<>();
    public final PriorityQueue<Event> queue;
    public final List<Map<String, Object>> trace = new ArrayList<>();
    public final List<String> outputs = new ArrayList<>();
    /** External events applied since the branch root, in application order. */
    public final List<Event> externalApplied = new ArrayList<>();
    public long internalSeq = 0;
    public long rngState;
    public String traceHash;
    public final List<Checkpoint> checkpoints = new ArrayList<>();

    private final Definition def;

    public Branch(Definition def, String id, String name, long seed, String baseHash) {
        this.def = def;
        this.id = id;
        this.name = name;
        this.state = def.initialState;
        this.rngState = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        this.traceHash = baseHash;
        this.queue = new PriorityQueue<>(Event.order(def));
    }

    private Branch(Definition def) {
        this.def = def;
        this.queue = new PriorityQueue<>(Event.order(def));
    }

    // ---------- RNG (deterministic xorshift64*) ----------

    public long nextRandom() {
        long x = rngState;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        rngState = x;
        return x * 0x2545F4914F6CDD1DL;
    }

    // ---------- stepping ----------

    public static final class ActionFailure extends RuntimeException {
        public ActionFailure(String msg) { super(msg); }
    }

    /** Process the next queued event. Returns the trace entry, or null when the queue is empty. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> step() {
        Event ev = queue.poll();
        if (ev == null) return null;
        final long stepNo = trace.size();
        final String beforeState = state;
        final Map<String, Object> beforeVars = (Map<String, Object>) Json.deepCopy(vars);

        int transitionIndex = -1;
        Definition.Transition matched = null;
        for (int i = 0; i < def.transitions.size(); i++) {
            Definition.Transition t = def.transitions.get(i);
            if (!"*".equals(t.from) && !t.from.equals(beforeState)) continue;
            if (!t.event.equals(ev.type)) continue;
            if (t.condition != null) {
                // conditions read the snapshot taken BEFORE this event is processed
                Object r = Expr.eval(t.condition, ctx(beforeVars, beforeState, ev));
                if (!Expr.truthy(r)) continue;
            }
            transitionIndex = i;
            matched = t;
            break;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", stepNo);
        entry.put("event", ev.toJson());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("state", beforeState);
        before.put("vars", Json.deepCopy(beforeVars));
        entry.put("before", before);

        List<String> producedOutputs = new ArrayList<>();
        String status;
        String error = null;

        if (matched == null) {
            status = "NO_MATCH";
        } else {
            Map<String, Object> savedVars = (Map<String, Object>) Json.deepCopy(vars);
            String savedState = state;
            int savedOutputs = outputs.size();
            try {
                for (Map<String, Object> action : matched.actions) {
                    execAction(action, ev, stepNo, producedOutputs);
                }
                if (matched.to != null) state = matched.to;
                status = "APPLIED";
            } catch (ActionFailure failure) {
                // roll back state changes and any internal events derived from this event
                vars.clear();
                vars.putAll(savedVars);
                state = savedState;
                while (outputs.size() > savedOutputs) outputs.remove(outputs.size() - 1);
                producedOutputs.clear();
                queue.removeIf(queued -> queued.batchId == stepNo);
                status = "FAILED";
                error = failure.getMessage();
            }
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("state", state);
        after.put("vars", Json.deepCopy(vars));
        entry.put("after", after);
        entry.put("status", status);
        entry.put("transition", transitionIndex);
        if (!producedOutputs.isEmpty()) entry.put("outputs", new ArrayList<>(producedOutputs));
        if (error != null) entry.put("error", error);

        trace.add(entry);
        traceHash = Definition.sha256(traceHash + "|" + Json.canonical(entry));
        if (!ev.internal) externalApplied.add(ev);
        return entry;
    }

    public int runToCompletion(int maxSteps) {
        int n = 0;
        while (!queue.isEmpty() && n < maxSteps) {
            step();
            n++;
        }
        return n;
    }

    // ---------- actions ----------

    @SuppressWarnings("unchecked")
    private void execAction(Map<String, Object> action, Event ev, long stepNo, List<String> producedOutputs) {
        if (action.containsKey("set")) {
            String name = Json.asString(action.get("set"), "action.set");
            Object value;
            if (action.containsKey("expr")) {
                value = Expr.eval(Json.asString(action.get("expr"), "action.expr"), ctx(vars, state, ev));
            } else {
                value = Json.deepCopy(action.get("value"));
            }
            vars.put(name, value);
            return;
        }
        if (action.containsKey("emit")) {
            Event internal = new Event();
            internal.id = "i" + (internalSeq + 1);
            internal.internal = true;
            internal.source = Event.INTERNAL_SOURCE;
            internal.type = Json.asString(action.get("emit"), "action.emit");
            Object delay = action.get("delay");
            internal.time = ev.time + (delay == null ? 0 : Json.asLong(delay, "action.delay"));
            internal.seq = ++internalSeq;
            internal.batchId = stepNo;
            Object payload = action.get("payload");
            if (payload != null) {
                for (Map.Entry<String, Object> e : Json.asMap(payload, "action.payload").entrySet()) {
                    internal.payload.put(e.getKey(), resolvePayloadValue(e.getValue(), ev));
                }
            }
            queue.add(internal);
            return;
        }
        if (action.containsKey("output")) {
            String text = interpolate(Json.asString(action.get("output"), "action.output"), ev);
            outputs.add(text);
            producedOutputs.add(text);
            return;
        }
        if (action.containsKey("fail")) {
            throw new ActionFailure(Json.asString(action.get("fail"), "action.fail"));
        }
        if (action.containsKey("failIf")) {
            Object r = Expr.eval(Json.asString(action.get("failIf"), "action.failIf"), ctx(vars, state, ev));
            if (Expr.truthy(r)) {
                Object msg = action.get("message");
                throw new ActionFailure(msg == null ? "failIf condition matched" : Json.asString(msg, "action.message"));
            }
            return;
        }
        throw new ActionFailure("unknown action: " + Json.write(action));
    }

    @SuppressWarnings("unchecked")
    private Object resolvePayloadValue(Object value, Event ev) {
        if (value instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) value;
            Object expr = m.get("$expr");
            if (expr != null) {
                return Expr.eval(Json.asString(expr, "payload.$expr"), ctx(vars, state, ev));
            }
        }
        return Json.deepCopy(value);
    }

    private String interpolate(String template, Event ev) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("${", i);
            if (start < 0) {
                sb.append(template, i, template.length());
                break;
            }
            int end = template.indexOf('}', start);
            if (end < 0) throw new ActionFailure("unclosed ${ in output template");
            sb.append(template, i, start);
            Object v = Expr.eval(template.substring(start + 2, end), ctx(vars, state, ev));
            sb.append(Expr.truthy(v) || v != null ? ExprStr(v) : "");
            i = end + 1;
        }
        return sb.toString();
    }

    private static String ExprStr(Object v) {
        return replay.expr.ExprStr.of(v);
    }

    // ---------- expression context ----------

    @SuppressWarnings("unchecked")
    private Expr.Ctx ctx(Map<String, Object> scopeVars, String scopeState, Event ev) {
        return new Expr.Ctx() {
            @Override
            public Object resolve(String name) {
                if ("state".equals(name)) return scopeState;
                if (name.startsWith("event.")) {
                    String rest = name.substring("event.".length());
                    if ("type".equals(rest)) return ev.type;
                    if ("source".equals(rest)) return ev.source;
                    if ("time".equals(rest)) return ev.time;
                    if ("seq".equals(rest)) return ev.seq;
                    if ("id".equals(rest)) return ev.id;
                    if (rest.startsWith("payload.")) {
                        Object cur = ev.payload;
                        for (String part : rest.substring("payload.".length()).split("\\.")) {
                            if (!(cur instanceof Map)) return null;
                            cur = ((Map<String, Object>) cur).get(part);
                        }
                        return cur;
                    }
                    return null;
                }
                return scopeVars.get(name);
            }

            @Override
            public Object call(String name, List<Object> args) {
                if ("rand".equals(name)) {
                    long raw = nextRandom() >>> 1;
                    if (args.isEmpty()) return raw;
                    long bound = ((Number) args.get(0)).longValue();
                    if (bound <= 0) throw new Expr.ExprException("rand(bound) needs bound > 0");
                    return raw % bound;
                }
                throw new Expr.ExprException("unknown function '" + name + "'");
            }
        };
    }

    // ---------- checkpoints ----------

    public Checkpoint checkpoint(String id, String name) {
        Checkpoint cp = new Checkpoint();
        cp.id = id;
        cp.name = name;
        cp.branchId = this.id;
        cp.stepCount = trace.size();
        cp.state = state;
        cp.vars = (Map<String, Object>) Json.deepCopy(vars);
        cp.outputs = new ArrayList<>(outputs);
        cp.rngState = rngState;
        cp.internalSeq = internalSeq;
        cp.traceHash = traceHash;
        cp.defFingerprint = def.fingerprint();
        for (Event e : queue) cp.queue.add(e.copy());
        cp.queue.sort(Event.order(def));
        for (Event e : externalApplied) cp.externalPrefix.add(e.copy());
        for (Map<String, Object> t : trace) cp.trace.add((Map<String, Object>) Json.deepCopy(t));
        checkpoints.add(cp);
        return cp;
    }

    /** Restore this branch to a checkpoint snapshot (used by merge). */
    public void restore(Checkpoint cp) {
        state = cp.state;
        vars.clear();
        vars.putAll((Map<String, Object>) Json.deepCopy(cp.vars));
        outputs.clear();
        outputs.addAll(cp.outputs);
        rngState = cp.rngState;
        internalSeq = cp.internalSeq;
        traceHash = cp.traceHash;
        queue.clear();
        for (Event e : cp.queue) queue.add(e.copy());
        externalApplied.clear();
        for (Event e : cp.externalPrefix) externalApplied.add(e.copy());
        trace.clear();
        for (Map<String, Object> t : cp.trace) trace.add((Map<String, Object>) Json.deepCopy(t));
    }

    /** Create a new branch forked from a checkpoint. Caller validates the definition fingerprint. */
    public static Branch fork(Definition def, Checkpoint cp, String newId, String name) {
        Branch b = new Branch(def);
        b.id = newId;
        b.name = name;
        b.ancestorCheckpointId = cp.id;
        b.restore(cp);
        return b;
    }

    // ---------- serialization ----------

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("ancestorCheckpointId", ancestorCheckpointId);
        m.put("state", state);
        m.put("vars", Json.deepCopy(vars));
        List<Object> q = new ArrayList<>();
        List<Event> sorted = new ArrayList<>(queue);
        sorted.sort(Event.order(def));
        for (Event e : sorted) q.add(e.toJson());
        m.put("queue", q);
        m.put("trace", Json.deepCopy(trace));
        m.put("outputs", new ArrayList<>(outputs));
        List<Object> ext = new ArrayList<>();
        for (Event e : externalApplied) ext.add(e.toJson());
        m.put("externalApplied", ext);
        m.put("internalSeq", internalSeq);
        m.put("rngState", rngState);
        m.put("traceHash", traceHash);
        List<Object> cps = new ArrayList<>();
        for (Checkpoint cp : checkpoints) cps.add(cp.toJson());
        m.put("checkpoints", cps);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Branch fromJson(Definition def, Map<String, Object> m) {
        Branch b = new Branch(def);
        b.id = Json.asString(m.get("id"), "branch.id");
        b.name = Json.asString(m.get("name"), "branch.name");
        Object anc = m.get("ancestorCheckpointId");
        b.ancestorCheckpointId = anc == null ? null : Json.asString(anc, "branch.ancestorCheckpointId");
        b.state = Json.asString(m.get("state"), "branch.state");
        b.vars.putAll(Json.asMap(m.get("vars"), "branch.vars"));
        for (Object o : Json.asList(m.get("queue"), "branch.queue")) {
            b.queue.add(Event.fromJson(Json.asMap(o, "event")));
        }
        for (Object o : Json.asList(m.get("trace"), "branch.trace")) {
            b.trace.add((Map<String, Object>) Json.deepCopy(Json.asMap(o, "trace entry")));
        }
        for (Object o : Json.asList(m.get("outputs"), "branch.outputs")) {
            b.outputs.add(Json.asString(o, "output"));
        }
        for (Object o : Json.asList(m.get("externalApplied"), "branch.externalApplied")) {
            b.externalApplied.add(Event.fromJson(Json.asMap(o, "event")));
        }
        b.internalSeq = Json.asLong(m.get("internalSeq"), "branch.internalSeq");
        b.rngState = Json.asLong(m.get("rngState"), "branch.rngState");
        b.traceHash = Json.asString(m.get("traceHash"), "branch.traceHash");
        for (Object o : Json.asList(m.get("checkpoints"), "branch.checkpoints")) {
            b.checkpoints.add(Checkpoint.fromJson(Json.asMap(o, "checkpoint")));
        }
        return b;
    }
}
