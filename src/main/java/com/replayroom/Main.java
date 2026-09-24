package com.replayroom;

import com.replayroom.server.ApiServer;
import com.replayroom.store.SessionStore;

import java.nio.file.Path;

/** Entry point: {@code --host 127.0.0.1 --port 5214 [--data data]}. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5214;
        String data = "data";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> data = args[++i];
                default -> {
                    if (!args[i].startsWith("--")) {
                        throw new IllegalArgumentException("unknown argument: " + args[i]);
                    }
                }
            }
        }
        ApiServer server = new ApiServer(new SessionStore(Path.of(data, "sessions")));
        server.start(host, port);
        System.out.println("状态机回放室 listening on http://" + host + ":" + port);
        Thread.currentThread().join();
    }
}
