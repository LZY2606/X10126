package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinates sessions, persistence and cross-session operations (fork/import/export). */
public final class Manager {
    private final Store store;
    private final Map<String, Session> sessions;
    private long sessionCounter = 0;

    public Manager(Store store) {
        this.store = store;
        this.sessions = store.loadAll();
        for (String id : sessions.keySet()) {
            if (id.startsWith("s")) {
                try {
                    sessionCounter = Math.max(sessionCounter, Long.parseLong(id.substring(1)));
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    public synchronized Session create(String name, Map<String, Object> definition, long seed,
                                       Map<String, Long> priorities) {
        if (definition == null) throw new ApiException(400, "definition is required");
        if (definition.get("initialState") == null) throw new ApiException(400, "definition.initialState is required");
        String id = "s" + (++sessionCounter);
        Session s = Session.create(id, name != null ? name : id, definition, seed, priorities);
        sessions.put(id, s);
        store.save(s);
        return s;
    }

    public synchronized Session get(String id) {
        Session s = sessions.get(id);
        if (s == null) throw new ApiException(404, "unknown session: " + id);
        return s;
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Session s : sessions.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("name", s.name);
            m.put("definitionFingerprint", s.definitionFingerprint);
            m.put("seed", s.seed);
            m.put("trajectoryHash", s.trajectoryHash("main"));
            out.add(m);
        }
        return out;
    }

    public synchronized void save(Session s) {
        store.save(s);
    }

    public synchronized Session.Branch fork(String sessionId, String checkpointId, String fromSessionId,
                                            String name) {
        Session target = get(sessionId);
        Session source = fromSessionId != null ? get(fromSessionId) : target;
        Session.Checkpoint c = source.findCheckpoint(checkpointId);
        if (c == null) throw new ApiException(404, "unknown checkpoint: " + checkpointId);
        Session.Branch b = target.fork(c, name);
        store.save(target);
        return b;
    }

    public synchronized Session.MergeResult merge(String sessionId, String a, String b) {
        Session s = get(sessionId);
        Session.MergeResult r = s.merge(a, b);
        if (r.ok) store.save(s);
        return r;
    }

    public synchronized Map<String, Object> export(String id) {
        return get(id).toJson();
    }

    /** Imports an exported session and verifies every branch replays to the exported hash. */
    public synchronized Map<String, Object> importSession(Map<String, Object> json) {
        Session imported = Session.fromJson(json);
        String expectedFingerprint = imported.definitionFingerprint;
        String actualFingerprint = Json.sha256(Json.canonical(imported.definition));
        if (!expectedFingerprint.equals(actualFingerprint)) {
            throw new ApiException(400, "definition fingerprint mismatch on import: exported="
                    + expectedFingerprint + " computed=" + actualFingerprint);
        }
        @SuppressWarnings("unchecked")
        List<Object> exportedBranches = (List<Object>) ((Map<String, Object>) json).getOrDefault("branches", List.of());
        Map<String, String> exportedHashes = new LinkedHashMap<>();
        for (Object bo : exportedBranches) {
            @SuppressWarnings("unchecked")
            Map<String, Object> bm = (Map<String, Object>) bo;
            Object h = bm.get("trajectoryHash");
            if (h != null) exportedHashes.put(String.valueOf(bm.get("id")), String.valueOf(h));
        }
        // Re-replay each branch from scratch and compare hashes.
        for (Session.Branch b : new ArrayList<>(imported.branches.values())) {
            Session fresh = Session.create("verify", "verify", imported.definition, imported.seed, imported.priorities);
            Session.Branch fb = fresh.branches.get("main");
            fb.events = new ArrayList<>(b.events);
            while (fb.stepIndex < fb.events.size()) fresh.step("main");
            String recomputed = fresh.trajectoryHash("main");
            String exported = exportedHashes.get(b.id);
            if (exported != null && !exported.equals(recomputed)) {
                throw new ApiException(400, "trajectory hash mismatch on branch " + b.id
                        + ": exported=" + exported + " recomputed=" + recomputed);
            }
            if (!recomputed.equals(imported.trajectoryHash(b.id))) {
                throw new ApiException(400, "stored trajectory does not re-replay on branch " + b.id);
            }
        }
        String id = imported.id;
        if (sessions.containsKey(id)) {
            long n = 1;
            while (sessions.containsKey(id + "-imported-" + n)) n++;
            id = id + "-imported-" + n;
        }
        imported.id = id;
        sessions.put(id, imported);
        store.save(imported);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("verified", true);
        result.put("trajectoryHash", imported.trajectoryHash("main"));
        return result;
    }
}
