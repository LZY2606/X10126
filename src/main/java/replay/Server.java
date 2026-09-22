package replay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON API + static UI, backed only by the JDK built-in HTTP server. */
public final class Server {
    private final Manager manager;
    private final HttpServer http;

    public Server(Manager manager, String host, int port) throws IOException {
        this.manager = manager;
        this.http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.createContext("/", this::handle);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    }

    public void start() { http.start(); }

    public void stop() { http.stop(0); }

    public int port() { return http.getAddress().getPort(); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (ApiException e) {
            sendJson(ex, e.status, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        } finally {
            ex.close();
        }
    }

    @SuppressWarnings("unchecked")
    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String[] seg = path.split("/");

        if ("GET".equals(method) && "/".equals(path)) {
            sendHtml(ex, 200, loadIndex());
            return;
        }
        if ("GET".equals(method) && "/api/sessions".equals(path)) {
            sendJson(ex, 200, Map.of("sessions", manager.list()));
            return;
        }
        if ("POST".equals(method) && "/api/sessions".equals(path)) {
            Map<String, Object> body = bodyJson(ex);
            Map<String, Long> priorities = new LinkedHashMap<>();
            Object pr = body.get("sourcePriorities");
            if (pr instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) pr).entrySet())
                    priorities.put(e.getKey(), ((Number) e.getValue()).longValue());
            }
            long seed = body.get("seed") instanceof Number ? ((Number) body.get("seed")).longValue() : 0L;
            Session s = manager.create((String) body.get("name"),
                    (Map<String, Object>) body.get("definition"), seed, priorities);
            sendJson(ex, 201, s.toJson());
            return;
        }
        if ("POST".equals(method) && "/api/sessions/import".equals(path)) {
            Map<String, Object> body = bodyJson(ex);
            Object sessionObj = body.containsKey("session") ? body.get("session") : body;
            sendJson(ex, 200, manager.importSession((Map<String, Object>) sessionObj));
            return;
        }
        // /api/sessions/{id}/...
        if (seg.length >= 4 && "api".equals(seg[1]) && "sessions".equals(seg[2])) {
            String id = seg[3];
            String tail = seg.length >= 5 ? seg[4] : "";
            if ("GET".equals(method) && tail.isEmpty()) {
                sendJson(ex, 200, manager.get(id).toJson());
                return;
            }
            if ("GET".equals(method) && "export".equals(tail)) {
                sendJson(ex, 200, manager.export(id));
                return;
            }
            if ("GET".equals(method) && "hash".equals(tail)) {
                String branchId = queryParam(ex, "branchId");
                sendJson(ex, 200, Map.of("trajectoryHash",
                        manager.get(id).trajectoryHash(branchId != null ? branchId : "main")));
                return;
            }
            if ("POST".equals(method) && "events".equals(tail)) {
                Map<String, Object> body = bodyJson(ex);
                Session s = manager.get(id);
                String branchId = strOr(body.get("branchId"), "main");
                Object evs = body.get("events");
                if (!(evs instanceof List)) throw new ApiException(400, "events array required");
                List<Map<String, Object>> added = s.addEvents(branchId, (List<Object>) evs);
                manager.save(s);
                sendJson(ex, 200, Map.of("added", added));
                return;
            }
            if ("POST".equals(method) && "step".equals(tail)) {
                Map<String, Object> body = bodyJson(ex);
                Session s = manager.get(id);
                Map<String, Object> record = s.step(strOr(body.get("branchId"), "main"));
                manager.save(s);
                sendJson(ex, 200, record);
                return;
            }
            if ("POST".equals(method) && "run".equals(tail)) {
                Map<String, Object> body = bodyJson(ex);
                Session s = manager.get(id);
                long max = body.get("max") instanceof Number ? ((Number) body.get("max")).longValue() : 10000L;
                Map<String, Object> result = s.run(strOr(body.get("branchId"), "main"), max);
                manager.save(s);
                sendJson(ex, 200, result);
                return;
            }
            if ("POST".equals(method) && "checkpoints".equals(tail)) {
                Map<String, Object> body = bodyJson(ex);
                Session s = manager.get(id);
                Session.Checkpoint c = s.checkpoint(strOr(body.get("branchId"), "main"),
                        (String) body.get("name"));
                manager.save(s);
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("id", c.id);
                cm.put("name", c.name);
                cm.put("branchId", c.branchId);
                cm.put("stepIndex", c.stepIndex);
                cm.put("definitionFingerprint", c.definitionFingerprint);
                sendJson(ex, 201, cm);
                return;
            }
            if ("POST".equals(method) && "branches".equals(tail)) {
                Map<String, Object> body = bodyJson(ex);
                Session.Branch b = manager.fork(id, (String) body.get("checkpointId"),
                        (String) body.get("fromSessionId"), (String) body.get("name"));
                sendJson(ex, 201, Map.of("id", b.id, "name", b.name, "forkStep", b.forkStep));
                return;
            }
            if ("POST".equals(method) && "merge".equals(tail)) {
                Map<String, Object> body = bodyJson(ex);
                Session.MergeResult r = manager.merge(id, (String) body.get("a"), (String) body.get("b"));
                if (r.ok) {
                    sendJson(ex, 200, Map.of("ok", true, "branchId", r.branchId,
                            "trajectoryHash", manager.get(id).trajectoryHash(r.branchId)));
                } else {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("ok", false);
                    resp.put("reason", r.reason);
                    resp.put("conflictA", r.conflictA);
                    resp.put("conflictB", r.conflictB);
                    sendJson(ex, 409, resp);
                }
                return;
            }
        }
        sendJson(ex, 404, Map.of("error", "not found: " + method + " " + path));
    }

    private static String strOr(Object o, String fallback) {
        return o != null ? String.valueOf(o) : fallback;
    }

    private static String queryParam(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).equals(key))
                return java.net.URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static Map<String, Object> bodyJson(HttpExchange ex) throws IOException {
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) return new LinkedHashMap<>();
        return Json.parseObject(text);
    }

    private static String loadIndex() throws IOException {
        try (InputStream in = Server.class.getResourceAsStream("/web/index.html")) {
            if (in == null) throw new IOException("index.html not found in resources");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
