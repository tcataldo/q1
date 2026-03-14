package io.q1.api.handler;

import io.q1.cluster.EcConfig;
import io.q1.cluster.RatisCluster;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Handles {@code GET /healthz}.
 *
 * <p>Returns a JSON snapshot of the node's operational state. The HTTP status
 * reflects readiness: 200 when the node can serve traffic, 503 when the cluster
 * has no known leader yet (cluster mode only).
 *
 * <h3>Why "z"?</h3>
 * The {@code /healthz} convention comes from Google's internal "z-pages" ({@code /statusz},
 * {@code /varz}, {@code /rpcz}, …). Kubernetes adopted it for liveness and readiness probes.
 * The trailing "z" distinguishes internal diagnostic endpoints from application content.
 *
 * <h3>Response format</h3>
 * <pre>{@code
 * // standalone
 * { "nodeId":"standalone", "mode":"standalone", "status":"ok", "partitions":16 }
 *
 * // cluster — leader
 * { "nodeId":"q1-01", "mode":"ec", "status":"ok", "leader":true,
 *   "peers":3, "partitions":16, "ec":{"k":2,"m":1} }
 *
 * // cluster — follower
 * { "nodeId":"q1-02", "mode":"replication", "status":"ok", "leader":false,
 *   "leaderUrl":"http://q1-01:9000", "peers":3, "partitions":16 }
 *
 * // cluster — no leader yet
 * { "nodeId":"q1-02", "mode":"replication", "status":"degraded", "leader":false,
 *   "peers":3, "partitions":16 }
 * }</pre>
 */
public final class HealthHandler {

    private final String       nodeId;
    private final int          numPartitions;
    private final RatisCluster cluster;       // null in standalone mode
    private final String       mode;          // "standalone" | "replication" | "ec"

    public HealthHandler(String nodeId, int numPartitions, RatisCluster cluster) {
        this.nodeId        = nodeId;
        this.numPartitions = numPartitions;
        this.cluster       = cluster;
        if (cluster == null) {
            this.mode = "standalone";
        } else if (cluster.config().ecConfig().enabled()) {
            this.mode = "ec";
        } else {
            this.mode = "replication";
        }
    }

    public void handle(HttpServerExchange exchange) {
        boolean ready;
        String  json;

        if (cluster == null) {
            ready = true;
            json  = buildStandaloneJson();
        } else {
            ready = cluster.isClusterReady();
            json  = buildClusterJson();
        }

        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.setStatusCode(ready ? StatusCodes.OK : StatusCodes.SERVICE_UNAVAILABLE);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, body.length);
        exchange.getResponseSender().send(ByteBuffer.wrap(body));
    }

    // ── JSON builders ─────────────────────────────────────────────────────

    private String buildStandaloneJson() {
        return "{\n" +
               "  \"nodeId\":\"" + nodeId + "\",\n" +
               "  \"mode\":\"standalone\",\n" +
               "  \"status\":\"ok\",\n" +
               "  \"partitions\":" + numPartitions + "\n" +
               "}\n";
    }

    private String buildClusterJson() {
        boolean          isLeader   = cluster.isLocalLeader();
        boolean          ready      = cluster.isClusterReady();
        Optional<String> leaderUrl  = isLeader ? Optional.empty() : cluster.leaderHttpBaseUrl();
        int              peerCount  = cluster.activeNodes().size();
        EcConfig         ec         = cluster.config().ecConfig();

        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"nodeId\":\"").append(nodeId).append("\",\n");
        sb.append("  \"mode\":\"").append(mode).append("\",\n");
        sb.append("  \"status\":\"").append(ready ? "ok" : "degraded").append("\",\n");
        sb.append("  \"leader\":").append(isLeader).append(",\n");
        leaderUrl.ifPresent(url ->
                sb.append("  \"leaderUrl\":\"").append(url).append("\",\n"));
        sb.append("  \"peers\":").append(peerCount).append(",\n");
        sb.append("  \"partitions\":").append(numPartitions);
        if (ec.enabled()) {
            sb.append(",\n  \"ec\":{\"k\":").append(ec.dataShards())
              .append(",\"m\":").append(ec.parityShards()).append("}");
        }
        sb.append("\n}\n");
        return sb.toString();
    }
}
