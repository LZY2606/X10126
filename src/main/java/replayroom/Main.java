package replayroom;

import replayroom.core.Store;
import replayroom.server.ApiServer;

import java.nio.file.Path;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5214;
        String dataDir = "data";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    host = args[++i];
                    break;
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--data-dir":
                    dataDir = args[++i];
                    break;
                default:
                    System.err.println("未知参数: " + args[i]);
                    System.exit(2);
            }
        }
        Store store = new Store(Path.of(dataDir));
        store.load();
        ApiServer server = new ApiServer(store, host, port);
        server.start();
        System.out.println("状态机回放室已启动: http://" + host + ":" + port + " (数据目录: " + dataDir + ")");
        Thread.currentThread().join();
    }
}
