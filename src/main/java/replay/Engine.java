package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic replay engine. All time is logical (taken from events);
 * no wall-clock or sleep is involved anywhere.
 */
public final class Engine {
    private Engine() {}

    public static final class ActionFailure extends RuntimeException {
        public ActionFailure(String message) {
            super(message);
        }
    }

    /**
     * Apply exactly one step on the branch: the next internal event (FIFO),
     * otherwise the next external event in stable sort order.
     * Returns the step record, or null when there is nothing to process.
     */
    public static StepRecord step(Session session, Branch branch) {
        Snapshot current = branch.current();
        boolean internal = !current.internalQueue.isEmpty();
        if (!internal && current.pendingExternal.isEmpty()) {
            return null;
        }
        // Working copy: the event is consumed even if the transition fails.
        Snapshot work = current.copy();
        Event event;
        if (internal) {
            event = work.internalQueue.remove(0);
        } else {
            event = pickNextExternal(work.pendingExternal, session.sourcePriorities);
            work.pendingExternal.remove(event);
        }

        String beforeState = work.state;
        @SuppressWarnings("unchecked")
        Map<String, Object> beforeVars = (Map<String, Object>) Json.deepCopy(work.vars);

        int transitionIndex = findTransition(session.definition, work, event);
        List<Object> outputs = new ArrayList<>();
        String failure = null;
        String status;
        if (transitionIndex < 0) {
            status = StepRecord.NO_TRANSITION;
        } else {
            Snapshot beforeActions = work.copy();
            try {
                applyActions(session.definition, transitionIndex, work, event, outputs);
                status = StepRecord.OK;
            } catch (ActionFailure ex) {
                // Roll back state changes and any internal events derived from them.
                work = beforeActions;
                outputs = new ArrayList<>();
                failure = ex.getMessage();
                status = StepRecord.FAILED;
            }
        }
        work.logicalTime = Math.max(work.logicalTime, event.time);

        StepRecord record = new StepRecord(
                branch.trace.size(), event, status, beforeState, beforeVars, work,
                outputs, failure, transitionIndex < 0 ? null : transitionIndex);
        branch.trace.add(record);
        return record;
    }

    /** Step until no events remain (internal queue and external pool both empty). */
    public static List<StepRecord> drain(Session session, Branch branch) {
        List<StepRecord> out = new ArrayList<>();
        StepRecord r;
        while ((r = step(session, branch)) != null) {
            out.add(r);
        }
        return out;
    }

    private static Event pickNextExternal(List<Event> pending, Map<String, Long> priorities) {
        Event best = null;
        for (Event e : pending) {
            if (best == null || Event.comparator(priorities).compare(e, best) < 0) {
                best = e;
            }
        }
        return best;
    }

    private static int findTransition(Definition def, Snapshot snap, Event event) {
        for (int i = 0; i < def.transitions.size(); i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tr = (Map<String, Object>) def.transitions.get(i);
            if (!Json.asString(tr.get("event"), "transition.event").equals(event.type)) continue;
            String from = Json.asString(tr.get("from"), "transition.from");
            if (!"*".equals(from) && !from.equals(snap.state)) continue;
            if (conditionsHold(tr, snap.vars, event)) return i;
        }
        return -1;
    }

