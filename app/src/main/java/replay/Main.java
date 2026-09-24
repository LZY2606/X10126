package replay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Main {
    private final Store store;
    private final HttpServer server;

    private Main(Store store, HttpServer server) {
        this.store = store;
        this.server = server;
    }

    public static void main(String[] args) throws IOException {
        String host = "127.0.0.1";
        int port = 5214;
        String data = "data/replay-room.json";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> data = args[++i];
                case "--help" -> {
                    System.out.println("Usage: ./gradlew run --args='--host 127.0.0.1 --port 5214'");
                    return;
                }
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        Store store = new Store(Path.of(data));
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        Main application = new Main(store, server);
        application.routes();
        server.start();
        System.out.println("状态机回放室 listening at http://" + host + ":" + port);
    }

    private void routes() {
        server.createContext("/", this::handle);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            Request request = new Request(exchange);
            if ("GET".equals(request.method) && (request.path.equals("/") || request.path.equals("/index.html"))) {
                staticFile(exchange, "/web/index.html", "text/html; charset=utf-8");
            } else if ("GET".equals(request.method) && request.path.equals("/app.js")) {
                staticFile(exchange, "/web/app.js", "application/javascript; charset=utf-8");
            } else if ("GET".equals(request.method) && request.path.equals("/styles.css")) {
                staticFile(exchange, "/web/styles.css", "text/css; charset=utf-8");
            } else if (request.path.startsWith("/api/")) {
                api(exchange, request);
            } else {
                error(exchange, 404, "not found");
            }
        } catch (RuntimeException e) {
            error(exchange, 400, e.getMessage());
        }
    }

    private void api(HttpExchange exchange, Request request) throws IOException {
        String path = request.path;
        if ("GET".equals(request.method) && path.equals("/api/state")) {
            json(exchange, 200, store.state());
        } else if ("POST".equals(request.method) && path.equals("/api/sessions")) {
            Map<String, Object> body = request.body();
            json(exchange, 201, store.createSession(
                    Json.optionalString(body, "name", ""),
                    Json.object(body.get("definition")),
                    Json.longValue(body, "seed", 0L)));
        } else if ("POST".equals(request.method) && path.equals("/api/validate")) {
            Map<String, Object> definition = Json.object(request.body().get("definition"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", true);
            result.put("fingerprint", Engine.definitionFingerprint(definition));
            json(exchange, 200, result);
        } else if ("POST".equals(request.method) && path.equals("/api/sessions/import")) {
            json(exchange, 201, store.importSession(request.bodyObject()));
        } else {
            String[] parts = path.split("/");
            if (parts.length >= 4 && "api".equals(parts[1]) && "sessions".equals(parts[2])) {
                String sessionId = parts[3];
                String action = parts.length == 4 ? "" : parts[4];
                if ("GET".equals(request.method) && action.equals("export")) {
                    json(exchange, 200, store.exportSession(sessionId));
                } else if ("POST".equals(request.method)) {
                    Map<String, Object> body = request.body();
                    String branchId = Json.optionalString(body, "branchId", "main");
                    switch (action) {
                        case "events" -> json(exchange, 200, store.importEvents(sessionId, branchId, Json.list(body.get("events"))));
                        case "step" -> json(exchange, 200, store.step(sessionId, branchId));
                        case "checkpoints" -> json(exchange, 201, store.checkpoint(sessionId, branchId,
                                Json.optionalString(body, "name", "")));
                        case "fork" -> json(exchange, 201, store.fork(sessionId,
                                Json.string(body, "checkpointId"), Json.optionalString(body, "name", "")));
                        case "restore" -> json(exchange, 200, store.restore(sessionId, Json.string(body, "checkpointId")));
                        case "merge" -> json(exchange, 200, store.merge(sessionId, Json.string(body, "leftId"),
                                Json.string(body, "rightId"), Json.optionalString(body, "name", ""),
                                Boolean.TRUE.equals(body.get("create"))));
                        default -> error(exchange, 404, "unknown API route");
                    }
                } else if ("GET".equals(request.method) && action.equals("compare")) {
                    json(exchange, 200, store.compare(sessionId,
                            parameter(request.uri, "left"), parameter(request.uri, "right")));
                } else {
                    error(exchange, 404, "unknown API route");
                }
            } else {
                error(exchange, 404, "unknown API route");
            }
        }
    }

    private static String parameter(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) throw new EngineException("missing query parameter " + name);
        for (String pair : query.split("&")) {
            String[] values = pair.split("=", 2);
            if (values[0].equals(name) && values.length == 2) return values[1];
        }
        throw new EngineException("missing query parameter " + name);
    }

    private void staticFile(HttpExchange exchange, String resource, String contentType) throws IOException {
        byte[] bytes = Main.class.getResourceAsStream(resource).readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void error(HttpExchange exchange, int status, String message) throws IOException {
        json(exchange, status, Map.of("error", message == null ? "error" : message));
    }

    private static final class Request {
        private final String method;
        private final String path;
        private final URI uri;
        private final byte[] bodyBytes;

        private Request(HttpExchange exchange) throws IOException {
            this.method = exchange.getRequestMethod();
            this.uri = exchange.getRequestURI();
            this.path = uri.getPath();
            this.bodyBytes = exchange.getRequestBody().readAllBytes();
        }

        private Map<String, Object> body() {
            if (bodyBytes.length == 0) return new HashMap<>();
            return Json.object(Json.parse(new String(bodyBytes, StandardCharsets.UTF_8)));
        }

        private Object bodyObject() {
            return Json.parse(new String(bodyBytes, StandardCharsets.UTF_8));
        }
    }
}
