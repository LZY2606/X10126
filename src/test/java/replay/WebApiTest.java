package replay;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import replay.engine.Definition;
import replay.engine.Store;
import replay.web.ApiServer;

/** Boots the real server on an ephemeral loopback port; no external services. */
public class WebApiTest {

    private ApiServer server;
    private String base;
    private Path dataDir;
    private int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    public void fullWorkflowOverHttp() throws Exception {
        bootWithFixedPort();
        try {
            // Static UI title.
            HttpResponse<String> index = get("/");
            Asserts.assertEquals(200, index.statusCode(), "index served");
            Asserts.assertTrue(index.body().contains("状态机回放室"),
                    "UI contains the room title");

            // Create session.
            Map<String, Object> createReq = new LinkedHashMap<>();
            createReq.put("id", "http1");
            createReq.put("name", "http session");
            createReq.put("definition", Fixtures.turnstile().toMap());
            createReq.put("events", List.of(
                    Fixtures.event("coin-1", "coin", 10L, 0, 0L, null),
                    Fixtures.event("push-1", "push", 20L, 0, 1L, null)));
            HttpResponse<String> created = post("/api/session", createReq);
            Asserts.assertEquals(200, created.statusCode(), "session created");
            Map<String, Object> session = Json.parseObject(created.body());
            Asserts.assertTrue(session.containsKey("lockFingerprint"), "returns lock");

            // Step once: external coin with internal event queued.
            HttpResponse<String> stepped = post("/api/branches/br-main/step",
                    Map.of("count", 1));
            Asserts.assertEquals(200, stepped.statusCode(), "step ok");
            Map<String, Object> stepResp = Json.parseObject(stepped.body());
            Asserts.assertEquals(1, stepResp.get("applied"), "one step applied");

            // Checkpoint.
            HttpResponse<String> cp = post("/api/branches/br-main/checkpoints",
                    Map.of("label", "cp-after-coin"));
            Asserts.assertEquals(200, cp.statusCode(), "checkpoint created");
            Map<String, Object> cpObj = Json.parseObject(cp.body());

            // Run to end and capture trace hash.
            HttpResponse<String> run = post("/api/branches/br-main/run", Map.of());
            Map<String, Object> runObj = Json.parseObject(run.body());
            String hash1 = String.valueOf(runObj.get("traceHash"));

            // Persistence: new store over the same data directory resumes.
            server.stop(0);
            Store reopened = new Store(dataDir);
            reopened.load();
            String resumedHash = reopened.session().branches.get("br-main").engine().getTraceHash();
            Asserts.assertEquals(hash1, resumedHash, "restart resumes persisted trajectory");

            // Export round trip via API on a fresh server over the same data.
            ApiServer server2 = new ApiServer(reopened, "127.0.0.1", port);
            server2.start();
            try {
                HttpResponse<String> exported = get("/api/session/export");
                Asserts.assertEquals(200, exported.statusCode(), "export endpoint");
                Asserts.assertTrue(exported.body().contains("traceHash"),
                        "export includes trajectory");
            } finally {
                server2.stop(0);
            }
        } finally {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    private void bootWithFixedPort() throws IOException {
        dataDir = Files.createTempDirectory("replay-room-test-");
        Store store = new Store(dataDir);
        server = new ApiServer(store, "127.0.0.1", 0);
        server.start();
        port = server.getPort();
        base = "http://127.0.0.1:" + port;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, Object body)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
