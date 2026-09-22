package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A replay branch: one engine lineage plus its checkpoints and fork ancestry. */
public final class Branch {
    public final String id;
    public String name;
    public final String definitionId;
    public final long definitionVersion;
    public final long seed;
    public final String initialState;
    public final Map<String, Object> initialVariables;
    public final List<Event> externalEvents;
    public final String fingerprint;
    public String ancestorBranchId;
    public String ancestorCheckpointId;
    public long ancestorStep;
    public Engine engine;
    public final Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();

    private Branch(String id, String name, String definitionId, long definitionVersion,
                   long seed, String initialState, Map<String, Object> initialVariables,
                   List<Event> externalEvents, String fingerprint, Engine engine) {
        this.id = id;
        this.name = name;
        this.definitionId = definitionId;
        this.definitionVersion = definitionVersion;
        this.seed = seed;
        this.initialState = initialState;
        this.initialVariables = initialVariables;
        this.externalEvents = externalEvents;
        this.fingerprint = fingerprint;
        this.engine = engine;
    }

    public static Branch create(MachineDefinition def, String name, long seed,
                                String initialState, Map<String, Object> initialVars,
                                List<Event> events) {
        Engine engine = new Engine(def, seed);
        if (initialState != null) engine.setState(initialState);
        if (initialVars != null) engine.vars().putAll(Json.obj(Json.deepCopy(initialVars)));
        List<Event> evts = new ArrayList<>(events);
        engine.enqueueExternal(evts);

        Map<String, Object> fpInput = Json.newObj();
        fpInput.put("definition", def.fingerprint);
        fpInput.put("seed", seed);
        fpInput.put("initialState", engine.state());
        fpInput.put("initialVariables", engine.vars());
        List<Object> sorted = Json.newArr();
        for (Event e : engine.pending()) sorted.add(e.toJson());
        fpInput.put("events", sorted);
        String fingerprint = Hashing.fingerprint(fpInput);

        return new Branch(newId(), name == null ? "main" : name, def.id, def.version, seed,
                engine.state(), Json.obj(Json.deepCopy(engine.vars())), evts, fingerprint, engine);
    }

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public Checkpoint addCheckpoint(String name) {
        Checkpoint cp = new Checkpoint(newId(), name == null ? "cp-" + engine.stepCount() : name,
                engine.stepCount(), engine.definition().fingerprint, engine.snapshot());
        checkpoints.put(cp.id, cp);
        return cp;
    }

    public Checkpoint checkpoint(String id) {
        Checkpoint cp = checkpoints.get(id);
        if (cp == null) throw new IllegalArgumentException("unknown checkpoint: " + id);
        return cp;
    }

    /** Restore this branch's engine to a checkpoint; rejects cross-definition checkpoints. */
    public void restore(String checkpointId) {
        Checkpoint cp = checkpoint(checkpointId);
        engine = Engine.restore(engine.definition(), cp.snapshot);
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = Json.newObj();
        m.put("id", id);
        m.put("name", name);
        m.put("definitionId", definitionId);
        m.put("definitionVersion", definitionVersion);
        m.put("seed", seed);
        m.put("fingerprint", fingerprint);
        m.put("trajectoryHash", engine.trajectoryHash());
        m.put("state", engine.state());
        m.put("stepCount", engine.stepCount());
        m.put("pending", engine.pendingCount());
        if (ancestorBranchId != null) {
            m.put("ancestorBranchId", ancestorBranchId);
            m.put("ancestorCheckpointId", ancestorCheckpointId);
            m.put("ancestorStep", ancestorStep);
        }
        return m;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = Json.newObj();
        m.put("id", id);
        m.put("name", name);
        m.put("definitionId", definitionId);
        m.put("definitionVersion", definitionVersion);
        m.put("seed", seed);
        m.put("initialState", initialState);
        m.put("initialVariables", Json.deepCopy(initialVariables));
        List<Object> evts = Json.newArr();
        for (Event e : externalEvents) evts.add(e.toJson());
        m.put("externalEvents", evts);
        m.put("fingerprint", fingerprint);
        if (ancestorBranchId != null) {
            m.put("ancestorBranchId", ancestorBranchId);
            m.put("ancestorCheckpointId", ancestorCheckpointId);
            m.put("ancestorStep", ancestorStep);
        }
        m.put("engine", engine.snapshot());
        List<Object> cps = Json.newArr();
        for (Checkpoint cp : checkpoints.values()) cps.add(cp.toJson());
        m.put("checkpoints", cps);
        return m;
    }

    public static Branch fromJson(MachineDefinition def, Map<String, Object> m) {
        List<Event> evts = new ArrayList<>();
        long insertion = 0;
        for (Object e : Json.arr(m.getOrDefault("externalEvents", Json.newArr()))) {
            evts.add(Event.fromJson(Json.obj(e), false, insertion++));
        }
        Branch b = new Branch(
                Json.str(m.get("id")),
                Json.str(m.getOrDefault("name", "main")),
                Json.str(m.get("definitionId")),
                Json.num(m.get("definitionVersion")),
                Json.num(m.get("seed")),
                Json.str(m.get("initialState")),
                Json.obj(Json.deepCopy(m.getOrDefault("initialVariables", Json.newObj()))),
                evts,
                Json.str(m.get("fingerprint")),
                Engine.restore(def, Json.obj(m.get("engine"))));
        if (m.containsKey("ancestorBranchId")) {
            b.ancestorBranchId = Json.str(m.get("ancestorBranchId"));
            b.ancestorCheckpointId = Json.str(m.get("ancestorCheckpointId"));
            b.ancestorStep = Json.num(m.get("ancestorStep"));
        }
        for (Object c : Json.arr(m.getOrDefault("checkpoints", Json.newArr()))) {
            Checkpoint cp = Checkpoint.fromJson(Json.obj(c));
            b.checkpoints.put(cp.id, cp);
        }
        return b;
    }

    public static final class Checkpoint {
        public final String id;
        public final String name;
        public final long step;
        public final String definitionFingerprint;
        public final Map<String, Object> snapshot;

        Checkpoint(String id, String name, long step, String definitionFingerprint,
                   Map<String, Object> snapshot) {
            this.id = id;
            this.name = name;
            this.step = step;
            this.definitionFingerprint = definitionFingerprint;
            this.snapshot = snapshot;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = Json.newObj();
            m.put("id", id);
            m.put("name", name);
            m.put("step", step);
            m.put("definitionFingerprint", definitionFingerprint);
            m.put("snapshot", snapshot);
            return m;
        }

        static Checkpoint fromJson(Map<String, Object> m) {
            return new Checkpoint(
                    Json.str(m.get("id")),
                    Json.str(m.getOrDefault("name", "cp")),
                    Json.num(m.get("step")),
                    Json.str(m.get("definitionFingerprint")),
                    Json.obj(Json.deepCopy(m.get("snapshot"))));
        }
    }
}
