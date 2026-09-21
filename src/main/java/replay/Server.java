package replay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import replay.Model.Branch;
import replay.Model.Checkpoint;
import replay.Model.Event;
import replay.Model.TraceEntry;

/** JSON API + static web UI on the JDK built-in HTTP server. No external components. */
public final class Server {
    private final Store store;
    private final HttpServer http;

    public Server(Store store, String host, int port) throws IOException {
        this.store = store;
        this.http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.createContext("/", this::handle);
        http.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
    }

    public void start() { http.start(); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (ApiException ae) {
            sendJson(ex, ae.status, errorBody(ae.getMessage()));
        } catch (Session.MergeConflict mc) {
            sendJson(ex, 409, mc.detail);
        } catch (IllegalArgumentException iae) {
            sendJson(ex, 400, errorBody(iae.getMessage()));
        } catch (Exception e) {
            sendJson(ex, 500, errorBody("internal error: " + e));
        } finally {
            ex.close();
        }
    }

    private Map<String, Object> errorBody(String message) {
        Map<String, Object> m = Json.map();
        m.put("error", message);
        return m;
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String[] parts = path.split("/");

        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            sendStatic(ex, "web/index.html", "text/html; charset=utf-8");
            return;
        }

        if (parts.length >= 2 && "api".equals(parts[1])) {
            if (parts.length == 3 && "sessions".equals(parts[2])) {
                if ("GET".equals(method)) { listSessions(ex); return; }
                if ("POST".equals(method)) { createSession(ex); return; }
            }
            if (parts.length == 4 && "sessions".equals(parts[2]) && "import".equals(parts[3])
                    && "POST".equals(method)) {
                importSession(ex);
                return;
            }
            if (parts.length >= 4 && "sessions".equals(parts[2])) {
                String id = parts[3];
                String sub = parts.length >= 5 ? parts[4] : "";
                switch (sub) {
                    case "" -> {
                        if ("GET".equals(method)) { getSession(ex, id); return; }
                    }
                    case "definition" -> {
                        if ("PUT".equals(method)) { updateDefinition(ex, id); return; }
                    }
                    case "events" -> {
                        if ("POST".equals(method)) { addEvents(ex, id); return; }
                    }
                    case "step" -> {
                        if ("POST".equals(method)) { step(ex, id); return; }
                    }
                    case "run" -> {
                        if ("POST".equals(method)) { run(ex, id); return; }
                    }
                    case "reset" -> {
                        if ("POST".equals(method)) { reset(ex, id); return; }
                    }
                    case "checkpoints" -> {
                        if ("POST".equals(method)) { checkpoint(ex, id); return; }
                    }
                    case "restore" -> {
                        if ("POST".equals(method)) { restore(ex, id); return; }
                    }
                    case "branches" -> {
                        if ("POST".equals(method)) { fork(ex, id); return; }
                    }
                    case "switch" -> {
                        if ("POST".equals(method)) { switchBranch(ex, id); return; }
                    }
                    case "merge" -> {
                        if ("POST".equals(method)) { merge(ex, id); return; }
                    }
                    case "export" -> {
                        if ("GET".equals(method)) { exportSession(ex, id); return; }
                    }
                    default -> { }
                }
            }
        }
        sendJson(ex, 404, errorBody("not found: " + method + " " + path));
    }

    private void listSessions(HttpExchange ex) throws IOException {
        List<Object> out = Json.list();
        for (Session s : store.all().values()) {
            Map<String, Object> m = Json.map();
            m.put("id", s.id);
            m.put("name", s.name);
            m.put("fingerprint", s.fingerprint());
            m.put("definitionFingerprint", s.definition.fingerprint);
            m.put("branches", (long) s.branches.size());
            out.add(m);
        }
        sendJson(ex, 200, out);
    }

    private void createSession(HttpExchange ex) throws IOException {
        Map<String, Object> body = body(ex);
        String name = String.valueOf(body.getOrDefault("name", "session"));
        Map<String, Object> def = Json.asMap(body.get("definition"));
        long seed = body.containsKey("seed") ? Json.asLong(body.get("seed")) : 42L;
        Map<String, Integer> prio = parsePriorities(body.get("sourcePriorities"));
        Session s = store.create(name, def, seed, prio);
        sendJson(ex, 201, stateView(s));
    }

    private void importSession(HttpExchange ex) throws IOException {
        Map<String, Object> body = body(ex);
        Session s = Session.fromJson(Json.asMap(body.get("session")));
        store.put(s);
        Map<String, Object> resp = stateView(s);
        resp.put("imported", true);
        sendJson(ex, 201, resp);
    }

    private void getSession(HttpExchange ex, String id) throws IOException {
        sendJson(ex, 200, stateView(store.get(id)));
    }

    private void updateDefinition(HttpExchange ex, String id) throws IOException {
        Session s = store.get(id);
        s.updateDefinition(Json.asMap(body(ex).get("definition")));
        store.save(s);
        sendJson(ex, 200, stateView(s));
    }

    private void addEvents(HttpExchange ex, String id) throws IOException {
        Session s = store.get(id);
        Map<String, Object> body = body(ex);
        List<Event> events = new ArrayList<>();
        for (Object o : Json.asList(body.get("events"))) {
            events.add(Event.fromJson(Json.asMap(o), false));
        }
        s.addEvents(events);
        store.save(s);
        sendJson(ex, 200, stateView(s));
    }

    private void step(HttpExchange ex, String id) throws IOException {
        Session s = store.get(id);
        TraceEntry entry = s.step();
        store.save(s);
        Map<String, Object> resp = stateView(s);
        resp.put("stepped", entry.toJson());
        sendJson(ex, 200, resp);
    }

    private void run(HttpExchange ex, String id) throws IOException {
        Session s = store.get(id);
        int stepped = s.runToEnd();
        store.save(s);
        Map<String, Object> resp = stateView(s);
        resp.put("steppedCount", (long) stepped);
        sendJson(ex, 200, resp);
    }

    private void reset(HttpExchange ex, String id) throws IOException {
        Session s = store.get(id);
        s.resetCursor();
        store.save(s);
        sendJson(ex, 200, stateView(s));
    }

    private void checkpoint(HttpExchange ex, String id) throws IOException {
        Session s = store.get(id);
        Map<String, Object> body = body(ex);
        Checkpoint cp = s.checkpoint(String.valueOf(body.getOrDefault("name", "checkpoint")));
        store.save(s);
        Map<String, Object> resp = stateView(s);
        resp.put("checkpoint", cp.toJson());
        sendJson(ex, 201, resp);
    }

    private void restore(HttpExchange ex, String id) throws IOException {
        Session s = store.get(id);
        s.restore(Json.asString(body(ex).get("checkpointId")));
        store.save(s);
        sendJson(ex, 200, stateView(s));
    }

    private void fork(HttpExchange ex, String id) throws IOException {
        Session s = store.get(id);
        Map<String, Object> body = body(ex);
        Object cpId = body.get("checkpointId");
        Branch b = s.fork(String.valueOf(body.getOrDefault("name", "branch")),
                cpId == null ? null : Json.asString(cpId));
        store.save(s);
        Map<String, Object> resp = stateView(s);
        resp.put("branch", b.toJson());
        sendJson(ex, 201, resp);
    }

    private void switchBranch(HttpExchange ex, String id) throws IOException {
        Session s = store.get(id);
        s.switchBranch(Json.asString(body(ex).get("branchId")));
        store.save(s);
        sendJson(ex, 200, stateView(s));
    }

    private void merge(HttpExchange ex, String id) throws IOException {
        Session s = store.get(id);
        Branch merged = s.merge(Json.asString(body(ex).get("branchId")));
        store.save(s);
        Map<String, Object> resp = stateView(s);
        resp.put("mergedBranch", merged.toJson());
        sendJson(ex, 200, resp);
    }

    private void exportSession(HttpExchange ex, String id) throws IOException {
        Session s = store.get(id);
        Map<String, Object> m = Json.map();
        m.put("session", s.toJson());
        m.put("fingerprint", s.fingerprint());
        m.put("traceHash", s.traceHash(s.currentBranch()));
        sendJson(ex, 200, m);
    }

    private Map<String, Integer> parsePriorities(Object o) {
        Map<String, Integer> prio = new LinkedHashMap<>();
        if (o != null) {
            for (Map.Entry<String, Object> e : Json.asMap(o).entrySet()) {
                prio.put(e.getKey(), (int) Json.asLong(e.getValue()));
            }
        }
        return prio;
    }

    /** Full state view for the UI: trace with diffs, checkpoints, branches, fingerprints. */
    private Map<String, Object> stateView(Session s) {
        Branch b = s.currentBranch();
        Engine.Outcome o = s.replayToCursor(b);
        Map<String, Object> m = Json.map();
        m.put("id", s.id);
        m.put("name", s.name);
        m.put("fingerprint", s.fingerprint());
        m.put("definitionFingerprint", s.definition.fingerprint);
        m.put("traceHash", s.traceHash(b));
        m.put("definition", s.definition.raw);
        m.put("seed", s.seed);
        m.put("sourcePriorities", s.sourcePriorities);
        m.put("currentBranchId", b.id);
        m.put("cursor", (long) b.cursor);
        m.put("pendingEvents", (long) (Engine.replay(s.definition, s.seed, b.events, -1, s.sourcePriorities).trace.size() - b.cursor));
        Map<String, Object> state = Json.map();
        state.put("state", o.state);
        state.put("vars", o.vars);
        state.put("clock", o.clock);
        m.put("current", state);
        List<Object> trace = Json.list();
        for (TraceEntry t : o.trace) trace.add(t.toJson());
        m.put("trace", trace);
        List<Object> cps = Json.list();
        for (Checkpoint c : b.checkpoints) cps.add(c.toJson());
        m.put("checkpoints", cps);
        List<Object> branches = Json.list();
        for (Branch br : s.branches.values()) {
            Map<String, Object> bm = Json.map();
            bm.put("id", br.id);
            bm.put("name", br.name);
            bm.put("cursor", (long) br.cursor);
            bm.put("events", (long) br.events.size());
            bm.put("traceHash", s.traceHash(br));
            branches.add(bm);
        }
        m.put("branches", branches);
        List<Object> outputs = Json.list();
        outputs.addAll(o.allOutputs);
        m.put("outputs", outputs);
        return m;
    }

    private Map<String, Object> body(HttpExchange ex) throws IOException {
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) return Json.map();
        return Json.asMap(Json.parse(text));
    }

    private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendStatic(HttpExchange ex, String resource, String contentType) throws IOException {
        InputStream in = Server.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            sendJson(ex, 404, errorBody("missing resource " + resource));
            return;
        }
        byte[] bytes = in.readAllBytes();
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
