package replay.core;

import replay.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** File-backed persistence: the whole session is one JSON document, written atomically. */
public final class Store {
    private final Path file;

    public Store(Path dir) {
        this.file = dir.resolve("session.json");
    }

    public boolean exists() {
        return Files.exists(file);
    }

    public Session load() {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> m = Json.parseObject(text);
            return Session.fromJson(m);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load session from " + file, e);
        }
    }

    public void save(Session session) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("session.json.tmp");
            Files.writeString(tmp, Json.writePretty(session.toJson()), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("cannot save session to " + file, e);
        }
    }
}
