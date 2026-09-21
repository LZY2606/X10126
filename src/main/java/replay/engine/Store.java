package replay.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import replay.Json;

/**
 * File-backed, process-safe (single server) store. Every mutating operation
 * serializes the full session atomically (temp file + move), so a restart can
 * always resume the replay. No external components are used.
 */
public final class Store {

    private final Path dir;
    private Session session;

    public Store(Path dataDir) {
        this.dir = dataDir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create data dir: " + dir, e);
        }
    }

    public synchronized void createNew(Session newSession) {
        this.session = newSession;
        persist();
    }

    public synchronized Session session() {
        if (session == null) {
            throw new Session.ApiException(404, "no active session; create or import one first");
        }
        return session;
    }

    public synchronized boolean exists() {
        return session != null || Files.exists(file());
    }

    public synchronized void load() {
        Path f = file();
        if (!Files.exists(f)) {
            return;
        }
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Map<String, Object> data = Json.parseObject(text);
            session = Session.importVerified(data);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read session file", e);
        }
    }

    public synchronized void save() {
        persist();
    }

    private void persist() {
        if (session == null) {
            return;
        }
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, "session-", ".json.tmp");
            String content = Json.writePretty(session.export());
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("cannot persist session", e);
        }
    }

    private Path file() {
        return dir.resolve("session.json");
    }
}
