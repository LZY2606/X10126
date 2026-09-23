package replay.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** A replay line with its own engine, trace and checkpoints. */
public final class Branch {
    public String id;
    public String name;
    public Engine engine;
    public Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();
    public String forkedFrom; // nullable branch id
    public long checkpointCounter;

    public Branch(String id, String name, Engine engine) {
        this.id = id;
        this.name = name;
        this.engine = engine;
    }

    public Checkpoint createCheckpoint(String name) {
        Checkpoint c = new Checkpoint();
        c.id = "c" + (++checkpointCounter);
        c.name = name == null || name.isEmpty() ? c.id : name;
        c.defFingerprint = engine.def.fingerprint();
        c.engineSnapshot = engine.snapshotToMap();
        checkpoints.put(c.id, c);
        return c;
    }

    /** Restore this branch to a checkpoint; rejects snapshots from another definition version. */
    public void restoreCheckpoint(String checkpointId) {
        Checkpoint c = checkpoints.get(checkpointId);
        if (c == null) throw new IllegalArgumentException("unknown checkpoint: " + checkpointId);
        String currentFp = engine.def.fingerprint();
        if (!c.defFingerprint.equals(currentFp)) {
            throw new IllegalStateException(
                "checkpoint " + c.id + " belongs to definition " + c.defFingerprint.substring(0, 12)
                + " but current definition is " + currentFp.substring(0, 12));
        }
        engine.restoreFromMap(c.engineSnapshot);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("forkedFrom", forkedFrom);
        m.put("checkpointCounter", checkpointCounter);
        m.put("engine", engine.snapshotToMap());
        Map<String, Object> cps = new LinkedHashMap<>();
        checkpoints.forEach((k, v) -> cps.put(k, v.toMap()));
        m.put("checkpoints", cps);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Branch fromMap(Map<String, Object> m, Definition def) {
        Engine engine = new Engine(def, 0);
        Branch b = new Branch(EventInstance.str(m.get("id")), EventInstance.str(m.get("name")), engine);
        b.forkedFrom = EventInstance.str(m.get("forkedFrom"));
        b.checkpointCounter = EventInstance.num(m.get("checkpointCounter"));
        engine.restoreFromMap((Map<String, Object>) m.get("engine"));
        if (m.get("checkpoints") instanceof Map) {
            ((Map<String, Object>) m.get("checkpoints")).forEach((k, v) ->
                b.checkpoints.put(k, Checkpoint.fromMap((Map<String, Object>) v)));
        }
        return b;
    }
}
