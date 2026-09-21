package com.replayroom.engine;

import com.replayroom.json.Json;
import com.replayroom.model.Events;
import com.replayroom.model.ModelAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless transition engine. All mutable replay state lives in a
 * {@link Frame} supplied by the caller, which makes checkpointing and
 * branch forking a matter of copying/restoring frame snapshots.
 */
public final class Engine {

    private final Definition definition;

    public Engine(Definition definition) {
        this.definition = definition;
    }

    public Definition definition() {
        return definition;
    }

    /**
     * Processes exactly one event against the frame.
     *
     * Conditions read the pre-event snapshot. When a transition matches,
     * its actions run in one transaction: any failure restores state data,
     * machine state and RNG state, and discards the internal events and
     * outputs produced inside the failed transaction. The failure itself is
     * reported in the result so it can be recorded in the trace.
     */
    public StepResult step(Frame frame, Map<String, Object> event, long internalCounter) {
        String stateBefore = frame.state();
        Map<String, Object> dataBefore = deepCopy(frame.data());
        StepResult result = new StepResult();
        long rngBefore = frame.rng().state();
        String eventType = String.valueOf(event.getOrDefault("type", ""));
        result.rngBefore = rngBefore;
        result.rngAfter = rngBefore;

        Map<String, Object> matched = matchTransition(stateBefore, eventType, frame.data(), event, frame.rng());

        result.event = event;
        result.stateBefore = stateBefore;
        result.dataBefore = dataBefore;
        result.stateAfter = stateBefore;
        result.dataAfter = deepCopy(dataBefore);
        result.matched = matched != null;
        result.transition = matched;

        if (matched == null) {
            result.status = "no_transition";
            return result;
        }

        String target = String.valueOf(matched.get("to"));
        List<Map<String, Object>> actions = ModelAccess.objectListField(matched, "actions");

        Map<String, Object> workingData = deepCopy(frame.data());
        List<Map<String, Object>> outputs = new ArrayList<>();
        List<Map<String, Object>> internalEvents = new ArrayList<>();
        Rng rng = frame.rng();

        try {
            for (Map<String, Object> action : actions) {
                runAction(action, event, workingData, outputs, internalEvents, rng, internalCounter);
            }
        } catch (EvalException | ActionFailure failure) {
            frame.restoreData(dataBefore);
            rng.restore(rngBefore);
            result.status = "failed";
            result.rngAfter = rngBefore;
            result.failure = failure.getMessage();
            result.stateAfter = stateBefore;
            result.dataAfter = deepCopy(dataBefore);
            result.outputs = List.of();
            result.emittedInternal = List.of();
            return result;
        }

        String stateAfter = "*".equals(target) ? stateBefore : target;
        frame.commit(stateAfter, workingData);
        result.status = "ok";
        result.stateAfter = stateAfter;
        result.rngAfter = rng.state();
        result.dataAfter = deepCopy(workingData);
        result.outputs = List.copyOf(outputs);
        result.emittedInternal = List.copyOf(internalEvents);
        return result;
    }

    private Map<String, Object> matchTransition(String currentState, String eventType,
                                                Map<String, Object> data,
                                                Map<String, Object> event, Rng rng) {
        for (Map<String, Object> transition : definition.transitions()) {
            String from = String.valueOf(transition.get("from"));
            String transitionEvent = String.valueOf(transition.get("event"));
            boolean fromMatches = "*".equals(from) || from.equals(currentState);
            boolean eventMatches = "*".equals(transitionEvent) || transitionEvent.equals(eventType);
            if (!fromMatches || !eventMatches) {
                continue;
            }
            Object when = transition.get("when");
            if (when instanceof String expression && !expression.isBlank()) {
                EvalContext context = new EvalContext(
                        data,
                        ModelAccess.objectField(event, "data"),
                        Events.time(event),
                        Events.seq(event),
                        Events.source(event),
                        eventType,
                        Events.id(event),
                        rng
                );
                boolean conditionResult;
                try {
                    conditionResult = Evaluator.evaluateBoolean(expression, context);
                } catch (EvalException e) {
                    throw new EvalException("condition error in transition "
                            + from + " -> " + transitionEvent + ": " + e.getMessage());
                }
                if (!conditionResult) {
                    continue;
                }
            }
            return transition;
        }
        return null;
    }

