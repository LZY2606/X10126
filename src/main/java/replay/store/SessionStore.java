package replay.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import replay.core.Session;
import replay.json.Json;

/** File-backed session store: one JSON document per session, reloadable after restart. */
public final class SessionStore {
    private final Path dir;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private long sessionCounter;

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
            paths.filter(p -> p.toString().endsWith(".json")).sorted().forEach(p -> {
                try {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    Session s = Session.fromMap(Json.parseObject(text));
                    sessions.put(s.id, s);
                    long n = Long.parseLong(s.id.substring(1));
                    if (n > sessionCounter) sessionCounter = n;
                } catch (Exception e) {
                    System.err.println("skipping unreadable session file " + p + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized String nextId() {
        return "s" + (++sessionCounter);
    }

    public synchronized void put(Session s) {
        sessions.put(s.id, s);
        save(s);
    }

    public synchronized void save(Session s) {
        try {
            Path tmp = dir.resolve(s.id + ".json.tmp");
            Files.writeString(tmp, Json.write(s.toMap()), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(s.id + ".json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Session get(String id) {
        Session s = sessions.get(id);
        if (s == null) throw new IllegalArgumentException("unknown session: " + id);
        return s;
    }

    public synchronized Map<String, Session> all() {
        return new LinkedHashMap<>(sessions);
    }
}
