package replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** File-backed session store; every mutation is flushed so a restart resumes the replay. */
public final class Store {
    private final Path dir;

    public Store(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("cannot create data dir " + dir, e);
        }
    }

    public synchronized void save(Session s) {
        try {
            Path tmp = dir.resolve("session-" + s.id + ".json.tmp");
            Path dst = dir.resolve("session-" + s.id + ".json");
            Files.writeString(tmp, Json.write(s.toJson()), StandardCharsets.UTF_8);
            Files.move(tmp, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("failed to persist session " + s.id, e);
        }
    }

    public synchronized Map<String, Session> loadAll() {
        Map<String, Session> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return out;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> st = Files.list(dir)) {
            st.filter(p -> p.getFileName().toString().startsWith("session-")
                    && p.getFileName().toString().endsWith(".json")).forEach(files::add);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        files.sort(null);
        for (Path f : files) {
            try {
                Session s = Session.fromJson(Json.parseObject(Files.readString(f, StandardCharsets.UTF_8)));
                out.put(s.id, s);
            } catch (Exception e) {
                System.err.println("skipping unreadable session file " + f + ": " + e.getMessage());
            }
        }
        return out;
    }
}
