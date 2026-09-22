package replay;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registry of definitions and branches with JSON-file persistence. */
public final class Store {
    private final Path dir;
    private final Map<String, List<MachineDefinition>> definitions = new LinkedHashMap<>();
    private final Map<String, Branch> branches = new LinkedHashMap<>();

    public Store(Path dir) {
        this.dir = dir;
    }

    public synchronized void load() {
        definitions.clear();
        branches.clear();
        Path defFile = dir.resolve("definitions.json");
        if (Files.exists(defFile)) {
            for (Object raw : Json.arr(Json.parse(read(defFile)))) {
                MachineDefinition def = MachineDefinition.parse(Json.obj(raw));
                definitions.computeIfAbsent(def.id, k -> new ArrayList<>()).add(def);
            }
        }
        Path branchDir = dir.resolve("branches");
        if (Files.isDirectory(branchDir)) {
            try (var stream = Files.list(branchDir)) {
                for (Path f : stream.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    Map<String, Object> m = Json.parseObj(read(f));
                    MachineDefinition def = definition(Json.str(m.get("definitionId")),
                            Json.num(m.get("definitionVersion")));
                    Branch b = Branch.fromJson(def, m);
                    branches.put(b.id, b);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    // ---------- definitions ----------

    public synchronized MachineDefinition saveDefinition(Map<String, Object> raw) {
        MachineDefinition def = MachineDefinition.parse(raw);
        List<MachineDefinition> versions = definitions.computeIfAbsent(def.id, k -> new ArrayList<>());
        for (MachineDefinition existing : versions) {
            if (existing.version == def.version) {
                if (!existing.fingerprint.equals(def.fingerprint)) {
                    throw new IllegalArgumentException(
                            "definition " + def.id + " version " + def.version
                                    + " already exists with different content; bump the version");
                }
                return existing;
            }
        }
        versions.add(def);
        versions.sort(Comparator.comparingLong(d -> d.version));
        persistDefinitions();
        return def;
    }

    public synchronized MachineDefinition definition(String id, long version) {
        List<MachineDefinition> versions = definitions.get(id);
        if (versions == null) throw new IllegalArgumentException("unknown definition: " + id);
        for (MachineDefinition d : versions) if (d.version == version) return d;
        throw new IllegalArgumentException("unknown version " + version + " of definition " + id);
    }

    public synchronized MachineDefinition latestDefinition(String id) {
        List<MachineDefinition> versions = definitions.get(id);
        if (versions == null || versions.isEmpty()) {
            throw new IllegalArgumentException("unknown definition: " + id);
        }
        return versions.get(versions.size() - 1);
    }

    public synchronized List<Map<String, Object>> definitionSummaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<MachineDefinition> versions : definitions.values()) {
            for (MachineDefinition d : versions) {
                Map<String, Object> m = Json.newObj();
                m.put("id", d.id);
                m.put("version", d.version);
                m.put("fingerprint", d.fingerprint);
                m.put("definition", d.raw);
                out.add(m);
            }
        }
        return out;
    }

    // ---------- branches ----------

    public synchronized Branch createBranch(String definitionId, Long version, String name, long seed,
                                            String initialState, Map<String, Object> initialVars,
                                            List<Event> events) {
        MachineDefinition def = version == null ? latestDefinition(definitionId)
                : definition(definitionId, version);
        Branch b = Branch.create(def, name, seed, initialState, initialVars, events);
        branches.put(b.id, b);
        persistBranch(b);
        return b;
    }

    public synchronized Branch branch(String id) {
        Branch b = branches.get(id);
        if (b == null) throw new IllegalArgumentException("unknown branch: " + id);
        return b;
    }

    public synchronized List<Branch> branches() {
        return new ArrayList<>(branches.values());
    }

    public synchronized void save(Branch b) {
        persistBranch(b);
    }

    /** Fork a new branch from a checkpoint of an existing branch. */
    public synchronized Branch fork(String branchId, String checkpointId, String name) {
        Branch parent = branch(branchId);
        Branch.Checkpoint cp = parent.checkpoint(checkpointId);
        MachineDefinition def = definition(parent.definitionId, parent.definitionVersion);
        Engine engine = Engine.restore(def, cp.snapshot); // validates fingerprint

        Branch fork = Branch.create(def, name, parent.seed, null, null, List.of());
        // replace the fresh engine with the checkpoint state
        fork.engine = engine;
        fork.ancestorBranchId = parent.id;
        fork.ancestorCheckpointId = cp.id;
        fork.ancestorStep = cp.step;
        branches.put(fork.id, fork);
        persistBranch(fork);
        return fork;
    }

    /** Restore a branch to one of its checkpoints (fingerprint-validated). */
    public synchronized void restoreCheckpoint(String branchId, String checkpointId) {
        Branch b = branch(branchId);
        b.restore(checkpointId);
        persistBranch(b);
    }

    /**
     * Merge `fromId` into `intoId`: allowed only when both branches share the
     * fork checkpoint as common ancestor and their external events since that
     * ancestor are compatible. Produces a new merged branch re-derived from the
     * ancestor checkpoint — final states are never copied across.
     */
    public synchronized Branch merge(String intoId, String fromId) {
        Branch into = branch(intoId);
        Branch from = branch(fromId);
        if (into.id.equals(from.id)) throw new IllegalArgumentException("cannot merge a branch into itself");

        Branch.Checkpoint ancestor = findCommonAncestor(into, from);
        MachineDefinition def = definition(into.definitionId, into.definitionVersion);

        List<Event> eventsA = into.engine.externalEventsSince(ancestor.step);
        List<Event> eventsB = from.engine.externalEventsSince(ancestor.step);
        List<Event> union = Merger.compatibleUnion(def, eventsA, eventsB);

        Branch merged = Branch.create(def, "merge(" + into.name + "+" + from.name + ")",
                into.seed, null, null, List.of());
        merged.engine = Engine.restore(def, ancestor.snapshot);
        merged.ancestorBranchId = into.id;
        merged.ancestorCheckpointId = ancestor.id;
        merged.ancestorStep = ancestor.step;
        long insertion = 1_000_000_000L;
        List<Event> fresh = new ArrayList<>();
        for (Event e : union) {
            fresh.add(new Event(e.id, e.time, e.source, e.seq, e.name, e.payload, false, insertion++));
        }
        merged.engine.enqueueExternal(fresh);
        branches.put(merged.id, merged);
        persistBranch(merged);
        return merged;
    }

    private Branch.Checkpoint findCommonAncestor(Branch into, Branch from) {
        // case 1: `from` was forked from a checkpoint of `into`
        if (into.id.equals(from.ancestorBranchId) && into.checkpoints.containsKey(from.ancestorCheckpointId)) {
            return into.checkpoints.get(from.ancestorCheckpointId);
        }
        // case 2: `into` was forked from a checkpoint of `from`
        if (from.id.equals(into.ancestorBranchId) && from.checkpoints.containsKey(into.ancestorCheckpointId)) {
            return from.checkpoints.get(into.ancestorCheckpointId);
        }
        // case 3: siblings forked from the same checkpoint of the same parent
        if (into.ancestorBranchId != null && into.ancestorBranchId.equals(from.ancestorBranchId)
                && into.ancestorCheckpointId != null
                && into.ancestorCheckpointId.equals(from.ancestorCheckpointId)) {
            Branch parent = branches.get(into.ancestorBranchId);
            if (parent != null && parent.checkpoints.containsKey(into.ancestorCheckpointId)) {
                return parent.checkpoints.get(into.ancestorCheckpointId);
            }
        }
        throw new IllegalArgumentException(
                "branches " + into.id + " and " + from.id + " share no common ancestor checkpoint");
    }

    // ---------- diff / export / import ----------

    public synchronized Map<String, Object> diff(String aId, String bId) {
        Branch a = branch(aId);
        Branch b = branch(bId);
        Map<String, Object> m = Json.newObj();
        m.put("a", a.summary());
        m.put("b", b.summary());
        m.put("stateEqual", a.engine.state().equals(b.engine.state()));
        m.put("trajectoryHashEqual", a.engine.trajectoryHash().equals(b.engine.trajectoryHash()));

        Map<String, Object> varDiff = Json.newObj();
        Map<String, Object> av = a.engine.vars();
        Map<String, Object> bv = b.engine.vars();
        java.util.Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(av.keySet());
        keys.addAll(bv.keySet());
        for (String k : keys) {
            Object x = av.get(k);
            Object y = bv.get(k);
            if (!java.util.Objects.equals(x, y)) {
                Map<String, Object> d = Json.newObj();
                d.put("a", x);
                d.put("b", y);
                varDiff.put(k, d);
            }
        }
        m.put("varDiff", varDiff);
        m.put("outputsA", outputsOf(a));
        m.put("outputsB", outputsOf(b));
        return m;
    }

    private List<Object> outputsOf(Branch b) {
        List<Object> out = Json.newArr();
        for (Map<String, Object> entry : b.engine.trace()) {
            for (Object o : Json.arr(entry.getOrDefault("outputs", Json.newArr()))) out.add(o);
        }
        return out;
    }

    public synchronized Map<String, Object> exportBranch(String id) {
        Branch b = branch(id);
        MachineDefinition def = definition(b.definitionId, b.definitionVersion);
        Map<String, Object> m = Json.newObj();
        m.put("format", "state-machine-replay-export");
        m.put("formatVersion", 1);
        m.put("definition", def.raw);
        m.put("branch", b.toJson());
        return m;
    }

    public synchronized Branch importBranch(Map<String, Object> exportJson) {
        MachineDefinition def = saveDefinition(Json.obj(exportJson.get("definition")));
        Map<String, Object> branchJson = Json.obj(exportJson.get("branch"));
        // re-resolve against the stored definition to guarantee identity of content
        branchJson.put("definitionId", def.id);
        branchJson.put("definitionVersion", def.version);
        Branch imported = Branch.fromJson(def, branchJson);
        if (branches.containsKey(imported.id)) {
            imported = Branch.fromJson(def, branchJson);
            // assign a fresh id on collision while keeping all replay content identical
            String newId = Branch.newId();
            branchJson.put("id", newId);
            imported = Branch.fromJson(def, branchJson);
        }
        branches.put(imported.id, imported);
        persistBranch(imported);
        return imported;
    }

    // ---------- persistence ----------

    private void persistDefinitions() {
        List<Object> raws = Json.newArr();
        for (List<MachineDefinition> versions : definitions.values()) {
            for (MachineDefinition d : versions) raws.add(d.raw);
        }
        write(dir.resolve("definitions.json"), Json.pretty(raws));
    }

    private void persistBranch(Branch b) {
        try {
            Files.createDirectories(dir.resolve("branches"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        write(dir.resolve("branches").resolve(b.id + ".json"), Json.pretty(b.toJson()));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path p, String content) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
