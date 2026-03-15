package io.q1.api;

import io.q1.cluster.NodeId;
import io.q1.core.StorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Q1ServerTest {

    @TempDir Path tmp;

    // ── parsePeers ────────────────────────────────────────────────────────

    @Test
    void parsePeers_singlePeer() {
        List<NodeId> peers = Q1Server.parsePeers("node1:10.0.0.1:9000:6000");
        assertEquals(1, peers.size());
        assertEquals("node1",    peers.get(0).id());
        assertEquals("10.0.0.1", peers.get(0).host());
        assertEquals(9000,       peers.get(0).port());
        assertEquals(6000,       peers.get(0).raftPort());
    }

    @Test
    void parsePeers_multiplePeers() {
        List<NodeId> peers = Q1Server.parsePeers(
                "n1:host1:9001:6001,n2:host2:9002:6002,n3:host3:9003:6003");
        assertEquals(3, peers.size());
        assertEquals("n1",    peers.get(0).id());
        assertEquals("n2",    peers.get(1).id());
        assertEquals("host3", peers.get(2).host());
        assertEquals(9003,    peers.get(2).port());
    }

    @Test
    void parsePeers_stripsWhitespaceAroundEntries() {
        List<NodeId> peers = Q1Server.parsePeers(
                " n1:h1:9001:6001 , n2:h2:9002:6002 ");
        assertEquals(2, peers.size());
        assertEquals("n1", peers.get(0).id());
        assertEquals("n2", peers.get(1).id());
    }

    @Test
    void parsePeers_blankEntriesIgnored() {
        List<NodeId> peers = Q1Server.parsePeers(" , ,node1:localhost:9000:6000, ");
        assertEquals(1, peers.size());
        assertEquals("node1", peers.get(0).id());
    }

    @Test
    void parsePeers_emptyString_returnsEmptyList() {
        assertTrue(Q1Server.parsePeers("").isEmpty());
        assertTrue(Q1Server.parsePeers("   ").isEmpty());
    }

    @Test
    void parsePeers_invalidFormat_throws() {
        assertThrows(Exception.class, () -> Q1Server.parsePeers("no-colons-here"));
    }

    // ── server lifecycle ──────────────────────────────────────────────────

    @Test
    void standaloneConstructor_startAndClose() throws Exception {
        StorageEngine engine = new StorageEngine(tmp, 4);
        try (Q1Server server = new Q1Server(engine, 19850)) {
            server.start();
            // Verify the server is up by hitting /healthz
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:19850/healthz")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
        }
        // After close() the engine should also be closed — any further operation throws
        assertThrows(Exception.class, () -> engine.get("b", "k"));
    }

    @Test
    void stop_doesNotCloseEngine() throws Exception {
        StorageEngine engine = new StorageEngine(tmp.resolve("e2"), 4);
        Q1Server server = new Q1Server(engine, 19851);
        server.start();
        server.stop();   // stop() keeps engine open
        // Engine still usable after stop()
        assertDoesNotThrow(() -> engine.put("b", "k", new byte[]{1}));
        engine.close();
    }
}
