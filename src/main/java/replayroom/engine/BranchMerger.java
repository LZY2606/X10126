package replayroom.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import replayroom.model.Envelope;
import replayroom.model.TraceEntry;

/**
 * Merges two branches forked from the same checkpoint.
 *
 * Merge is allowed only when the external events applied after the common
 * ancestor are compatible and have one unambiguous total ordering. The first
 * offending pair is reported when the merge is rejected.
 */
public final class BranchMerger {

    public interface StoreLike {
        CheckpointRecord checkpoint(String id);
        CompiledDefinition compiled(String fingerprint);
    }

    public interface CheckpointRecord {
        String id();
        String definitionFingerprint();
        List<String> processedExternalIds();
    }

    private final StoreLike store;

    public BranchMerger(StoreLike store) {
        this.store = store;
    }

    public Object merge(String newSessionId, String name, Session a, Session b) {
        String ancestorId = commonAncestor(a, b);
        if (ancestorId == null) {
            return new MergeConflict("ancestor-mismatch", reference(a), reference(b),
                    "Branches do not share the same ancestor checkpoint");
        }
        if (!a.definitionFingerprint().equals(b.definitionFingerprint())) {
            return new MergeConflict("definition-mismatch", reference(a), reference(b),
                    "Branches were replayed under different definition fingerprints");
        }
        CheckpointRecord ancestor = store.checkpoint(ancestorId);
        if (ancestor == null || !ancestor.definitionFingerprint().equals(a.definitionFingerprint())) {
            return new MergeConflict("checkpoint-definition-mismatch", reference(a), reference(b),
                    "Ancestor checkpoint is not valid for the current definition");
        }

        List<Envelope> appliedA = branchApplied(a, ancestor);
        List<Envelope> appliedB = branchApplied(b, ancestor);
        List<Envelope> futureA = externalOnly(a.pending());
        List<Envelope> futureB = externalOnly(b.pending());

        Map<String, Envelope> nodes = new LinkedHashMap<>();
        for (Envelope e : appliedA) nodes.put(e.id(), e);
        for (Envelope e : appliedB) {
            Envelope prev = nodes.put(e.id(), e);
            if (prev != null && !sameContent(prev, e)) return MergeConflict.content(prev, e);
        }
        for (Envelope e : futureA) nodes.put(e.id(), e);
        for (Envelope e : futureB) {
            Envelope prev = nodes.put(e.id(), e);
            if (prev != null && !sameContent(prev, e)) return MergeConflict.content(prev, e);
        }

        Object mergedApplied = topoMerge(appliedA, appliedB, nodes);
        if (mergedApplied instanceof MergeConflict conflict) return conflict;
        @SuppressWarnings("unchecked")
        List<Envelope> applied = (List<Envelope>) mergedApplied;

        Object futureConflict = checkFutureEvents(applied, futureA, futureB, nodes);
        if (futureConflict instanceof MergeConflict conflict) return conflict;

        List<Envelope> mergedLog = new ArrayList<>(applied);
        Set<String> inApplied = new HashSet<>();
        for (Envelope e : applied) inApplied.add(e.id());
        List<Envelope> futureMerged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Envelope e : futureA) if (seen.add(e.id()) && !inApplied.contains(e.id())) futureMerged.add(e);
        for (Envelope e : futureB) if (seen.add(e.id()) && !inApplied.contains(e.id())) futureMerged.add(e);
        futureMerged.sort(Envelope::compareExternal);
        mergedLog.addAll(futureMerged);

