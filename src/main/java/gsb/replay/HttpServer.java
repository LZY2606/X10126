package gsb.replay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDK 内置 HTTP 服务：静态页面 + JSON API，无任何外部组件。 */
public final class HttpServer {
    private final Store store;
    private final Replay replay = new Replay();
    private final String defaultSessionId = "default";
    private com.sun.net.httpserver.HttpServer server;

    public HttpServer(Path dataDir) {
        this.store = new Store(dataDir);
    }

    public void start(String host, int port) throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", new RootHandler());
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private Map<String, Object> session() {
        return store.loadOrCreate(defaultSessionId, Demo.definition());
    }

    private void persist(Map<String, Object> s) {
        store.save(defaultSessionId, s);
    }

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                route(ex);
            } catch (Replay.ConflictException ce) {
                writeJson(ex, 409, errorBody(ce.getMessage(), ce.detail));
            } catch (Json.JsonException je) {
                writeJson(ex, 400, errorBody(je.getMessage(), null));
            } catch (IllegalArgumentException iae) {
                writeJson(ex, 400, errorBody(iae.getMessage(), null));
            } catch (Exception e) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                writeJson(ex, 500, body);
            } finally {
                ex.close();
            }
        }
    }

    private Map<String, Object> errorBody(String msg, Map<String, Object> detail) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", msg);
        if (detail != null) {
            b.put("detail", detail);
        }
        return b;
    }

    // ---------------- 路由 ----------------

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            serveStatic(ex, "/static/index.html", "text/html; charset=utf-8");
            return;
        }
        if ("GET".equals(method) && path.startsWith("/static/")) {
            serveStatic(ex, path, "application/octet-stream");
            return;
        }
        if ("GET".equals(method) && "/api/health".equals(path)) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("ok", true);
            b.put("title", "状态机回放室");
            writeJson(ex, 200, b);
            return;
        }

        if ("GET".equals(method) && "/api/session".equals(path)) {
            writeJson(ex, 200, sessionView(session()));
            return;
        }
        if ("POST".equals(method) && "/api/session".equals(path)) {
            Map<String, Object> req = readJson(ex);
            Map<String, Object> s = replay.createSession(Json.obj(req.get("definition"), "definition"));
            persist(s);
            writeJson(ex, 200, sessionView(s));
            return;
        }

        if ("POST".equals(method) && "/api/definition".equals(path)) {
            Map<String, Object> req = readJson(ex);
            Map<String, Object> cur = session();
            Map<String, Object> main = replay.branch(cur, "main");
            if (((Number) main.get("stepCount")).longValue() > 0
                    || Replay.checkpoints(cur).size() > 1) {
                writeJson(ex, 409, errorBody("已有回放历史，请新建会话后再更换定义",
                        Map.of("hint", "use POST /api/session")));
                return;
            }
            int version = Integer.parseInt(String.valueOf(
                    Json.obj(cur.get("definition"), "definition").get("version"))) + 1;
            Map<String, Object> fresh = replay.createSession(Json.obj(req.get("definition"), "definition"), version);
            persist(fresh);
            writeJson(ex, 200, sessionView(fresh));
            return;
        }

        if ("POST".equals(method) && "/api/events/import".equals(path)) {
            Map<String, Object> req = readJson(ex);
            Map<String, Object> s = session();
            String branch = Json.str(req.getOrDefault("branch", "main"));
            List<Object> events = Json.list(req.get("events"), "events");
            List<Map<String, Object>> accepted = replay.importEvents(s, branch, new ArrayList<>(events));
            persist(s);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("imported", accepted.size());
            b.put("events", accepted);
            b.put("session", sessionView(s));
            writeJson(ex, 200, b);
            return;
        }

        if ("POST".equals(method) && "/api/step".equals(path)) {
            Map<String, Object> req = readJson(ex);
            Map<String, Object> s = session();
            String branch = Json.str(req.getOrDefault("branch", "main"));
            long max = Json.asLong(req.get("maxSteps"), 1L);
            if (max <= 0) {
                max = 1L;
            }
            List<Object> entries = new ArrayList<>();
            Map<String, Object> before = replay.branch(s, branch).get("state") instanceof Map
                    ? Definition.deepCopy(Json.obj(replay.branch(s, branch).get("state"), "state"))
                    : new LinkedHashMap<>();
            for (long i = 0; i < max && replay.canStep(s, branch); i++) {
                entries.add(replay.step(s, branch));
            }
            Map<String, Object> after = Definition.deepCopy(
                    Json.obj(replay.branch(s, branch).get("state"), "state"));
            persist(s);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("entries", entries);
            b.put("stateDiff", replay.diffState(before, after));
            b.put("session", sessionView(s));
            writeJson(ex, 200, b);
            return;
        }

        if ("POST".equals(method) && "/api/run".equals(path)) {
            Map<String, Object> req = readJson(ex);
            Map<String, Object> s = session();
            String branch = Json.str(req.getOrDefault("branch", "main"));
            int n = replay.runTo(s, branch, Json.asLong(req.get("maxSteps"), 0L));
            persist(s);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("steps", n);
            b.put("session", sessionView(s));
            writeJson(ex, 200, b);
            return;
        }

        if ("POST".equals(method) && "/api/checkpoints".equals(path)) {
            Map<String, Object> req = readJson(ex);
            Map<String, Object> s = session();
            Map<String, Object> cp = replay.checkpoint(s,
                    Json.str(req.getOrDefault("branch", "main")), Json.str(req.get("label")));
            persist(s);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("checkpoint", cp);
            b.put("session", sessionView(s));
            writeJson(ex, 200, b);
            return;
        }

        if ("POST".equals(method) && "/api/branches/fork".equals(path)) {
            Map<String, Object> req = readJson(ex);
            Map<String, Object> s = session();
            Map<String, Object> nb = replay.fork(s,
                    Json.str(req.get("checkpoint")), Json.str(req.get("name")),
                    Boolean.TRUE.equals(req.get("playPending")));
            persist(s);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("branch", replay.branchSummary(s, nb));
            b.put("session", sessionView(s));
            writeJson(ex, 200, b);
            return;
        }

        if ("POST".equals(method) && "/api/branches/compare".equals(path)) {
            Map<String, Object> req = readJson(ex);
            Map<String, Object> s = session();
            writeJson(ex, 200, replay.compareBranches(s,
                    Json.str(req.get("a")), Json.str(req.get("b"))));
            return;
        }

        if ("POST".equals(method) && "/api/merge/plan".equals(path)) {
            Map<String, Object> req = readJson(ex);
            Map<String, Object> s = session();
            Map<String, Object> plan = replay.mergePlan(s,
                    Json.str(req.get("a")), Json.str(req.get("b")),
                    Json.str(req.get("ancestor")));
            writeJson(ex, 200, plan);
            return;
        }

        if ("POST".equals(method) && "/api/merge".equals(path)) {
            Map<String, Object> req = readJson(ex);
            Map<String, Object> s = session();
            Map<String, Object> res = replay.merge(s,
                    Json.str(req.get("a")), Json.str(req.get("b")),
                    Json.str(req.get("ancestor")), Json.str(req.get("name")));
            persist(s);
            writeJson(ex, 200, res);
            return;
        }

        if ("GET".equals(method) && "/api/export".equals(path)) {
            Map<String, Object> s = session();
            writeJson(ex, 200, replay.exportSession(s));
            return;
        }

        if ("POST".equals(method) && "/api/import".equals(path)) {
            Map<String, Object> req = readJson(ex);
            Map<String, Object> restored = replay.importSession(req,
                    !Boolean.FALSE.equals(req.get("verify")));
            persist(restored);
            writeJson(ex, 200, sessionView(restored));
            return;
        }

        writeJson(ex, 404, errorBody("not found: " + method + " " + path, null));
    }

    // ---------------- 视图 / IO ----------------

    private Map<String, Object> sessionView(Map<String, Object> s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("definition", s.get("definition"));
        v.put("definitionFingerprint", replay.definitionFingerprint(s));
        v.put("sessionFingerprint", replay.sessionFingerprint(s));
        List<Object> allExternal = new ArrayList<>();
        for (Object bObj : Replay.branches(s).values()) {
            Map<String, Object> b = Json.obj(bObj, "branch");
            Object log = b.get("externalLog");
            if (log instanceof List) {
                allExternal.addAll((List<?>) log);
            }
        }
        v.put("importLog", allExternal);
        v.put("checkpoints", checkpointViews(s));

        Map<String, Object> branches = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Replay.branches(s).entrySet()) {
            branches.put(e.getKey(), replay.branchSummary(s,
                    Json.obj(e.getValue(), "branch")));
        }
        v.put("branches", branches);

        // main 的完整轨迹用于页面逐步展示；其他分支只给摘要 + 轨迹
        Map<String, Object> traces = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Replay.branches(s).entrySet()) {
            Map<String, Object> b = Json.obj(e.getValue(), "branch");
            traces.put(e.getKey(), b.get("trace"));
        }
        v.put("traces", traces);
        return v;
    }

    private List<Object> checkpointViews(Map<String, Object> s) {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : Replay.checkpoints(s).entrySet()) {
            Map<String, Object> cp = Json.obj(e.getValue(), "checkpoint");
            Map<String, Object> view = new LinkedHashMap<>();
            for (String k : new String[]{"id", "label", "branch", "step",
                    "definitionFingerprint", "definitionVersion", "traceHash", "currentState"}) {
                view.put(k, cp.get(k));
            }
            view.put("state", cp.get("state"));
            view.put("compatible", replay.definitionFingerprint(s).equals(cp.get("definitionFingerprint")));
            out.add(view);
        }
        return out;
    }

    private Map<String, Object> readJson(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return new LinkedHashMap<>();
        }
        return Json.parseObject(new String(body, StandardCharsets.UTF_8));
    }

    private void writeJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] data = Json.pretty(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private void serveStatic(HttpExchange ex, String resource, String contentType) throws IOException {
        try (var in = HttpServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                writeJson(ex, 404, errorBody("not found: " + resource, null));
                return;
            }
            byte[] data = in.readAllBytes();
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
    }
}
