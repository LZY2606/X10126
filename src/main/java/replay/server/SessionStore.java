package replay.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import replay.core.Engine;
import replay.json.Json;

/** File-backed session store; every mutation is flushed to disk atomically. */
public final class SessionStore {
    private final Path dir;
    private final Map<String, Engine> sessions = new LinkedHashMap<>();
    private long idCounter;

    public SessionStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        loadAll();
    }

    private void loadAll() {
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                Engine engine = Engine.fromJson(Json.obj(Json.parse(text), "session"));
                sessions.put(engine.id, engine);
                try {
                    idCounter = Math.max(idCounter,
                            Long.parseLong(engine.id.replace("s-", "")) + 1);
                } catch (NumberFormatException ignored) {
                    // imported sessions may carry foreign ids
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Engine create(Map<String, Object> def, List<Object> events, long seed,
            Map<String, Object> initialVars) {
        String id;
        do {
            id = "s-" + (++idCounter);
        } while (sessions.containsKey(id));
        Engine engine = Engine.create(id, def, events, seed, initialVars);
        sessions.put(id, engine);
        save(engine);
        return engine;
    }

    /** Imports an exported session document, keeping its id when free. */
    public synchronized Engine importSession(Map<String, Object> doc) {
        Engine engine = Engine.fromJson(doc);
        String id = engine.id;
        if (sessions.containsKey(id)) {
            id = id + "-re" + (++idCounter);
            engine.id = id;
        }
        sessions.put(engine.id, engine);
        save(engine);
        return engine;
    }

    public synchronized Engine get(String id) {
        Engine engine = sessions.get(id);
        if (engine == null) {
            throw new IllegalArgumentException("unknown session: " + id);
        }
        return engine;
    }

    public synchronized List<Engine> list() {
        return new ArrayList<>(sessions.values());
    }

    public synchronized void save(Engine engine) {
        Path tmp = dir.resolve(engine.id + ".tmp");
        Path target = dir.resolve(engine.id + ".json");
        try {
            Files.writeString(tmp, Json.canonical(engine.toJson()), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
