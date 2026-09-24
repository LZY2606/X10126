package dev.replay.engine;

import dev.replay.crypto.Fingerprint;
import dev.replay.json.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic replay engine. Virtual logical time only; no sleeps anywhere.
 *
 * Ordering of the ready queue (stable, total):
 *   1. logical time ascending
 *   2. external events before internal events at the same time
 *   3. source priority descending (internal source priority = -1)
 *   4. original sequence number ascending (internal events: commit sequence)
 *   5. id ascending (final deterministic tie-break)
 *
 * Conditions read the snapshot taken before processing the current event.
 * Actions run against a working copy; on failure every state mutation, RNG
 * draw and emitted internal event is rolled back, while the failure record
 * still enters the trace.
 */
public final class Engine {
    public static final String INTERNAL_SOURCE = "internal";
    private static final int MAX_STEPS = 100_000;

    private final DefinitionView definition;
    private final List<Map<String, Object>> events;

    private String state;
    private Map<String, Object> data;
    private long rngState;
    private long nextInternalSeq;
    private int eventIndex;
    private final List<Map<String, Object>> pending;
    private final List<Map<String, Object>> trace;
    private String traceHash;

    public Engine(DefinitionView definition,
                  List<Map<String, Object>> externalEvents,
                  List<Map<String, Object>> pending,
                  List<Map<String, Object>> trace,
                  String state,
                  Map<String, Object> data,
                  long rngState,
                  long nextInternalSeq,
                  int eventIndex,
                  String traceHash) {
        this.definition = definition;
        this.events = externalEvents;
        this.pending = pending != null ? pending : new ArrayList<>();
        this.trace = trace != null ? trace : new ArrayList<>();
        this.state = state;
        this.data = data;
        this.rngState = rngState;
        this.nextInternalSeq = nextInternalSeq;
        this.eventIndex = eventIndex;
        this.traceHash = traceHash != null ? traceHash : genesisHash(definition, externalEvents);
        sortPending();
    }

    public static Engine fresh(DefinitionView definition, List<Map<String, Object>> externalEvents) {
        return new Engine(definition, externalEvents, new ArrayList<>(), new ArrayList<>(),
                definition.initialState(), copy(definition.initialData()),
                initialRng(definition.seed()), 1L, 0, null);
    }

    public boolean canStep() {
        return eventIndex < events.size() || !pending.isEmpty();
    }

    public String state() {
        return state;
    }

    public Map<String, Object> data() {
        return data;
    }

    public long rngState() {
        return rngState;
    }

    public long nextInternalSeq() {
        return nextInternalSeq;
    }

    public int eventIndex() {
        return eventIndex;
    }

    public List<Map<String, Object>> pending() {
        return pending;
    }

    public List<Map<String, Object>> trace() {
        return trace;
    }

    public String traceHash() {
        return traceHash;
    }

    public List<Map<String, Object>> events() {
        return events;
    }

    public DefinitionView definition() {
        return definition;
    }

    // ------------------------------------------------------------- stepping

    public Map<String, Object> step() {
        if (!canStep()) {
            throw new IllegalStateException("没有可处理的事件");
        }
        if (trace.size() >= MAX_STEPS) {
            throw new IllegalStateException("步数超过上限 " + MAX_STEPS + "（内部事件可能死循环）");
        }
        Map<String, Object> event = nextEvent();
        String stateBefore = state;
        Map<String, Object> dataBefore = copy(data);

        Map<String, Object> workingData = copy(data);
        long workingRng = rngState;
        long workingNextSeq = nextInternalSeq;
        List<Map<String, Object>> emitted = new ArrayList<>();

        Map<String, Object> record = baseRecord(event, stateBefore, dataBefore);
        try {
            Map<String, Object> transition = selectTransition(event, stateBefore, dataBefore);
            record.put("transition", transition != null ? transition.get("name") : null);
            if (transition != null) {
                Object target = transition.get("to");
                String stateAfter = target != null ? (String) target : stateBefore;
                List<Map<String, Object>> outputs = new ArrayList<>();
                runActions(transition, event, workingData, workingRngHolder(workingRng),
                        workingNextSeq, stateBefore, emitted, outputs);
                // commit: everything succeeded
                workingRng = rngHolderValue;
                workingNextSeq = seqHolderValue;
                this.data = workingData;
                this.state = stateAfter;
                this.rngState = workingRng;
                this.nextInternalSeq = workingNextSeq;
                pending.addAll(emitted);
                sortPending();
                record.put("outcome", "ok");
                record.put("stateAfter", stateAfter);
                record.put("dataAfter", copy(workingData));
                record.put("outputs", outputs);
                record.put("emitted", copy(emitted));
            } else {
                record.put("outcome", "noop");
                record.put("stateAfter", stateBefore);
                record.put("dataAfter", copy(dataBefore));
                record.put("outputs", List.of());
                record.put("emitted", List.of());
            }
        } catch (RuntimeException failure) {
            // rollback: no mutation, no RNG draw, no internal events survive
            record.put("outcome", "failed");
            record.put("error", failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName());
            record.put("stateAfter", stateBefore);
            record.put("dataAfter", copy(dataBefore));
            record.put("outputs", List.of());
            record.put("emitted", List.of());
        }
        record.put("traceHash", nextHash(record));
        trace.add(record);
        traceHash = (String) record.get("traceHash");
        return record;
    }

