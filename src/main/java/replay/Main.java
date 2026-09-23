package replay;

import replay.core.Session;
import replay.core.Store;
import replay.server.ApiServer;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 8080;
        Path dataDir = Path.of("data");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host": host = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--data-dir": dataDir = Path.of(args[++i]); break;
                default:
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
            }
        }
        Store store = new Store(dataDir);
        Session session = store.exists() ? store.load() : null;
        ApiServer server = new ApiServer(host, port, session, store);
        server.start();
        System.out.println("状态机回放室 listening on http://" + host + ":" + port);
    }
}
