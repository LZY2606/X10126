package replayroom;

import java.net.URL;
import java.nio.file.Path;
import replayroom.engine.Store;
import replayroom.web.ApiService;
import replayroom.web.HttpServer;

public final class Main {

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 5214;
        String dataDir = "data";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = requireArg(args, ++i, "--host");
                case "--port" -> port = Integer.parseInt(requireArg(args, ++i, "--port"));
                case "--data" -> dataDir = requireArg(args, ++i, "--data");
                case "--help", "-h" -> {
                    System.out.println("Usage: run --host 127.0.0.1 --port 5214 [--data data]");
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        Store store = new Store(Path.of(dataDir));
        store.open();
        ApiService api = new ApiService(store);
        Path webRoot = locateWebRoot();
        HttpServer server = new HttpServer(api, webRoot);
        server.start(host, port);
        System.out.println("状态机回放室 listening on http://" + host + ":" + port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

    private static String requireArg(String[] args, int index, String name) {
        if (index >= args.length) throw new IllegalArgumentException(name + " requires a value");
        return args[index];
    }

    private static Path locateWebRoot() {
        URL resource = Main.class.getClassLoader().getResource("web/index.html");
        if (resource != null && "file".equals(resource.getProtocol())) {
            Path classesPath = Path.of(resource.getFile()).getParent();
            Path sourcePath = Path.of("src/main/resources/web");
            if (java.nio.file.Files.isDirectory(sourcePath)) return sourcePath.toAbsolutePath();
            return classesPath;
        }
        Path sourcePath = Path.of("src/main/resources/web");
        if (java.nio.file.Files.isDirectory(sourcePath)) return sourcePath.toAbsolutePath();
        return Path.of("web").toAbsolutePath();
    }
}
