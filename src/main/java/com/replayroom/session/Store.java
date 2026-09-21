package com.replayroom.session;

import com.replayroom.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * File-backed store. Sessions are written as pretty JSON to
 * dataDir/sessions/&lt;id&gt;.json atomically (temp file + ATOMIC_MOVE), and
 * definitions are stored independently under dataDir/definitions/.
 */
public final class Store {

    private final Path dataDir;
    private final Path sessionDir;
    private final Path definitionDir;

    public Store(Path dataDir) {
        this.dataDir = dataDir;
        this.sessionDir = dataDir.resolve("sessions");
        this.definitionDir = dataDir.resolve("definitions");
        try {
            Files.createDirectories(sessionDir);
            Files.createDirectories(definitionDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create data directory " + dataDir, e);
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    public synchronized void saveSession(Session session) {
        Path target = sessionDir.resolve(session.id + ".json");
        writeAtomically(target, Json.write(session.toMap()));
    }

    public synchronized Session loadSession(String sessionId) {
        Path target = sessionDir.resolve(sessionId + ".json");
        if (!Files.isRegularFile(target)) {
            return null;
        }
        try {
            String text = Files.readString(target, StandardCharsets.UTF_8);
            return Session.fromMap(Json.parseObject(text));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read session " + sessionId, e);
        }
    }

    public synchronized List<Session> loadAllSessions() {
        List<Session> sessions = new ArrayList<>();
        if (!Files.isDirectory(sessionDir)) {
            return sessions;
        }
        try (Stream<Path> paths = Files.list(sessionDir)) {
            paths.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            String text = Files.readString(path, StandardCharsets.UTF_8);
                            sessions.add(Session.fromMap(Json.parseObject(text)));
                        } catch (IOException e) {
                            throw new IllegalStateException("cannot read session file " + path, e);
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("cannot list session directory", e);
        }
        return sessions;
    }

    public synchronized void deleteSession(String sessionId) throws IOException {
        Files.deleteIfExists(sessionDir.resolve(sessionId + ".json"));
    }

    public synchronized void saveDefinition(String fingerprint, Map<String, Object> definition) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("fingerprint", fingerprint);
        wrapper.put("definition", definition);
        writeAtomically(definitionDir.resolve(fingerprint.substring(0, 16) + ".json"),
                Json.write(wrapper));
    }

    private void writeAtomically(Path target, String content) {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + target, e);
        }
    }
}
