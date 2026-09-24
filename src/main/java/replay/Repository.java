package replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class Repository {
    private final Path file;

    Repository(Path directory) {
        try {
            Files.createDirectories(directory);
            this.file = directory.resolve("replay-room.json");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    synchronized Store load() {
        if (!Files.exists(file)) return null;
        try {
            return Store.fromMap(Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)), "store"));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    synchronized void save(Store store) {
        try {
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, Json.write(store.toMap()), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