    public Map<String, Object> runToEnd() {
        Map<String, Object> last = null;
        while (canStep()) {
            last = step();
        }
        return last;
    }

    private Map<String, Object> nextEvent() {
        if (!pending.isEmpty()) {
            Map<String, Object> firstPending = pending.get(0);
            long pendingTime = ((Number) firstPending.get("time")).longValue();
            if (eventIndex >= events.size() || pendingTime <= ((Number) events.get(eventIndex).get("time")).longValue()) {
                return pending.remove(0);
            }
        }
        return events.get(eventIndex++);
    }

    private Map<String, Object> baseRecord(Map<String, Object> event, String stateBefore,
                                           Map<String, Object> dataBefore) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("step", trace.size() + 1);
        record.put("event", copy(event));
        record.put("stateBefore", stateBefore);
        record.put("dataBefore", dataBefore);
        return record;
    }

    private Map<String, Object> selectTransition(Map<String, Object> event, String currentState,
                                                 Map<String, Object> snapshot) {
        String eventType = (String) event.get("type");
        for (Map<String, Object> transition : definition.transitions()) {
            Object from = transition.get("from");
            if (!"*".equals(from) && !from.equals(currentState)) {
                continue;
            }
            if (!transition.get("event").equals(eventType)) {
                continue;
            }
            Object when = transition.get("when");
            if (when instanceof List) {
                boolean allMatch = true;
                for (Object condition : (List<?>) when) {
                    if (!Expr.truthy(Expr.eval(condition, snapshot, event))) {
                        allMatch = false;
                        break;
                    }
                }
                if (!allMatch) {
                    continue;
                }
            }
            return transition;
        }
        return null;
    }

    private void runActions(Map<String, Object> transition,
                            Map<String, Object> event,
                            Map<String, Object> workingData,
                            LongHolder rng,
                            long startingSeq,
                            String currentState,
                            List<Map<String, Object>> emitted,
                            List<Map<String, Object>> outputs) {
        LongHolder seq = new LongHolder(startingSeq);
        for (Object actionRaw : (List<?>) transition.getOrDefault("actions", List.of())) {
            Map<String, Object> action = castMap(actionRaw);
            String op = (String) action.get("op");
            switch (op) {
                case "set" -> {
                    Object value = action.containsKey("value")
                            ? Expr.eval(action.get("value"), workingData, event)
                            : null;
                    writePath(workingData, str(action.get("path")), value);
                }
                case "inc" -> {
                    String path = str(action.get("path"));
                    long delta = action.containsKey("by")
                            ? Expr.asLong(Expr.eval(action.get("by"), workingData, event), "inc.by")
                            : 1L;
                    Object existing = readPath(workingData, path);
                    long base = existing instanceof Number ? ((Number) existing).longValue() : 0L;
                    writePath(workingData, path, base + delta);
                }
                case "random" -> {
                    long bound = Expr.asLong(Expr.eval(action.get("bound"), workingData, event),
                            "random.bound");
                    if (bound <= 0) {
                        throw new IllegalStateException("random.bound 必须是正整数");
                    }
                    long drawn = nextRandom(rng) % bound;
                    writePath(workingData, str(action.get("path")), drawn);
                }
                case "assert" -> {
                    if (!Expr.truthy(Expr.eval(action.get("that"), workingData, event))) {
                        throw new IllegalStateException(str(action.get("message")));
                    }
                }
                case "fail" -> throw new IllegalStateException(str(action.get("message")));
                case "emit" -> {
                    long delay = action.containsKey("delay")
                            ? ((Number) action.get("delay")).longValue()
                            : 0L;
                    Object sourceRaw = action.get("source");
                    String source = sourceRaw instanceof String ? (String) sourceRaw : INTERNAL_SOURCE;
                    long eventTime = ((Number) event.get("time")).longValue() + Math.max(0L, delay);
                    Map<String, Object> internalEvent = new LinkedHashMap<>();
                    internalEvent.put("kind", "internal");
                    internalEvent.put("time", eventTime);
                    internalEvent.put("source", source);
                    internalEvent.put("seq", seq.value);
                    internalEvent.put("type", str(action.get("type")));
                    internalEvent.put("data", buildActionData(action.get("data"), workingData, event));
                    internalEvent.put("id", Fingerprint.shortHash(
                            Fingerprint.ofCanonical(List.of("internal", eventTime, source,
                                    seq.value, internalEvent.get("type"), internalEvent.get("data"))), 16));
                    emitted.add(internalEvent);
                    seq.value++;
                }
                case "output" -> {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("name", str(action.get("name")));
                    output.put("value", Expr.eval(action.get("value"), workingData, event));
                    outputs.add(output);
                }
                default -> throw new IllegalStateException("未知动作: " + op);
            }
        }
        seqHolderValue = seq.value;
    }

    private long seqHolderValue;
    private long rngHolderValue;
    private LongHolder workingRngHolder(long value) {
        this.rngHolderValue = value;
        return new LongHolder(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildActionData(Object template, Map<String, Object> workingData,
                                                Map<String, Object> event) {
        if (!(template instanceof Map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) template).entrySet()) {
            result.put((String) entry.getKey(), buildActionValue(entry.getValue(), workingData, event));
        }
        return result;
    }

    private Object buildActionValue(Object template, Map<String, Object> workingData,
                                    Map<String, Object> event) {
        if (template instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) template;
            if (map.containsKey("expr")) {
                return Expr.eval(map.get("expr"), workingData, event);
            }
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                nested.put((String) entry.getKey(), buildActionValue(entry.getValue(), workingData, event));
            }
            return nested;
        }
        if (template instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) template) {
                result.add(buildActionValue(item, workingData, event));
            }
            return result;
        }
        return template;
    }

    // ------------------------------------------------------------- RNG

    private static long initialRng(long seed) {
        return Mix.mix(seed ^ 0x9E3779B97F4A7C15L);
    }

    private static long nextRandom(LongHolder holder) {
        long next = Mix.advance(holder.value);
        holder.value = next;
        return Mix.toLong(next);
    }

    // ----------------------------------------------------- events / hashing

    /** Normalize imported external events: stable field order, seq fallback, ids. */
    public static List<Map<String, Object>> normalizeEvents(List<?> rawEvents) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        int index = 0;
        for (Object rawEvent : rawEvents) {
            require(rawEvent instanceof Map, "events[" + index + "] 必须是对象");
            Map<?, ?> source = (Map<?, ?>) rawEvent;
            require(source.get("type") instanceof String, "events[" + index + "].type 必须是字符串");
            require(source.get("time") instanceof Number, "events[" + index + "].time 必须是逻辑时间数字");
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("kind", "external");
            event.put("time", ((Number) source.get("time")).longValue());
            Object eventSource = source.get("source");
            event.put("source", eventSource instanceof String ? eventSource : "default");
            Object seq = source.get("seq");
            event.put("seq", seq instanceof Number ? ((Number) seq).longValue() : (long) index);
            event.put("type", source.get("type"));
            Object payload = source.get("data");
            event.put("data", payload instanceof Map ? copy(payload) : new LinkedHashMap<>());
            event.put("id", Fingerprint.shortHash(Fingerprint.ofCanonical(List.of("external",
                    event.get("time"), event.get("source"), event.get("seq"),
                    event.get("type"), event.get("data"))), 16));
            normalized.add(event);
            index++;
        }
        return normalized;
    }

    private void sortPending() {
        pendingComparator = (a, b) -> {
            int byTime = Long.compare(((Number) a.get("time")).longValue(),
                    ((Number) b.get("time")).longValue());
            if (byTime != 0) {
                return byTime;
            }
            int byPriority = Integer.compare(priorityOf(b), priorityOf(a));
            if (byPriority != 0) {
                return byPriority;
            }
            int bySeq = Long.compare(((Number) a.get("seq")).longValue(),
                    ((Number) b.get("seq")).longValue());
            if (bySeq != 0) {
                return bySeq;
            }
            return ((String) a.get("id")).compareTo((String) b.get("id"));
        };
        pending.sort(pendingComparator);
    }

    private Comparator<Map<String, Object>> pendingComparator;

    private int priorityOf(Map<String, Object> event) {
        if ("internal".equals(event.get("kind"))) {
            return definition.priorityOf((String) event.get("source"));
        }
        return definition.priorityOf((String) event.get("source"));
    }

    private String nextHash(Map<String, Object> record) {
        List<Object> payload = List.of(
                traceHash,
                record.get("event"),
                record.get("stateBefore"),
                record.get("dataBefore"),
                record.get("transition"),
                record.get("outcome"),
                record.get("stateAfter"),
                record.get("dataAfter"),
                record.get("outputs"),
                record.getOrDefault("error", null));
        return Fingerprint.ofCanonical(payload);
    }

    private static String genesisHash(DefinitionView definition, List<Map<String, Object>> events) {
        List<Object> payload = List.of(
                "genesis-v1",
                Fingerprint.ofCanonical(definition.raw()),
                definition.initialState(),
                definition.initialData(),
                definition.seed(),
                events);
        return Fingerprint.ofCanonical(payload);
    }

    // ------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalStateException("期望对象");
        }
        return (Map<String, Object>) value;
    }

    private static String str(Object value) {
        if (!(value instanceof String)) {
            throw new IllegalStateException("期望字符串字段");
        }
        return (String) value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object readPath(Map<String, Object> root, String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static void writePath(Map<String, Object> root, String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            Object child = current.get(segments[i]);
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                current.put(segments[i], child);
            }
            current = (Map<String, Object>) child;
        }
        current.put(segments[segments.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    static <T> T copy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put((String) entry.getKey(), copy(entry.getValue()));
            }
            return (T) result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                result.add(copy(item));
            }
            return (T) result;
        }
        return (T) value;
    }

    private static final class LongHolder {
        private long value;

        private LongHolder(long value) {
            this.value = value;
        }
    }
}
}
