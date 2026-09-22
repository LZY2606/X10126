package replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class Persistence {
    private static Path path;

    private Persistence() {}

    static synchronized void configure(Path storagePath) {
        path = storagePath;
    }

    static synchronized ReplayRoom loadOrCreate(Models.Definition defaultDefinition) {
        if (path != null && Files.exists(path)) {
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                if (!text.isBlank()) return ReplayRoom.importSession(Json.parse(text));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read session data: " + path, e);
            }
        }
        return new ReplayRoom(defaultDefinition);
    }

    static synchronized void saveIfConfigured(ReplayRoom room) {
        if (path == null) return;
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, room.exportJson(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist session data: " + path, e);
        }
    }
}
