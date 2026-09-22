package replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** File-based persistence: one JSON file per session, rewritten on each mutation. */
public final class Store {
    private final Path dir;

    public Store(Path dir) {
        this.dir = dir;
    }

    public void save(Session session) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(session.id + ".json.tmp");
            Files.writeString(tmp, Json.write(session.toJson()), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(session.id + ".json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist session " + session.id, e);
        }
    }

    public List<Session> loadAll() {
        List<Session> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path p : paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    out.add(Session.fromJson(Json.parseObject(Files.readString(p, StandardCharsets.UTF_8))));
                } catch (RuntimeException e) {
                    System.err.println("skipping unreadable session file " + p + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to load sessions from " + dir, e);
        }
        return out;
    }
}
