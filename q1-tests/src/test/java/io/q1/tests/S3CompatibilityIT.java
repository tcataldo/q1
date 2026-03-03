package io.q1.tests;

import io.q1.api.Q1Server;
import io.q1.core.StorageEngine;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S3 compatibility tests driven by the AWS SDK v2.
 *
 * The tests start an in-process Q1Server, run all assertions through the
 * standard SDK, then tear down.  Any operation that compiles and passes here
 * is considered compatible.
 *
 * Run with: mvn verify -pl q1-tests
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3CompatibilityIT {

    private static final int    PORT   = 19000; // high port to avoid conflicts
    private static final String BUCKET = "test-bucket";

    private static StorageEngine engine;
    private static Q1Server      server;
    private static S3Client      s3;
    private static Path          dataDir;

    @BeforeAll
    static void startServer() throws Exception {
        dataDir = Files.createTempDirectory("q1-it-");
        engine  = new StorageEngine(dataDir);
        server  = new Q1Server(engine, PORT);
        server.start();

        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + PORT))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .checksumValidationEnabled(false)
                        .build())
                .build();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (s3     != null) s3.close();
        if (server != null) server.close();
        // dataDir is a temp dir; OS cleans it up
    }

    // ── bucket operations ─────────────────────────────────────────────────

    @Test @Order(1)
    void createBucket() {
        CreateBucketResponse resp = s3.createBucket(
                CreateBucketRequest.builder().bucket(BUCKET).build());
        assertNotNull(resp);
    }

    @Test @Order(2)
    void createBucketIdempotent() {
        // Creating the same bucket again must not throw
        assertDoesNotThrow(() ->
                s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()));
    }

    // ── object CRUD ───────────────────────────────────────────────────────

    @Test @Order(10)
    void putAndGetObject() throws Exception {
        String body = "Hello, Q1!";
        s3.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("hello.txt").build(),
                RequestBody.fromString(body));

        ResponseBytes<GetObjectResponse> resp = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET).key("hello.txt").build());

        assertEquals(body, resp.asUtf8String());
    }

    @Test @Order(11)
    void putAndGetBinaryObject() {
        byte[] data = new byte[128 * 1024]; // 128 KiB
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);

        s3.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("binary/128k.bin").build(),
                RequestBody.fromBytes(data));

        ResponseBytes<GetObjectResponse> resp = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET).key("binary/128k.bin").build());

        assertArrayEquals(data, resp.asByteArray());
    }

    @Test @Order(12)
    void headObjectExists() {
        s3.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("meta.txt").build(),
                RequestBody.fromString("metadata test"));

        HeadObjectResponse head = s3.headObject(
                HeadObjectRequest.builder().bucket(BUCKET).key("meta.txt").build());

        assertEquals(200, head.sdkHttpResponse().statusCode());
        assertTrue(head.contentLength() > 0);
    }

    @Test @Order(13)
    void headObjectMissing() {
        NoSuchKeyException ex = assertThrows(NoSuchKeyException.class, () ->
                s3.headObject(HeadObjectRequest.builder()
                        .bucket(BUCKET).key("does-not-exist").build()));
        assertEquals(404, ex.statusCode());
    }

    @Test @Order(14)
    void deleteObject() {
        s3.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("to-delete.txt").build(),
                RequestBody.fromString("bye"));

        s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key("to-delete.txt").build());

        NoSuchKeyException ex = assertThrows(NoSuchKeyException.class, () ->
                s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(BUCKET).key("to-delete.txt").build()));
        assertEquals(404, ex.statusCode());
    }

    @Test @Order(15)
    void getMissingObject() {
        NoSuchKeyException ex = assertThrows(NoSuchKeyException.class, () ->
                s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(BUCKET).key("no-such-key").build()));
        assertEquals(404, ex.statusCode());
    }

    // ── listing ───────────────────────────────────────────────────────────

    @Test @Order(20)
    void listObjectsV2() {
        // Upload a handful of objects
        for (int i = 0; i < 5; i++) {
            s3.putObject(
                    PutObjectRequest.builder().bucket(BUCKET).key("list/item-" + i).build(),
                    RequestBody.fromString("item " + i));
        }

        ListObjectsV2Response resp = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET)
                        .prefix("list/")
                        .build());

        List<String> keys = resp.contents().stream()
                .map(S3Object::key)
                .sorted()
                .toList();

        assertTrue(keys.size() >= 5, "Expected at least 5 keys, got " + keys.size());
        assertTrue(keys.stream().allMatch(k -> k.startsWith("list/")));
    }

    @Test @Order(21)
    void listObjectsSlashInKey() {
        s3.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("a/b/c/deep.txt").build(),
                RequestBody.fromString("deep"));

        ListObjectsV2Response resp = s3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(BUCKET).prefix("a/").build());

        assertTrue(resp.contents().stream().anyMatch(o -> o.key().equals("a/b/c/deep.txt")));
    }

    // ── key edge cases ────────────────────────────────────────────────────

    @Test @Order(30)
    void overwriteObject() {
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key("overwrite.txt").build(),
                RequestBody.fromString("v1"));
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key("overwrite.txt").build(),
                RequestBody.fromString("v2"));

        String body = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET).key("overwrite.txt").build())
                .asUtf8String();

        assertEquals("v2", body);
    }

    @Test @Order(31)
    void emptyObject() {
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key("empty").build(),
                RequestBody.empty());

        ResponseBytes<GetObjectResponse> resp = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET).key("empty").build());

        assertEquals(0, resp.asByteArray().length);
    }
}