    /** Conditions read the snapshot of variables as of before the event is processed. */
    @SuppressWarnings("unchecked")
    private static boolean conditionsHold(Map<String, Object> transition,
                                          Map<String, Object> vars, Event event) {
        Object conds = transition.get("conditions");
        if (conds == null) return true;
        for (Object c : Json.asList(conds, "transition.conditions")) {
            Map<String, Object> cond = Json.asMap(c, "condition");
            String op = Json.asString(cond.get("op"), "condition.op");
            if ("exists".equals(op) || "missing".equals(op)) {
                boolean present = operandPresent(cond.get("left"), vars, event);
                if ("exists".equals(op) != present) return false;
                continue;
            }
            Object left = evalOperand(cond.get("left"), vars, event);
            Object right = evalOperand(cond.get("right"), vars, event);
            if (!compare(left, op, right)) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean operandPresent(Object operand, Map<String, Object> vars, Event event) {
        if (!(operand instanceof Map)) return operand != null;
        Map<String, Object> m = (Map<String, Object>) operand;
        if (m.containsKey("var")) return vars.containsKey(m.get("var"));
        if (m.containsKey("field")) return event.fields.containsKey(m.get("field"));
        return true;
    }

    @SuppressWarnings("unchecked")
    static Object evalOperand(Object operand, Map<String, Object> vars, Event event) {
        if (!(operand instanceof Map)) return operand;
        Map<String, Object> m = (Map<String, Object>) operand;
        if (m.containsKey("literal")) return m.get("literal");
        if (m.containsKey("var")) return vars.get(m.get("var"));
        if (m.containsKey("field")) return event.fields.get(m.get("field"));
        return null;
    }

    private static boolean compare(Object left, String op, Object right) {
        if (left instanceof Number && right instanceof Number) {
            double l = ((Number) left).doubleValue();
            double r = ((Number) right).doubleValue();
            return switch (op) {
                case "==" -> l == r;
                case "!=" -> l != r;
                case "<" -> l < r;
                case "<=" -> l <= r;
                case ">" -> l > r;
                case ">=" -> l >= r;
                default -> throw new Json.JsonException("unknown op: " + op);
            };
        }
        boolean eq = left == null ? right == null : left.equals(right);
        return switch (op) {
            case "==" -> eq;
            case "!=" -> !eq;
            default -> throw new Json.JsonException("op " + op + " requires numbers");
        };
    }

    @SuppressWarnings("unchecked")
    private static void applyActions(Definition def, int transitionIndex, Snapshot work,
                                     Event event, List<Object> outputs) {
        Map<String, Object> tr = (Map<String, Object>) def.transitions.get(transitionIndex);
        Object actions = tr.get("actions");
        if (actions == null) return;
        for (Object a : Json.asList(actions, "transition.actions")) {
            Map<String, Object> action = Json.asMap(a, "action");
            String type = Json.asString(action.get("type"), "action.type");
            switch (type) {
                case "set" -> {
                    String var = Json.asString(action.get("var"), "action.var");
                    work.vars.put(var, evalOperand(action.get("value"), work.vars, event));
                }
                case "goto" -> {
                    String target = Json.asString(action.get("state"), "action.state");
                    if (!def.states.contains(target)) {
                        throw new ActionFailure("unknown target state: " + target);
                    }
                    work.state = target;
                }
                case "output" -> outputs.add(action.get("message"));
                case "emit" -> {
                    String type2 = Json.asString(action.get("eventType"), "action.eventType");
                    Map<String, Object> fields = Json.map();
                    if (action.containsKey("fields")) {
                        for (Map.Entry<String, Object> f : Json.asMap(action.get("fields"), "action.fields").entrySet()) {
                            fields.put(f.getKey(), evalOperand(f.getValue(), work.vars, event));
                        }
                    }
                    long seq = work.internalSeq++;
                    work.internalQueue.add(new Event("i" + seq, event.time, Event.INTERNAL_SOURCE,
                            seq, seq, type2, fields, true));
                }
                case "random" -> {
                    String var = Json.asString(action.get("var"), "action.var");
                    long min = action.containsKey("min") ? Json.asLong(action.get("min"), "action.min") : 0;
                    long max = action.containsKey("max") ? Json.asLong(action.get("max"), "action.max") : 100;
                    long[] holder = {work.rngState};
                    long value = Prng.draw(holder, min, max);
                    work.rngState = holder[0];
                    work.vars.put(var, value);
                }
                case "fail" -> throw new ActionFailure(
                        action.containsKey("message") ? String.valueOf(action.get("message")) : "action failed");
                default -> throw new ActionFailure("unknown action type: " + type);
            }
        }
    }
}
