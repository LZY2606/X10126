package replayroom.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import replayroom.json.Json;
import replayroom.model.Checkpoint;
import replayroom.model.Definition;

/** File-backed, in-process store for definitions, sessions and checkpoints. */
public final class Store implements BranchMerger.StoreLike {

    private final Path root;
    private final Map<String, Definition> definitions = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Checkpoint> checkpoints = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public Store(Path root) {
        this.root = root;
    }

    public void open() {
        ensureDirectories();
        loadAll(definitionsDir(), this::loadDefinition);
        loadAll(sessionsDir(), this::loadSession);
        loadAll(checkpointsDir(), this::loadCheckpoint);
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(definitionsDir());
            Files.createDirectories(sessionsDir());
            Files.createDirectories(checkpointsDir());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create data directories under " + root, e);
        }
    }

    private Path definitionsDir() { return root.resolve("definitions"); }
    private Path sessionsDir() { return root.resolve("sessions"); }
    private Path checkpointsDir() { return root.resolve("checkpoints"); }

    private void loadAll(Path dir, java.util.function.Consumer<Path> loader) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(p -> p.toString().endsWith(".json")).sorted().forEach(loader);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list " + dir, e);
        }
    }

    private void loadDefinition(Path path) {
        try {
            Map<String, Object> map = Json.object(Json.parse(Files.readString(path, StandardCharsets.UTF_8)), "definition");
            Definition definition = Definition.fromMap(map);
            definitions.put(definition.fingerprint(), definition);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }

    private void loadSession(Path path) {
        try {
            Map<String, Object> map = Json.object(Json.parse(Files.readString(path, StandardCharsets.UTF_8)), "session");
            Session session = Session.fromMap(map);
            sessions.put(session.id(), session);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }

    private void loadCheckpoint(Path path) {
        try {
            Map<String, Object> map = Json.object(Json.parse(Files.readString(path, StandardCharsets.UTF_8)), "checkpoint");
            Checkpoint checkpoint = Checkpoint.fromMap(map);
            checkpoints.put(checkpoint.id(), checkpoint);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }

    public Definition saveDefinition(Definition definition) {
        lock.lock();
        try {
            definitions.put(definition.fingerprint(), definition);
            atomicWrite(definitionsDir().resolve(definition.fingerprint() + ".json"),
                    Json.writePretty(definition.toMap()));
            return definition;
        } finally {
            lock.unlock();
        }
    }

    public Definition definition(String fingerprint) {
        return definitions.get(fingerprint);
    }

    public List<Definition> definitions() {
        return new ArrayList<>(definitions.values());
    }

    public CompiledDefinition compiled(String fingerprint) {
        Definition definition = definitions.get(fingerprint);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown definition fingerprint: " + fingerprint);
        }
        return CompiledDefinition.compile(definition);
    }

    public void saveSession(Session session) {
        lock.lock();
        try {
            sessions.put(session.id(), session);
            atomicWrite(sessionsDir().resolve(session.id() + ".json"),
                    Json.writePretty(session.toMap(false)));
        } finally {
            lock.unlock();
        }
    }

    public Session session(String id) {
        return sessions.get(id);
    }

    public List<Session> sessions() {
        return new ArrayList<>(sessions.values());
    }

    public void saveCheckpoint(Checkpoint checkpoint) {
        lock.lock();
        try {
            checkpoints.put(checkpoint.id(), checkpoint);
            atomicWrite(checkpointsDir().resolve(checkpoint.id() + ".json"),
                    Json.writePretty(checkpoint.toMap()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public BranchMerger.CheckpointRecord checkpoint(String id) {
        return checkpoints.get(id);
    }

    public Checkpoint getCheckpoint(String id) {
        return checkpoints.get(id);
    }

    public List<Checkpoint> checkpoints() {
        return new ArrayList<>(checkpoints.values());
    }

    private void atomicWrite(Path target, String content) {
        try {
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + target, e);
        }
    }

    public Path root() { return root; }
}
