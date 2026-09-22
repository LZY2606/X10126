package replay;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5214;
        String dataDir = "data";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host": host = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--data": dataDir = args[++i]; break;
                default: break;
            }
        }
        Store store = new Store(Path.of(dataDir));
        store.load();
        Server server = new Server(store, host, port);
        server.start();
        System.out.println("状态机回放室 listening on http://" + host + ":" + port);
        Thread.currentThread().join();
    }
}
