package io.example.replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Store {
    private final Path dataDir;
    private final Path stateFile;

    public Store(Path dataDir) {
        this.dataDir = dataDir;
        this.stateFile = dataDir.resolve("state.json");
    }

    public synchronized AppState load() {
        if (!Files.exists(stateFile)) {
            return new AppState();
        }
        try {
            String text = Files.readString(stateFile, StandardCharsets.UTF_8);
            Map<String, Object> root = Json.object(Json.parse(text));
            AppState state = new AppState();
            if (root.get("definition") != null) {
                state.definition = MachineDefinition.parse(root.get("definition"));
            }
            Object sessions = root.get("sessions");
            if (sessions instanceof List<?> list) {
                for (Object session : list) {
                    Session parsed = Session.fromJson(session);
                    state.sessions.put(parsed.id, parsed);
                }
            }
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load state", e);
        }
    }

    public synchronized void save(AppState state) {
        try {
            Files.createDirectories(dataDir);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("definition", state.definition == null ? null : state.definition.toJson());
            root.put("sessions", state.sessions.values().stream().map(Session::toJson).toList());
            Path temp = dataDir.resolve("state.json.tmp");
            Files.writeString(temp, Json.write(root), StandardCharsets.UTF_8);
            Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save state", e);
        }
    }

    public static Map<String, Object> stateToJson(ReplayState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentState", state.currentState);
        result.put("data", Json.deepCopy(state.data));
        result.put("variables", Json.deepCopy(state.variables));
        result.put("internalQueue", state.internalQueue.stream().map(StoredEvent::toJsonMutable).toList());
        result.put("trace", state.trace.stream().map(TraceStep::toJson).toList());
        result.put("nextInternalSeq", state.nextInternalSeq);
        result.put("randomSeedState", state.randomSeedState);
        result.put("randomDraws", state.randomDraws);
        return result;
    }

    public static ReplayState stateFromJson(Object value) {
        Map<String, Object> object = Json.object(value);
        ReplayState state = new ReplayState();
        state.currentState = Json.string(object, "currentState");
        state.data.putAll(Json.object(Json.deepCopy(object.get("data"))));
        state.variables.putAll(Json.object(Json.deepCopy(object.get("variables"))));
        List<Object> queue = object.get("internalQueue") == null
                ? new ArrayList<>() : Json.list(object.get("internalQueue"));
        for (Object event : queue) {
            state.internalQueue.add(StoredEvent.fromJson(event));
        }
        List<Object> trace = object.get("trace") == null
                ? new ArrayList<>() : Json.list(object.get("trace"));
        for (Object step : trace) {
            state.trace.add(TraceStep.fromJson(step));
        }
        state.nextInternalSeq = Json.longValue(object, "nextInternalSeq", 1L);
        state.randomSeedState = Json.longValue(object, "randomSeedState", 0L);
        state.randomDraws = Json.longValue(object, "randomDraws", 0L);
        return state;
    }
}
