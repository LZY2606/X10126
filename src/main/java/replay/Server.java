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
import java.util.concurrent.Executors;

/** JSON API + static web UI, served by the JDK built-in HTTP server. No external components. */
public final class Server {
    private final SessionStore store;
    private final HttpServer http;

    public Server(SessionStore store, String host, int port) throws IOException {
        this.store = store;
        this.http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.createContext("/", this::route);
    }

    public void start() { http.start(); }

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/") || path.equals("/index.html")) { serveStatic(ex, "/web/index.html", "text/html; charset=utf-8"); return; }
            if (path.startsWith("/api/")) { handleApi(ex, method, path); return; }
            send(ex, 404, Map.of("error", "not found"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            send(ex, 400, Map.of("error", e.getMessage() == null ? "bad request" : e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, Map.of("error", String.valueOf(e)));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleApi(HttpExchange ex, String method, String path) throws IOException {
        String[] parts = path.substring("/api/".length()).split("/");
        Map<String, Object> body = method.equals("POST") || method.equals("PUT")
                ? Json.parseObject(readBody(ex)) : Map.of();

        switch (parts[0]) {
            case "sessions" -> {
                if (parts.length == 1 && method.equals("GET")) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (Session s : store.list()) list.add(s.summary());
                    send(ex, 200, Map.of("sessions", list));
                    return;
                }
                if (parts.length == 1 && method.equals("POST")) {
                    Session s = createSession(body);
                    store.put(s);
                    send(ex, 200, s.summary());
                    return;
                }
                Session s = store.get(parts[1]);
                if (parts.length == 2 && method.equals("GET")) { send(ex, 200, s.toJson()); return; }
                switch (parts[2]) {
                    case "step" -> {
                        TraceEntry entry = s.engine.step();
                        store.save(s);
                        send(ex, 200, Map.of("entry", entry == null ? Map.of() : entry.toJson(),
                                "done", entry == null, "state", s.engine.state(),
                                "vars", s.engine.vars(), "pending", s.engine.pending(),
                                "trajectoryHash", s.trajectoryHash()));
                        return;
                    }
                    case "run" -> {
                        int max = body.get("max") instanceof Number n ? n.intValue() : 100000;
                        int steps = 0;
                        while (steps < max && s.engine.step() != null) steps++;
                        store.save(s);
                        send(ex, 200, Map.of("steps", (long) steps, "pending", s.engine.pending(),
                                "state", s.engine.state(), "vars", s.engine.vars(),
                                "trajectoryHash", s.trajectoryHash()));
                        return;
                    }
                    case "events" -> {
                        List<EventInstance> events = parseEvents(body.get("events"));
                        s.addEvents(events);
                        store.save(s);
                        send(ex, 200, Map.of("accepted", (long) events.size(), "pending", s.engine.pending()));
                        return;
                    }
                    case "trace" -> {
                        List<Map<String, Object>> trace = new ArrayList<>();
                        for (TraceEntry e : s.engine.trace()) trace.add(e.toJson());
                        send(ex, 200, Map.of("trace", trace, "trajectoryHash", s.trajectoryHash()));
                        return;
                    }
                    case "checkpoints" -> {
                        if (method.equals("POST")) {
                            Checkpoint cp = s.checkpoint(String.valueOf(body.getOrDefault("label", "checkpoint")));
                            store.save(s);
                            send(ex, 200, cp.toJson());
                        } else {
                            List<Map<String, Object>> cps = new ArrayList<>();
                            for (Checkpoint cp : s.checkpoints) cps.add(cp.toJson());
                            send(ex, 200, Map.of("checkpoints", cps));
                        }
                        return;
                    }
                    case "restore" -> {
                        Checkpoint cp = resolveCheckpoint(s, body);
                        s.restore(cp);
                        store.save(s);
                        send(ex, 200, s.summary());
                        return;
                    }
                    case "branch" -> {
                        Checkpoint cp = resolveCheckpoint(s, body);
                        Session child = s.branch(cp, String.valueOf(body.getOrDefault("name", s.name + "-branch")));
                        store.put(child);
                        store.save(s);
                        send(ex, 200, child.summary());
                        return;
                    }
                    case "export" -> {
                        send(ex, 200, s.toJson());
                        return;
                    }
                    default -> send(ex, 404, Map.of("error", "unknown session action: " + parts[2]));
                }
            }
            case "merge" -> {
                Session a = store.get(String.valueOf(body.get("a")));
                Session b = store.get(String.valueOf(body.get("b")));
                Merge.Result result = Merge.merge(a, b);
                if (result.conflict != null) {
                    send(ex, 200, Map.of("merged", false, "conflict", result.conflict.toJson()));
                } else {
                    store.put(result.merged);
                    send(ex, 200, Map.of("merged", true, "session", result.merged.summary()));
                }
                return;
            }
            case "compare" -> {
                Map<String, String> q = query(ex);
                Session a = store.get(q.get("a"));
                Session b = store.get(q.get("b"));
                send(ex, 200, Map.of(
                        "a", a.summary(), "b", b.summary(),
                        "stateDiff", diff(a, b),
                        "sameTrajectory", a.trajectoryHash().equals(b.trajectoryHash())));
                return;
            }
            case "import" -> {
                Session s = store.importSession(body);
                send(ex, 200, s.summary());
                return;
            }
            default -> send(ex, 404, Map.of("error", "unknown endpoint"));
        }
    }

    private Session createSession(Map<String, Object> body) {
        Object defObj = body.get("definition");
        if (!(defObj instanceof Map)) throw new IllegalArgumentException("definition object required");
        @SuppressWarnings("unchecked")
        Definition def = Definition.fromJson((Map<String, Object>) defObj);
        String initialState = String.valueOf(body.getOrDefault("initialState", def.states.get(0)));
        @SuppressWarnings("unchecked")
        Map<String, Object> initialVars = body.get("initialVars") instanceof Map
                ? (Map<String, Object>) body.get("initialVars") : Map.of();
        long seed = body.get("seed") instanceof Number n ? n.longValue() : 1L;
        List<EventInstance> events = parseEvents(body.get("events"));
        return Session.create(String.valueOf(body.getOrDefault("name", "session")),
                def, initialState, initialVars, seed, events);
    }

    @SuppressWarnings("unchecked")
    static List<EventInstance> parseEvents(Object obj) {
        List<EventInstance> out = new ArrayList<>();
        if (obj instanceof List) {
            for (Object o : (List<Object>) obj) out.add(EventInstance.fromJson((Map<String, Object>) o));
        }
        return out;
    }

    private Checkpoint resolveCheckpoint(Session s, Map<String, Object> body) {
        // A checkpoint document may be supplied directly (e.g. from an export or another definition
        // version) to exercise fingerprint validation; otherwise look it up by id.
        if (body.get("checkpoint") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) body.get("checkpoint");
            return Checkpoint.fromJson(doc);
        }
        String id = String.valueOf(body.get("checkpointId"));
        for (Checkpoint cp : s.checkpoints) if (cp.id.equals(id)) return cp;
        throw new IllegalArgumentException("no such checkpoint: " + id);
    }

    private Map<String, Object> diff(Session a, Session b) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("stateEqual", a.engine.state().equals(b.engine.state()));
        Map<String, Object> varDiff = new LinkedHashMap<>();
        java.util.Set<String> keys = new java.util.TreeSet<>(a.engine.vars().keySet());
        keys.addAll(b.engine.vars().keySet());
        for (String k : keys) {
            Object va = a.engine.vars().get(k);
            Object vb = b.engine.vars().get(k);
            if (!java.util.Objects.equals(va, vb)) varDiff.put(k, Map.of("a", va == null ? "∅" : va, "b", vb == null ? "∅" : vb));
        }
        d.put("varDiff", varDiff);
        d.put("stateA", a.engine.state());
        d.put("stateB", b.engine.state());
        return d;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) q.put(pair.substring(0, i), java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private void serveStatic(HttpExchange ex, String resource, String contentType) throws IOException {
        try (InputStream in = Server.class.getResourceAsStream(resource)) {
            if (in == null) { send(ex, 404, Map.of("error", "missing resource " + resource)); return; }
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void send(HttpExchange ex, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = Json.canonical(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
