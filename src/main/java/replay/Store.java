package replay;

import replay.Model.Event;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** File-backed session store; also hosts branch/merge lineage logic. */
public final class Store {
    private final Path dir;
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public Store(Path dir) {
        this.dir = dir;
    }

    public void load() throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                Session s = Session.fromMap(Json.parseObject(Files.readString(p, StandardCharsets.UTF_8)));
                sessions.put(s.id, s);
            }
        }
    }

    public synchronized void save(Session s) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(s.id + ".tmp");
            Files.writeString(tmp, Json.pretty(s.toMap()), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(s.id + ".json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist session " + s.id, e);
        }
    }

    public synchronized Session get(String id) {
        Session s = sessions.get(id);
        if (s == null) throw new IllegalArgumentException("unknown session: " + id);
        return s;
    }

    public synchronized List<Session> list() {
        return new ArrayList<>(sessions.values());
    }

    public synchronized void put(Session s) {
        sessions.put(s.id, s);
        save(s);
    }

    public synchronized Session export(String id) {
        return get(id);
    }

    /** Import an exported session document; verifies the trace hash. */
    public synchronized Session importSession(Map<String, Object> doc) {
        Object payload = doc.containsKey("session") ? doc.get("session") : doc;
        Session s = Session.fromMap(Model.asMap(payload, "session"));
        String recomputed = s.recomputeTraceHash();
        if (!recomputed.equals(s.traceHash)) {
            throw new IllegalStateException("trace hash mismatch on import: expected "
                    + s.traceHash + " recomputed " + recomputed);
        }
        if (sessions.containsKey(s.id)) {
            s.id = s.id + "-i" + (sessions.size() + 1);
        }
        sessions.put(s.id, s);
        save(s);
        return s;
    }

    // ---------- merge ----------

    public static final class MergeConflict extends RuntimeException {
        public final Map<String, Object> eventA;
        public final Map<String, Object> eventB;
        public final String reason;

        MergeConflict(Map<String, Object> a, Map<String, Object> b, String reason) {
            super(reason);
            this.eventA = a;
            this.eventB = b;
            this.reason = reason;
        }
    }

    /** External events in s.trace[0, upToStep), in application order. */
    private List<Map<String, Object>> timelineEvents(Session s, int upToStep) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (s.branchPoint != null) {
            Session parent = get((String) s.branchPoint.get("sessionId"));
            result.addAll(timelineEvents(parent, ((Number) s.branchPoint.get("stepIndex")).intValue()));
        }
        for (Map<String, Object> rec : s.externalApplied) {
            if (((Number) rec.get("traceIndex")).intValue() < upToStep) {
                result.add(Model.asMap(rec.get("event"), "event"));
            }
        }
        return result;
    }

    public synchronized Session merge(String idA, String idB) {
        Session a = get(idA);
        Session b = get(idB);
        // chain: sessionId -> number of that session's trace steps visible to the descendant,
        // ordered from the leaf upward so the first shared entry is the deepest common point
        Map<String, Integer> chainA = lineage(a);
        Session ancestor = null;
        int k = -1;
        for (Map.Entry<String, Integer> e : lineage(b).entrySet()) {
            Integer aSteps = chainA.get(e.getKey());
            if (aSteps != null) {
                ancestor = get(e.getKey());
                k = Math.min(aSteps, e.getValue());
                break;
            }
        }
        if (ancestor == null) {
            throw new IllegalArgumentException("sessions share no common ancestor");
        }
        List<Map<String, Object>> prefix = timelineEvents(ancestor, k);
        List<Map<String, Object>> la = dropPrefix(timelineEvents(a, Integer.MAX_VALUE), prefix);
        List<Map<String, Object>> lb = dropPrefix(timelineEvents(b, Integer.MAX_VALUE), prefix);

        // shared prefix of the two post-ancestor streams
        int i = 0;
        while (i < la.size() && i < lb.size()) {
            Map<String, Object> ea = la.get(i);
            Map<String, Object> eb = lb.get(i);
            if (identity(ea).equals(identity(eb))) {
                if (!Json.canonical(ea).equals(Json.canonical(eb))) {
                    throw new MergeConflict(ea, eb,
                            "same event identity but different content");
                }
                i++;
            } else {
                break;
            }
        }
        List<Map<String, Object>> restA = la.subList(i, la.size());
        List<Map<String, Object>> restB = lb.subList(i, lb.size());
        for (Map<String, Object> x : restA) {
            for (Map<String, Object> y : restB) {
                if (sortKey(ancestor.def, x).equals(sortKey(ancestor.def, y))) {
                    throw new MergeConflict(x, y,
                            "ambiguous order: distinct events share the same sort key");
                }
            }
        }
        // union, deterministically ordered
        Map<String, Map<String, Object>> union = new TreeMap<>();
        for (Map<String, Object> e : la) union.putIfAbsent(identity(e), e);
        for (Map<String, Object> e : lb) union.putIfAbsent(identity(e), e);
        List<Map<String, Object>> merged = new ArrayList<>(union.values());
        merged.sort(Comparator.comparing(e -> sortKey(ancestor.def, e)));

        if (!ancestor.definitionFingerprint().equals(a.definitionFingerprint())
                || !ancestor.definitionFingerprint().equals(b.definitionFingerprint())) {
            throw new IllegalStateException("definition fingerprint mismatch across branches");
        }

        Session m = new Session(ancestor.def, ancestor.seed, "merge(" + a.name + "," + b.name + ")");
        m.initialVars = new LinkedHashMap<>(ancestor.initialVars);
        List<Event> all = new ArrayList<>();
        for (Map<String, Object> e : prefix) all.add(Event.fromMap(e));
        for (Map<String, Object> e : merged) all.add(Event.fromMap(e));
        m.addEvents(all);
        Map<String, Object> bp = new LinkedHashMap<>();
        bp.put("sessionId", ancestor.id);
        bp.put("stepIndex", k);
        m.branchPoint = bp;
        m.runAll();
        put(m);
        return m;
    }

    private static List<Map<String, Object>> dropPrefix(List<Map<String, Object>> list,
                                                        List<Map<String, Object>> prefix) {
        if (list.size() < prefix.size()) throw new IllegalStateException("broken lineage");
        for (int j = 0; j < prefix.size(); j++) {
            if (!identity(list.get(j)).equals(identity(prefix.get(j)))) {
                throw new IllegalStateException("broken lineage");
            }
        }
        return list.subList(prefix.size(), list.size());
    }

    private Map<String, Integer> lineage(Session s) {
        Map<String, Integer> chain = new LinkedHashMap<>();
        Session cur = s;
        chain.put(cur.id, Integer.MAX_VALUE);
        while (cur.branchPoint != null) {
            int stepIndex = ((Number) cur.branchPoint.get("stepIndex")).intValue();
            cur = get((String) cur.branchPoint.get("sessionId"));
            chain.put(cur.id, Math.min(chain.getOrDefault(cur.id, Integer.MAX_VALUE), stepIndex));
        }
        return chain;
    }

    private static String identity(Map<String, Object> e) {
        return e.get("time") + "|" + e.get("source") + "|" + e.get("seq");
    }

    private static String sortKey(replay.Model.Definition def, Map<String, Object> e) {
        long time = ((Number) e.get("time")).longValue();
        long seq = ((Number) e.get("seq")).longValue();
        String source = String.valueOf(e.get("source"));
        return String.format("%020d|%010d|%020d|%s", time, def.priorityOf(source), seq,
                String.valueOf(e.get("name")));
    }
}
