package replay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JDK-only HTTP layer: JSON API under /api and a single-page UI at /. */
public class WebServer {

    private final ProjectStore store;
    private final HttpServer server;

    public WebServer(ProjectStore store, String host, int port) throws IOException {
        this.store = store;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(null);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (ProjectStore.ConflictException e) {
            sendJson(exchange, 409, Map.of("error", e.getMessage(), "conflict", e.detail));
        } catch (ProjectStore.DefinitionMismatchException e) {
            sendJson(exchange, 422, Map.of("error", e.getMessage()));
        } catch (ProjectStore.NotFoundException e) {
            sendJson(exchange, 404, Map.of("error", e.getMessage()));
        } catch (ProjectStore.BadRequestException e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
        } catch (ApiException e) {
            sendJson(exchange, e.status, Map.of("error", e.getMessage(),
                    "detail", e.detail == null ? Map.of() : e.detail));
        } catch (Json.JsonException | IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            sendJson(exchange, 500, Map.of("error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
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
        if ("GET".equals(method) && path.startsWith("/static/")) {
            serveResource(exchange, path.substring(1));
            return;
        }

        if (!path.startsWith("/api/")) {
            throw new ApiException(404, "not found", null);
        }
        String route = path.substring("/api/".length());
        Map<String, Object> body = readBody(exchange);

        switch (route) {
            case "overview":
                requireGet(method);
                sendJson(exchange, 200, store.overview());
                break;
            case "definition":
                if ("PUT".equals(method)) {
                    sendJson(exchange, 200, store.updateDefinition(body));
                } else if ("GET".equals(method)) {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("definition", store.definition().raw);
                    response.put("fingerprint", store.definition().fingerprint);
                    sendJson(exchange, 200, response);
                } else {
                    throw new ApiException(405, "method not allowed", null);
                }
                break;
            case "events":
                if ("PUT".equals(method) || "POST".equals(method)) {
                    sendJson(exchange, 200, store.importEvents(
                            Json.arr(body, "events"), String.valueOf(body.getOrDefault("mode", ""))));
                } else if ("GET".equals(method)) {
                    sendJson(exchange, 200, Map.of("events", store.externalEvents().stream()
                            .map(Models.Event::toMap).toList()));
                } else {
                    throw new ApiException(405, "method not allowed", null);
                }
                break;
            case "reset":
                sendJson(exchange, 200, store.reset(Json.str(body, "branch")));
                break;
            case "step":
                sendJson(exchange, 200, store.step(
                        body.getOrDefault("branch", "main").toString()));
                break;
            case "run": {
                Integer limit = body.get("limit") instanceof Number
                        ? ((Number) body.get("limit")).intValue() : null;
                sendJson(exchange, 200, store.run(
                        body.getOrDefault("branch", "main").toString(), limit));
                break;
            }
            case "drop":
                sendJson(exchange, 200, store.drop(
                        body.getOrDefault("branch", "main").toString(),
                        Json.requireStr(body, "eventId")));
                break;
            case "fork":
                sendJson(exchange, 200, store.fork(
                        body.getOrDefault("branch", "main").toString(),
                        Json.requireStr(body, "name"),
                        Json.str(body, "checkpoint")));
                break;
            case "checkpoint":
                sendJson(exchange, 200, store.createCheckpoint(
                        body.getOrDefault("branch", "main").toString(),
                        Json.requireStr(body, "name")));
                break;
            case "trace":
                sendJson(exchange, 200, store.trace(
                        body.getOrDefault("branch", "main").toString()));
                break;
            case "compare":
                sendJson(exchange, 200, store.compare(
                        Json.requireStr(body, "a"), Json.requireStr(body, "b")));
                break;
            case "merge":
                sendJson(exchange, 200, store.merge(
                        Json.requireStr(body, "a"), Json.requireStr(body, "b"),
                        Json.requireStr(body, "name")));
                break;
            case "export":
                requireGet(method);
                sendJson(exchange, 200, store.exportSession());
                break;
            case "import":
                sendJson(exchange, 200, store.importSession(body));
                break;
            default:
                throw new ApiException(404, "unknown api route: " + route, null);
        }
    }

    private static void requireGet(String method) {
        if (!"GET".equals(method)) {
            throw new ApiException(405, "method not allowed", null);
        }
    }

    private Map<String, Object> readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isEmpty()) {
                return new LinkedHashMap<>();
            }
            return Json.parseObject(text);
        }
    }

    private void serveIndex(HttpExchange exchange) throws IOException {
        serveResource(exchange, "index.html");
    }

    private void serveResource(HttpExchange exchange, String resourcePath) throws IOException {
        String name = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        if (name.contains("..")) {
            throw new ApiException(400, "bad path", null);
        }
        try (InputStream input = WebServer.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new ApiException(404, "not found: " + name, null);
            }
            byte[] bytes = input.readAllBytes();
            String contentType = name.endsWith(".html") ? "text/html; charset=utf-8"
                    : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : name.endsWith(".css") ? "text/css; charset=utf-8"
                    : "application/octet-stream";
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = Json.writePretty(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static class ApiException extends RuntimeException {
        final int status;
        final Map<String, Object> detail;

        ApiException(int status, String message, Map<String, Object> detail) {
            super(message);
            this.status = status;
            this.detail = detail;
        }
    }

}
