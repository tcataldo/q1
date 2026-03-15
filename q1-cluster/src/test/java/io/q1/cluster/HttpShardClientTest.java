package io.q1.cluster;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HttpShardClient} using an embedded JDK {@link HttpServer}
 * to simulate remote node responses without requiring a real Ratis cluster.
 */
class HttpShardClientTest {

    private HttpServer     server;
    private NodeId         node;
    private HttpShardClient client;

    /** Mutable response state shared with the embedded server handler. */
    private volatile int    stubStatus = 200;
    private volatile byte[] stubBody   = new byte[0];

    @BeforeEach
    void setUp() throws IOException {
        client = new HttpShardClient();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 4);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); // drain any request body
            byte[] body = stubBody;
            if (body.length > 0) {
                exchange.sendResponseHeaders(stubStatus, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(stubStatus, -1);
            }
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        node = new NodeId("stub-node", "localhost", port, 16099);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ── putShard ──────────────────────────────────────────────────────────

    @Test
    void putShard_success_noException() throws IOException {
        stubStatus = 200;
        assertDoesNotThrow(() -> client.putShard(node, 0, "bucket", "key", new byte[]{1, 2, 3}));
    }

    @Test
    void putShard_serverError_throwsIOException() {
        stubStatus = 500;
        assertThrows(IOException.class,
                () -> client.putShard(node, 1, "bucket", "key", new byte[0]));
    }

    @Test
    void putShard_300Redirect_throwsIOException() {
        stubStatus = 301;
        assertThrows(IOException.class,
                () -> client.putShard(node, 2, "bucket", "key", new byte[0]));
    }

    // ── getShard ──────────────────────────────────────────────────────────

    @Test
    void getShard_found_returnsData() throws IOException {
        byte[] data = {10, 20, 30};
        stubStatus = 200;
        stubBody   = data;
        assertArrayEquals(data, client.getShard(node, 0, "bucket", "mykey"));
    }

    @Test
    void getShard_notFound_returnsNull() throws IOException {
        stubStatus = 404;
        assertNull(client.getShard(node, 0, "bucket", "missing"));
    }

    @Test
    void getShard_serverError_throwsIOException() {
        stubStatus = 503;
        assertThrows(IOException.class, () -> client.getShard(node, 0, "bucket", "err"));
    }

    // ── shardExists ───────────────────────────────────────────────────────

    @Test
    void shardExists_found_returnsTrue() throws IOException {
        stubStatus = 200;
        assertTrue(client.shardExists(node, 2, "b", "k"));
    }

    @Test
    void shardExists_notFound_returnsFalse() throws IOException {
        stubStatus = 404;
        assertFalse(client.shardExists(node, 2, "b", "missing"));
    }

    @Test
    void shardExists_serverError_throwsIOException() {
        stubStatus = 500;
        assertThrows(IOException.class, () -> client.shardExists(node, 2, "b", "err"));
    }

    // ── deleteShard ───────────────────────────────────────────────────────

    @Test
    void deleteShard_success_noException() {
        stubStatus = 204;
        assertDoesNotThrow(() -> client.deleteShard(node, 3, "b", "k"));
    }

    @Test
    void deleteShard_notFound_treatedAsSuccess() {
        stubStatus = 404;
        assertDoesNotThrow(() -> client.deleteShard(node, 3, "b", "gone"));
    }

    @Test
    void deleteShard_serverError_throwsIOException() {
        stubStatus = 500;
        assertThrows(IOException.class, () -> client.deleteShard(node, 3, "b", "err"));
    }
}