    private void runAction(Map<String, Object> action,
                           Map<String, Object> event,
                           Map<String, Object> workingData,
                           List<Map<String, Object>> outputs,
                           List<Map<String, Object>> internalEvents,
                           Rng rng,
                           long internalCounter) {
        String type = String.valueOf(action.get("type"));
        switch (type) {
            case "set" -> runSet(action, event, workingData, rng);
            case "output" -> runOutput(action, event, workingData, outputs, rng);
            case "emit" -> runEmit(action, event, workingData, internalEvents, rng, internalCounter);
            case "fail" -> {
                Object message = action.get("message");
                String text = message instanceof String expression && !expression.isBlank()
                        ? String.valueOf(Evaluator.evaluate(expression,
                                contextFor(event, workingData, rng)))
                        : (message == null ? "explicit fail action" : String.valueOf(message));
                throw new ActionFailure(text);
            }
            default -> throw new ActionFailure("unknown action type '" + type + "'");
        }
    }

    private void runSet(Map<String, Object> action, Map<String, Object> event,
                        Map<String, Object> workingData, Rng rng) {
        String path = String.valueOf(action.get("path"));
        Object rawValue = action.get("value");
        Object evaluated = evaluateActionValue(rawValue, event, workingData, rng);
        List<String> segments = splitPath(path);
        if (segments.isEmpty()) {
            throw new ActionFailure("set path cannot be empty");
        }
        Map<String, Object> target = workingData;
        for (int i = 0; i < segments.size() - 1; i++) {
            Object child = target.get(segments.get(i));
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                target.put(segments.get(i), child);
            }
            target = ModelAccess.asObject(child);
        }
        target.put(segments.get(segments.size() - 1), evaluated);
    }

    private void runOutput(Map<String, Object> action, Map<String, Object> event,
                           Map<String, Object> workingData,
                           List<Map<String, Object>> outputs, Rng rng) {
        String name = String.valueOf(action.get("name"));
        Object rawData = action.get("data");
        Object outputData = rawData == null ? new LinkedHashMap<String, Object>()
                : evaluateActionValue(rawData, event, workingData, rng);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("name", name);
        output.put("data", outputData);
        outputs.add(output);
    }

    private void runEmit(Map<String, Object> action, Map<String, Object> event,
                         Map<String, Object> workingData,
                         List<Map<String, Object>> internalEvents,
                         Rng rng, long internalCounter) {
        String eventType = String.valueOf(action.get("event"));
        Object rawData = action.get("data");
        Object eventData = rawData == null ? new LinkedHashMap<String, Object>()
                : evaluateActionValue(rawData, event, workingData, rng);

        long eventTime = Events.time(event);
        if (action.get("time") != null) {
            Object timeValue = evaluateActionValue(action.get("time"), event, workingData, rng);
            if (!(timeValue instanceof Number number)) {
                throw new ActionFailure("emit time must evaluate to a number");
            }
            eventTime = number.longValue();
        }

        Map<String, Object> internal = new LinkedHashMap<>();
        internal.put("id", Events.INTERNAL_SOURCE + ":" + eventType + ":" + internalCounter);
        internal.put("type", eventType);
        internal.put("time", eventTime);
        internal.put("source", Events.INTERNAL_SOURCE);
        internal.put("seq", internalCounter);
        internal.put("internal", true);
        internal.put("parentId", Events.id(event));
        internal.put("data", eventData);
        internalEvents.add(internal);
    }

    private Object evaluateActionValue(Object rawValue, Map<String, Object> event,
                                       Map<String, Object> workingData, Rng rng) {
        if (rawValue instanceof String text && text.startsWith("=")) {
            String expression = text.substring(1);
            return Evaluator.evaluate(expression, contextFor(event, workingData, rng));
        }
        return Json.deepCopy(rawValue);
    }

    private EvalContext contextFor(Map<String, Object> event, Map<String, Object> workingData, Rng rng) {
        return new EvalContext(
                workingData,
                ModelAccess.objectField(event, "data"),
                Events.time(event),
                Events.seq(event),
                Events.source(event),
                String.valueOf(event.getOrDefault("type", "")),
                Events.id(event),
                rng
        );
    }

    private List<String> splitPath(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("\\.")) {
            if (!segment.isBlank()) {
                segments.add(segment.trim());
            }
        }
        return segments;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> value) {
        return (Map<String, Object>) Json.deepCopy(value);
    }
}
