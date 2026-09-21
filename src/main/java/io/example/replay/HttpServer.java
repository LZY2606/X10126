package io.example.replay;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpServer {
    private final ReplayService service;
    private com.sun.net.httpserver.HttpServer server;

    public HttpServer(ReplayService service) {
        this.service = service;
    }

    public void start(String host, int port) {
        try {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start HTTP server", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (ReplayService.DefinitionMismatchException e) {
            sendError(exchange, 409, e.getMessage());
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            send(exchange, 200, "text/html; charset=utf-8", resource("/io/example/replay/index.html"));
            return;
        }
        if ("GET".equals(method) && path.equals("/app.js")) {
            send(exchange, 200, "application/javascript; charset=utf-8", resource("/io/example/replay/app.js"));
            return;
        }
        if ("GET".equals(method) && path.equals("/styles.css")) {
            send(exchange, 200, "text/css; charset=utf-8", resource("/io/example/replay/styles.css"));
            return;
        }
        if ("GET".equals(method) && path.equals("/api/health")) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("title", "状态机回放室");
            sendJson(exchange, 200, result);
            return;
        }
        if ("GET".equals(method) && path.equals("/api/definition")) {
            sendJson(exchange, 200, service.definition() == null ? Map.of() : service.definition().toJson());
            return;
        }
        if ("PUT".equals(method) && path.equals("/api/definition")) {
            Object body = parse(exchange);
            sendJson(exchange, 200, service.updateDefinition(body).toJson());
            return;
        }
        if ("GET".equals(method) && path.equals("/api/sessions")) {
            sendJson(exchange, 200, Map.of("sessions", service.sessions().stream()
                    .map(service::sessionView).toList()));
            return;
        }
        if ("POST".equals(method) && path.equals("/api/sessions")) {
            sendJson(exchange, 201, service.sessionView(service.createSession(parse(exchange))));
            return;
        }
        if ("POST".equals(method) && path.equals("/api/sessions/import")) {
            String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            sendJson(exchange, 201, service.sessionView(service.importSession(text)));
            return;
        }
        if (path.startsWith("/api/sessions/")) {
            routeSession(exchange, method, path);
            return;
        }
        sendError(exchange, 404, "Not found");
    }

    private void routeSession(HttpExchange exchange, String method, String path) throws IOException {
        String suffix = path.substring("/api/sessions/".length());
        String[] parts = suffix.split("/");
        if (parts.length == 1 && "GET".equals(method)) {
            sendJson(exchange, 200, service.sessionView(service.session(parts[0])));
            return;
        }
        if (parts.length == 2 && parts[1].equals("events") && "POST".equals(method)) {
            sendJson(exchange, 200, service.appendEvents(parts[0], parse(exchange)));
            return;
        }
        if (parts.length == 2 && parts[1].equals("export") && "GET".equals(method)) {
            send(exchange, 200, "application/json; charset=utf-8", service.exportSession(parts[0]));
            return;
        }
        if (parts.length == 3 && parts[1].equals("branches")) {
            String sessionId = parts[0];
            String branchId = parts[2];
            if ("POST".equals(method) && queryKey(exchange, "action").equals("step")) {
                sendJson(exchange, 200, stepView(service.step(sessionId, branchId)));
                return;
            }
            if ("POST".equals(method) && queryKey(exchange, "action").equals("checkpoint")) {
                Map<String, Object> body = parseMap(exchange);
                String label = body.get("label") == null ? null : String.valueOf(body.get("label"));
                Checkpoint checkpoint = service.checkpoint(sessionId, branchId, label);
                sendJson(exchange, 200, checkpointView(checkpoint));
                return;
            }
        }
        if (parts.length == 4 && parts[1].equals("branches") && parts[3].equals("restore")
                && "POST".equals(method)) {
            String checkpointId = Json.string(parseMap(exchange), "checkpointId");
            Checkpoint checkpoint = service.restoreCheckpoint(parts[0], parts[2], checkpointId);
            sendJson(exchange, 200, checkpointView(checkpoint));
            return;
        }
        if (parts.length == 2 && parts[1].equals("forks") && "POST".equals(method)) {
            sendJson(exchange, 201, service.branchView(service.session(parts[0]), service.fork(parts[0], parse(exchange))));
            return;
        }
        if (parts.length == 2 && parts[1].equals("merges") && "POST".equals(method)) {
            sendJson(exchange, 200, service.merge(parts[0], parse(exchange)).toJson());
            return;
        }
        if (parts.length == 3 && parts[1].equals("compare") && "GET".equals(method)) {
            sendJson(exchange, 200, service.compare(parts[0], parts[2],
                    queryParam(exchange, "with")));
            return;
        }
        sendError(exchange, 404, "Not found");
    }

    private Map<String, Object> stepView(ReplayEngine.StepResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", result.event().toJsonMutable());
        body.put("step", result.step().toJson());
        body.put("diff", result.diff().stream().map(Diff.Change::toJson).toList());
        body.put("before", result.before());
        body.put("after", result.after());
        body.put("remainingExternal", result.remainingExternal());
        return body;
    }

    private Map<String, Object> checkpointView(Checkpoint checkpoint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", checkpoint.id());
        result.put("branchId", checkpoint.branchId());
        result.put("stepIndex", checkpoint.stepIndex());
        result.put("traceHash", checkpoint.traceHash());
        result.put("label", checkpoint.label());
        result.put("definitionFingerprint", checkpoint.definitionFingerprint());
        return result;
    }

    private Object parse(HttpExchange exchange) throws IOException {
        return Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private Map<String, Object> parseMap(HttpExchange exchange) throws IOException {
        return Json.object(parse(exchange));
    }

    private String queryKey(HttpExchange exchange, String key) {
        String value = queryParam(exchange, key);
        return value == null ? "" : value;
    }

    private String queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] values = pair.split("=", 2);
            if (values[0].equals(key)) {
                return values.length > 1 ? java.net.URLDecoder.decode(values[1], StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", Json.write(body));
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message == null ? "error" : message);
        sendJson(exchange, status, body);
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String resource(String path) throws IOException {
        try (InputStream input = HttpServer.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
