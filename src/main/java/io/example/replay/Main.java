package io.example.replay;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 5214;
        Path dataDir = Path.of("data");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> dataDir = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        Store store = new Store(dataDir);
        ReplayService service = new ReplayService(store);
        if (service.definition() == null) {
            service.updateDefinition(SampleData.definition());
        }
        HttpServer server = new HttpServer(service);
        server.start(host, port);
        System.out.println("状态机回放室 listening on http://" + host + ":" + server.port());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
