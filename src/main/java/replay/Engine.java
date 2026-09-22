package replay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic replay engine. One "step" consumes exactly one external event plus the
 * full cascade of internal events it spawns (internal events always run after the event
 * that produced them, FIFO). On action failure the state changes and derived internal
 * events are rolled back, while the failure itself is recorded in the trajectory.
 */
public final class Engine {
    private Engine() {}

    public static final class ActionFailure extends RuntimeException {
        public ActionFailure(String msg) { super(msg); }
    }

    public static final class StepOutcome {
        public final Map<String, Object> record;
        public final Snapshot snapshot;

        StepOutcome(Map<String, Object> record, Snapshot snapshot) {
            this.record = record;
            this.snapshot = snapshot;
        }
    }

    @SuppressWarnings("unchecked")
    public static StepOutcome runStep(Map<String, Object> definition, Snapshot start,
                                      Map<String, Object> event, long stepIndex) {
        Snapshot cur = start.copy();
        List<Object> entries = new ArrayList<>();
        List<Object> outputs = new ArrayList<>();
        Deque<Map<String, Object>> queue = new ArrayDeque<>();
        int[] internalSeq = {0};

        processOne(definition, cur, event, entries, outputs, queue, internalSeq, stepIndex);
        while (!queue.isEmpty()) {
            processOne(definition, cur, queue.poll(), entries, outputs, queue, internalSeq, stepIndex);
        }

        String failure = null;
        for (Object e : entries) {
            Object f = ((Map<String, Object>) e).get("failure");
            if (f != null) { failure = (String) f; break; }
        }

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("index", stepIndex);
        record.put("event", Json.deepCopy(event));
        record.put("before", start.toJson());
        record.put("after", cur.toJson());
        record.put("entries", entries);
        record.put("outputs", outputs);
        record.put("failure", failure);
        return new StepOutcome(record, cur);
    }

    @SuppressWarnings("unchecked")
    private static void processOne(Map<String, Object> definition, Snapshot cur,
                                   Map<String, Object> event, List<Object> entries,
                                   List<Object> allOutputs, Deque<Map<String, Object>> queue,
                                   int[] internalSeq, long stepIndex) {
        Snapshot pre = cur.copy();
        List<Object> outputs = new ArrayList<>();
        List<Map<String, Object>> emitted = new ArrayList<>();
        String failure = null;
        int matched = -1;

        List<Object> transitions = (List<Object>) definition.getOrDefault("transitions", List.of());
        Map<String, Object> transition = null;
        for (int i = 0; i < transitions.size(); i++) {
            Map<String, Object> t = (Map<String, Object>) transitions.get(i);
            String from = (String) t.get("from");
            String on = (String) t.get("event");
            boolean fromOk = "*".equals(from) || java.util.Objects.equals(from, pre.state);
            boolean eventOk = java.util.Objects.equals(on, event.get("name"));
            if (fromOk && eventOk && Condition.eval((String) t.get("condition"), event, pre)) {
                matched = i;
                transition = t;
                break;
            }
        }

        if (transition != null) {
            Snapshot work = cur.copy();
            try {
                List<Object> actions = (List<Object>) transition.getOrDefault("actions", List.of());
                for (Object a : actions) {
                    applyAction((Map<String, Object>) a, work, event, outputs, emitted, internalSeq, stepIndex);
                }
                Object to = transition.get("to");
                if (to != null) work.state = (String) to;
                cur.state = work.state;
                cur.vars.clear();
                cur.vars.putAll(work.vars);
                cur.rng = work.rng;
            } catch (ActionFailure ex) {
                // Roll back state changes, outputs and derived internal events; keep the failure record.
                failure = ex.getMessage();
                outputs.clear();
                emitted.clear();
            }
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event", Json.deepCopy(event));
        entry.put("matched", matched);
        entry.put("outputs", outputs);
        entry.put("failure", failure);
        entry.put("stateBefore", pre.state);
        entry.put("stateAfter", cur.state);
        entry.put("varsBefore", Json.deepCopy(pre.vars));
        entry.put("varsAfter", Json.deepCopy(cur.vars));
        entries.add(entry);
        allOutputs.addAll(outputs);
        queue.addAll(emitted); // internal events always run after the current event
    }

    @SuppressWarnings("unchecked")
    private static void applyAction(Map<String, Object> action, Snapshot work, Map<String, Object> event,
                                    List<Object> outputs, List<Map<String, Object>> emitted,
                                    int[] internalSeq, long stepIndex) {
        String type = (String) action.get("type");
        if (type == null) throw new ActionFailure("action missing type");
        switch (type) {
            case "set": {
                String target = (String) action.get("target");
                if (target == null) throw new ActionFailure("set action missing target");
                work.vars.put(target, Condition.resolve(action.get("value"), event, work));
                break;
            }
            case "emit": {
                String name = (String) Condition.resolve(action.get("event"), event, work);
                if (name == null) throw new ActionFailure("emit action missing event name");
                Map<String, Object> ie = new LinkedHashMap<>();
                ie.put("id", "int-" + stepIndex + "-" + (internalSeq[0]++));
                ie.put("time", event.get("time"));
                ie.put("source", "internal");
                ie.put("seq", event.get("seq"));
                ie.put("name", name);
                Object payload = action.get("payload");
                ie.put("payload", payload == null ? new LinkedHashMap<>() : Condition.resolve(payload, event, work));
                ie.put("internal", true);
                emitted.add(ie);
                break;
            }
            case "output": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("message", Condition.resolve(action.get("message"), event, work));
                if (action.containsKey("value")) out.put("value", Condition.resolve(action.get("value"), event, work));
                outputs.add(out);
                break;
            }
            case "random": {
                String target = (String) action.get("target");
                if (target == null) throw new ActionFailure("random action missing target");
                work.vars.put(target, work.nextRandom() % 1000);
                break;
            }
            case "fail":
                throw new ActionFailure(action.get("message") != null
                        ? String.valueOf(action.get("message")) : "action failed");
            default:
                throw new ActionFailure("unknown action type: " + type);
        }
    }
}
