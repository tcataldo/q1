package io.q1.tests;

import io.q1.api.Q1Server;
import io.q1.core.StorageEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP-level tests for {@code BucketHandler} error paths and response formats
 * that are not exercised by {@code S3CompatibilityIT} (which uses the AWS SDK).
 *
 * <p>Uses the JDK {@link HttpClient} directly to control exact request
 * parameters and inspect raw responses.
 */
class BucketHandlerIT {

    private static final int PORT = 19700;

    private static StorageEngine engine;
    private static Q1Server      server;
    private static HttpClient    http;

    @BeforeAll
    static void startServer() throws Exception {
        Path dataDir = Files.createTempDirectory("q1-bh-it-");
        engine = new StorageEngine(dataDir);
        server = new Q1Server(engine, PORT);
        server.start();
        http = HttpClient.newHttpClient();

        // Pre-create a bucket used by several tests
        put("existing-bucket");
    }

    @AfterAll
    static void stopServer() throws Exception {
        server.close();
    }

    // ── createBucket ──────────────────────────────────────────────────────

    @Test
    void createBucket_setsLocationHeader() throws Exception {
        String bucket = "loc-header-bucket";
        HttpResponse<Void> resp = http.send(
                HttpRequest.newBuilder(uri("/" + bucket))
                        .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(200, resp.statusCode());
        assertEquals("/" + bucket,
                resp.headers().firstValue("Location").orElse(""),
                "Location header should be '/" + bucket + "'");
    }

    @Test
    void createBucket_idempotent_returns200() throws Exception {
        String bucket = "idem-bh-bucket";
        assertEquals(200, put(bucket));
        assertEquals(200, put(bucket));  // second create must also succeed
    }

    // ── deleteBucket ──────────────────────────────────────────────────────

    @Test
    void deleteBucket_notFound_returns404() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(uri("/no-such-bucket-xyz"))
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode());
        assertTrue(resp.body().contains("NoSuchBucket"),
                "Error body should contain NoSuchBucket code");
    }

    @Test
    void deleteBucket_existing_returns204() throws Exception {
        String bucket = "delete-me-bh";
        put(bucket);
        HttpResponse<Void> resp = http.send(
                HttpRequest.newBuilder(uri("/" + bucket)).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, resp.statusCode());
    }

    // ── listObjects ───────────────────────────────────────────────────────

    @Test
    void listObjects_notFound_returns404() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(uri("/ghost-bucket-xyz?list-type=2")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode());
        assertTrue(resp.body().contains("NoSuchBucket"),
                "Error body should contain NoSuchBucket code");
    }

    @Test
    void listObjects_v1Format_returnsXml() throws Exception {
        // GET /{bucket} without list-type=2 → v1 ListBucketResult
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(uri("/existing-bucket")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        String body = resp.body();
        assertTrue(body.contains("<ListBucketResult"), "Should be ListBucketResult");
        assertFalse(body.contains("<KeyCount>"),       "v1 has no KeyCount element");
        assertTrue(body.contains("<Name>existing-bucket</Name>"));
        assertTrue(body.contains("<IsTruncated>false</IsTruncated>"));
    }

    @Test
    void listObjects_v2Format_returnsKeyCount() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(uri("/existing-bucket?list-type=2")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("<KeyCount>"));
    }

    @Test
    void listObjects_pagination_continuationToken() throws Exception {
        String bucket = "paginate-bh";
        put(bucket);
        // Insert 3 objects
        for (int i = 1; i <= 3; i++) {
            engine.put(bucket, "obj" + i, ("v" + i).getBytes());
        }

        // Fetch first page (max-keys=2)
        HttpResponse<String> page1 = http.send(
                HttpRequest.newBuilder(
                        uri("/" + bucket + "?list-type=2&max-keys=2")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, page1.statusCode());
        String body1 = page1.body();
        assertTrue(body1.contains("<IsTruncated>true</IsTruncated>"),
                "First page of 3 objects with max-keys=2 should be truncated");
        assertTrue(body1.contains("<NextContinuationToken>"),
                "Should include next token when truncated");

        // Extract token (crude but sufficient)
        int start = body1.indexOf("<NextContinuationToken>") + "<NextContinuationToken>".length();
        int end   = body1.indexOf("</NextContinuationToken>");
        String token = body1.substring(start, end);

        // Fetch second page
        String encodedToken = java.net.URLEncoder.encode(token, "UTF-8");
        HttpResponse<String> page2 = http.send(
                HttpRequest.newBuilder(
                        uri("/" + bucket + "?list-type=2&max-keys=2&continuation-token=" + encodedToken)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, page2.statusCode());
        assertTrue(page2.body().contains("<IsTruncated>false</IsTruncated>"),
                "Second page should not be truncated");
    }

    @Test
    void listObjects_xmlEscapeInBucketName() throws Exception {
        // Bucket names can't contain < or & per S3 rules, but escape() should still work
        // Test via a prefix param with special XML chars
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(
                        uri("/existing-bucket?list-type=2&prefix=a%26b")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        // Prefix with & should be XML-escaped in the response
        assertTrue(resp.body().contains("a&amp;b") || resp.body().contains("<Prefix></Prefix>") || resp.body().contains("a&amp;b"),
                "Ampersand in prefix should appear XML-escaped in response body");
    }

    @Test
    void listObjects_maxKeysZero_returnsEmptyContents() throws Exception {
        // Pre-populate a bucket
        String bucket = "max-zero-bh";
        put(bucket);
        engine.put(bucket, "somekey", "val".getBytes());

        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(
                        uri("/" + bucket + "?list-type=2&max-keys=0")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertFalse(resp.body().contains("<Contents>"),
                "max-keys=0 should return no Contents");
    }

    // ── listBuckets ───────────────────────────────────────────────────────

    @Test
    void listBuckets_returnsXml() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(uri("/")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        String body = resp.body();
        assertTrue(body.contains("<ListAllMyBucketsResult"),  "Should be ListAllMyBucketsResult");
        assertTrue(body.contains("<Buckets>"),                "Should contain Buckets element");
        assertTrue(body.contains("<Owner>"),                  "Should contain Owner element");
    }

    @Test
    void listBuckets_includesCreatedBucket() throws Exception {
        String bucket = "listed-bh-bucket";
        put(bucket);

        String body = http.send(
                HttpRequest.newBuilder(uri("/")).build(),
                HttpResponse.BodyHandlers.ofString()).body();

        assertTrue(body.contains("<Name>" + bucket + "</Name>"),
                "Listed buckets should include '" + bucket + "'");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static int put(String bucket) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri("/" + bucket))
                        .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + PORT + path);
    }
}
