package replay;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 5214;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--host".equals(args[i])) host = args[i + 1];
            if ("--port".equals(args[i])) port = Integer.parseInt(args[i + 1]);
        }
        WebServer server = WebServer.create(Path.of("data"), host, port);
        server.start();
        System.out.println("状态机回放室: http://" + host + ":" + server.port());
    }

    private Main() {
    }
}
