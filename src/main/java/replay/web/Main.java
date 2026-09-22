package replay.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import replay.json.Json;
import replay.json.JsonUtil;
import replay.store.ProjectStore;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        String host = "127.0.0.1";
        int port = 5214;
        Path dataDirectory = Path.of("data");
        for (int i = 0; i < args.length; i++) {
            if ("--host".equals(args[i]) && i + 1 < args.length) {
                host = args[++i];
            } else if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--data".equals(args[i]) && i + 1 < args.length) {
                dataDirectory = Path.of(args[++i]);
            }
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        WebApi api = new WebApi(new ProjectStore(dataDirectory));
        server.createContext("/", api::handle);
        server.start();
        System.out.println("状态机回放室 listening at http://" + host + ":" + port);
    }
}
