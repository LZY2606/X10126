package com.replay.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.replay.core.Session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** JSON-file session store. Every mutation is persisted atomically (tmp file + rename). */
public final class SessionStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dir;
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public SessionStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void loadAll() {
        sessions.clear();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
                try {
                    JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                    Session s = Session.fromJson(o);
                    sessions.put(s.id, s);
                } catch (Exception e) {
                    System.err.println("skipping unreadable session file " + p + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void save(Session s) {
        try {
            Path tmp = dir.resolve(s.id + ".json.tmp");
            Files.writeString(tmp, GSON.toJson(s.toJson()), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(s.id + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void delete(String id) {
        sessions.remove(id);
        try {
            Files.deleteIfExists(dir.resolve(id + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized List<Session> list() {
        return new ArrayList<>(sessions.values());
    }

    public synchronized Session get(String id) {
        Session s = sessions.get(id);
        if (s == null) throw new IllegalArgumentException("unknown session: " + id);
        return s;
    }

    public synchronized void put(Session s) {
        sessions.put(s.id, s);
        save(s);
    }
}
