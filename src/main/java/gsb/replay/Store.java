package gsb.replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简单文件持久化：data 目录下每个会话一个 JSON 文件，原子写入（临时文件 + rename）。
 * 重启后可继续回放；不连接任何外部组件。
 */
public final class Store {
    private final Path dir;

    public Store(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create data dir: " + dir, e);
        }
    }

    public Path sessionFile(String sessionId) {
        return dir.resolve(sessionId + ".json");
    }

    public synchronized void save(String sessionId, Map<String, Object> session) {
        Path target = sessionFile(sessionId);
        Path tmp = dir.resolve(sessionId + ".json.tmp");
        try {
            Files.writeString(tmp, Json.pretty(session), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
            throw new IllegalStateException("cannot save session: " + target, e);
        }
    }

    public synchronized Map<String, Object> load(String sessionId) {
        Path target = sessionFile(sessionId);
        if (!Files.exists(target)) {
            return null;
        }
        try {
            String text = Files.readString(target, StandardCharsets.UTF_8);
            return Json.parseObject(text);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load session: " + target, e);
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> loadOrCreate(String sessionId, Map<String, Object> demoDefinition) {
        Map<String, Object> existing = load(sessionId);
        if (existing != null) {
            return existing;
        }
        Replay replay = new Replay();
        Map<String, Object> session = replay.createSession(demoDefinition);
        Demo.seedDemo(replay, session);
        save(sessionId, session);
        return session;
    }

    public synchronized Map<String, String> listSessions() throws IOException {
        Map<String, String> ids = new LinkedHashMap<>();
        if (!Files.exists(dir)) {
            return ids;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        String n = p.getFileName().toString();
                        ids.put(n.substring(0, n.length() - 5), n);
                    });
        }
        return ids;
    }

    public Path directory() {
        return dir;
    }
}
