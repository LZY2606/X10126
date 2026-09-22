package replay;

import java.nio.file.Path;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5214;
        Path storage = Path.of("data", "session.json");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> storage = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        Persistence.configure(storage);
        ReplayRoom room = Persistence.loadOrCreate(Demo.definition());
        ApiServer server = ApiServer.start(host, port, room);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        System.out.println("状态机回放室 listening on http://" + host + ":" + port);
    }
}
