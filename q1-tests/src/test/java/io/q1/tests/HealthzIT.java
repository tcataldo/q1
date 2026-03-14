package io.q1.tests;

import io.q1.api.Q1Server;
import io.q1.core.StorageEngine;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@code GET /healthz}.
 *
 * Starts an in-process standalone Q1Server and verifies the health endpoint
 * contract: correct HTTP status, Content-Type, and JSON field presence.
 */
class HealthzIT {

    private static final int PORT = 19100;

    private static StorageEngine engine;
    private static Q1Server      server;
    private static HttpClient    http;

    @BeforeAll
    static void startServer() throws Exception {
        Path dataDir = Files.createTempDirectory("q1-healthz-it-");
        engine = new StorageEngine(dataDir);
        server = new Q1Server(engine, PORT);
        server.start();
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopServer() throws Exception {
        server.close();
    }

    @Test
    void returns200WithJsonContentType() throws Exception {
        var resp = get("/healthz");

        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("content-type")
                       .orElse("").contains("application/json"),
                "Content-Type should be application/json");
    }

    @Test
    void standaloneBodyHasExpectedFields() throws Exception {
        var resp = get("/healthz");
        String body = resp.body();

        assertAll("standalone healthz fields",
            () -> assertTrue(body.contains("\"nodeId\""),    "missing nodeId"),
            () -> assertTrue(body.contains("\"mode\""),      "missing mode"),
            () -> assertTrue(body.contains("\"status\""),    "missing status"),
            () -> assertTrue(body.contains("\"partitions\""),"missing partitions"),
            () -> assertTrue(body.contains("standalone"),    "mode should be standalone"),
            () -> assertTrue(body.contains("ok"),            "status should be ok")
        );
    }

    @Test
    void standaloneHasNoClusterFields() throws Exception {
        String body = get("/healthz").body();

        assertAll("standalone should not expose cluster fields",
            () -> assertFalse(body.contains("\"leader\""),    "leader should be absent"),
            () -> assertFalse(body.contains("\"peers\""),     "peers should be absent"),
            () -> assertFalse(body.contains("\"leaderUrl\""), "leaderUrl should be absent"),
            () -> assertFalse(body.contains("\"ec\""),        "ec config should be absent")
        );
    }

    @Test
    void partitionCountMatchesDefault() throws Exception {
        String body = get("/healthz").body();
        // Default StorageEngine constructor uses 16 partitions
        assertTrue(body.contains("\"partitions\":16"),
                "partitions count should be 16 (default), got: " + body);
    }

    @Test
    void servedBeforeS3PathParsing() throws Exception {
        // /healthz must be reachable even if a request would otherwise fail
        // S3 path parsing (no bucket, no key — root path normally means listBuckets)
        // Verify /healthz is distinct from S3 routing
        var healthz = get("/healthz");
        var root    = get("/");

        assertEquals(200, healthz.statusCode(), "/healthz should be 200");
        // root path is listBuckets (also 200 but different body)
        assertNotEquals(healthz.body(), root.body(), "/healthz and / should return different bodies");
    }

    // ── helper ────────────────────────────────────────────────────────────

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
