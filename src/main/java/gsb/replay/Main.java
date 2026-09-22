package gsb.replay;

import java.nio.file.Path;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5214;
        Path dataDir = Path.of("data");

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    host = args[++i];
                    break;
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--data":
                    dataDir = Path.of(args[++i]);
                    break;
                default:
                    System.err.println("unknown argument: " + args[i]);
                    usage();
                    System.exit(2);
            }
        }

        HttpServer server = new HttpServer(dataDir);
        server.start(host, port);
        System.out.println("状态机回放室已启动: http://" + host + ":" + server.port());
        System.out.println("数据目录: " + dataDir.toAbsolutePath());

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

    private static void usage() {
        System.err.println("用法: run --host 127.0.0.1 --port 5214 [--data data]");
    }
}
