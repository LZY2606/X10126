package replayroom.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import replayroom.core.Checkpoint;
import replayroom.core.Definition;
import replayroom.core.Engine;
import replayroom.core.EventInstance;
import replayroom.core.MergeConflict;
import replayroom.core.Session;
import replayroom.core.Store;
import replayroom.core.TraceEntry;
import replayroom.core.Transfer;
import replayroom.core.VersionMismatch;
import replayroom.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** JSON API + 内置 Web 界面，只使用 JDK 自带 HttpServer，无外部运行时组件。 */
public class ApiServer {
    private final Store store;
    private final HttpServer server;

    public ApiServer(Store store, String host, int port) throws IOException {
        this.store = store;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
    }

    public void start() {
        server.start();
    }

    private void handle(HttpExchange ex) {
        try {
            route(ex);
        } catch (VersionMismatch | MergeConflict e) {
            error(ex, 409, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            error(ex, 400, e.getMessage(), null);
        } catch (Exception e) {
            error(ex, 500, e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String[] parts = path.split("/");

        if (method.equals("GET") && path.equals("/")) {
            serveIndex(ex);
            return;
        }
        if (method.equals("GET") && path.equals("/api/sessions")) {
            ArrayNode arr = Json.M.createArrayNode();
            for (Session s : store.list()) {
                arr.add(summary(s));
            }
            send(ex, 200, arr);
            return;
        }
        if (method.equals("POST") && path.equals("/api/sessions")) {
            JsonNode body = body(ex);
            Definition def = Definition.parse(body.get("definition"));
            long seed = body.path("seed").asLong(0);
            String name = body.path("name").asText(def.name);
            Session s = Session.create(store.newId(), name, def, seed);
            store.put(s);
            send(ex, 200, detail(s));
            return;
        }
        if (method.equals("POST") && path.equals("/api/sessions/import")) {
            Transfer.ImportResult result = Transfer.importSession(store, body(ex), null);
            ObjectNode out = detail(result.session);
            out.put("expectedTraceHash", result.expectedTraceHash);
            out.put("actualTraceHash", result.actualTraceHash);
            out.put("traceHashMatches", result.matches);
            send(ex, result.matches ? 200 : 422, out);
            return;
        }
        if (parts.length >= 4 && parts[1].equals("api") && parts[2].equals("sessions")) {
            Session s = store.get(parts[3]);
            String tail = parts.length == 4 ? "" : parts[4];
            switch (tail) {
                case "":
                    if (method.equals("GET")) {
                        send(ex, 200, detail(s));
                        return;
                    }
                    break;
                case "events":
                    if (method.equals("POST")) {
                        JsonNode body = body(ex);
                        List<EventInstance> events = Json.M.convertValue(body.get("events"),
                                new com.fasterxml.jackson.core.type.TypeReference<List<EventInstance>>() {
                                });
                        s.engine.importEvents(events);
                        store.save(s);
                        ObjectNode out = detail(s);
                        out.put("imported", events.size());
                        send(ex, 200, out);
                        return;
                    }
                    break;
                case "step":
                    if (method.equals("POST")) {
                        TraceEntry entry = s.engine.step();
                        store.save(s);
                        ObjectNode out = detail(s);
                        out.set("entry", Json.M.valueToTree(entry));
                        send(ex, 200, out);
                        return;
                    }
                    break;
                case "run":
                    if (method.equals("POST")) {
                        JsonNode body = body(ex);
                        int limit = body.path("limit").asInt(Integer.MAX_VALUE);
                        Long untilTime = body.hasNonNull("untilTime") ? body.get("untilTime").asLong() : null;
                        List<TraceEntry> entries = s.engine.runLimit(limit, untilTime);
                        store.save(s);
                        ObjectNode out = detail(s);
                        out.put("steps", entries.size());
                        send(ex, 200, out);
                        return;
                    }
                    break;
                case "checkpoints":
                    if (method.equals("POST")) {
                        JsonNode body = body(ex);
                        Checkpoint cp = s.checkpoint(body.path("name").asText(null));
                        store.save(s);
                        send(ex, 200, Json.M.valueToTree(cp));
                        return;
                    }
                    break;
                case "restore":
                    if (method.equals("POST")) {
                        JsonNode body = body(ex);
                        s.restore(body.get("checkpointId").asText());
                        store.save(s);
                        send(ex, 200, detail(s));
                        return;
                    }
                    break;
                case "definition":
                    if (method.equals("PUT") || method.equals("POST")) {
                        Definition def = Definition.parse(body(ex).get("definition"));
                        s.replaceDefinition(def);
                        store.save(s);
                        send(ex, 200, detail(s));
                        return;
                    }
                    break;
                case "branches":
                    if (method.equals("GET")) {
                        ArrayNode arr = Json.M.createArrayNode();
                        for (Session b : store.branchesOf(s.id)) {
                            arr.add(summary(b));
                        }
                        send(ex, 200, arr);
                        return;
                    }
                    if (method.equals("POST")) {
                        JsonNode body = body(ex);
                        Session branch = s.fork(store.newId(),
                                body.get("checkpointId").asText(),
                                body.path("name").asText("branch"));
                        store.put(branch);
                        send(ex, 200, detail(branch));
                        return;
                    }
                    break;
                case "merge":
                    if (method.equals("POST")) {
                        JsonNode body = body(ex);
                        Session branch = store.get(body.get("branchId").asText());
                        Session.MergeResult result = s.merge(branch);
                        store.save(s);
                        ObjectNode out = detail(s);
                        out.put("mergedEvents", result.mergedEvents);
                        send(ex, 200, out);
                        return;
                    }
                    break;
                case "export":
                    if (method.equals("GET")) {
                        send(ex, 200, Transfer.export(s));
                        return;
                    }
                    break;
                default:
                    break;
            }
        }
        error(ex, 404, "未找到: " + method + " " + path, null);
    }

    private ObjectNode summary(Session s) {
        ObjectNode out = Json.M.createObjectNode();
        out.put("id", s.id);
        out.put("name", s.name);
        out.put("kind", s.kind);
        out.put("parentId", s.parentId);
        out.put("fingerprint", s.fingerprint);
        out.put("definitionFingerprint", s.def.fingerprint);
        out.put("state", s.engine.state);
        out.put("traceHash", s.engine.traceHash());
        out.put("steps", s.engine.trace.size());
        out.put("pending", s.engine.pending.size());
        return out;
    }

    private ObjectNode detail(Session s) {
        ObjectNode out = summary(s);
        out.put("seed", s.engine.seed);
        out.set("vars", Json.M.valueToTree(s.engine.vars));
        out.set("pendingEvents", Json.M.valueToTree(s.engine.pending));
        out.set("trace", Json.M.valueToTree(s.engine.trace));
        out.set("definition", s.def.raw);
        out.set("mergeLog", Json.M.valueToTree(s.mergeLog));
        ArrayNode cps = Json.M.createArrayNode();
        for (Checkpoint cp : s.checkpoints) {
            ObjectNode c = Json.M.createObjectNode();
            c.put("id", cp.id);
            c.put("name", cp.name);
            c.put("definitionFingerprint", cp.definitionFingerprint);
            c.put("currentDefinition", cp.definitionFingerprint.equals(s.def.fingerprint));
            cps.add(c);
        }
        out.set("checkpoints", cps);
        ArrayNode branches = Json.M.createArrayNode();
        for (Session b : store.branchesOf(s.id)) {
            branches.add(summary(b));
        }
        out.set("branches", branches);
        return out;
    }

    private void serveIndex(HttpExchange ex) throws IOException {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("/web/index.html")) {
            if (in == null) {
                error(ex, 500, "缺少内置页面资源", null);
                return;
            }
            bytes = in.readAllBytes();
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private JsonNode body(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return Json.M.createObjectNode();
        }
        return Json.M.readTree(new String(bytes, StandardCharsets.UTF_8));
    }

    private void error(HttpExchange ex, int status, String message, Exception e) {
        try {
            ObjectNode out = Json.M.createObjectNode();
            out.put("error", message);
            if (e instanceof MergeConflict) {
                MergeConflict mc = (MergeConflict) e;
                ObjectNode conflict = Json.M.createObjectNode();
                conflict.put("reason", mc.first.reason);
                conflict.set("a", Json.M.valueToTree(mc.first.a));
                conflict.set("b", Json.M.valueToTree(mc.first.b));
                out.set("firstConflict", conflict);
                out.put("conflictCount", mc.conflictCount);
            }
            send(ex, status, out);
        } catch (IOException ignored) {
            // exchange already broken
        }
    }

    private void send(HttpExchange ex, int status, JsonNode node) throws IOException {
        byte[] bytes = Json.M.writerWithDefaultPrettyPrinter()
                .writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
