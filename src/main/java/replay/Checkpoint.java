package replay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class Checkpoint {
    public final String id;
    public final String label;
    public final String definitionFingerprint;
    public final long createdAtLogicalClock;
    public final int traceLength;
    public final long externalCount; // external events accepted when checkpoint was taken
    public final String traceHash;   // hash of trace[0..traceLength)
    final Engine.Snapshot snapshot;

    public Checkpoint(String id, String label, String definitionFingerprint, long clock,
                      long externalCount, String traceHash, Engine.Snapshot snapshot) {
        this.id = id;
        this.label = label;
        this.definitionFingerprint = definitionFingerprint;
        this.createdAtLogicalClock = clock;
        this.traceLength = snapshot.traceLength();
        this.externalCount = externalCount;
        this.traceHash = traceHash;
        this.snapshot = snapshot;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("definitionFingerprint", definitionFingerprint);
        m.put("clock", createdAtLogicalClock);
        m.put("traceLength", (long) traceLength);
        m.put("externalCount", externalCount);
        m.put("traceHash", traceHash);
        m.put("state", snapshot.state);
        m.put("vars", snapshot.vars);
        m.put("queue", snapshot.queue.stream().map(EventInstance::toJson).collect(Collectors.toList()));
        m.put("internalQueue", snapshot.internalQueue.stream().map(EventInstance::toJson).collect(Collectors.toList()));
        m.put("rngState", Long.toString(snapshot.rngState));
        m.put("internalSeq", snapshot.internalSeq);
        m.put("tracePrefix", snapshot.tracePrefix.stream().map(TraceEntry::toJson).collect(Collectors.toList()));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Checkpoint fromJson(Map<String, Object> m) {
        List<EventInstance> queue = events(m.get("queue"));
        List<EventInstance> internal = events(m.get("internalQueue"));
        List<TraceEntry> tracePrefix = ((List<Object>) m.getOrDefault("tracePrefix", List.of())).stream()
                .map(o -> TraceEntry.fromJson((Map<String, Object>) o)).collect(Collectors.toList());
        Engine.Snapshot snap = new Engine.Snapshot(
                String.valueOf(m.get("state")),
                new LinkedHashMap<>((Map<String, Object>) m.getOrDefault("vars", Map.of())),
                queue, internal,
                ((Number) m.getOrDefault("clock", 0L)).longValue(),
                Long.parseLong(String.valueOf(m.get("rngState"))),
                ((Number) m.getOrDefault("internalSeq", 0L)).longValue(),
                tracePrefix);
        return new Checkpoint(
                String.valueOf(m.get("id")),
                String.valueOf(m.getOrDefault("label", "")),
                String.valueOf(m.get("definitionFingerprint")),
                ((Number) m.getOrDefault("clock", 0L)).longValue(),
                ((Number) m.getOrDefault("externalCount", 0L)).longValue(),
                String.valueOf(m.get("traceHash")),
                snap);
    }

    @SuppressWarnings("unchecked")
    private static List<EventInstance> events(Object o) {
        if (!(o instanceof List)) return List.of();
        return ((List<Object>) o).stream()
                .map(x -> EventInstance.fromJson((Map<String, Object>) x)).collect(Collectors.toList());
    }
}
