package replay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** JSON API + static UI over the JDK built-in HTTP server. No external components. */
public final class Server {
    private final Store store;
    private final HttpServer http;

    public Server(Store store, String host, int port) throws IOException {
        this.store = store;
        this.http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.createContext("/", this::route);
        http.setExecutor(Executors.newFixedThreadPool(4));
    }

    public void start() {
        http.start();
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            dispatch(ex);
        } catch (Engine.DefinitionMismatchException e) {
            respond(ex, 409, error("definition-mismatch", e.getMessage()
                    + " (expected " + e.expected + ", checkpoint has " + e.actual + ")"));
        } catch (Merger.MergeException e) {
            Map<String, Object> body = error("merge-conflict", e.getMessage());
            body.put("conflict", e.firstConflict.toJson());
            respond(ex, 409, body);
        } catch (IllegalArgumentException e) {
            respond(ex, 400, error("bad-request", e.getMessage()));
        } catch (Exception e) {
            respond(ex, 500, error("internal", e.toString()));
        } finally {
            ex.close();
        }
    }

    private void dispatch(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String[] seg = path.split("/");

        if ("GET".equals(method) && "/".equals(path)) {
            respondHtml(ex, 200, Ui.PAGE);
            return;
        }
        if ("GET".equals(method) && "/api/state".equals(path)) {
            Map<String, Object> m = Json.newObj();
            m.put("definitions", store.definitionSummaries());
            List<Object> bs = Json.newArr();
            for (Branch b : store.branches()) bs.add(b.summary());
            m.put("branches", bs);
            respond(ex, 200, m);
            return;
        }
        if ("POST".equals(method) && "/api/definitions".equals(path)) {
            MachineDefinition def = store.saveDefinition(Json.parseObj(body(ex)));
            respond(ex, 200, defSummary(def));
            return;
        }
        if ("POST".equals(method) && "/api/branches".equals(path)) {
            Map<String, Object> req = Json.parseObj(body(ex));
            Long version = req.containsKey("definitionVersion") ? Json.num(req.get("definitionVersion")) : null;
            List<Event> events = parseEvents(req.get("events"));
            Branch b = store.createBranch(
                    Json.str(req.get("definitionId")),
                    version,
                    Json.str(req.getOrDefault("name", "main")),
                    Json.num(req.getOrDefault("seed", 42L)),
                    req.containsKey("initialState") ? Json.str(req.get("initialState")) : null,
                    req.containsKey("initialVariables") ? Json.obj(req.get("initialVariables")) : null,
                    events);
            respond(ex, 200, b.summary());
            return;
        }
        if ("POST".equals(method) && "/api/import".equals(path)) {
            Branch b = store.importBranch(Json.parseObj(body(ex)));
            respond(ex, 200, b.summary());
            return;
        }
        if ("POST".equals(method) && "/api/merge".equals(path)) {
            Map<String, Object> req = Json.parseObj(body(ex));
            Branch merged = store.merge(Json.str(req.get("into")), Json.str(req.get("from")));
            respond(ex, 200, merged.summary());
            return;
        }
        // /api/branches/{id}/...
        if (seg.length >= 4 && "api".equals(seg[1]) && "branches".equals(seg[2])) {
            String id = seg[3];
            String sub = seg.length >= 5 ? seg[4] : "";
            switch (method + " " + sub) {
                case "GET ": {
                    respond(ex, 200, branchDetail(id));
                    return;
                }
                case "POST events": {
                    Map<String, Object> req = Json.parseObj(body(ex));
                    Branch b = store.branch(id);
                    List<Event> events = parseEvents(req.get("events"));
                    b.externalEvents.addAll(events);
                    b.engine.enqueueExternal(events);
                    store.save(b);
                    respond(ex, 200, b.summary());
                    return;
                }
                case "POST step": {
                    Branch b = store.branch(id);
                    Map<String, Object> entry = b.engine.step();
                    store.save(b);
                    Map<String, Object> m = Json.newObj();
                    m.put("entry", entry);
                    m.put("branch", b.summary());
                    respond(ex, 200, m);
                    return;
                }
                case "POST run": {
                    Map<String, Object> req = body(ex).isBlank() ? Json.newObj() : Json.parseObj(body(ex));
                    Branch b = store.branch(id);
                    int n = b.engine.run(Json.num(req.getOrDefault("maxSteps", 10000L)));
                    store.save(b);
                    Map<String, Object> m = Json.newObj();
                    m.put("steps", n);
                    m.put("branch", b.summary());
                    respond(ex, 200, m);
                    return;
                }
                case "POST checkpoints": {
                    Map<String, Object> req = body(ex).isBlank() ? Json.newObj() : Json.parseObj(body(ex));
                    Branch b = store.branch(id);
                    Branch.Checkpoint cp = b.addCheckpoint(Json.str(req.getOrDefault("name", null)));
                    store.save(b);
                    Map<String, Object> m = Json.newObj();
                    m.put("id", cp.id);
                    m.put("name", cp.name);
                    m.put("step", cp.step);
                    m.put("definitionFingerprint", cp.definitionFingerprint);
                    respond(ex, 200, m);
                    return;
                }
                case "POST restore": {
                    Map<String, Object> req = Json.parseObj(body(ex));
                    store.restoreCheckpoint(id, Json.str(req.get("checkpointId")));
                    respond(ex, 200, store.branch(id).summary());
                    return;
                }
                case "POST fork": {
                    Map<String, Object> req = Json.parseObj(body(ex));
                    Branch fork = store.fork(id, Json.str(req.get("checkpointId")),
                            Json.str(req.getOrDefault("name", "fork")));
                    respond(ex, 200, fork.summary());
                    return;
                }
                case "GET export": {
                    respond(ex, 200, store.exportBranch(id));
                    return;
                }
            }
            if ("GET".equals(method) && seg.length >= 5 && "diff".equals(seg[4]) && seg.length >= 6) {
                respond(ex, 200, store.diff(id, seg[5]));
                return;
            }
        }
        respond(ex, 404, error("not-found", method + " " + path));
    }

    private Map<String, Object> branchDetail(String id) {
        Branch b = store.branch(id);
        Map<String, Object> m = b.summary();
        m.put("vars", Json.deepCopy(b.engine.vars()));
        List<Object> pending = Json.newArr();
        for (Event e : b.engine.pending()) pending.add(e.toJson());
        m.put("pendingEvents", pending);
        m.put("trace", new ArrayList<>(b.engine.trace()));
        List<Object> cps = Json.newArr();
        for (Branch.Checkpoint cp : b.checkpoints.values()) cps.add(cp.toJson());
        m.put("checkpoints", cps);
        return m;
    }

    private static List<Event> parseEvents(Object raw) {
        List<Event> events = new ArrayList<>();
        if (raw == null) return events;
        long insertion = 1;
        for (Object e : Json.arr(raw)) {
            events.add(Event.fromJson(Json.obj(e), false, insertion++));
        }
        return events;
    }

    private static Map<String, Object> defSummary(MachineDefinition def) {
        Map<String, Object> m = Json.newObj();
        m.put("id", def.id);
        m.put("version", def.version);
        m.put("fingerprint", def.fingerprint);
        return m;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> m = Json.newObj();
        m.put("error", code);
        m.put("message", message == null ? "" : message);
        return m;
    }

    private static String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange ex, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = Json.pretty(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static void respondHtml(HttpExchange ex, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
