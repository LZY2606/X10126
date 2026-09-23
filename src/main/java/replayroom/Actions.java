package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Actions {
    private Actions() {
    }

    record Result(Map<String, Object> vars, long rngState, List<Map<String, Object>> outputs, List<Models.EventEnvelope> events) {
    }

    static void validate(Models.Action action, Map<String, Object> context) {
        switch (action.type()) {
            case "set", "add", "random", "emit", "emitEvent", "fail" -> evaluateAction(action, context, 1L, 1L);
            default -> throw new IllegalArgumentException("Unknown action type " + action.type());
        }
    }

    static Result execute(Models.Action action, Map<String, Object> vars, long rngState, Map<String, Object> event, long stepIndex, long actionIndex, String eventId) {
        Map<String, Object> context = Expression.context(vars, event, rngState);
        if (action.when() != null && !action.when().isBlank() && !Expression.condition(action.when(), context)) {
            return new Result(vars, rngState, List.of(), List.of());
        }
        return evaluateAction(action, context, stepIndex, actionIndex, eventId);
    }

    private static Result evaluateAction(Models.Action action, Map<String, Object> context, long stepIndex, long actionIndex) {
        return evaluateAction(action, context, stepIndex, actionIndex, "validate");
    }

    private static Result evaluateAction(Models.Action action, Map<String, Object> context, long stepIndex, long actionIndex, String parentEventId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = new LinkedHashMap<>((Map<String, Object>) context.get("state"));
        long rngState = ((Number) context.getOrDefault("rngState", 0L)).longValue();
        List<Map<String, Object>> outputs = new ArrayList<>();
        List<Models.EventEnvelope> events = new ArrayList<>();
        switch (action.type()) {
            case "set" -> {
                String name = requireText(action.params(), "name");
                vars.put(name, value(action.params(), "value", context));
            }
            case "add" -> {
                String name = requireText(action.params(), "name");
                Object delta = value(action.params(), "delta", context);
                Object current = vars.getOrDefault(name, 0L);
                if (!(current instanceof Number) || !(delta instanceof Number)) {
                    throw new IllegalArgumentException("add action requires numeric state and delta");
                }
                vars.put(name, numeric(current, delta, "+"));
            }
            case "random" -> {
                String name = requireText(action.params(), "name");
                long bound = longValue(action.params().getOrDefault("bound", 100L), context, "bound");
                if (bound <= 0) throw new IllegalArgumentException("random bound must be positive");
                long nextRng = nextRandom(rngState, stepIndex, actionIndex);
                vars.put(name, Math.floorMod(nextRng, bound));
                rngState = nextRng;
            }
            case "emit" -> {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("channel", requireText(action.params(), "channel"));
                output.put("payload", value(action.params(), "payload", context));
                outputs.add(output);
            }
            case "emitEvent" -> {
                Map<String, Object> sourceMap = Json.objectField(action.params(), "source");
                @SuppressWarnings("unchecked")
                Map<String, Object> currentEvent = (Map<String, Object>) context.get("event");
                Object defaultTime = currentEvent == null ? 0L : currentEvent.getOrDefault("time", 0L);
                Object timeValue = action.params().containsKey("time") ? action.params().get("time") : defaultTime;
                long time = longValue(timeValue, context, "time");
                String source = sourceMap.isEmpty() ? "internal" : String.valueOf(value(sourceMap, "name", context));
                long seq = sourceMap.containsKey("seq") ? longValue(sourceMap.get("seq"), context, "source.seq") : 0L;
                int priority = sourceMap.containsKey("priority") ? (int) longValue(sourceMap.get("priority"), context, "source.priority") : 100;
                String type = requireText(action.params(), "eventType");
                Map<String, Object> payload = action.params().containsKey("payload")
                    ? Json.object(value(action.params(), "payload", context))
                    : new LinkedHashMap<>();
                String id = parentEventId + ".i" + stepIndex + "." + actionIndex;
                events.add(new Models.EventEnvelope(id, type, time, source, seq, payload, true, parentEventId, generationOf(parentEventId) + 1, priority, 0L));
            }
            case "fail" -> {
                String message = action.params().containsKey("message")
                    ? String.valueOf(value(action.params(), "message", context))
                    : "Action failed";
                throw new ActionFailureException(message);
            }
            default -> throw new ActionFailureException("Unknown action type " + action.type());
        }
        return new Result(vars, rngState, outputs, events);
    }

    private static long nextRandom(long current, long stepIndex, long actionIndex) {
        long value = current ^ Long.rotateLeft(stepIndex + 1L, 17) ^ Long.rotateLeft(actionIndex + 1L, 31) ^ 0x9e3779b97f4a7c15L;
        value ^= (value >>> 30);
        value *= 0xbf58476d1ce4e5b9L;
        value ^= (value >>> 27);
        value *= 0x94d049bb133111ebL;
        value ^= (value >>> 31);
        return value;
    }

    private static long generationOf(String id) {
        int index = id.lastIndexOf(".i");
        if (index < 0) return 0;
        try {
            String tail = id.substring(index + 2);
            return Long.parseLong(tail.substring(0, tail.indexOf('.'))) + 1;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static Object numeric(Object left, Object right, String operator) {
        double a = ((Number) left).doubleValue();
        double b = ((Number) right).doubleValue();
        double result = operator.equals("+") ? a + b : a - b;
        if (left instanceof Double || right instanceof Double || result % 1 != 0) return result;
        return (long) result;
    }

    private static Object value(Map<String, Object> map, String key, Map<String, Object> context) {
        Object raw = map.get(key);
        if (raw instanceof String string) {
            return Expression.evaluate(string, context);
        }
        return raw;
    }

    private static long longValue(Object raw, Map<String, Object> context, String name) {
        Object evaluated = raw instanceof String string ? Expression.evaluate(string, context) : raw;
        if (!(evaluated instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        return number.longValue();
    }

    private static String requireText(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return String.valueOf(value);
    }

    static class ActionFailureException extends RuntimeException {
        ActionFailureException(String message) {
            super(message);
        }
    }
}
