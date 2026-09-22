package replay.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;

import replay.engine.Definition;
import replay.json.Json;
import replay.json.JsonUtil;
import replay.store.ProjectStore;

public final class WebApi {
    private final ProjectStore store;

    public WebApi(ProjectStore store) {
        this.store = store;
    }

    public void handle(HttpExchange exchange) throws IOException {
        addCommonHeaders(exchange.getResponseHeaders());
        try {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                send(exchange, HttpURLConnection.HTTP_OK, Map.of("ok", true));
                return;
            }
            route(exchange);
        } catch (ProjectStore.BadRequestException | IllegalArgumentException e) {
            sendError(exchange, HttpURLConnection.HTTP_BAD_REQUEST, e.getMessage());
        } catch (ProjectStore.NotFoundException e) {
            sendError(exchange, HttpURLConnection.HTTP_NOT_FOUND, e.getMessage());
        } catch (ProjectStore.DefinitionVersionException e) {
            sendError(exchange, HttpURLConnection.HTTP_CONFLICT, e.getMessage());
        } catch (ProjectStore.ConflictException e) {
            sendError(exchange, HttpURLConnection.HTTP_CONFLICT, e.getMessage());
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = query(exchange.getRequestURI());
        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            staticFile(exchange, "/web/index.html", "text/html; charset=utf-8");
            return;
        }
        if ("GET".equals(method) && "/app.js".equals(path)) {
            staticFile(exchange, "/web/app.js", "application/javascript; charset=utf-8");
            return;
        }
        if ("GET".equals(method) && "/styles.css".equals(path)) {
            staticFile(exchange, "/web/styles.css", "text/css; charset=utf-8");
            return;
        }
        if ("GET".equals(method) && "/api/sample".equals(path)) {
            send(exchange, 200, Definition.sample());
            return;
        }
        if ("POST".equals(method) && "/api/projects".equals(path)) {
            Map<String, Object> body = body(exchange);
            Map<String, Object> definition = JsonUtil.object(body.getOrDefault("definition", Definition.sample()), "definition");
            List<Map<String, Object>> events = eventList(body.get("events"));
            String id = JsonUtil.optionalString(body, "id", "project-" + Long.toString(System.nanoTime(), 36));
            send(exchange, 201, store.createProject(id, definition, events,
                    JsonUtil.optionalString(body, "seedLogId", "")));
            return;
        }
        if ("GET".equals(method) && "/api/projects".equals(path)) {
            send(exchange, 200, store.project(query.get("projectId"), query.get("branchId")));
            return;
        }
        if ("POST".equals(method) && "/api/events/import".equals(path)) {
            Map<String, Object> body = body(exchange);
            send(exchange, 200, store.importEvents(required(query, "projectId"), eventList(body.get("events"))));
            return;
        }
        if ("POST".equals(method) && "/api/events/append".equals(path)) {
            Map<String, Object> body = body(exchange);
            send(exchange, 200, store.appendEvents(required(query, "projectId"),
                    query.getOrDefault("branchId", "main"), eventList(body.get("events"))));
            return;
        }
        if ("POST".equals(method) && "/api/replay/step".equals(path)) {
            Map<String, Object> body = body(exchange);
            long count = JsonUtil.integer(body.getOrDefault("count", 1L), "count");
            send(exchange, 200, store.step(required(query, "projectId"), query.getOrDefault("branchId", "main"), count));
            return;
        }
        if ("POST".equals(method) && "/api/checkpoints".equals(path)) {
            Map<String, Object> body = body(exchange);
            send(exchange, 201, store.checkpoint(required(query, "projectId"),
                    query.getOrDefault("branchId", "main"), JsonUtil.string(body, "name")));
            return;
        }
        if ("POST".equals(method) && "/api/replay/reset".equals(path)) {
            send(exchange, 200, store.reset(required(query, "projectId"), query.getOrDefault("branchId", "main"),
                    required(query, "checkpointId")));
            return;
        }
        if ("POST".equals(method) && "/api/branches/fork".equals(path)) {
            Map<String, Object> body = body(exchange);
            send(exchange, 201, store.fork(required(query, "projectId"),
                    query.getOrDefault("sourceBranchId", "main"), JsonUtil.string(body, "checkpointId"),
                    JsonUtil.string(body, "branchId"), JsonUtil.optionalString(body, "name", body.get("branchId").toString())));
            return;
        }
        if ("POST".equals(method) && "/api/branches/merge".equals(path)) {
            Map<String, Object> body = body(exchange);
            send(exchange, 200, store.merge(required(query, "projectId"),
                    query.getOrDefault("targetBranchId", "main"),
                    query.getOrDefault("sourceBranchId", JsonUtil.string(body, "sourceBranchId")),
                    optionalString(body, "targetCheckpointId"),
                    optionalString(body, "sourceCheckpointId"),
                    JsonUtil.string(body, "branchId"),
                    JsonUtil.optionalString(body, "name", body.get("branchId").toString())));
            return;
        }
        if ("GET".equals(method) && "/api/branches/compare".equals(path)) {
            send(exchange, 200, store.compare(required(query, "projectId"),
                    query.getOrDefault("leftBranchId", "main"), required(query, "rightBranchId")));
            return;
        }
        if ("GET".equals(method) && "/api/sessions/export".equals(path)) {
            send(exchange, 200, store.exportSession(required(query, "projectId")));
            return;
        }
        if ("POST".equals(method) && "/api/sessions/import".equals(path)) {
            Map<String, Object> body = body(exchange);
            send(exchange, 201, store.importSession(body, query.get("newProjectId")));
            return;
        }
        sendError(exchange, HttpURLConnection.HTTP_NOT_FOUND, "Not found");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> eventList(Object value) {
        if (value == null) {
            return List.of();
        }
        List<Object> raw = JsonUtil.list(value, "events");
        return raw.stream().map(item -> JsonUtil.object(item, "event")).toList();
    }

    private String optionalString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, String> query(URI uri) {
        Map<String, Object> raw = splitQuery(uri.getRawQuery());
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            result.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return result;
    }

    private Map<String, Object> splitQuery(String rawQuery) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length == 2 ? decode(parts[1]) : "";
            result.put(key, value);
        }
        return result;
    }

    private String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String required(Map<String, String> query, String key) {
        String value = query.get(key);
        if (value == null || value.isBlank()) {
            throw new ProjectStore.BadRequestException("缺少查询参数 " + key);
        }
        return value;
    }

    private Map<String, Object> body(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return new LinkedHashMap<>();
            }
            return JsonUtil.object(Json.parse(text), "request body");
        }
    }

    private void staticFile(HttpExchange exchange, String resource, String contentType) throws IOException {
        try (InputStream input = Main.class.getResourceAsStream(resource)) {
            if (input == null) {
                sendError(exchange, HttpURLConnection.HTTP_NOT_FOUND, "Resource missing");
                return;
            }
            byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    private void addCommonHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private void send(HttpExchange exchange, int status, Object response) throws IOException {
        byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        send(exchange, status, Map.of("ok", false, "error", message));
    }
}
