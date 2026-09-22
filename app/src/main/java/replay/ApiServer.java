package replay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class ApiServer {
    private final HttpServer server;
    private ReplayRoom room;

    private ApiServer(HttpServer server, ReplayRoom room) {
        this.server = server;
        this.room = room;
    }

    static ApiServer start(String host, int port, ReplayRoom room) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        ApiServer api = new ApiServer(server, room);
        server.createContext("/", api::handle);
        server.start();
        return api;
    }

    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            if (e instanceof ReplayRoom.VersionException version) {
                error.put("code", "definition_fingerprint_mismatch");
                error.put("checkpointId", version.checkpointId);
                error.put("checkpointFingerprint", version.checkpointFingerprint);
                error.put("currentFingerprint", version.currentFingerprint);
                writeJson(exchange, 409, error);
            } else if (e instanceof ReplayRoom.MergeConflictException conflict) {
                error.put("code", "merge_conflict");
                error.putAll(conflict.details);
                writeJson(exchange, 409, error);
            } else if (e instanceof IllegalArgumentException) {
                writeJson(exchange, 400, error);
            } else {
                error.put("error", "internal error");
                writeJson(exchange, 500, error);
            }
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            serveIndex(exchange);
            return;
        }
        if (path.startsWith("/api/")) {
            handleApi(exchange, method, path, query(exchange));
            return;
        }
        writeJson(exchange, 404, Map.of("error", "not found"));
    }

    private synchronized void handleApi(HttpExchange exchange, String method, String path, Map<String, String> query) throws IOException {
        Object body = body(exchange);
        Map<String, Object> bodyMap = body instanceof Map<?, ?> ? Json.object(body) : new LinkedHashMap<String, Object>();

        if ("GET".equals(method) && "/api/state".equals(path)) {
            writeJson(exchange, 200, room.state());
        } else if ("GET".equals(method) && "/api/export".equals(path)) {
            writeJson(exchange, 200, room.exportSession());
        } else if ("POST".equals(method) && "/api/import".equals(path)) {
            ReplayRoom imported = ReplayRoom.importSession(body);
            room = imported;
            Persistence.saveIfConfigured(room);
            writeJson(exchange, 200, room.state());
        } else if ("PUT".equals(method) && "/api/definition".equals(path)) {
            Object definition = bodyMap.containsKey("definition") ? bodyMap.get("definition") : bodyMap;
            room.replaceDefinition(DefinitionCodec.parse(Json.object(definition)));
            writeJson(exchange, 200, room.state());
        } else if ("POST".equals(method) && "/api/reset".equals(path)) {
            room = new ReplayRoom(Demo.definition());
            Persistence.saveIfConfigured(room);
            writeJson(exchange, 200, room.state());
        } else if (path.startsWith("/api/branches/")) {
            String rest = path.substring("/api/branches/".length());
            int slash = rest.indexOf('/');
            String branchId = slash < 0 ? decode(rest) : decode(rest.substring(0, slash));
            String suffix = slash < 0 ? "" : rest.substring(slash);
            if ("GET".equals(method) && suffix.isEmpty()) {
                writeJson(exchange, 200, room.branchState(branchId));
            } else if ("POST".equals(method) && "/events".equals(suffix)) {
                boolean replace = Boolean.TRUE.equals(bodyMap.get("replace"));
                room.importEvents(branchId, Json.required(bodyMap, "events"), replace);
                writeJson(exchange, 200, room.branchState(branchId));
            } else if ("POST".equals(method) && "/step".equals(suffix)) {
                int count = (int) Json.optionalLong(bodyMap, "count", 1L);
                writeJson(exchange, 200, room.step(branchId, count));
            } else if ("POST".equals(method) && "/checkpoints".equals(suffix)) {
                String name = Json.optionalString(bodyMap, "name", "");
                writeJson(exchange, 200, room.checkpoint(branchId, name));
            } else {
                writeJson(exchange, 404, Map.of("error", "not found"));
            }
        } else if (path.startsWith("/api/checkpoints/")) {
            String checkpointId = decode(path.substring("/api/checkpoints/".length()));
            if ("POST".equals(method) && checkpointId.endsWith("/fork")) {
                checkpointId = decode(checkpointId.substring(0, checkpointId.length() - "/fork".length()));
                writeJson(exchange, 200, room.fork(checkpointId, Json.optionalString(bodyMap, "name", "")));
            } else {
                writeJson(exchange, 404, Map.of("error", "not found"));
            }
        } else if ("GET".equals(method) && "/api/compare".equals(path)) {
            writeJson(exchange, 200, room.compare(require(query, "left"), require(query, "right")));
        } else if ("POST".equals(method) && "/api/merge".equals(path)) {
            String name = Json.optionalString(bodyMap, "name", "");
            writeJson(exchange, 200, room.merge(Json.string(bodyMap, "left"), Json.string(bodyMap, "right"), name));
        } else {
            writeJson(exchange, 404, Map.of("error", "not found"));
        }
    }

    private void serveIndex(HttpExchange exchange) throws IOException {
        try (InputStream input = ApiServer.class.getResourceAsStream("/web/index.html")) {
            if (input == null) {
                writeJson(exchange, 404, Map.of("error", "index missing"));
                return;
            }
            byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    private static Object body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) return new LinkedHashMap<String, Object>();
        return Json.parse(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void writeJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new LinkedHashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return result;
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            result.put(decode(key), decode(value));
        }
        return result;
    }

    private static String require(Map<String, String> query, String key) {
        String value = query.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " query parameter is required");
        return value;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
