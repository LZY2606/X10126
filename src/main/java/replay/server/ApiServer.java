package replay.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import replay.core.Branch;
import replay.core.Checkpoint;
import replay.core.Definition;
import replay.core.Sample;
import replay.core.Session;
import replay.core.Store;
import replay.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** JSON API + static web UI. No external components; everything runs in-process. */
public final class ApiServer {
    private final HttpServer server;
    private Session session;
    private final Store store;

    public ApiServer(String host, int port, Session session, Store store) throws IOException {
        this.session = session;
        this.store = store;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        this.server.createContext("/", this::dispatch);
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

    // ---------- routing ----------

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            String path = ex.getURI().getPath();
            String method = ex.getRequestMethod();
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                sendHtml(ex, loadIndexHtml());
                return;
            }
            if (path.startsWith("/api/")) {
                Object result = route(method, path, ex);
                sendJson(ex, 200, result);
                return;
            }
            sendJson(ex, 404, Map.of("error", "not found: " + path));
        } catch (Session.ApiException e) {
            sendJson(ex, e.status, Map.of("error", e.getMessage()));
        } catch (Session.CheckpointMismatch e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", e.getMessage());
            m.put("checkpointDefinitionFingerprint", e.expected);
            m.put("currentDefinitionFingerprint", e.actual);
            sendJson(ex, 409, m);
        } catch (Session.MergeConflict e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", "merge rejected: " + e.reason);
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("reason", e.reason);
            conflict.put("eventA", e.a.toJson());
            conflict.put("eventB", e.b.toJson());
            m.put("conflict", conflict);
            sendJson(ex, 409, m);
        } catch (Json.JsonException e) {
            sendJson(ex, 400, Map.of("error", "bad json: " + e.getMessage()));
        } catch (replay.expr.Expr.ExprException e) {
            sendJson(ex, 400, Map.of("error", "expression error: " + e.getMessage()));
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private Object route(String method, String path, HttpExchange ex) throws IOException {
        switch (path) {
            case "/api/session":
                require("GET", method, path);
                return sessionView();
            case "/api/session/new": {
                require("POST", method, path);
                Map<String, Object> body = bodyJson(ex);
                String name = body.get("name") == null ? "replay" : Json.asString(body.get("name"), "name");
                long seed = body.get("seed") == null ? 42L : Json.asLong(body.get("seed"), "seed");
                Definition def = body.get("definition") == null
                        ? Sample.definition()
                        : Definition.fromJson(Json.asMap(body.get("definition"), "definition"));
                session = Session.create(name, seed, def);
                save();
                return sessionView();
            }
            case "/api/definition": {
                require("POST", method, path);
                Map<String, Object> body = bodyJson(ex);
                ensureSession();
                Definition def = Definition.fromJson(Json.asMap(body.get("definition"), "definition"));
                boolean reset = !Boolean.FALSE.equals(body.get("reset"));
                session.updateDefinition(def, reset);
                save();
                return sessionView();
            }
            case "/api/events/import": {
                require("POST", method, path);
                Map<String, Object> body = bodyJson(ex);
                ensureSession();
                List<Object> events = Json.asList(body.get("events"), "events");
                List<Object> imported = new ArrayList<>();
                for (Object o : events) {
                    imported.add(session.importEvent(session.active(), Json.asMap(o, "event")).toJson());
                }
                save();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("imported", imported);
                r.put("queueSize", session.active().queue.size());
                return r;
            }
            case "/api/step": {
                require("POST", method, path);
                ensureSession();
                Map<String, Object> entry = session.active().step();
                save();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("entry", entry);
                r.put("done", entry == null);
                r.put("state", session.active().state);
                r.put("traceHash", session.active().traceHash);
                return r;
            }
            case "/api/run": {
                require("POST", method, path);
                ensureSession();
                Map<String, Object> body = bodyJson(ex);
                int max = body.get("maxSteps") == null ? 100000 : (int) Json.asLong(body.get("maxSteps"), "maxSteps");
                int n = session.active().runToCompletion(max);
                save();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("steps", n);
                r.put("state", session.active().state);
                r.put("traceHash", session.active().traceHash);
                return r;
            }
            case "/api/checkpoint": {
                require("POST", method, path);
                ensureSession();
                Map<String, Object> body = bodyJson(ex);
                String name = body.get("name") == null ? null : Json.asString(body.get("name"), "name");
                Checkpoint cp = session.checkpoint(session.active(), name);
                save();
                return cp.toJson();
            }
            case "/api/branch": {
                require("POST", method, path);
                ensureSession();
                Map<String, Object> body = bodyJson(ex);
                String cpId = Json.asString(body.get("checkpointId"), "checkpointId");
                String name = body.get("name") == null ? null : Json.asString(body.get("name"), "name");
                Branch b = session.fork(cpId, name);
                save();
                return b.toJson();
            }
            case "/api/branch/switch": {
                require("POST", method, path);
                ensureSession();
                Map<String, Object> body = bodyJson(ex);
                String id = Json.asString(body.get("branchId"), "branchId");
                session.branch(id);
                session.activeBranchId = id;
                save();
                return sessionView();
            }
            case "/api/merge": {
                require("POST", method, path);
                ensureSession();
                Map<String, Object> body = bodyJson(ex);
                String source = Json.asString(body.get("source"), "source");
                String target = Json.asString(body.get("target"), "target");
                Session.MergeResult r = session.merge(source, target);
                save();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("merged", true);
                m.put("mergedEvents", r.mergedEvents);
                m.put("traceHash", r.traceHash);
                return m;
            }
            case "/api/compare": {
                require("GET", method, path);
                ensureSession();
                Map<String, String> q = query(ex.getURI().getRawQuery());
                String a = q.get("a");
                String b = q.get("b");
                if (a == null || b == null) throw new Session.ApiException(400, "compare needs ?a=<branch>&b=<branch>");
                return session.compare(a, b);
            }
            case "/api/export": {
                require("GET", method, path);
                ensureSession();
                return session.toJson();
            }
            case "/api/import": {
                require("POST", method, path);
                Map<String, Object> body = bodyJson(ex);
                session = Session.fromJson(body);
                save();
                return sessionView();
            }
            default:
                throw new Session.ApiException(404, "not found: " + path);
        }
    }

    // ---------- views ----------

    private Map<String, Object> sessionView() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (session == null) {
            m.put("initialized", false);
            return m;
        }
        m.put("initialized", true);
        m.put("name", session.name);
        m.put("seed", session.seed);
        m.put("fingerprint", session.fingerprint());
        m.put("definition", session.definition.toJson());
        m.put("definitionFingerprint", session.definition.fingerprint());
        m.put("activeBranchId", session.activeBranchId);
        List<Object> bs = new ArrayList<>();
        for (Branch b : session.branches.values()) {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("id", b.id);
            bm.put("name", b.name);
            bm.put("ancestorCheckpointId", b.ancestorCheckpointId);
            bm.put("state", b.state);
            bm.put("vars", Json.deepCopy(b.vars));
            bm.put("queueSize", b.queue.size());
            List<Object> preview = new ArrayList<>();
            List<replay.core.Event> sorted = new ArrayList<>(b.queue);
            sorted.sort(replay.core.Event.order(session.definition));
            for (replay.core.Event e : sorted) preview.add(e.toJson());
            bm.put("queue", preview);
            bm.put("outputs", new ArrayList<>(b.outputs));
            bm.put("trace", Json.deepCopy(b.trace));
            bm.put("traceHash", b.traceHash);
            List<Object> cps = new ArrayList<>();
            for (Checkpoint cp : b.checkpoints) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("id", cp.id);
                cm.put("name", cp.name);
                cm.put("stepCount", cp.stepCount);
                cm.put("state", cp.state);
                cm.put("defFingerprint", cp.defFingerprint);
                cm.put("fingerprintValid", cp.defFingerprint.equals(session.definition.fingerprint()));
                cps.add(cm);
            }
            bm.put("checkpoints", cps);
            bs.add(bm);
        }
        m.put("branches", bs);
        return m;
    }

    // ---------- plumbing ----------

    private void ensureSession() {
        if (session == null) {
            session = Session.create("replay", 42L, Sample.definition());
        }
    }

    private void save() {
        if (store != null && session != null) store.save(session);
    }

    private static void require(String expected, String actual, String path) {
        if (!expected.equals(actual)) throw new Session.ApiException(405, path + " needs " + expected);
    }

    private static Map<String, Object> bodyJson(HttpExchange ex) throws IOException {
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) return new LinkedHashMap<>();
        return Json.parseObject(text);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                m.put(java.net.URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                      java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static String loadIndexHtml() throws IOException {
        try (InputStream in = ApiServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) throw new IOException("web/index.html resource missing");
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

    private static void sendHtml(HttpExchange ex, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
