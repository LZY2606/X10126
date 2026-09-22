package replay;

import java.nio.file.Path;

/** Entry point: --host 127.0.0.1 --port 5214 [--file data/session.json]. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5214;
        Path file = Path.of("data", "session.json");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    host = args[++i];
                    break;
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--file":
                    file = Path.of(args[++i]);
                    break;
                default:
                    System.err.println("unknown argument: " + args[i]);
                    System.err.println("usage: --host HOST --port PORT [--file PATH]");
                    System.exit(2);
            }
        }
        ProjectStore store = new ProjectStore(file);
        WebServer server = new WebServer(store, host, port);
        server.start();
        System.out.println("状态机回放室已启动: http://" + host + ":" + server.port());
        System.out.println("数据文件: " + file.toAbsolutePath());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
