package replay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import replay.Model.Definition;
import replay.Model.Event;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Embedded HTTP server: JSON API + single-page web UI. No external services. */
public final class Server {
    private final Store store;
    private final HttpServer http;

    public Server(Store store, String host, int port) throws IOException {
        this.store = store;
        this.http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.createContext("/", this::route);
        http.setExecutor(Executors.newFixedThreadPool(4));
    }

    public void start() { http.start(); }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5214;
        String dataDir = "data";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data-dir" -> dataDir = args[++i];
            }
        }
        Store store = new Store(Path.of(dataDir));
        store.load();
        Server server = new Server(store, host, port);
        server.start();
        System.out.println("状态机回放室 listening on http://" + host + ":" + port);
        Thread.currentThread().join();
    }

    // ---------- routing ----------

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/") && method.equals("GET")) {
                sendHtml(ex, indexHtml());
                return;
            }
            if (path.equals("/app.js") && method.equals("GET")) {
                sendJs(ex, resource("/web/app.js"));
                return;
            }
            if (path.startsWith("/api/")) {
                Object result = handleApi(method, path, ex);
                sendJson(ex, 200, result);
                return;
            }
            sendJson(ex, 404, Map.of("error", "not found: " + path));
        } catch (Store.MergeConflict mc) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "merge conflict: " + mc.reason);
            body.put("conflict", Map.of("a", mc.eventA, "b", mc.eventB, "reason", mc.reason));
            sendJson(ex, 409, body);
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendJson(ex, 400, Map.of("error", e.getMessage() == null ? "bad request" : e.getMessage()));
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", String.valueOf(e)));
        } finally {
            ex.close();
        }
    }

    private Object handleApi(String method, String path, HttpExchange ex) throws IOException {
        String[] parts = path.split("/");
        // /api/sessions ...
        if (parts.length < 3 || !parts[2].equals("sessions")) {
            throw new IllegalArgumentException("unknown api path: " + path);
        }
        if (parts.length == 3) {
            if (method.equals("GET")) {
                List<Object> out = new ArrayList<>();
                for (Session s : store.list()) out.add(s.status());
                return Map.of("sessions", out);
            }
            if (method.equals("POST")) {
                Map<String, Object> body = body(ex);
                Definition def = Definition.fromMap(Model.asMap(body.get("definition"), "definition"));
                long seed = body.get("seed") == null ? 1L : ((Number) body.get("seed")).longValue();
                Session s = new Session(def, seed, Model.str(body, "name"));
                if (body.get("initialVars") != null) {
                    s.initialVars = new LinkedHashMap<>(Model.asMap(body.get("initialVars"), "initialVars"));
                    s.snapshot = new Model.Snapshot(def.initialState, new LinkedHashMap<>(s.initialVars));
                }
                if (body.get("events") != null) s.addEvents(eventsOf(body.get("events")));
                store.put(s);
                return s.status();
            }
        }
        if (parts.length == 4 && parts[3].equals("import") && method.equals("POST")) {
            Session s = store.importSession(body(ex));
            return s.status();
        }
        if (parts.length >= 4) {
            String id = parts[3];
            Session s = store.get(id);
            String sub = parts.length == 4 ? "" : parts[4];
            switch (sub) {
                case "" -> {
                    if (method.equals("GET")) return s.status();
                }
                case "trace" -> {
                    if (method.equals("GET")) return Map.of("trace", s.trace, "traceHash", s.traceHash());
                }
                case "outputs" -> {
                    if (method.equals("GET")) return Map.of("outputs", s.outputs());
                }
                case "events" -> {
                    if (method.equals("POST")) {
                        Map<String, Object> body = body(ex);
                        s.addEvents(eventsOf(body.get("events")));
                        store.save(s);
                        return s.status();
                    }
                }
                case "step" -> {
                    if (method.equals("POST")) {
                        Map<String, Object> step = s.step();
                        store.save(s);
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("done", step == null);
                        out.put("step", step);
                        out.put("status", s.status());
                        return out;
                    }
                }
                case "run" -> {
                    if (method.equals("POST")) {
                        Map<String, Object> body = body(ex);
                        int count = body.get("count") == null ? Integer.MAX_VALUE
                                : ((Number) body.get("count")).intValue();
                        List<Object> steps = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            Map<String, Object> step = s.step();
                            if (step == null) break;
                            steps.add(step);
                        }
                        store.save(s);
                        return Map.of("steps", steps, "status", s.status());
                    }
                }
                case "checkpoints" -> {
                    if (parts.length == 6 && parts[5].equals("attach") && method.equals("POST")) {
                        Map<String, Object> body = body(ex);
                        Map<String, Object> cp = s.attachCheckpoint(
                                Model.asMap(body.get("checkpoint"), "checkpoint"));
                        store.save(s);
                        return cp;
                    }
                    if (method.equals("GET")) return Map.of("checkpoints", s.checkpoints);
                    if (method.equals("POST")) {
                        Map<String, Object> cp = s.checkpoint(Model.str(body(ex), "name"));
                        store.save(s);
                        return cp;
                    }
                }
                case "branch" -> {
                    if (method.equals("POST")) {
                        Map<String, Object> body = body(ex);
                        Session branch = s.branch(Model.str(body, "checkpointId"),
                                Model.str(body, "name"));
                        store.put(branch);
                        return branch.status();
                    }
                }
                case "merge" -> {
                    if (method.equals("POST")) {
                        Map<String, Object> body = body(ex);
                        Session merged = store.merge(id, Model.str(body, "other"));
                        return merged.status();
                    }
                }
                case "compare" -> {
                    if (method.equals("GET")) {
                        String other = queryParam(ex, "other");
                        Session o = store.get(other);
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("a", s.status());
                        out.put("b", o.status());
                        out.put("stateEqual", s.snapshot.toMap().equals(o.snapshot.toMap()));
                        out.put("outputsEqual", s.outputs().equals(o.outputs()));
                        out.put("outputsA", s.outputs());
                        out.put("outputsB", o.outputs());
                        return out;
                    }
                }
                case "export" -> {
                    if (method.equals("GET")) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("format", "smrr-session/1");
                        out.put("session", s.toMap());
                        return out;
                    }
                }
                default -> throw new IllegalArgumentException("unknown api path: " + path);
            }
        }
        throw new IllegalArgumentException("unsupported: " + method + " " + path);
    }

    @SuppressWarnings("unchecked")
    private static List<Event> eventsOf(Object o) {
        List<Event> out = new ArrayList<>();
        for (Object e : (List<Object>) o) {
            out.add(Event.fromMap(Model.asMap(e, "event")));
        }
        return out;
    }

    private static String queryParam(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q != null) {
            for (String pair : q.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && kv[0].equals(key)) {
                    return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalArgumentException("missing query param: " + key);
    }

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) return new LinkedHashMap<>();
        return Json.parseObject(text);
    }

    private static void sendJson(HttpExchange ex, int code, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendHtml(HttpExchange ex, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String indexHtml() {
        return resource("/web/index.html");
    }

    private static String resource(String path) {
        try (InputStream in = Server.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException(path + " missing from classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sendJs(HttpExchange ex, String js) throws IOException {
        byte[] bytes = js.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/javascript; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
