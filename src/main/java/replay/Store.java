package replay;

import replay.Model.Session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** File-backed session store: every mutation is persisted as JSON. */
public class Store {
    private final Path dir;
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public Store(Path dir) {
        this.dir = dir;
    }

    public synchronized void load() {
        sessions.clear();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path p : paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    Session s = Json.M.readValue(Files.readString(p), Session.class);
                    sessions.put(s.id, s);
                } catch (Exception e) {
                    System.err.println("skipping unreadable session file " + p + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized List<Session> list() {
        return new ArrayList<>(sessions.values());
    }

    public synchronized Session get(String id) {
        Session s = sessions.get(id);
        if (s == null) throw new Model.ApiException(404, "no such session: " + id);
        return s;
    }

    public synchronized void put(Session s) {
        sessions.put(s.id, s);
        persist(s);
    }

    public synchronized void delete(String id) {
        Session removed = sessions.remove(id);
        if (removed == null) throw new Model.ApiException(404, "no such session: " + id);
        try {
            Files.deleteIfExists(file(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(String id) {
        return dir.resolve("session-" + id + ".json");
    }

    private void persist(Session s) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve("session-" + s.id + ".tmp");
            Files.writeString(tmp, Json.pretty(s));
            Files.move(tmp, file(s.id), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
