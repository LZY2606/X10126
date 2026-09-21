package replayroom.web;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import replayroom.engine.Store;
import replayroom.json.Json;

public final class ApiHttpTest {

    private static HttpServer server;
    private static String base;
    private static Store store;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static void start() throws Exception {
        Path dir = Files.createTempDirectory("replay-room-api-");
        store = new Store(dir);
        store.open();
        ApiService api = new ApiService(store);
        Path webRoot = Path.of("src/main/resources/web").toAbsolutePath();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        replayroom.web.HttpServer handler = new replayroom.web.HttpServer(api, webRoot);
        server.createContext("/", handler::exchange);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpResponse<String> request(String method, String path, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                   .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonBody(HttpResponse<String> response) {
        return (Map<String, Object>) Json.parse(response.body());
    }

    public static void testPageContainsChineseTitle() throws Exception {
        start();
        var response = request("GET", "/", null);
        replayroom.Assert.assertEquals(200, response.statusCode(), "index served");
        replayroom.Assert.assertTrue(response.body().contains("状态机回放室"),
                "page title/body contains 状态机回放室");
    }

    public static void testFullWorkflowViaJsonApi() throws Exception {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", "api-def");
        definition.put("initialState", "s");
        definition.put("initialVars", Map.of("n", 0L));
        definition.put("transitions", List.of(Map.of(
                "event", "go",
                "actions", List.of(Map.of("type", "set", "target", "n", "valueExpr", "n + 1")))));

        var defResponse = request("POST", "/api/definitions", definition);
        replayroom.Assert.assertEquals(201, defResponse.statusCode(), "definition created");
        String fingerprint = String.valueOf(jsonBody(defResponse).get("fingerprint"));

        var sessionResponse = request("POST", "/api/sessions", Map.of(
                "definitionFingerprint", fingerprint,
                "seed", 42,
                "name", "api-session",
                "events", List.of(
                        Map.of("id", "g2", "name", "go", "source", "b", "time", 1, "seq", 2, "priority", 1),
                        Map.of("id", "g1", "name", "go", "source", "a", "time", 1, "seq", 1, "priority", 9))));
        replayroom.Assert.assertEquals(201, sessionResponse.statusCode(), "session created");
        var session = jsonBody(sessionResponse);
        String sessionId = String.valueOf(session.get("id"));

        var stepResponse = request("POST", "/api/sessions/" + sessionId + "/step", Map.of("count", 1));
        var stepped = jsonBody(stepResponse);
        var firstTrace = (Map<String, Object>) ((List<Object>) stepped.get("trace")).get(0);
        var firstEnvelope = (Map<String, Object>) firstTrace.get("event");
        replayroom.Assert.assertEquals("a", firstEnvelope.get("source"), "priority orders same-time events");

        var checkpointResponse = request("POST", "/api/sessions/" + sessionId + "/checkpoints", Map.of());
        replayroom.Assert.assertEquals(201, checkpointResponse.statusCode(), "checkpoint created");
        String checkpointId = String.valueOf(jsonBody(checkpointResponse).get("id"));

        var secondDefinition = Map.of(
                "name", "api-def-2",
                "initialState", "s",
                "initialVars", Map.of("n", 0L),
                "transitions", List.of(Map.of(
                        "event", "go", "to", "other",
                        "actions", List.of())));
        var def2 = jsonBody(request("POST", "/api/definitions", secondDefinition));
        var rejectedFork = request("POST", "/api/checkpoints/" + checkpointId + "/fork", Map.of(
                "definitionFingerprint", def2.get("fingerprint"),
                "name", "bad-branch"));
        replayroom.Assert.assertEquals(409, rejectedFork.statusCode(), "old checkpoint rejected under new definition");
        replayroom.Assert.assertTrue(rejectedFork.body().contains("definition-fingerprint-mismatch"),
                "rejection explains fingerprint mismatch");

        var forkResponse = request("POST", "/api/checkpoints/" + checkpointId + "/fork", Map.of(
                "definitionFingerprint", fingerprint,
                "name", "branch-a",
                "events", List.of(Map.of("id", "ga", "name", "go", "source", "a",
                        "time", 9, "seq", 1, "priority", 5))));
        var branchA = jsonBody(forkResponse);
        request("POST", "/api/sessions/" + branchA.get("id") + "/step", Map.of("count", 100));

        var forkBResponse = request("POST", "/api/checkpoints/" + checkpointId + "/fork", Map.of(
                "definitionFingerprint", fingerprint,
                "name", "branch-b",
                "events", List.of(Map.of("id", "gb", "name", "go", "source", "b",
                        "time", 9, "seq", 1, "priority", 5))));
        var branchB = jsonBody(forkBResponse);
        request("POST", "/api/sessions/" + branchB.get("id") + "/step", Map.of("count", 100));

        var conflictMerge = request("POST", "/api/merge", Map.of(
                "a", branchA.get("id"), "b", branchB.get("id"), "name", "merged"));
        replayroom.Assert.assertEquals(409, conflictMerge.statusCode(), "ambiguous merge rejected");
        replayroom.Assert.assertTrue(conflictMerge.body().contains("firstConflict"),
                "conflict payload identifies the first group");

        var exportResponse = request("GET", "/api/sessions/" + branchA.get("id") + "/export", null);
        var exported = jsonBody(exportResponse);
        var imported = jsonBody(request("POST", "/api/sessions/import", exported));
        replayroom.Assert.assertEquals(branchA.get("traceHeadHash"), imported.get("traceHeadHash"),
                "imported session keeps the same trace hash");
    }
}