        CompiledDefinition compiled = store.compiled(a.definitionFingerprint());
        Session merged = Session.create(newSessionId, name, compiled, a.seed(), mergedLog);
        merged.markMerged(ancestorId);
        TraceEntry last = merged.runToEnd(compiled);
        return new MergeResult(merged, last, merged.traceHeadHash());
    }

    /**
     * Produces a deterministic merge of two branch orders.
     *
     * Shared events must occur in the same relative order. For side-unique
     * events, the merged order is forced by the global ordering key
     * (time, priority desc, seq, id); when two side-unique events have the same
     * (time, priority), the merge is ambiguous.
     */
    private Object topoMerge(List<Envelope> a, List<Envelope> b, Map<String, Envelope> nodes) {
        Set<String> setA = new LinkedHashSet<>();
        Set<String> setB = new LinkedHashSet<>();
        for (Envelope e : a) setA.add(e.id());
        for (Envelope e : b) setB.add(e.id());

        Object orderConflict = validateSharedOrder(a, b);
        if (orderConflict != null) return orderConflict;

        Set<String> remainingA = new LinkedHashSet<>(setA);
        Set<String> remainingB = new LinkedHashSet<>(setB);
        List<Envelope> result = new ArrayList<>();
        while (!remainingA.isEmpty() || !remainingB.isEmpty()) {
            String headA = remainingA.isEmpty() ? null : remainingA.iterator().next();
            String headB = remainingB.isEmpty() ? null : remainingB.iterator().next();
            if (headA != null && headA.equals(headB)) {
                result.add(nodes.get(headA));
                remainingA.remove(headA);
                remainingB.remove(headB);
                continue;
            }
            if (headA != null && headB != null
                    && sameOrderKey(nodes.get(headA), nodes.get(headB))
                    && !(setB.contains(headA) || setA.contains(headB))) {
                return MergeConflict.ambiguous(nodes.get(headA), nodes.get(headB));
            }
            String chosen;
            if (headB == null) chosen = headA;
            else if (headA == null) chosen = headB;
            else {
                int cmp = Envelope.compareExternal(nodes.get(headA), nodes.get(headB));
                chosen = cmp <= 0 ? headA : headB;
            }
            result.add(nodes.get(chosen));
            remainingA.remove(chosen);
            remainingB.remove(chosen);
        }
        return result;
    }

    private MergeConflict validateSharedOrder(List<Envelope> a, List<Envelope> b) {
        List<Envelope> sharedA = new ArrayList<>();
        for (Envelope e : a) {
            for (Envelope f : b) {
                if (e.id().equals(f.id())) { sharedA.add(e); break; }
            }
        }
        List<Envelope> sharedB = new ArrayList<>();
        for (Envelope e : b) {
            for (Envelope f : a) {
                if (e.id().equals(f.id())) { sharedB.add(e); break; }
            }
        }
        for (int i = 0; i < Math.min(sharedA.size(), sharedB.size()); i++) {
            if (!sharedA.get(i).id().equals(sharedB.get(i).id())) {
                return MergeConflict.order(sharedA.get(i), sharedB.get(i));
            }
        }
        return null;
    }

    private Object checkFutureEvents(List<Envelope> applied, List<Envelope> futureA, List<Envelope> futureB,
                                     Map<String, Envelope> nodes) {
        List<Envelope> all = new ArrayList<>(applied);
        Set<String> seen = new HashSet<>();
        for (Envelope e : applied) seen.add(e.id());
        for (Envelope e : futureA) if (seen.add(e.id())) all.add(e);
        for (Envelope e : futureB) if (seen.add(e.id())) all.add(e);
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Envelope x = all.get(i);
                Envelope y = all.get(j);
                if (sameOrderKey(x, y) && !x.id().equals(y.id())) {
                    return new MergeConflict("ambiguous-order", x.toMap(), y.toMap(),
                            "Unprocessed events share time and source priority across branches");
                }
            }
        }
        return null;
    }

    private boolean sameOrderKey(Envelope a, Envelope b) {
        return a.time() == b.time() && a.priority() == b.priority();
    }

    private boolean sameContent(Envelope a, Envelope b) {
        return JsonEquals.maps(a.toMap(), b.toMap());
    }

    private String commonAncestor(Session a, Session b) {
        if (a.parentCheckpointId() == null || b.parentCheckpointId() == null) return null;
        return a.parentCheckpointId().equals(b.parentCheckpointId()) ? a.parentCheckpointId() : null;
    }

    private Map<String, Object> reference(Session session) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", session.id());
        map.put("ancestor", session.parentCheckpointId());
        map.put("definitionFingerprint", session.definitionFingerprint());
        return map;
    }

    private List<Envelope> branchApplied(Session session, CheckpointRecord ancestor) {
        Set<String> before = new LinkedHashSet<>(ancestor.processedExternalIds());
        List<Envelope> result = new ArrayList<>();
        for (String id : session.processedExternalIds()) {
            if (!before.contains(id)) {
                Envelope event = findById(session.externalLog(), id);
                if (event != null) result.add(event);
            }
        }
        return result;
    }

    private Envelope findById(List<Envelope> events, String id) {
        for (Envelope event : events) if (event.id().equals(id)) return event;
        return null;
    }

    private List<Envelope> externalOnly(List<Envelope> events) {
        List<Envelope> result = new ArrayList<>();
        for (Envelope event : events) if (!event.internal()) result.add(event);
        return result;
    }
}
