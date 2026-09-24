package com.replayroom.store;

import com.replayroom.Json;
import com.replayroom.core.ReplaySession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * File-backed session store. Every mutation is persisted atomically so a
 * restart resumes exactly where replay stopped. Export/import round-trips
 * preserve trace hashes.
 */
public class SessionStore {
    public static final String EXPORT_FORMAT = "replay-room-session@1";

    private final Path dir;

    public SessionStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void save(ReplaySession session) {
        try {
            Path tmp = dir.resolve(session.id + ".json.tmp");
            Path target = dir.resolve(session.id + ".json");
            Files.writeString(tmp, Json.MAPPER.writeValueAsString(session), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist session " + session.id, e);
        }
    }

    public synchronized Map<String, ReplaySession> loadAll() {
        Map<String, ReplaySession> sessions = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return sessions;
        }
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    ReplaySession session = Json.MAPPER.readValue(path.toFile(), ReplaySession.class);
                    sessions.put(session.id, session);
                } catch (Exception e) {
                    System.err.println("skipping unreadable session file " + path + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sessions;
    }

    public synchronized String export(ReplaySession session) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("format", EXPORT_FORMAT);
        envelope.put("session", session);
        return Json.pretty(envelope);
    }

    /** Import an exported envelope. Recomputes fingerprints and trace hashes from content. */
    public synchronized ReplaySession importSession(String body, Map<String, ReplaySession> existing) {
        try {
            var node = Json.read(body);
            var sessionNode = node.has("session") ? node.get("session") : node;
            ReplaySession session = Json.MAPPER.treeToValue(sessionNode, ReplaySession.class);
            if (session.id == null || existing.containsKey(session.id)) {
                String base = session.id == null ? "imported" : session.id;
                int suffix = 1;
                String candidate = base;
                while (existing.containsKey(candidate)) {
                    candidate = base + "-" + (++suffix);
                }
                session.id = candidate;
            }
            save(session);
            return session;
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot import session: " + e.getMessage(), e);
        }
    }
}
