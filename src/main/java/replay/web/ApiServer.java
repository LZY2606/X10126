package replay.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import replay.Json;
import replay.engine.Definition;
import replay.engine.Session;
import replay.engine.Store;

/**
 * Small HTTP layer: JSON API under /api and a static single-page UI at /.
 * Uses only the JDK built-in HttpServer - no external components.
 */
public final class ApiServer {

    private final Store store;
    private final HttpServer server;

    public ApiServer(Store store, String host, int port) {
        this.store = store;
        try {
            this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind " + host + ":" + port, e);
        }
        server.createContext("/", this::route);
        server.setExecutor(null);
    }

    public void start() {
        server.start();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.startsWith("/api/")) {
                handleApi(ex, method, path);
            } else {
                serveStatic(ex, path);
            }
        } catch (Session.ApiException e) {
            writeJson(ex, e.status(), Map.of("error", e.getMessage()));
        } catch (Json.JsonException e) {
            writeJson(ex, 400, Map.of("error", "invalid JSON: " + e.getMessage()));
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        } finally {
            ex.close();
        }
    }

    private void handleApi(HttpExchange ex, String method, String path) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
            String text = readBody(ex);
            if (!text.isBlank()) {
                Object parsed = Json.parse(text);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) parsed;
                    body = m;
                }
            }
        }

        switch (path) {
            case "/api/health" -> writeJson(ex, 200, Map.of("status", "ok"));
            case "/api/session" -> {
                if ("POST".equalsIgnoreCase(method)) {
                    createSession(body);
                }
                writeJson(ex, 200, store.session().describe());
            }
            case "/api/session/reset" -> {
                store.createNew(Session.create(
                        Json.str(body, "id", "session"),
                        Json.str(body, "name"),
                        Definition.fromMap(Json.obj(body, "definition")),
                        Json.list(body, "events")));
                writeJson(ex, 200, store.session().describe());
            }
            case "/api/session/export" -> writeJson(ex, 200, store.session().export());
            case "/api/session/import" -> {
                store.createNew(Session.importVerified(body));
                writeJson(ex, 200, store.session().describe());
            }
            case "/api/branches" -> writeJson(ex, 200, store.session().describe());
            default -> dispatchBranchApi(ex, method, path, body);
        }
    }

    private void createSession(Map<String, Object> body) {
        if (store.exists()) {
            throw new Session.ApiException(409,
                    "a session already exists; use /api/session/reset to replace it");
        }
        Definition def = Definition.fromMap(Json.obj(body, "definition"));
        store.createNew(Session.create(
                Json.str(body, "id", "session"),
                Json.str(body, "name"),
                def,
                Json.list(body, "events")));
    }

    private void dispatchBranchApi(HttpExchange ex, String method, String path,
                                   Map<String, Object> body) throws IOException {
        Session session = store.session();
        String[] parts = path.substring("/api/".length()).split("/");

        // /api/branches/{id}/step ...
        if (parts.length >= 3 && "branches".equals(parts[0])) {
            String branchId = parts[1];
            String action = parts[2];
            switch (action) {
                case "step" -> {
                    int count = Json.integer(body, "count", 1);
                    var resp = session.step(branchId, Math.max(1, count));
                    store.save();
                    writeJson(ex, 200, resp);
                    return;
                }
                case "run" -> {
                    var resp = session.runToEnd(branchId);
                    store.save();
                    writeJson(ex, 200, resp);
                    return;
                }
                case "checkpoints" -> {
                    if ("POST".equalsIgnoreCase(method)) {
                        var cp = session.checkpoint(branchId, Json.str(body, "label"));
                        store.save();
                        writeJson(ex, 200, cp.toMap());
                        return;
                    }
                }
                case "events" -> {
                    var resp = session.appendExternal(branchId, Json.list(body, "events"));
                    store.save();
                    writeJson(ex, 200, resp);
                    return;
                }
                case "view" -> {
                    int offset = Json.integer(query(ex), "offset", 0);
                    int limit = Json.integer(query(ex), "limit", 0);
                    writeJson(ex, 200, session.branchView(branchId, offset, limit));
                    return;
                }
                default -> {
                }
            }
        }

        if (path.equals("/api/fork") && "POST".equalsIgnoreCase(method)) {
            var branch = session.fork(Json.str(body, "branch"),
                    Json.str(body, "checkpoint"),
                    Json.str(body, "name"),
                    Json.list(body, "events"));
            store.save();
            writeJson(ex, 200, branch.describe());
            return;
        }

        if (path.equals("/api/compare") && "POST".equalsIgnoreCase(method)) {
            writeJson(ex, 200, session.compare(
                    Json.str(body, "left"), Json.str(body, "right")));
            return;
        }

        if (path.equals("/api/merge") && "POST".equalsIgnoreCase(method)) {
            var resp = session.merge(Json.str(body, "left"), Json.str(body, "right"),
                    Json.str(body, "name"), Json.bool(body, "execute", true));
            boolean rejected = Boolean.FALSE.equals(resp.get("compatible"));
            if (!rejected) {
                store.save();
            }
            writeJson(ex, rejected ? 409 : 200, resp);
            return;
        }

        if (path.startsWith("/api/checkpoints/") && path.endsWith("/restore")) {
            String cpId = path.substring("/api/checkpoints/".length(),
                    path.length() - "/restore".length());
            session.restoreCheckpoint(Json.str(body, "branch"), cpId);
            store.save();
            writeJson(ex, 200, session.requireBranch(Json.str(body, "branch")).describe());
            return;
        }

        writeJson(ex, 404, Map.of("error", "no such API route: " + path));
    }

    private static Map<String, Object> query(HttpExchange ex) {
        Map<String, Object> params = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return params;
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void writeJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = Json.writePretty(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        String resource = "/web" + (path.equals("/") ? "/index.html" : path);
        try (InputStream in = ApiServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                byte[] msg = "not found".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, msg.length);
                ex.getResponseBody().write(msg);
                return;
            }
            byte[] bytes = in.readAllBytes();
            String contentType = contentType(resource);
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static String contentType(String resource) {
        if (resource.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (resource.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
    }
}
