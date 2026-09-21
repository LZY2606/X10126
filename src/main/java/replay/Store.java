package replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/** File-backed session store: every mutation is persisted, restart resumes replay. */
public final class Store {
    private final Path dir;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private long sessionCounter;

    public Store(Path dir) {
        this.dir = dir;
    }

    public synchronized void load() {
        try {
            Files.createDirectories(dir);
            try (Stream<Path> paths = Files.list(dir)) {
                for (Path p : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    Session s = Session.fromJson(Json.asMap(Json.parse(Files.readString(p, StandardCharsets.UTF_8))));
                    sessions.put(s.id, s);
                    String num = s.id.replaceAll("\\D", "");
                    if (!num.isEmpty()) sessionCounter = Math.max(sessionCounter, Long.parseLong(num));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to load sessions from " + dir, e);
        }
    }

    public synchronized Session create(String name, Map<String, Object> definitionRaw,
                                       long seed, Map<String, Integer> priorities) {
        Session s = new Session("s" + (++sessionCounter), name,
                new Model.Definition(definitionRaw), seed, priorities);
        sessions.put(s.id, s);
        save(s);
        return s;
    }

    public synchronized void put(Session s) {
        sessions.put(s.id, s);
        save(s);
    }

    public synchronized Session get(String id) {
        Session s = sessions.get(id);
        if (s == null) throw new ApiException(404, "session not found: " + id);
        return s;
    }

    public synchronized Map<String, Session> all() {
        return new LinkedHashMap<>(sessions);
    }

    public synchronized void save(Session s) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(s.id + ".json.tmp");
            Files.writeString(tmp, Json.pretty(s.toJson()), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(s.id + ".json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("failed to persist session " + s.id, e);
        }
    }
}
