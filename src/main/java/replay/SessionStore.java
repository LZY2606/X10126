package replay;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** File-backed session store. Sessions persist as JSON so a restart resumes replay exactly. */
public final class SessionStore {
    private final Path dir;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

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
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                try {
                    Session s = Session.fromJson(Json.parseObject(Files.readString(f, StandardCharsets.UTF_8)));
                    sessions.put(s.id, s);
                } catch (Exception e) {
                    System.err.println("skipping unreadable session file " + f + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Session> list() { return new ArrayList<>(sessions.values()); }

    public Session get(String id) {
        Session s = sessions.get(id);
        if (s == null) throw new IllegalArgumentException("no such session: " + id);
        return s;
    }

    public synchronized void put(Session s) {
        sessions.put(s.id, s);
        save(s);
    }

    public synchronized void save(Session s) {
        try {
            Path tmp = dir.resolve(s.id + ".json.tmp");
            Files.writeString(tmp, Json.canonical(s.toJson()), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(s.id + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Import an exported session document; verifies the trajectory hash before accepting. */
    public synchronized Session importSession(Map<String, Object> doc) {
        Session s = Session.fromJson(doc);
        Object expected = doc.get("trajectoryHash");
        if (expected != null && !s.trajectoryHash().equals(expected)) {
            throw new IllegalStateException("imported trajectory hash mismatch: expected " + expected
                    + " got " + s.trajectoryHash());
        }
        if (sessions.containsKey(s.id)) {
            // Re-import under a fresh id to avoid clobbering; trajectory stays identical.
            Map<String, Object> renamed = new LinkedHashMap<>(doc);
            renamed.put("id", Session.newId());
            s = Session.fromJson(renamed);
        }
        sessions.put(s.id, s);
        save(s);
        return s;
    }
}
