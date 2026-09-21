package replay;

import java.nio.file.Path;

import replay.engine.Store;
import replay.web.ApiServer;

/**
 * Entry point for the state-machine replay room.
 *
 * Usage: run --host 127.0.0.1 --port 5214
 * Data is persisted under ./data and resumed on restart.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 5214;
        Path dataDir = Path.of("data");

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = requireArg(args, ++i, "--host");
                case "--port" -> port = Integer.parseInt(requireArg(args, ++i, "--port"));
                case "--data" -> dataDir = Path.of(requireArg(args, ++i, "--data"));
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.err.println("usage: --host HOST --port PORT [--data DIR]");
                    System.exit(2);
                }
            }
        }

        Store store = new Store(dataDir);
        store.load();
        if (store.exists()) {
            System.out.println("resumed existing session from " + dataDir.toAbsolutePath());
        } else {
            System.out.println("no saved session found; create one in the web UI");
        }

        ApiServer server = new ApiServer(store, host, port);
        server.start();
        System.out.println("状态机回放室 (state-machine replay room)");
        System.out.println("listening on http://" + host + ":" + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    }

    private static String requireArg(String[] args, int index, String name) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + name);
        }
        return args[index];
    }
}
