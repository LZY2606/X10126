package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Branch merging. A merge never copies a final state over; it re-derives the
 * merged branch from the common ancestor checkpoint with the union of external
 * events. It is only allowed when the two external event sets are compatible
 * and their relative order is unambiguous.
 */
public final class Merger {

    public static final class Conflict {
        public final Event a;
        public final Event b;
        public final String reason;

        Conflict(Event a, Event b, String reason) {
            this.a = a;
            this.b = b;
            this.reason = reason;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = Json.newObj();
            m.put("reason", reason);
            m.put("eventA", a.toJson());
            m.put("eventB", b.toJson());
            return m;
        }
    }

    public static final class MergeException extends RuntimeException {
        public final Conflict firstConflict;
        public MergeException(Conflict conflict) {
            super(conflict.reason + ": " + conflict.a.id + " vs " + conflict.b.id);
            this.firstConflict = conflict;
        }
    }

    /**
     * Validate compatibility of the external events applied in two branches
     * since their common ancestor step. Returns the deduplicated union, sorted
     * in deterministic order. Throws MergeException with the first conflict.
     */
    public static List<Event> compatibleUnion(MachineDefinition def,
                                              List<Event> eventsA,
                                              List<Event> eventsB) {
        Map<String, Event> byIdA = new LinkedHashMap<>();
        for (Event e : eventsA) byIdA.put(e.id, e);
        Map<String, Event> byIdB = new LinkedHashMap<>();
        for (Event e : eventsB) byIdB.put(e.id, e);

        List<Conflict> conflicts = new ArrayList<>();

        // same id, different content
        for (Map.Entry<String, Event> en : byIdA.entrySet()) {
            Event other = byIdB.get(en.getKey());
            if (other != null && !contentEquals(en.getValue(), other)) {
                conflicts.add(new Conflict(en.getValue(), other, "same event id with different content"));
            }
        }
        // distinct events with ambiguous (identical) ordering key; pairs that
        // co-occurred inside one branch are exempt (that branch's trace already
        // fixes their relative order)
        List<Event> all = new ArrayList<>();
        all.addAll(eventsA);
        all.addAll(eventsB);
        all.sort((x, y) -> x.compareTo(y, def.priorityFn()));
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Event x = all.get(i);
                Event y = all.get(j);
                if (x.id.equals(y.id)) continue;
                if (x.time != y.time) break;
                boolean bothInA = byIdA.containsKey(x.id) && byIdA.containsKey(y.id);
                boolean bothInB = byIdB.containsKey(x.id) && byIdB.containsKey(y.id);
                if (bothInA || bothInB) continue;
                if (x.orderKey(def.priorityFn()).equals(y.orderKey(def.priorityFn()))) {
                    conflicts.add(new Conflict(x, y, "ambiguous ordering: identical time/priority/seq"));
                }
            }
        }
        if (!conflicts.isEmpty()) {
            conflicts.sort((c1, c2) -> c1.a.compareTo(c2.a, def.priorityFn()));
            throw new MergeException(conflicts.get(0));
        }

        Map<String, Event> union = new LinkedHashMap<>();
        for (Event e : eventsA) union.put(e.id, e);
        for (Event e : eventsB) union.putIfAbsent(e.id, e);
        List<Event> result = new ArrayList<>(union.values());
        result.sort((x, y) -> x.compareTo(y, def.priorityFn()));
        return result;
    }

    private static boolean contentEquals(Event a, Event b) {
        return Json.canonical(a.toJson()).equals(Json.canonical(b.toJson()));
    }
}
