package com.replayroom.core;

import com.replayroom.expr.Expr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Deterministic replay engine. External events are ordered by
 * (logical time, source priority, original sequence). Internal events raised
 * by actions are appended to the branch queue and therefore always run after
 * the event that produced them. Applying an event is transactional: when an
 * action fails, state changes, RNG advances and raised internal events are
 * rolled back, while the failure itself is recorded in the trace.
 */
public class Engine {
    /** Safety bound against runaway internal-event chains. */
    public static final long MAX_STEPS = 1_000_000;

    private final MachineDefinition definition;

    public Engine(MachineDefinition definition) {
        this.definition = definition;
    }

    public Comparator<LogEvent> externalOrder() {
        return Comparator
                .comparingLong((LogEvent e) -> e.time)
                .thenComparingInt(e -> definition.priorityOf(e.source))
                .thenComparing((LogEvent e) -> e.source)
                .thenComparingLong(e -> e.seq)
                .thenComparing(e -> e.id);
    }

    /** Advance the branch by exactly one event. Returns null when nothing remains. */
    public TraceRecord step(Branch branch) {
        LogEvent event;
        boolean internal;
        if (!branch.internalQueue.isEmpty()) {
            event = branch.internalQueue.remove(0);
            internal = true;
        } else if (!branch.pendingExternal.isEmpty()) {
            event = branch.pendingExternal.remove(0);
            internal = false;
        } else {
            return null;
        }
        if (branch.trace.size() >= MAX_STEPS) {
            throw new IllegalStateException("step limit exceeded; possible internal event loop");
        }

        StateSnapshot before = branch.snapshot.copy();
        TraceRecord record = new TraceRecord();
        record.step = branch.trace.size();
        record.eventId = event.id;
        record.eventName = event.name;
        record.internal = internal;
        record.eventTime = event.time;
        record.eventSource = event.source;
        record.before = before.toMap();

        MachineDefinition.EventDef eventDef = definition.findEvent(event.name);
        if (eventDef == null) {
            record.status = TraceRecord.UNMATCHED;
            record.after = branch.snapshot.toMap();
            branch.trace.add(record);
            return record;
        }

        LcgRandom rng = new LcgRandom(branch.snapshot.rngState);
        Expr.Context ctx = context(branch.snapshot, event, rng);

        // The condition reads the snapshot taken before this event is handled.
        boolean conditionMet;
        try {
            conditionMet = Expr.evalBool(
                    eventDef.condition == null || eventDef.condition.isBlank() ? "true" : eventDef.condition, ctx);
        } catch (RuntimeException e) {
            conditionMet = false;
            record.error = "condition error: " + e.getMessage();
        }

        if (!conditionMet) {
            branch.snapshot.rngState = rng.state();
            record.status = TraceRecord.SKIPPED;
            record.after = branch.snapshot.toMap();
            branch.trace.add(record);
            return record;
        }

        List<LogEvent> raised = new ArrayList<>();
        List<Object> producedOutputs = new ArrayList<>();
        try {
            for (MachineDefinition.Action action : eventDef.actions) {
                apply(action, branch, event, ctx, rng, raised, producedOutputs);
            }
        } catch (ActionFailure failure) {
            // Roll back every effect of this event: state, variables, RNG and
            // the internal events it derived. The failure stays in the trace.
            branch.snapshot.state = before.state;
            branch.snapshot.variables = before.variables;
            branch.snapshot.rngState = before.rngState;
            record.status = TraceRecord.FAILED;
            record.error = failure.getMessage();
            record.outputs = producedOutputs;
            record.after = branch.snapshot.toMap();
            branch.trace.add(record);
            return record;
        }

        branch.snapshot.rngState = rng.state();
        for (LogEvent r : raised) {
            branch.internalQueue.add(r);
            record.raisedEvents.add(r.id);
        }
        branch.outputs.addAll(producedOutputs);
        record.outputs = producedOutputs;
        record.status = TraceRecord.APPLIED;
        record.after = branch.snapshot.toMap();
        branch.trace.add(record);
        return record;
    }

    /** Replay until the branch is exhausted or the step budget is hit. */
    public List<TraceRecord> runToEnd(Branch branch, long maxSteps) {
        List<TraceRecord> records = new ArrayList<>();
        long budget = Math.min(maxSteps, MAX_STEPS);
        while (budget-- > 0) {
            TraceRecord record = step(branch);
            if (record == null) {
                return records;
            }
            records.add(record);
        }
        if (!branch.exhausted()) {
            throw new IllegalStateException("step limit exceeded; possible internal event loop");
        }
        return records;
    }

    private Expr.Context context(StateSnapshot snapshot, LogEvent event, LcgRandom rng) {
        return new Expr.Context() {
            @Override
            public Object variable(String name) {
                return snapshot.variables.get(name);
            }

            @Override
            public String state() {
                return snapshot.state;
            }

            @Override
            public Object arg(String name) {
                return event.args == null ? null : Expr.lookupPath(event.args, name);
            }

            @Override
            public LcgRandom rng() {
                return rng;
            }
        };
    }

    private void apply(MachineDefinition.Action action, Branch branch, LogEvent event,
                       Expr.Context ctx, LcgRandom rng, List<LogEvent> raised, List<Object> outputs) {
        StateSnapshot snapshot = branch.snapshot;
        String type = action.type == null ? "" : action.type;
        try {
            switch (type) {
                case "set" -> {
                    if (action.var == null || action.var.isBlank()) {
                        throw new ActionFailure("set action requires 'var'");
                    }
                    snapshot.variables.put(action.var, Expr.eval(action.expr == null ? "null" : action.expr, ctx));
                }
                case "transition" -> {
                    if (action.to == null || action.to.isBlank()) {
                        throw new ActionFailure("transition action requires 'to'");
                    }
                    snapshot.state = action.to;
                }
                case "emit" -> {
                    if (action.expr != null) {
                        outputs.add(Expr.eval(action.expr, ctx));
                    } else {
                        outputs.add(action.value);
                    }
                }
                case "raise" -> {
                    if (action.event == null || action.event.isBlank()) {
                        throw new ActionFailure("raise action requires 'event'");
                    }
                    LogEvent derived = new LogEvent();
                    derived.id = "I" + branch.trace.size() + "." + (raised.size() + 1);
                    derived.time = event.time;
                    derived.source = event.source;
                    derived.seq = event.seq;
                    derived.name = action.event;
                    derived.args = action.args == null ? new java.util.LinkedHashMap<>()
                            : new java.util.LinkedHashMap<>(action.args);
                    derived.internal = true;
                    raised.add(derived);
                }
                case "fail" -> throw new ActionFailure(
                        action.message == null ? "action failed" : action.message);
                default -> throw new ActionFailure("unknown action type: " + type);
            }
        } catch (ActionFailure failure) {
            throw failure;
        } catch (RuntimeException e) {
            throw new ActionFailure("action '" + type + "' failed: " + e.getMessage());
        }
    }

    /** Signals that applying an action failed and the event must roll back. */
    public static class ActionFailure extends RuntimeException {
        public ActionFailure(String message) {
            super(message);
        }
    }
}
