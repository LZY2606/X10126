package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Branch merge: allowed only when both branches descend from the same checkpoint and
 * their external event sets since that ancestor are compatible with an unambiguous order.
 * Never copies final state across; the merged session replays the union of events from
 * the common ancestor checkpoint.
 */
public final class Merge {

    public static final class Conflict {
        public final String reason;
        public final List<EventInstance> firstConflictGroup;

        Conflict(String reason, List<EventInstance> group) {
            this.reason = reason;
            this.firstConflictGroup = group;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reason", reason);
            List<Map<String, Object>> group = new ArrayList<>();
            for (EventInstance e : firstConflictGroup) group.add(e.toJson());
            m.put("firstConflictGroup", group);
            return m;
        }
    }

    public static final class Result {
        public final Session merged;   // null on conflict
        public final Conflict conflict; // null on success

        Result(Session merged, Conflict conflict) { this.merged = merged; this.conflict = conflict; }
    }

    public static Result merge(Session a, Session b) {
        if (a.ancestorCheckpoint == null || b.ancestorCheckpoint == null) {
            return new Result(null, new Conflict("both sessions must be branches of a common checkpoint", List.of()));
        }
        if (!a.ancestorCheckpoint.id.equals(b.ancestorCheckpoint.id)) {
            return new Result(null, new Conflict("branches do not share a common ancestor checkpoint", List.of()));
        }
        if (!a.definition.fingerprint().equals(b.definition.fingerprint())) {
            return new Result(null, new Conflict("definition fingerprints differ between branches", List.of()));
        }

        List<EventInstance> eventsA = a.eventsSinceBranchPoint();
        List<EventInstance> eventsB = b.eventsSinceBranchPoint();

        // Group both sides by identity key (time|source|seq|name).
        Map<String, EventInstance> mapA = byKey(eventsA);
        Map<String, EventInstance> mapB = byKey(eventsB);

        // 1) Same key, different content => ambiguous order / conflicting event.
        for (Map.Entry<String, EventInstance> e : mapA.entrySet()) {
            EventInstance other = mapB.get(e.getKey());
            if (other != null && !Json.canonical(e.getValue().payload).equals(Json.canonical(other.payload))) {
                return new Result(null, new Conflict(
                        "same event identity has conflicting payloads across branches",
                        groupAt(eventsA, eventsB, e.getValue().time)));
            }
        }
        // 2) Sets must be equal: an event present on only one side makes the sets incompatible.
        for (EventInstance ea : eventsA) {
            if (!mapB.containsKey(ea.key())) {
                return new Result(null, new Conflict(
                        "event sets differ: event exists only in branch A",
                        groupAt(eventsA, eventsB, ea.time)));
            }
        }
        for (EventInstance eb : eventsB) {
            if (!mapA.containsKey(eb.key())) {
                return new Result(null, new Conflict(
                        "event sets differ: event exists only in branch B",
                        groupAt(eventsA, eventsB, eb.time)));
            }
        }

        // Compatible: replay the union (== either set) from the common ancestor checkpoint.
        Session merged = Session.create(a.name + "+" + b.name, a.definition, a.initialState,
                a.initialVars, a.seed, new ArrayList<>(a.externalLog.subList(0, (int) a.branchPointExternalCount)));
        merged.parentSessionId = a.parentSessionId;
        merged.ancestorCheckpoint = a.ancestorCheckpoint;
        merged.branchPointExternalCount = a.branchPointExternalCount;
        merged.addEvents(eventsA);
        while (merged.engine.step() != null) { /* replay to fixpoint */ }
        return new Result(merged, null);
    }

    private static Map<String, EventInstance> byKey(List<EventInstance> events) {
        Map<String, EventInstance> m = new TreeMap<>();
        for (EventInstance e : events) m.put(e.key(), e);
        return m;
    }

    /** All events (from both branches) sharing the earliest conflicting logical time. */
    private static List<EventInstance> groupAt(List<EventInstance> a, List<EventInstance> b, long time) {
        List<EventInstance> group = new ArrayList<>();
        for (EventInstance e : a) if (e.time == time) group.add(e);
        for (EventInstance e : b) if (e.time == time) group.add(e);
        return group;
    }
}
