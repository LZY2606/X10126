package replay.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import replay.Json;

/**
 * Branch merge planning.
 *
 * Merge is NOT "overwrite final state". It re-derives a merged trajectory from
 * the common ancestor. It is allowed only when:
 *  1. the external-event SETS after the ancestor are compatible: every event
 *     identity introduced by one side is either shared or can be unambiguously
 *     interleaved (same identity means the same event content/fingerprint);
 *  2. their relative ORDER is unambiguous: whenever two identities appear in
 *     both branches they keep the same relative order, and the first position
 *     where stable interleaving is impossible identifies the conflict.
 *
 * Returned conflict lists "firstConflict": the first event pair that cannot be
 * ordered, and "reason".
 */
public final class MergePlanner {

    private MergePlanner() {
    }

    public static final class MergeResult {
        public boolean compatible;
        public List<Map<String, Object>> mergedEvents = new ArrayList<>();
        public Map<String, Object> firstConflict;
        public Map<String, Object> summary;
    }

    public static MergeResult plan(List<Map<String, Object>> left,
                                   List<Map<String, Object>> right) {
        MergeResult result = new MergeResult();

        List<String> aIds = ids(left);
        List<String> bIds = ids(right);

        Map<String, Map<String, Object>> aById = byId(left);
        Map<String, Map<String, Object>> bById = byId(right);

        // Identity content compatibility: same id must mean same content.
        for (String id : aIds) {
            if (bById.containsKey(id) && !contentEqual(aById.get(id), bById.get(id))) {
                result.compatible = false;
                result.firstConflict = conflict(
                        "same event id has different content on the two branches",
                        aById.get(id), bById.get(id));
                return result;
            }
        }

        // Shared identities must not be observed in contradictory relative
        // orders on the two branches.
        List<String> common = new ArrayList<>();
        for (String id : aIds) {
            if (bById.containsKey(id) && !common.contains(id)) {
                common.add(id);
            }
        }
        for (int i = 0; i < common.size(); i++) {
            for (int j = i + 1; j < common.size(); j++) {
                int ai = aIds.indexOf(common.get(i));
                int aj = aIds.indexOf(common.get(j));
                int bi = bIds.indexOf(common.get(i));
                int bj = bIds.indexOf(common.get(j));
                if (Integer.signum(aj - ai) != Integer.signum(bj - bi)) {
                    result.compatible = false;
                    Map<String, Object> fc = new LinkedHashMap<>();
                    fc.put("reason", "shared events are ordered differently on the two branches");
                    List<Object> pair = new ArrayList<>();
                    pair.add(brief(aById.get(common.get(i))));
                    pair.add(brief(aById.get(common.get(j))));
                    fc.put("events", pair);
                    result.firstConflict = fc;
                    return result;
                }
            }
        }

        // Stable interleaved merge with deterministic tie-breaking. When at
        // the first step of both sides the ids differ and are both "new"
        // (not appearing later on the other side), their relative order is
        // ambiguous -> reject, naming that first conflicting pair.
        List<Map<String, Object>> merged = new ArrayList<>();
        int ai = 0;
        int bi = 0;
        while (ai < aIds.size() || bi < bIds.size()) {
            if (ai < aIds.size() && bi < bIds.size()) {
                String x = aIds.get(ai);
                String y = bIds.get(bi);
                if (x.equals(y)) {
                    merged.add(aById.get(x));
                    ai++;
                    bi++;
                    continue;
                }
                boolean xAwaitedInB = bIds.subList(bi, bIds.size()).contains(x);
                boolean yAwaitedInA = aIds.subList(ai, aIds.size()).contains(y);
                if (xAwaitedInB && !yAwaitedInA) {
                    merged.add(bById.get(y));
                    bi++;
                    continue;
                }
                if (yAwaitedInA && !xAwaitedInB) {
                    merged.add(aById.get(x));
                    ai++;
                    continue;
                }
                if (xAwaitedInB) {
                    // both waited: order already validated; keep left-first deterministically
                    merged.add(aById.get(x));
                    ai++;
                    continue;
                }
                // Neither appears on the other side later: only acceptable when a
                // global stable key (time, priority, seq, id) decides their order.
                int keyOrder = stableKeyCompare(aById.get(x), bById.get(y));
                if (keyOrder != 0) {
                    if (keyOrder < 0) {
                        merged.add(aById.get(x));
                        ai++;
                    } else {
                        merged.add(bById.get(y));
                        bi++;
                    }
                    continue;
                }
                result.compatible = false;
                result.firstConflict = conflict(
                        "two different external events occur at the same logical time, "
                                + "priority and sequence with no stable ordering between them",
                        aById.get(x), bById.get(y));
                return result;
            } else if (ai < aIds.size()) {
                merged.add(aById.get(aIds.get(ai++)));
            } else {
                merged.add(bById.get(bIds.get(bi++)));
            }
        }

        result.compatible = true;
        result.mergedEvents = merged;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mergedEventCount", merged.size());
        summary.put("leftOnly", countOnly(aIds, bIds));
        summary.put("rightOnly", countOnly(bIds, aIds));
        summary.put("shared", common.size());
        result.summary = summary;
        return result;
    }

    private static int stableKeyCompare(Map<String, Object> x, Map<String, Object> y) {
        int cmp = Long.compare(Json.lng(x, "time", 0L), Json.lng(y, "time", 0L));
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(Json.integer(x, "priority", 0), Json.integer(y, "priority", 0));
        if (cmp != 0) {
            return cmp;
        }
        return Long.compare(Json.lng(x, "seq", 0L), Json.lng(y, "seq", 0L));
    }

    private static int countOnly(List<String> a, List<String> b) {
        int n = 0;
        for (String id : a) {
            if (!b.contains(id)) {
                n++;
            }
        }
        return n;
    }

    private static Map<String, Object> conflict(String reason,
                                                Map<String, Object> e1,
                                                Map<String, Object> e2) {
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("reason", reason);
        List<Object> pair = new ArrayList<>();
        pair.add(brief(e1));
        pair.add(brief(e2));
        fc.put("events", pair);
        return fc;
    }

    private static Map<String, Object> brief(Map<String, Object> event) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", Json.str(event, "id"));
        b.put("event", Json.str(event, "event"));
        b.put("time", Json.lng(event, "time", 0L));
        b.put("priority", Json.integer(event, "priority", 0));
        b.put("seq", Json.lng(event, "seq", 0L));
        b.put("source", Json.str(event, "source", "log"));
        return b;
    }

    private static List<String> ids(List<Map<String, Object>> events) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> e : events) {
            ids.add(Json.str(e, "id"));
        }
        return ids;
    }

    private static Map<String, Map<String, Object>> byId(List<Map<String, Object>> events) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (Map<String, Object> e : events) {
            m.put(Json.str(e, "id"), e);
        }
        return m;
    }

    private static boolean contentEqual(Map<String, Object> a, Map<String, Object> b) {
        return Hashes.canonical(Hashes.canonicalize(stripRuntime(a)))
                .equals(Hashes.canonical(Hashes.canonicalize(stripRuntime(b))));
    }

    private static Map<String, Object> stripRuntime(Map<String, Object> e) {
        Map<String, Object> copy = new LinkedHashMap<>(e);
        copy.remove("ordinal");
        return copy;
    }
}
