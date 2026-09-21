package replayroom.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import replayroom.engine.Session;
import replayroom.json.Json;

/** Tiny static-file + JSON HTTP server backed by the JDK HttpServer. */
public final class HttpServer {

    private final ApiService api;
    private final Path webRoot;
    private com.sun.net.httpserver.HttpServer server;

    public HttpServer(ApiService api, Path webRoot) {
        this.api = api;
        this.webRoot = webRoot;
    }

    public void start(String host, int port) {
        try {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start HTTP server on " + host + ":" + port, e);
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public void exchange(HttpExchange exchange) throws IOException {
        handle(exchange);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("ok", false);
            error.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            int status = 500;
            if (e instanceof ApiService.NotFoundException) status = 404;
            else if (e instanceof ApiService.BadRequestException || e instanceof IllegalArgumentException) status = 400;
            else if (e instanceof ApiService.ConflictException) status = 409;
            else if (e instanceof ApiService.DefinitionMismatchHttpException) status = 409;
            if (e instanceof ApiService.ConflictException conflict) {
                writeJson(exchange, 409, conflict.payload == null ? error : conflict.payload);
            } else if (e instanceof ApiService.DefinitionMismatchHttpException mismatch) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("ok", false);
                payload.put("error", "definition-fingerprint-mismatch");
                payload.put("detail", mismatch.getMessage());
                payload.put("checkpointFingerprint", mismatch.cause.checkpointFingerprint());
                payload.put("actualFingerprint", mismatch.cause.actualFingerprint());
                writeJson(exchange, 409, payload);
            } else {
                writeJson(exchange, status, error);
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if ("GET".equals(method) && path.equals("/api/health")) {
            writeJson(exchange, 200, Json.obj("ok", true, "service", "state-machine-replay-room"));
            return;
        }
        if (path.startsWith("/api/")) {
            handleApi(exchange, method, path);
            return;
        }
        serveStatic(exchange, path);
    }

    private void handleApi(HttpExchange exchange, String method, String path) throws IOException {
        String bodyText = readBody(exchange);
        Map<String, Object> body = bodyText.isBlank()
                ? new LinkedHashMap<>()
                : Json.object(Json.parse(bodyText), "request body");

        if ("POST".equals(method) && path.equals("/api/definitions")) {
            writeJson(exchange, 201, api.createDefinition(body));
            return;
        }
        if ("GET".equals(method) && path.equals("/api/definitions")) {
            writeJson(exchange, 200, api.listDefinitions());
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/definitions/")) {
            writeJson(exchange, 200, api.getDefinition(lastSegment(path)));
            return;
        }
        if ("POST".equals(method) && path.equals("/api/sessions")) {
            writeJson(exchange, 201, api.createSession(body));
            return;
        }
        if ("GET".equals(method) && path.equals("/api/sessions")) {
            writeJson(exchange, 200, api.listSessions());
            return;
        }
        if ("POST".equals(method) && path.equals("/api/sessions/import")) {
            writeJson(exchange, 201, api.importSession(body));
            return;
        }
        if (path.startsWith("/api/sessions/")) {
            handleSessionRoute(exchange, method, path, body);
            return;
        }
        if ("POST".equals(method) && path.startsWith("/api/checkpoints/") && path.endsWith("/fork")) {
            String checkpointId = path.substring("/api/checkpoints/".length(), path.length() - "/fork".length());
            writeJson(exchange, 201, api.fork(checkpointId, body));
            return;
        }
        if ("POST".equals(method) && path.equals("/api/merge")) {
            writeJson(exchange, 200, api.mergeSessions(body));
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/compare/")) {
            String[] pair = path.substring("/api/compare/".length()).split("/");
            if (pair.length != 2) throw new ApiService.BadRequestException("compare requires /a/b");
            writeJson(exchange, 200, api.compare(pair[0], pair[1]));
            return;
        }
        throw new ApiService.NotFoundException("route", path);
    }

    private void handleSessionRoute(HttpExchange exchange, String method, String path,
                                    Map<String, Object> body) throws IOException {
        String[] parts = path.substring("/api/sessions/".length()).split("/");
        String id = parts[0];
        if (parts.length == 1 && "GET".equals(method)) {
            writeJson(exchange, 200, api.getSession(id));
            return;
        }
        if (parts.length == 2 && "export".equals(parts[1]) && "GET".equals(method)) {
            writeJson(exchange, 200, api.exportSession(id));
            return;
        }
        if (parts.length == 2 && "events".equals(parts[1]) && "POST".equals(method)) {
            writeJson(exchange, 200, api.appendEvents(id, body));
            return;
        }
        if (parts.length == 2 && "step".equals(parts[1]) && "POST".equals(method)) {
            writeJson(exchange, 200, api.step(id, body));
            return;
        }
        if (parts.length == 2 && "checkpoints".equals(parts[1]) && "POST".equals(method)) {
            writeJson(exchange, 201, api.createCheckpoint(id, body));
            return;
        }
        throw new ApiService.NotFoundException("route", path);
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        String relative = "/".equals(path) ? "/index.html" : path;
        if (relative.contains("..")) {
            sendPlain(exchange, 400, "bad path");
            return;
        }
        Path resolved = webRoot.resolve(relative.substring(1)).normalize();
        if (!resolved.startsWith(webRoot) || !Files.isRegularFile(resolved)) {
            sendPlain(exchange, 404, "Not found");
            return;
        }
        byte[] content = Files.readAllBytes(resolved);
        exchange.getResponseHeaders().set("Content-Type", contentType(relative));
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(content);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static String lastSegment(String path) {
        int index = path.lastIndexOf('/');
        return path.substring(index + 1);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void writeJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] content = Json.writePretty(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(content);
        }
    }

    private void sendPlain(HttpExchange exchange, int status, String text) throws IOException {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(content);
        }
    }
}
