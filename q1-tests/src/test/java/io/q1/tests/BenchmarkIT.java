package io.q1.tests;

import io.q1.api.Q1Server;
import io.q1.cluster.ClusterConfig;
import io.q1.cluster.EcConfig;
import io.q1.cluster.ErasureCoder;
import io.q1.cluster.HttpShardClient;
import io.q1.cluster.NodeId;
import io.q1.cluster.PartitionRouter;
import io.q1.cluster.Q1StateMachine;
import io.q1.cluster.RatisCluster;
import io.q1.core.StorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Email-style throughput and latency benchmark.
 *
 * <p>Two variants run back-to-back on independent 3-node in-process clusters:
 * <ol>
 *   <li>Replication mode — every write committed through the Raft log.</li>
 *   <li>EC(2+1) mode — writes RS-encoded and fanned out; Raft off the data path.</li>
 * </ol>
 *
 * <h3>Workload</h3>
 * Each benchmark round issues 5 operations: PUT, PUT, DELETE, GET, GET.
 * Object sizes follow an email-like distribution (10 % @ 1 KB, 30 % @ 8 KB,
 * 40 % @ 32 KB, 20 % @ 128 KB → avg ≈ 41 KB).
 * A fixed key pool of {@value #KEY_POOL} keys bounds the live working set to
 * ≈ 160 MB per node; total bytes appended stay under 1 GB per node.
 *
 * <h3>Comparing runs</h3>
 * The report prints P50/P95/P99 per operation type and overall throughput.
 * Re-run after code changes to compare numbers directly.
 *
 * <h3>Running the benchmark</h3>
 * Both tests are disabled by default (they start 6 Ratis nodes and leave
 * background threads that would interfere with correctness ITs). Enable with:
 * <pre>
 * mvn verify -pl q1-core,q1-cluster,q1-api,q1-tests --also-make \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Dtest=NONE \
 *     -Dit.test="BenchmarkIT" -Dq1.benchmark=true
 * </pre>
 */
class BenchmarkIT {

    // ── tuning knobs ───────────────────────────────────────────────────────

    /** Number of distinct keys — bounds the live working set to ≈ 160 MB per node. */
    private static final int KEY_POOL   = 4_000;
    /**
     * Full cycles through the key pool.
     * Seed (4 K writes) + 2 cycles × 4 K × 2 PUTs ≈ 820 MB appended per node
     * in replication mode — under the 1 GB on-disk budget.
     */
    private static final int ROUNDS     = 2;
    /** Concurrent virtual-thread HTTP clients. */
    private static final int THREADS    = 8;
    /** Partition count (low for fast test startup). */
    private static final int PARTITIONS = 4;
    private static final String BUCKET  = "bench-bucket";

    // ── ports — isolated from all other IT classes ─────────────────────────

    private static final int[] HTTP_REPL = {19500, 19501, 19502};
    private static final int[] RAFT_REPL = {16500, 16501, 16502};
    private static final int[] HTTP_EC   = {19510, 19511, 19512};
    private static final int[] RAFT_EC   = {16510, 16511, 16512};

    /** Unique Raft group UUIDs — prevents background-thread interference across ITs. */
    private static final String GROUP_REPL = "51310006-0000-0000-0000-000000000001";
    private static final String GROUP_EC   = "51310007-0000-0000-0000-000000000001";

    // ── email-size distribution ────────────────────────────────────────────

    private static final int[]    SIZES = {1_024, 8_192, 32_768, 131_072};
    private static final double[] PROBS = {0.10,  0.30,  0.40,  0.20};
    /** avg size = 0.1×1 + 0.3×8 + 0.4×32 + 0.2×128 ≈ 41 KB */
    private static final long AVG_SIZE_KB = 41;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ── tests ──────────────────────────────────────────────────────────────

    @Test
    @EnabledIfSystemProperty(named = "q1.benchmark", matches = "true")
    void replicationBenchmark() throws Exception {
        List<NodeId> peers = buildPeers(HTTP_REPL, RAFT_REPL);
        ClusterNode[] nodes = startReplicationCluster(peers);
        try {
            benchmark("Replication (3-node, Raft log)", nodes, HTTP_REPL);
        } finally {
            stopAll(nodes);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "q1.benchmark", matches = "true")
    void ecBenchmark() throws Exception {
        List<NodeId> peers = buildPeers(HTTP_EC, RAFT_EC);
        ClusterNode[] nodes = startEcCluster(peers);
        try {
            benchmark("EC(2+1)  (3-node, k=2 m=1)", nodes, HTTP_EC);
        } finally {
            stopAll(nodes);
        }
    }

    // ── benchmark core ─────────────────────────────────────────────────────

    private void benchmark(String label, ClusterNode[] nodes, int[] ports) throws Exception {
        waitReady(nodes);
        createBucket(ports);

        byte[][] pool = buildPayloadPool(200);

        // Seeding — write all KEY_POOL keys once; not measured
        System.out.printf("%n[bench] Seeding %,d keys (%s)…%n", KEY_POOL, label);
        for (int i = 0; i < KEY_POOL; i++)
            put(ports[i % ports.length], key(i), pool[i % pool.length]);

        // Each thread owns a non-overlapping key slice: no cross-thread key interference,
        // so a DELETE on one thread can never race with a GET on another.
        int keyPerThread = KEY_POOL / THREADS;        // 500 keys per thread
        int perThread    = ROUNDS * keyPerThread;     // rounds per thread
        System.out.printf("[bench] Measuring: %,d rounds × %d threads × 5 ops/round…%n",
                perThread, THREADS);

        Histogram putH = new Histogram();
        Histogram delH = new Histogram();
        Histogram getH = new Histogram();

        long t0 = System.nanoTime();

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<long[][]>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final int keyStart = t * keyPerThread;
                futures.add(exec.submit(() ->
                        runThread(ports, pool, keyStart, keyPerThread, perThread)));
            }
            for (var f : futures) {
                long[][] raw = f.get();  // raw[0]=put, raw[1]=del, raw[2]=get
                for (long ns : raw[0]) putH.record(ns);
                for (long ns : raw[1]) delH.record(ns);
                for (long ns : raw[2]) getH.record(ns);
            }
        }

        long elapsed = System.nanoTime() - t0;
        printReport(label, elapsed, ROUNDS * KEY_POOL * 5, putH, delH, getH);
    }

    /**
     * One thread's workload slice — operates on an isolated key range [keyStart, keyStart+keyCount).
     * No key overlaps between threads: a DELETE on this thread never races with a GET on another.
     * Returns raw nanosecond latency arrays: [0]=PUT, [1]=DELETE, [2]=GET.
     */
    private long[][] runThread(int[] ports, byte[][] pool,
                               int keyStart, int keyCount, int rounds) throws Exception {
        long[] putNs = new long[rounds * 2];
        long[] delNs = new long[rounds];
        long[] getNs = new long[rounds * 2];
        int pi = 0, di = 0, gi = 0;

        for (int i = 0; i < rounds; i++) {
            // All keys stay within [keyStart, keyStart+keyCount) — no inter-thread conflict.
            int k0    = keyStart + (i % keyCount);
            int k1    = keyStart + ((i + keyCount / 2) % keyCount);
            int kdel  = keyStart + ((i + keyCount / 3) % keyCount);
            int port0 = ports[k0   % ports.length];
            int port1 = ports[k1   % ports.length];
            int portd = ports[kdel % ports.length];
            byte[] v0 = pool[(i * 2)     % pool.length];
            byte[] v1 = pool[(i * 2 + 1) % pool.length];

            long t;

            // write 1
            t = System.nanoTime(); put(port0, key(k0),   v0); putNs[pi++] = System.nanoTime() - t;
            // write 2
            t = System.nanoTime(); put(port1, key(k1),   v1); putNs[pi++] = System.nanoTime() - t;
            // delete
            t = System.nanoTime(); delete(portd, key(kdel)); delNs[di++] = System.nanoTime() - t;
            // read 1 — k0 was PUT two steps ago in this same thread → always 200
            t = System.nanoTime(); fetch(port0, key(k0));    getNs[gi++] = System.nanoTime() - t;
            // read 2 — k1 was PUT one step ago in this same thread → always 200
            t = System.nanoTime(); fetch(port1, key(k1));    getNs[gi++] = System.nanoTime() - t;
        }

        return new long[][]{putNs, delNs, getNs};
    }

    // ── cluster startup ────────────────────────────────────────────────────

    private ClusterNode[] startReplicationCluster(List<NodeId> peers) throws Exception {
        ClusterNode[] nodes = new ClusterNode[peers.size()];
        for (int i = 0; i < peers.size(); i++) {
            NodeId self = peers.get(i);
            StorageEngine engine = new StorageEngine(
                    Files.createTempDirectory("q1-bch-d-"), PARTITIONS);
            ClusterConfig cfg = ClusterConfig.builder()
                    .self(self).peers(peers).numPartitions(PARTITIONS)
                    .raftDataDir(Files.createTempDirectory("q1-bch-r-").toString())
                    .raftGroupId(GROUP_REPL)
                    .build();
            RatisCluster cluster = new RatisCluster(cfg, new Q1StateMachine(engine));
            cluster.start();
            Q1Server server = new Q1Server(engine, cluster, new PartitionRouter(cluster), self.port());
            server.start();
            nodes[i] = new ClusterNode(server, cluster);
        }
        return nodes;
    }

    private ClusterNode[] startEcCluster(List<NodeId> peers) throws Exception {
        EcConfig ec = new EcConfig(2, 1);
        ClusterNode[] nodes = new ClusterNode[peers.size()];
        for (int i = 0; i < peers.size(); i++) {
            NodeId self = peers.get(i);
            StorageEngine engine = new StorageEngine(
                    Files.createTempDirectory("q1-bce-d-"), PARTITIONS);
            ClusterConfig cfg = ClusterConfig.builder()
                    .self(self).peers(peers).numPartitions(PARTITIONS)
                    .raftDataDir(Files.createTempDirectory("q1-bce-r-").toString())
                    .raftGroupId(GROUP_EC)
                    .ecConfig(ec)
                    .build();
            RatisCluster cluster = new RatisCluster(cfg, new Q1StateMachine(engine));
            cluster.start();
            Q1Server server = new Q1Server(engine, cluster, new PartitionRouter(cluster),
                    new ErasureCoder(ec), new HttpShardClient(), self.port());
            server.start();
            nodes[i] = new ClusterNode(server, cluster);
        }
        return nodes;
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────

    private void put(int port, String k, byte[] value) throws Exception {
        http.send(HttpRequest.newBuilder(uri(port, k))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(value)).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private void delete(int port, String k) throws Exception {
        http.send(HttpRequest.newBuilder(uri(port, k)).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private void fetch(int port, String k) throws Exception {
        http.send(HttpRequest.newBuilder(uri(port, k)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private void createBucket(int[] ports) throws Exception {
        for (int port : ports) {
            int st = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + BUCKET))
                            .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            if (st == 200 || st == 204) return;
        }
    }

    private static String key(int idx) { return String.format("email-%07d", idx); }

    private static URI uri(int port, String k) {
        return URI.create("http://localhost:" + port + "/" + BUCKET + "/" + k);
    }

    private static List<NodeId> buildPeers(int[] httpPorts, int[] raftPorts) {
        List<NodeId> list = new ArrayList<>();
        for (int i = 0; i < httpPorts.length; i++)
            list.add(new NodeId("bn-" + i, "localhost", httpPorts[i], raftPorts[i]));
        return list;
    }

    // ── payload pool (fixed seed for reproducible runs) ────────────────────

    private static byte[][] buildPayloadPool(int count) {
        Random rng = new Random(42);
        byte[][] pool = new byte[count][];
        for (int i = 0; i < count; i++) {
            pool[i] = new byte[sampleSize(rng)];
            rng.nextBytes(pool[i]);
        }
        return pool;
    }

    private static int sampleSize(Random rng) {
        double r = rng.nextDouble();
        double cum = 0;
        for (int i = 0; i < PROBS.length - 1; i++) {
            cum += PROBS[i];
            if (r < cum) return SIZES[i];
        }
        return SIZES[SIZES.length - 1];
    }

    // ── cluster lifecycle ──────────────────────────────────────────────────

    private void waitReady(ClusterNode[] nodes) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (Arrays.stream(nodes).allMatch(n -> n.cluster().isClusterReady())) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("Cluster not ready after 20 s");
    }

    private void stopAll(ClusterNode[] nodes) {
        for (ClusterNode n : nodes)
            try { n.server().close(); } catch (Exception ignored) {}
    }

    // ── reporting ──────────────────────────────────────────────────────────

    private static void printReport(String label, long elapsedNs, int totalOps,
                                    Histogram put, Histogram del, Histogram get) {
        double secs   = elapsedNs / 1e9;
        double opsSec = totalOps  / secs;
        long   liveMb = KEY_POOL  * AVG_SIZE_KB / 1024;

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf( "║  Q1 Benchmark — %-48s  ║%n", label);
        System.out.printf( "║  KEY_POOL=%-5d  ROUNDS=%-3d  THREADS=%-3d  live≈%-4d MB/node    ║%n",
                KEY_POOL, ROUNDS, THREADS, liveMb);
        System.out.println("╠═════════════════╦══════════╦══════════╦══════════╦══════════════╣");
        System.out.println("║ Operation       ║  Count   ║  P50 ms  ║  P95 ms  ║   P99 ms     ║");
        System.out.println("╠═════════════════╬══════════╬══════════╬══════════╬══════════════╣");
        printRow("PUT",    put);
        printRow("DELETE", del);
        printRow("GET",    get);
        System.out.println("╠═════════════════╩══════════╩══════════╩══════════╩══════════════╣");
        System.out.printf( "║  total=%,7d ops   elapsed=%6.2f s   throughput=%,7.0f ops/s  ║%n",
                totalOps, secs, opsSec);
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void printRow(String name, Histogram h) {
        System.out.printf("║ %-15s ║ %8d ║ %8.2f ║ %8.2f ║ %12.2f ║%n",
                name, h.count,
                h.percentile(50) / 1e6,
                h.percentile(95) / 1e6,
                h.percentile(99) / 1e6);
    }

    // ── inner types ────────────────────────────────────────────────────────

    private record ClusterNode(Q1Server server, RatisCluster cluster) {}

    /**
     * Fixed-resolution latency histogram.
     * Resolution: 1 µs per bucket, cap at {@value #MAX_MICROS} µs (100 ms).
     * Memory: 100 K × 8 B = 800 KB per instance — three instances total.
     */
    static final class Histogram {

        private static final int MAX_MICROS = 100_000; // 100 ms

        private final long[] buckets = new long[MAX_MICROS + 1];
        long count;

        void record(long nanos) {
            buckets[(int) Math.min(nanos / 1_000L, MAX_MICROS)]++;
            count++;
        }

        /** Returns latency at {@code p}th percentile in nanoseconds. */
        long percentile(double p) {
            long target = Math.max(1L, (long) Math.ceil(count * p / 100.0));
            long seen   = 0;
            for (int i = 0; i <= MAX_MICROS; i++) {
                seen += buckets[i];
                if (seen >= target) return i * 1_000L;
            }
            return (long) MAX_MICROS * 1_000L;
        }
    }
}
