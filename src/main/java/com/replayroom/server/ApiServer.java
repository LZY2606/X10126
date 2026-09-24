package com.replayroom.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.replayroom.Json;
import com.replayroom.core.LogEvent;
import com.replayroom.core.MachineDefinition;
import com.replayroom.core.ReplaySession;
import com.replayroom.core.TraceRecord;
import com.replayroom.store.SessionStore;
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

/** JSON API + static UI for the deterministic state machine replay room. */
public class ApiServer {
    private final Map<String, ReplaySession> sessions;
    private final SessionStore store;
    private HttpServer server;

    public ApiServer(SessionStore store) {
        this.store = store;
        this.sessions = store.loadAll();
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::dispatch);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (ReplaySession.CheckpointMismatchException e) {
            sendJson(exchange, 409, Map.of("error", e.getMessage(), "kind", "checkpoint-mismatch"));
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");

        if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
            sendHtml(exchange, 200, loadIndex());
            return;
        }
        if (path.equals("/api/sessions") && method.equals("GET")) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (ReplaySession s : sessions.values()) {
                list.add(summary(s));
            }
            sendJson(exchange, 200, list);
            return;
        }
        if (path.equals("/api/sessions") && method.equals("POST")) {
            JsonNode body = readBody(exchange);
            MachineDefinition definition = Json.convert(body.required("definition"), MachineDefinition.class);
            List<LogEvent> events = new ArrayList<>();
            if (body.has("events")) {
                for (JsonNode e : body.get("events")) {
                    events.add(Json.convert(e, LogEvent.class));
                }
            }
            String id = nextSessionId();
            ReplaySession session = ReplaySession.create(id,
                    body.hasNonNull("name") ? body.get("name").asText() : id, definition, events);
            sessions.put(id, session);
            store.save(session);
            sendJson(exchange, 200, view(session));
            return;
        }
        if (path.equals("/api/import") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ReplaySession session = store.importSession(body, sessions);
            sessions.put(session.id, session);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", session.id);
            result.put("fingerprint", session.fingerprint);
            result.put("traceHashes", session.traceHashes());
            sendJson(exchange, 200, result);
            return;
        }
        // /api/sessions/{id}/...
        if (parts.length >= 4 && parts[1].equals("api") && parts[2].equals("sessions")) {
            ReplaySession session = sessions.get(parts[3]);
            if (session == null) {
                sendJson(exchange, 404, Map.of("error", "unknown session: " + parts[3]));
                return;
            }
            String sub = parts.length >= 5 ? parts[4] : "";
            switch (sub) {
                case "" -> {
                    if (method.equals("GET")) {
                        sendJson(exchange, 200, view(session));
                        return;
                    }
                }
                case "definition" -> {
                    if (method.equals("PUT")) {
                        MachineDefinition definition = Json.convert(readBody(exchange), MachineDefinition.class);
                        session.updateDefinition(definition);
                        store.save(session);
                        sendJson(exchange, 200, Map.of(
                                "fingerprint", session.fingerprint,
                                "definitionFingerprint", session.definitionFingerprint));
                        return;
                    }
                }
                case "events" -> {
                    if (method.equals("POST")) {
                        JsonNode body = readBody(exchange);
                        List<LogEvent> events = new ArrayList<>();
                        for (JsonNode e : body.required("events")) {
                            events.add(Json.convert(e, LogEvent.class));
                        }
                        String branch = body.hasNonNull("branch") ? body.get("branch").asText() : null;
                        List<LogEvent> added = session.addEvents(events, branch);
                        store.save(session);
                        sendJson(exchange, 200, Map.of("added", added));
                        return;
                    }
                }
                case "step" -> {
                    if (method.equals("POST")) {
                        JsonNode body = readBody(exchange);
                        String branch = body != null && body.hasNonNull("branch") ? body.get("branch").asText() : null;
                        TraceRecord record = session.step(branch);
                        store.save(session);
                        if (record == null) {
                            sendJson(exchange, 200, Map.of("done", true));
                        } else {
                            sendJson(exchange, 200, Map.of("done", false, "record", record));
                        }
                        return;
                    }
                }
                case "run" -> {
                    if (method.equals("POST")) {
                        JsonNode body = readBody(exchange);
                        String branch = body != null && body.hasNonNull("branch") ? body.get("branch").asText() : null;
                        long max = body != null && body.hasNonNull("max") ? body.get("max").asLong() : 100000;
                        List<TraceRecord> records = session.runToEnd(branch, max);
                        store.save(session);
                        sendJson(exchange, 200, Map.of("steps", records.size()));
                        return;
                    }
                }
                case "checkpoints" -> {
                    if (method.equals("POST")) {
                        JsonNode body = readBody(exchange);
                        String name = body != null && body.hasNonNull("name") ? body.get("name").asText() : null;
                        String branch = body != null && body.hasNonNull("branch") ? body.get("branch").asText() : null;
                        var checkpoint = session.checkpoint(name, branch);
                        store.save(session);
                        sendJson(exchange, 200, checkpoint);
                        return;
                    }
                }
                case "fork" -> {
                    if (method.equals("POST")) {
                        JsonNode body = readBody(exchange);
                        var fork = session.fork(body.required("checkpointId").asText(),
                                body.hasNonNull("name") ? body.get("name").asText() : null);
                        store.save(session);
                        sendJson(exchange, 200, fork);
                        return;
                    }
                }
                case "checkout" -> {
                    if (method.equals("POST")) {
                        JsonNode body = readBody(exchange);
                        session.branch(body.required("branch").asText());
                        session.currentBranchId = body.get("branch").asText();
                        store.save(session);
                        sendJson(exchange, 200, Map.of("currentBranchId", session.currentBranchId));
                        return;
                    }
                }
                case "merge" -> {
                    if (method.equals("POST")) {
                        JsonNode body = readBody(exchange);
                        var result = session.merge(body.required("source").asText(),
                                body.required("target").asText());
                        store.save(session);
                        if (result.ok) {
                            Map<String, Object> out = new LinkedHashMap<>();
                            out.put("ok", true);
                            out.put("branch", result.branch);
                            out.put("divergenceStep", result.divergenceStep);
                            sendJson(exchange, 200, out);
                        } else {
                            Map<String, Object> out = new LinkedHashMap<>();
                            out.put("ok", false);
                            out.put("reason", result.reason);
                            out.put("conflictA", result.conflictA);
                            out.put("conflictB", result.conflictB);
                            sendJson(exchange, 409, out);
                        }
                        return;
                    }
                }
                case "compare" -> {
                    if (method.equals("GET")) {
                        var query = parseQuery(exchange.getRequestURI().getRawQuery());
                        var a = session.branch(query.get("a"));
                        var b = session.branch(query.get("b"));
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("a", branchState(a));
                        out.put("b", branchState(b));
                        sendJson(exchange, 200, out);
                        return;
                    }
                }
                case "export" -> {
                    if (method.equals("GET")) {
                        byte[] bytes = store.export(session).getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                        exchange.getResponseHeaders().set("Content-Disposition",
                                "attachment; filename=\"" + session.id + ".json\"");
                        exchange.sendResponseHeaders(200, bytes.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(bytes);
                        }
                        return;
                    }
                }
                default -> {
                }
            }
            sendJson(exchange, 404, Map.of("error", "not found: " + method + " " + path));
            return;
        }
        sendJson(exchange, 404, Map.of("error", "not found: " + method + " " + path));
    }

    private Map<String, Object> branchState(com.replayroom.core.Branch branch) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", branch.id);
        out.put("name", branch.name);
        out.put("steps", branch.trace.size());
        out.put("state", branch.snapshot.state);
        out.put("variables", branch.snapshot.variables);
        out.put("outputs", branch.outputs);
        out.put("exhausted", branch.exhausted());
        return out;
    }

    private Map<String, Object> summary(ReplaySession s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.id);
        out.put("name", s.name);
        out.put("fingerprint", s.fingerprint);
        out.put("branches", s.branches.size());
        out.put("checkpoints", s.checkpoints.size());
        return out;
    }

    private Map<String, Object> view(ReplaySession s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.id);
        out.put("name", s.name);
        out.put("definition", s.definition);
        out.put("fingerprint", s.fingerprint);
        out.put("definitionFingerprint", s.definitionFingerprint);
        out.put("externalLog", s.externalLog);
        out.put("branches", s.branches);
        out.put("currentBranchId", s.currentBranchId);
        out.put("checkpoints", s.checkpoints);
        out.put("traceHashes", s.traceHashes());
        return out;
    }

    private String nextSessionId() {
        int n = sessions.size() + 1;
        while (sessions.containsKey("s" + n)) {
            n++;
        }
        return "s" + n;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null) {
            return map;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static JsonNode readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return null;
        }
        return Json.read(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String loadIndex() {
        try (InputStream in = ApiServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                return "<html><body><h1>状态机回放室</h1><p>index.html missing</p></body></html>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<html><body><h1>状态机回放室</h1></body></html>";
        }
    }
}
