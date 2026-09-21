package replayroom.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class FilesCreate {
    private FilesCreate() {}

    static Path tempDir() throws IOException {
        Path dir = Files.createTempDirectory("replay-room-test-");
        dir.toFile().deleteOnExit();
        return dir;
    }
}
