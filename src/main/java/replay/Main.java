package replay;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5214;
        Path dataDir = Path.of("data");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> dataDir = Path.of(args[++i]);
                default -> { }
            }
        }
        Store store = new Store(dataDir.resolve("sessions"));
        store.load();
        Server server = new Server(store, host, port);
        server.start();
        System.out.println("状态机回放室 listening on http://" + host + ":" + port);
        Thread.currentThread().join();
    }
}
