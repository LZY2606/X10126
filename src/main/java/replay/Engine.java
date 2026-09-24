package replay;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Engine {
    static final int INTERNAL_PRIORITY = 1_000_000;
    private static final long RNG_MULTIPLIER = 6364136223846793005L;
    private static final long RNG_INCREMENT = 1442695040888963407L;

    private final MachineDefinition definition;

    private String branchId;
    private String parentCheckpointId;
    private String state;
    private long randomState;
    private Map<String, Object> data;
    private List<EventRecord> pending;
    private List<OutputRecord> outputs;
    private List<TraceRecord> traces;
    private List<String> externalIds;
    private long stepCount;
    private long emissionCount;

    static Engine initial(MachineDefinition definition, String branchId, List<EventRecord> externalEvents) {
        Engine engine = new Engine(definition);
        engine.branchId = branchId;
        engine.parentCheckpointId = "";
        engine.state = definition.initialState();
        engine.randomState = definition.randomSeed();
        engine.data = Json.object(Json.copy(definition.initialData()), "initialData");
        engine.pending = new ArrayList<>(externalEvents);
        engine.outputs = new ArrayList<>();
        engine.traces = new ArrayList<>();
        engine.externalIds = new ArrayList<>();
        engine.stepCount = 0L;
        engine.emissionCount = 0L;
        engine.sortPending();
        return engine;
    }

    static Engine restore(MachineDefinition definition, Checkpoint checkpoint) {
        if (!definition.fingerprint().equals(checkpoint.definitionFingerprint())) {
            throw new DefinitionMismatchException(checkpoint.definitionFingerprint(), definition.fingerprint());
        }
        Engine engine = new Engine(definition);
        engine.branchId = checkpoint.branchId();
        engine.parentCheckpointId = checkpoint.id();
        engine.state = checkpoint.state();
        engine.randomState = checkpoint.randomState();
        engine.data = Json.object(Json.copy(checkpoint.data()), "data");
        engine.pending = new ArrayList<>(checkpoint.pending().stream().map(event ->
                EventRecord.fromMap(Json.object(Json.copy(event.toMap()), "event"), true)).toList());
        engine.outputs = new ArrayList<>(checkpoint.outputs());
        engine.traces = new ArrayList<>(checkpoint.traces());
        engine.externalIds = new ArrayList<>(checkpoint.externalIds());
        engine.stepCount = checkpoint.stepCount();
        engine.emissionCount = checkpoint.emissionCount();
        engine.sortPending();
        return engine;
    }

    private Engine(MachineDefinition definition) {
        this.definition = definition;
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    EventRecord peek() {
        return pending.isEmpty() ? null : pending.get(0);
    }

    StepResult step(String reason) {
        if (pending.isEmpty()) {
            throw new IllegalStateException("no pending event");
        }
        EventRecord event = pending.remove(0);
        String beforeState = state;
        Map<String, Object> beforeData = Json.object(Json.copy(data), "beforeData");
        long beforeRandom = randomState;
        List<OutputRecord> beforeOutputs = List.copyOf(outputs);

        Transition selected = selectTransition(event);
        String failure = "";
        List<EventRecord> emitted = new ArrayList<>();
        if (selected != null) {
            try {
                applyTransition(event, selected, emitted);
            } catch (ActionFailureException ex) {
                state = beforeState;
                randomState = beforeRandom;
                data = beforeData;
                outputs = new ArrayList<>(beforeOutputs);
                emitted = List.of();
                failure = ex.getMessage();
            }
        }

        pending.addAll(emitted);
        sortPending();
        if (!event.internal()) {
            externalIds = new ArrayList<>(externalIds);
            externalIds.add(event.id());
        }
        stepCount++;

        String transitionFrom = selected == null ? "" : selected.from();
        String transitionTo = selected == null ? "" : selected.to();
        TraceRecord trace = new TraceRecord(traceId(event), event.id(), event.event(), event.tick(),
                event.internal(), beforeState, state, transitionFrom, transitionTo, selected != null,
                !failure.isEmpty(), failure, beforeData, Json.object(Json.copy(data), "afterData"),
                Json.object(Json.copy(event.payload()), "eventPayload"));
        traces = new ArrayList<>(traces);
        traces.add(trace);
        Checkpoint checkpoint = checkpoint(reason, false);
        return new StepResult(checkpoint, trace);
    }

    void appendExternal(EventRecord event) {
        if (event.internal()) throw new IllegalArgumentException("external event expected");
        pending = new ArrayList<>(pending);
        pending.add(event);
        sortPending();
    }

    void beginBranch(String newBranchId, String parentCheckpointId) {
        this.branchId = newBranchId;
        this.parentCheckpointId = parentCheckpointId;
    }

    Checkpoint checkpoint(String reason, boolean manual) {
        String normalizedReason = reason == null || reason.isBlank() ? "step" : reason;
        Checkpoint candidate = new Checkpoint("", branchId, parentCheckpointId, definition.fingerprint(),
                normalizedReason, manual, state, randomState,
                Json.object(Json.copy(data), "data"),
                pending.stream().map(this::copyEvent).toList(),
                outputs.stream().map(output ->
                        OutputRecord.fromMap(Json.object(Json.copy(output.toMap()), "output"))).toList(),
                traces.stream().map(trace -> TraceRecord.fromMap(Json.object(Json.copy(trace.toMap()), "trace")))
                        .toList(),
                List.copyOf(externalIds), stepCount, emissionCount);
        String id = "cp_" + Hashes.shortHash(withoutId(candidate.toMap()));
        return new Checkpoint(id, candidate.branchId(), candidate.parentId(), candidate.definitionFingerprint(),
                candidate.reason(), candidate.manual(), candidate.state(), candidate.randomState(),
                candidate.data(), candidate.pending(), candidate.outputs(), candidate.traces(),
                candidate.externalIds(), candidate.stepCount(), candidate.emissionCount());
    }

    private Transition selectTransition(EventRecord event) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("state", state);
        context.put("data", data);
        context.put("event", event.payload());
        for (Transition transition : definition.transitions()) {
            boolean eventMatches = "*".equals(transition.event()) || transition.event().equals(event.event());
            boolean fromMatches = "*".equals(transition.from()) || transition.from().equals(state);
            if (eventMatches && fromMatches && ExpressionParser.evaluate(transition.condition(), context)) {
                return transition;
            }
        }
        return null;
    }

    private void applyTransition(EventRecord event, Transition transition, List<EventRecord> emitted) {
        Map<String, Object> candidateData = Json.object(Json.copy(data), "candidateData");
        List<OutputRecord> candidateOutputs = new ArrayList<>(outputs);
        List<EventRecord> candidateEmitted = new ArrayList<>();
        long candidateRandom = randomState;

        for (int actionIndex = 0; actionIndex < transition.actions().size(); actionIndex++) {
            Action action = transition.actions().get(actionIndex);
            switch (action.type()) {
                case "set" -> {
                    Object value = resolveValue(action, candidateData, event, candidateRandom);
                    Paths.set(candidateData, action.path(), value);
                }
                case "random" -> {
                    long nextSeed = nextRandom(candidateRandom);
                    long drawn = draw(nextSeed, action);
                    candidateRandom = nextSeed;
                    Paths.set(candidateData, action.path(), BigDecimal.valueOf(drawn));
                }
                case "output" -> {
                    Map<String, Object> value = outputValue(action, event);
                    candidateOutputs.add(new OutputRecord(action.output(), event.tick(), event.id(),
                            "tr_" + Hashes.shortHash(List.of(event.id(), candidateOutputs.size(), action.output())),
                            value));
                }
                case "emit" -> candidateEmitted.add(internalEvent(event, transition, action, actionIndex));
                case "fail" -> throw new ActionFailureException(action.output());
                default -> throw new IllegalArgumentException("unsupported action: " + action.type());
            }
        }
        data = candidateData;
        outputs = candidateOutputs;
        emitted.addAll(candidateEmitted);
        randomState = candidateRandom;
        state = "*".equals(transition.from()) ? state : transition.to();
    }

    private Object resolveValue(Action action, Map<String, Object> candidateData, EventRecord event, long seed) {
        Object value = action.value();
        if (value instanceof String text && text.startsWith("${") && text.endsWith("}")) {
            String expression = text.substring(2, text.length() - 1);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("state", state);
            context.put("data", candidateData);
            context.put("event", event.payload());
            context.put("seed", seed);
            return ExpressionParser.compile(expression).eval(context);
        }
        return Json.copy(value);
    }

    private long nextRandom(long current) {
        return current * RNG_MULTIPLIER + RNG_INCREMENT;
    }

    private long draw(long seed, Action action) {
        long min = Json.optionalLong(action.payload(), "min", 0L);
        long max = Json.optionalLong(action.payload(), "max", 1L);
        if (max < min) throw new ActionFailureException("random max must be >= min");
        long range = max - min + 1L;
        long positive = seed >>> 1;
        return min + Long.remainderUnsigned(positive, range);
    }

    private Map<String, Object> outputValue(Action action, EventRecord event) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (action.value() != null) {
            Object value = resolveValue(action, data, event, randomState);
            if (value instanceof Map<?, ?> map) {
                map.forEach((key, item) -> result.put(String.valueOf(key), Json.copy(item)));
            } else {
                result.put("value", value);
            }
        }
        result.putAll(Json.object(Json.copy(action.payload()), "payload"));
        return result;
    }

    private EventRecord internalEvent(EventRecord parent, Transition transition, Action action, int actionIndex) {
        long tick = parent.tick() + action.delayTicks();
        long sequence = emissionCount++;
        Map<String, Object> payload = Json.object(Json.copy(action.payload()), "payload");
        payload.put("parentEventId", parent.id());
        payload.put("origin", parent.event());
        EventRecord candidate = new EventRecord("", action.eventName(), tick, INTERNAL_PRIORITY,
                sequence, true, payload);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("event", candidate.event());
        identity.put("tick", candidate.tick());
        identity.put("sourcePriority", candidate.sourcePriority());
        identity.put("sourceSequence", candidate.sourceSequence());
        identity.put("payload", candidate.payload());
        identity.put("parent", parent.id());
        identity.put("actionIndex", actionIndex);
        identity.put("transition", List.of(transition.from(), transition.event(), transition.to()));
        return new EventRecord("in_" + Hashes.shortHash(identity), candidate.event(), candidate.tick(),
                candidate.sourcePriority(), candidate.sourceSequence(), true, candidate.payload());
    }

    private String traceId(EventRecord event) {
        return "tr_" + Hashes.shortHash(List.of(branchId, parentCheckpointId, event.id(), stepCount, traces.size()));
    }

    private EventRecord copyEvent(EventRecord event) {
        return EventRecord.fromMap(Json.object(Json.copy(event.toMap()), "event"), true);
    }

    private void sortPending() {
        pending.sort(Comparator.comparingLong(EventRecord::tick)
                .thenComparingInt(EventRecord::sourcePriority)
                .thenComparingLong(EventRecord::sourceSequence)
                .thenComparing(EventRecord::id));
    }

    private Map<String, Object> withoutId(Map<String, Object> map) {
        Map<String, Object> copy = Json.object(Json.copy(map), "checkpoint");
        copy.remove("id");
        return copy;
    }

    record StepResult(Checkpoint checkpoint, TraceRecord trace) {
    }
}

final class ActionFailureException extends RuntimeException {
    ActionFailureException(String message) {
        super(message);
    }
}

final class DefinitionMismatchException extends RuntimeException {
    private final String checkpointFingerprint;
    private final String currentFingerprint;

    DefinitionMismatchException(String checkpointFingerprint, String currentFingerprint) {
        super("checkpoint definition " + checkpointFingerprint + " cannot be replayed with definition "
                + currentFingerprint);
        this.checkpointFingerprint = checkpointFingerprint;
        this.currentFingerprint = currentFingerprint;
    }

    String checkpointFingerprint() {
        return checkpointFingerprint;
    }

    String currentFingerprint() {
        return currentFingerprint;
    }
}
