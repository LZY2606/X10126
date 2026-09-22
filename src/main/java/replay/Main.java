package replay;

import java.nio.file.Path;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5214;
        String data = "data";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host": host = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--data": data = args[++i]; break;
                default: throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }
        Manager manager = new Manager(new Store(Path.of(data)));
        Server server = new Server(manager, host, port);
        server.start();
        System.out.println("状态机回放室 listening on http://" + host + ":" + server.port());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
