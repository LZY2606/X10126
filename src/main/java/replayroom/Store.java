package replayroom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class Store {
    private final Path path;

    public Store(Path path) {
        this.path = path;
    }

    public Optional<Workspace> load() {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Workspace.fromMap(Json.parse(content)));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read workspace: " + path, e);
        }
    }

    public void save(Workspace workspace) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, Json.write(workspace.toMap()), StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save workspace: " + path, e);
        }
    }
}
