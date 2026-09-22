package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A replay session: locked definition + seed + priorities, plus branches. */
public final class Session {
    public final String id;
    public String name;
    public Definition definition;
    public final long seed;
    public final Map<String, Long> sourcePriorities;
    public long importCounter;
    public long branchCounter;
    public long checkpointCounter;
    public final LinkedHashMap<String, Branch> branches;
    public String currentBranch;

    public Session(String id, String name, Definition definition, long seed,
                   Map<String, Long> sourcePriorities) {
        this.id = id;
        this.name = name;
        this.definition = definition;
        this.seed = seed;
        this.sourcePriorities = sourcePriorities;
        this.importCounter = 0;
        this.branchCounter = 0;
        this.checkpointCounter = 0;
        this.branches = new LinkedHashMap<>();
        Branch main = new Branch("main", "main", initialSnapshot(definition, seed),
                new ArrayList<>(), new ArrayList<>(), null, 0);
        branches.put(main.id, main);
        this.currentBranch = main.id;
    }

    private Session(String id, String name, Definition definition, long seed,
                    Map<String, Long> sourcePriorities, long importCounter,
                    long branchCounter, long checkpointCounter,
                    LinkedHashMap<String, Branch> branches, String currentBranch) {
        this.id = id;
        this.name = name;
        this.definition = definition;
        this.seed = seed;
        this.sourcePriorities = sourcePriorities;
        this.importCounter = importCounter;
        this.branchCounter = branchCounter;
        this.checkpointCounter = checkpointCounter;
        this.branches = branches;
        this.currentBranch = currentBranch;
    }

    public static Snapshot initialSnapshot(Definition definition, long seed) {
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = (Map<String, Object>) Json.deepCopy(definition.initialVars);
        return new Snapshot(definition.initialState, vars, 0, seed == 0 ? 0x9E3779B97F4A7C15L : seed,
                0, new ArrayList<>(), new ArrayList<>());
    }

    public Branch branch() {
        return branches.get(currentBranch);
    }

    public Branch branch(String id) {
        Branch b = branches.get(id);
        if (b == null) throw new Json.JsonException("unknown branch: " + id);
        return b;
    }

    /** Fingerprint locking definition version, initial state, event-source priorities and seed. */
    public String fingerprint() {
        Map<String, Object> m = Json.map();
        m.put("definition", definition.raw());
        m.put("seed", seed);
        m.put("sourcePriorities", sourcePriorities);
        return Hash.sha256(Json.canonical(m));
    }

    public String traceHash(String branchId) {
        Branch b = branch(branchId);
        List<Object> tr = Json.list();
        for (StepRecord r : b.trace) tr.add(r.toJson());
        return Hash.sha256(Json.canonical(tr));
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = Json.map();
        m.put("id", id);
        m.put("name", name);
        m.put("definition", definition.raw());
        m.put("seed", seed);
        m.put("sourcePriorities", sourcePriorities);
        m.put("importCounter", importCounter);
        m.put("branchCounter", branchCounter);
        m.put("checkpointCounter", checkpointCounter);
        Map<String, Object> bs = Json.map();
        for (Map.Entry<String, Branch> e : branches.entrySet()) {
            bs.put(e.getKey(), e.getValue().toJson());
        }
        m.put("branches", bs);
        m.put("currentBranch", currentBranch);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Session fromJson(Map<String, Object> json) {
        Map<String, Long> priorities = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.asMap(json.get("sourcePriorities"), "session.sourcePriorities").entrySet()) {
            priorities.put(e.getKey(), ((Number) e.getValue()).longValue());
        }
        LinkedHashMap<String, Branch> branches = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.asMap(json.get("branches"), "session.branches").entrySet()) {
            branches.put(e.getKey(), Branch.fromJson(Json.asMap(e.getValue(), "branch")));
        }
        return new Session(
                Json.asString(json.get("id"), "session.id"),
                Json.asString(json.get("name"), "session.name"),
                new Definition(Json.asMap(json.get("definition"), "session.definition")),
                Json.asLong(json.get("seed"), "session.seed"),
                priorities,
                Json.asLong(json.get("importCounter"), "session.importCounter"),
                Json.asLong(json.get("branchCounter"), "session.branchCounter"),
                Json.asLong(json.get("checkpointCounter"), "session.checkpointCounter"),
                branches,
                Json.asString(json.get("currentBranch"), "session.currentBranch"));
    }
}
