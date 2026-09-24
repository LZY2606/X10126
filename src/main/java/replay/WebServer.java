package replay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WebServer {
    private final HttpServer server;

    WebServer(String host, int port, ReplayService service) {
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        server.createContext("/", exchange -> {
            try {
                route(exchange, service);
            } catch (RuntimeException ex) {
                error(exchange, ex);
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(null);
    }

    void start() {
        server.start();
    }

    void stop() {
        server.stop(0);
    }

    int port() {
        return server.getAddress().getPort();
    }

    private static void route(HttpExchange exchange, ReplayService service) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) && "/".equals(path)) {
            send(exchange, 200, "text/html; charset=utf-8", WebUi.html());
            return;
        }
        if ("GET".equals(method) && "/api/state".equals(path)) {
            json(exchange, 200, service.state());
            return;
        }
        if ("GET".equals(method) && "/api/export".equals(path)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("session", service.exportSession());
            payload.put("sessionHash", service.sessionHash());
            json(exchange, 200, payload);
            return;
        }
        if (!"POST".equals(method)) {
            throw new ReplayService.RequestException(405, "method not allowed");
        }
        Map<String, Object> body = readBody(exchange);
        switch (path) {
            case "/api/definition" -> json(exchange, 200, service.updateDefinition(body));
            case "/api/events/import" -> {
                Object events = body.get("events");
                json(exchange, 200, service.importEvents(Json.list(events, "events")));
            }
            case "/api/branch/step" -> json(exchange, 200, service.step(
                    Json.string(body, "branchId", ""),
                    (int) Json.optionalLong(body, "count", 1L),
                    Json.string(body, "reason", "step")));
            case "/api/branch/checkpoint" -> json(exchange, 200, service.checkpoint(
                    Json.string(body, "branchId", ""), Json.string(body, "name", "检查点")));
            case "/api/branch/append-external" -> json(exchange, 200, service.appendExternal(
                    Json.string(body, "branchId", ""), body));
            case "/api/branch/fork" -> json(exchange, 200, service.fork(
                    Json.requireString(body, "checkpointId"), Json.string(body, "name", "")));
            case "/api/branch/merge" -> json(exchange, 200, service.mergeBranches(
                    Json.requireString(body, "targetBranchId"), Json.requireString(body, "sourceBranchId")));
            case "/api/session/import" -> {
                Object session = body.get("session");
                json(exchange, 200, service.importSession(Json.object(session, "session")));
            }
            default -> throw new ReplayService.RequestException(404, "unknown API path: " + path);
        }
    }

    private static Map<String, Object> readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) return new LinkedHashMap<>();
        return Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8)), "request body");
    }

    private static void json(HttpExchange exchange, int status, Object payload) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", Json.write(payload));
    }

    private static void error(HttpExchange exchange, RuntimeException ex) throws IOException {
        int status = ex instanceof ReplayService.RequestException request ? request.status() : 500;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", ex.getMessage());
        payload.put("status", status);
        if (ex instanceof ReplayService.MergeConflict conflict) {
            payload.put("conflict", conflict.detail());
        }
        json(exchange, status, payload);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static WebServer create(Path dataDirectory, String host, int port) {
        return new WebServer(host, port, new ReplayService(new Repository(dataDirectory)));
    }
}
