package replay.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import replay.Json;

/**
 * External event log with stable total ordering.
 *
 * Same logical time is broken by: source priority (smaller first), then
 * original sequence number. Everything is virtual/logical time.
 */
public final class EventLog {

    public final List<Map<String, Object>> events = new ArrayList<>();

    public static final Comparator<Map<String, Object>> ORDER =
            Comparator.comparingLong((Map<String, Object> e) -> Json.lng(e, "time", 0L))
                    .thenComparingInt(e -> Json.integer(e, "priority", 0))
                    .thenComparingLong(e -> Json.lng(e, "seq", 0L)));

    public static EventLog fromMaps(List<Object> raw) {
        EventLog log = new EventLog();
        long autoSeq = 0L;
        for (Object item : raw) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("events must be objects");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) item);
            if (Json.str(m, "event") == null) {
                throw new IllegalArgumentException("event requires 'event'");
            }
            m.putIfAbsent("id", "e" + autoSeq);
            m.putIfAbsent("time", 0L);
            m.putIfAbsent("priority", 0);
            m.putIfAbsent("seq", autoSeq);
            m.putIfAbsent("source", "log");
            autoSeq++;
            log.events.add(m);
        }
        log.events.sort(ORDER);
        // Re-stamp stable ids only for synthetic duplicates? Keep user ids; add ordinal.
        for (int i = 0; i < log.events.size(); i++) {
            log.events.get(i).put("ordinal", i);
        }
        return log;
    }

    public List<Object> toMaps() {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> e : events) {
            out.add(new LinkedHashMap<>(e));
        }
        return out;
    }

    public String fingerprint() {
        List<Object> canonicalEvents = new ArrayList<>();
        for (Map<String, Object> e : events) {
            canonicalEvents.add(Hashes.canonicalize(e));
        }
        return Hashes.fingerprint(canonicalEvents);
    }
}
