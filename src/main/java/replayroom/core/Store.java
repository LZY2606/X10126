package replayroom.core;

import replayroom.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** 会话落盘存储：每个会话一个 JSON 文件，重启后整体加载继续回放。 */
public class Store {
    private final Path dir;
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public Store(Path dir) {
        this.dir = dir;
    }

    public synchronized void load() {
        sessions.clear();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path p : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".json"))::iterator) {
                try {
                    Session s = Json.M.readValue(Files.readString(p, StandardCharsets.UTF_8), Session.class);
                    s.reattach();
                    sessions.put(s.id, s);
                } catch (Exception e) {
                    System.err.println("跳过无法加载的会话文件 " + p + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法读取数据目录 " + dir, e);
        }
    }

    public synchronized void save(Session s) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(s.id + ".json.tmp");
            Path target = dir.resolve(s.id + ".json");
            Files.writeString(tmp, Json.M.writerWithDefaultPrettyPrinter().writeValueAsString(s),
                    StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("无法保存会话 " + s.id, e);
        }
    }

    public synchronized void put(Session s) {
        sessions.put(s.id, s);
        save(s);
    }

    public synchronized Session get(String id) {
        Session s = sessions.get(id);
        if (s == null) {
            throw new IllegalArgumentException("会话不存在: " + id);
        }
        return s;
    }

    public synchronized List<Session> list() {
        return new ArrayList<>(sessions.values());
    }

    public synchronized List<Session> branchesOf(String sessionId) {
        List<Session> out = new ArrayList<>();
        for (Session s : sessions.values()) {
            if (sessionId.equals(s.parentId)) {
                out.add(s);
            }
        }
        return out;
    }

    public String newId() {
        return "s-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
