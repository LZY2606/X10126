package replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class StateMachine {
    private final Models.Definition definition;

    StateMachine(Models.Definition definition) {
        this.definition = definition;
    }

    StepOutcome step(StepInput input) {
        Map<String, Object> beforeVariables = deep(input.variables);
        Map<String, Object> workingVariables = deep(input.variables);
        long randomState = input.randomState;
        String state = input.state;
        Models.EventEnvelope event = input.event;

        Models.StepRecord record = new Models.StepRecord();
        record.step = input.stepNumber;
        record.event = cloneEvent(event);
        record.fromState = state;
        record.toState = state;
        record.beforeHash = stateHash(state, beforeVariables);
        record.beforeSnapshot = new LinkedHashMap<>();
        record.beforeSnapshot.put("state", state);
        record.beforeSnapshot.put("vars", beforeVariables);

        Map<String, Object> eventJson = event.toIdentityJson();
        Expression.EvalContext conditionContext = context(state, beforeVariables, eventJson);
        Models.Transition selected = null;
        for (Models.Transition transition : definition.transitions) {
            if (matchesState(transition.from, state) && matchesEvent(transition.event, event.type)
                    && Expression.matches(transition.condition, conditionContext)) {
                selected = transition;
                break;
            }
        }

        List<Models.EventEnvelope> emitted = new ArrayList<>();
        List<Map<String, Object>> outputs = new ArrayList<>();
        String failure = null;
        if (selected != null) {
            record.matched = true;
            record.transition = selected.name;
            int actionIndex = 0;
            for (Models.Action action : selected.actions) {
                Expression.EvalContext actionContext = context(state, workingVariables, eventJson);
                try {
                    if (action instanceof Models.SetAction set) {
                        Object value = Expression.evaluate(set.value(), actionContext);
                        setTarget(workingVariables, set.target(), value);
                    } else if (action instanceof Models.RandomIntAction random) {
                        long min = ((Number) Expression.evaluate(random.min(), actionContext)).longValue();
                        long max = ((Number) Expression.evaluate(random.max(), actionContext)).longValue();
                        if (max <= min) throw new IllegalArgumentException("randomInt max must be greater than min");
                        long value = min + nextPositive(randomState) % (max - min);
                        randomState = nextRandomState(randomState);
                        setTarget(workingVariables, random.target(), value);
                    } else if (action instanceof Models.OutputAction output) {
                        Map<String, Object> produced = new LinkedHashMap<>();
                        produced.put("name", output.name());
                        if (output.payload() != null) produced.put("payload", Expression.evaluate(output.payload(), actionContext));
                        outputs.add(produced);
                    } else if (action instanceof Models.EmitAction emit) {
                        Models.EventEnvelope internal = new Models.EventEnvelope();
                        internal.id = event.id + "#" + emitted.size() + "-" + actionIndex;
                        internal.time = event.time + ((Number) Expression.evaluate(String.valueOf(emit.timeDelta()), actionContext)).longValue();
                        internal.source = emit.source();
                        internal.sequence = ((Number) Expression.evaluate(String.valueOf(emit.sequence()), actionContext)).longValue();
                        internal.type = emit.event();
                        internal.payload = emit.payload() == null ? null : Expression.evaluate(emit.payload(), actionContext);
                        internal.internal = true;
                        internal.parentEventId = event.id;
                        internal.internalOrder = input.internalOrderBase + actionIndex;
                        emitted.add(internal);
                    } else if (action instanceof Models.FailAction fail) {
                        throw new ActionFailure(
                                String.valueOf(Expression.evaluate(fail.error(), actionContext)),
                                fail.payload() == null ? null : Expression.evaluate(fail.payload(), actionContext));
                    }
                } catch (ActionFailure e) {
                    failure = e.getMessage();
                    Map<String, Object> failureOutput = new LinkedHashMap<>();
                    failureOutput.put("name", "actionFailed");
                    failureOutput.put("error", e.getMessage());
                    if (e.payload != null) failureOutput.put("payload", e.payload);
                    outputs.add(failureOutput);
                    break;
                }
                actionIndex++;
            }
        }

        if (failure == null && selected != null && !selected.to.isBlank() && !"*".equals(selected.to)) {
            state = selected.to;
        } else if (failure != null) {
            state = input.state;
            workingVariables = deep(beforeVariables);
            emitted = new ArrayList<>();
            randomState = input.randomState;
        }

        Map<String, Object> afterVariables = deep(workingVariables);
        record.toState = state;
        record.failed = failure != null;
        record.error = failure;
        record.outputs = outputs;
        record.emitted = emitted;
        record.afterSnapshot = new LinkedHashMap<>();
        record.afterSnapshot.put("state", state);
        record.afterSnapshot.put("vars", afterVariables);
        record.changes = diff(record.beforeSnapshot, record.afterSnapshot);
        record.afterHash = stateHash(state, afterVariables);
        record.stateHash = record.afterHash;

        StepOutcome outcome = new StepOutcome();
        outcome.record = record;
        outcome.state = state;
        outcome.variables = afterVariables;
        outcome.randomState = randomState;
        outcome.emitted = emitted;
        return outcome;
    }

    static String stateHash(String state, Map<String, Object> variables) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("state", state);
        snapshot.put("vars", variables);
        return Models.sha256(Json.writeCanonical(snapshot));
    }

    static List<Models.EventEnvelope> sortExternal(List<Models.EventEnvelope> events, Map<String, Integer> priorities) {
        List<Models.EventEnvelope> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong((Models.EventEnvelope event) -> event.time)
                .thenComparing(event -> -priorities.getOrDefault(event.source, 0))
                .thenComparingLong(event -> event.sequence)
                .thenComparing(event -> event.id));
        return sorted;
    }

    private Expression.EvalContext context(String state, Map<String, Object> variables, Map<String, Object> event) {
        Map<String, Object> vars = deep(variables);
        return name -> switch (name) {
            case "state" -> state;
            case "vars" -> vars;
            case "event" -> event;
            case "data" -> event.get("payload");
            default -> throw new IllegalArgumentException("Unknown variable: " + name);
        };
    }

    private boolean matchesState(String pattern, String state) {
        return "*".equals(pattern) || pattern.isBlank() || Objects.equals(pattern, state);
    }

    private boolean matchesEvent(String pattern, String type) {
        return "*".equals(pattern) || pattern.isBlank() || Objects.equals(pattern, type);
    }

    private static long nextRandomState(long value) {
        long z = value + 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static long nextPositive(long current) {
        long next = nextRandomState(current);
        return next & Long.MAX_VALUE;
    }

    @SuppressWarnings("unchecked")
    private static void setTarget(Map<String, Object> root, String target, Object value) {
        String path = target.startsWith("vars.") ? target.substring(5) : target;
        if (path.isBlank() || "state".equals(path)) throw new IllegalArgumentException("Invalid set target: " + target);
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(parts[parts.length - 1], value);
    }

    private static List<Map<String, Object>> diff(Object before, Object after) {
        List<Map<String, Object>> changes = new ArrayList<>();
        diff("$", before, after, changes);
        return changes;
    }

    private static void diff(String path, Object before, Object after, List<Map<String, Object>> changes) {
        if (Json.writeCanonical(before).equals(Json.writeCanonical(after))) return;
        if (before instanceof Map<?, ?> beforeMap && after instanceof Map<?, ?> afterMap) {
            for (Object key : beforeMap.keySet()) {
                if (afterMap.containsKey(key)) diff(path + "." + key, beforeMap.get(key), afterMap.get(key), changes);
                else addChange(path + "." + key, beforeMap.get(key), null, changes);
            }
            for (Object key : afterMap.keySet()) {
                if (!beforeMap.containsKey(key)) addChange(path + "." + key, null, afterMap.get(key), changes);
            }
        } else {
            addChange(path, before, after, changes);
        }
    }

    private static void addChange(String path, Object before, Object after, List<Map<String, Object>> changes) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("path", path);
        change.put("before", before);
        change.put("after", after);
        changes.add(change);
    }

    private static Map<String, Object> deep(Map<String, Object> value) {
        return Models.cloneJson(value);
    }

    private static Models.EventEnvelope cloneEvent(Models.EventEnvelope event) {
        Models.EventEnvelope copy = new Models.EventEnvelope();
        Object json = Json.parse(Json.write(event.toIdentityJson()));
        Map<String, Object> map = Json.object(json);
        return EventCodec.fill(copy, map);
    }

    static final class StepInput {
        int stepNumber;
        String state;
        Map<String, Object> variables;
        long randomState;
        Models.EventEnvelope event;
        int internalOrderBase;
    }

    static final class StepOutcome {
        Models.StepRecord record;
        String state;
        Map<String, Object> variables;
        long randomState;
        List<Models.EventEnvelope> emitted;
    }

    private static final class ActionFailure extends RuntimeException {
        private final Object payload;

        private ActionFailure(String message, Object payload) {
            super(message);
            this.payload = payload;
        }
    }
}
