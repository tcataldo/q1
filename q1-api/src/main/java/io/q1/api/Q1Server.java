package io.q1.api;

import io.q1.core.StorageEngine;
import io.undertow.Undertow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Entry point for the Q1 object store.
 *
 * <pre>
 * java --enable-preview --enable-native-access=ALL-UNNAMED \
 *      -jar q1-api.jar [port] [dataDir]
 * </pre>
 *
 * Defaults: port 9000, dataDir ./q1-data
 */
public final class Q1Server implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(Q1Server.class);

    private final StorageEngine engine;
    private final S3Router      router;
    private final Undertow      server;
    private final int           port;

    public Q1Server(StorageEngine engine, int port) {
        this.engine = engine;
        this.port   = port;
        this.router = new S3Router(engine);
        this.server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(router)
                .build();
    }

    public void start() {
        server.start();
        log.info("Q1 listening on port {}", port);
    }

    public void stop() {
        server.stop();
        router.shutdown();
        log.info("Q1 stopped");
    }

    @Override
    public void close() throws IOException {
        stop();
        engine.close();
    }

    // ── standalone entry point ────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        int    port    = args.length > 0 ? Integer.parseInt(args[0]) : 9000;
        String dataDir = args.length > 1 ? args[1] : "q1-data";

        StorageEngine engine = new StorageEngine(Path.of(dataDir));
        Q1Server      server = new Q1Server(engine, port);
        server.start();

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("Shutdown signal — stopping…");
            try { server.close(); } catch (IOException e) { log.error("Error on close", e); }
        }));
    }
}
