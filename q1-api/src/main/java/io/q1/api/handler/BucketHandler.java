package io.q1.api.handler;

import io.q1.cluster.RatisCluster;
import io.q1.cluster.RatisCommand;
import io.q1.core.StorageEngine;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * Handles bucket-level S3 operations:
 * <ul>
 *   <li>PUT    /{bucket}          — create bucket</li>
 *   <li>GET    /{bucket}?list-type=2 — list objects (v2)</li>
 *   <li>GET    /{bucket}          — list objects (v1 fallback)</li>
 *   <li>DELETE /{bucket}          — delete bucket</li>
 * </ul>
 */
public final class BucketHandler {

    private static final Logger log = LoggerFactory.getLogger(BucketHandler.class);

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final StorageEngine engine;
    private final RatisCluster  cluster; // null in standalone mode

    public BucketHandler(StorageEngine engine) {
        this(engine, null);
    }

    public BucketHandler(StorageEngine engine, RatisCluster cluster) {
        this.engine  = engine;
        this.cluster = cluster;
    }

    public void createBucket(HttpServerExchange exchange, String bucket) {
        try {
            if (cluster != null) {
                cluster.submit(RatisCommand.createBucket(bucket));
            } else {
                engine.createBucket(bucket);
            }
        } catch (Exception e) {
            sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                    "InternalError", "Bucket create failed: " + e.getMessage());
            return;
        }
        exchange.getResponseHeaders().put(Headers.LOCATION, "/" + bucket);
        exchange.setStatusCode(StatusCodes.OK);
        exchange.endExchange();
    }

    public void deleteBucket(HttpServerExchange exchange, String bucket) {
        if (!engine.bucketExists(bucket)) {
            sendError(exchange, StatusCodes.NOT_FOUND, "NoSuchBucket",
                    "The specified bucket does not exist.");
            return;
        }
        try {
            if (cluster != null) {
                cluster.submit(RatisCommand.deleteBucket(bucket));
            } else {
                engine.deleteBucket(bucket);
            }
        } catch (Exception e) {
            sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                    "InternalError", "Bucket delete failed: " + e.getMessage());
            return;
        }
        exchange.setStatusCode(StatusCodes.NO_CONTENT);
        exchange.endExchange();
    }

    public void listObjects(HttpServerExchange exchange, String bucket) throws IOException {
        if (!engine.bucketExists(bucket)) {
            sendError(exchange, StatusCodes.NOT_FOUND, "NoSuchBucket",
                    "The specified bucket does not exist.");
            return;
        }

        boolean isV2      = "2".equals(queryParam(exchange, "list-type"));
        String  prefix    = queryParam(exchange, "prefix");
        String  delimiter = queryParam(exchange, "delimiter");
        int     maxKeys   = 1000;
        try {
            String mk = queryParam(exchange, "max-keys");
            if (mk != null) maxKeys = Math.max(0, Math.min(1000, Integer.parseInt(mk)));
        } catch (NumberFormatException ignored) {}

        // Decode continuation / marker into an afterKey (first key to include, inclusive)
        String afterKey = null;
        String rawToken = isV2 ? queryParam(exchange, "continuation-token")
                                : queryParam(exchange, "marker");
        if (rawToken != null && !rawToken.isBlank()) {
            afterKey = isV2 ? decodeToken(rawToken) : rawToken;
        }

        StorageEngine.ListResult result =
                engine.listPaginated(bucket, prefix, delimiter, maxKeys, afterKey);

        String now = ISO.format(Instant.now());
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n");
        xml.append("  <Name>").append(escape(bucket)).append("</Name>\n");
        xml.append("  <Prefix>").append(escape(prefix)).append("</Prefix>\n");
        if (delimiter != null)
            xml.append("  <Delimiter>").append(escape(delimiter)).append("</Delimiter>\n");
        xml.append("  <MaxKeys>").append(maxKeys).append("</MaxKeys>\n");
        xml.append("  <IsTruncated>").append(result.truncated()).append("</IsTruncated>\n");

        if (isV2) {
            xml.append("  <KeyCount>")
               .append(result.contents().size() + result.commonPrefixes().size())
               .append("</KeyCount>\n");
            if (rawToken != null)
                xml.append("  <ContinuationToken>").append(escape(rawToken)).append("</ContinuationToken>\n");
            if (result.truncated())
                xml.append("  <NextContinuationToken>").append(encodeToken(result.nextKey())).append("</NextContinuationToken>\n");
        } else {
            if (rawToken != null)
                xml.append("  <Marker>").append(escape(rawToken)).append("</Marker>\n");
            if (result.truncated())
                xml.append("  <NextMarker>").append(escape(result.nextKey())).append("</NextMarker>\n");
        }

        for (String key : result.contents()) {
            xml.append("  <Contents>\n");
            xml.append("    <Key>").append(escape(key)).append("</Key>\n");
            xml.append("    <LastModified>").append(now).append("</LastModified>\n");
            xml.append("    <ETag>\"\"</ETag>\n");
            xml.append("    <Size>0</Size>\n");
            xml.append("    <StorageClass>STANDARD</StorageClass>\n");
            xml.append("  </Contents>\n");
        }
        for (String cp : result.commonPrefixes()) {
            xml.append("  <CommonPrefixes><Prefix>").append(escape(cp)).append("</Prefix></CommonPrefixes>\n");
        }
        xml.append("</ListBucketResult>");

        sendXml(exchange, StatusCodes.OK, xml.toString());
    }

    private static String encodeToken(String key) {
        return Base64.getUrlEncoder().withoutPadding()
                     .encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String token) {
        try {
            return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return token; // fall back to treating it as a plain key
        }
    }

    public void listBuckets(HttpServerExchange exchange) {
        List<String> names = engine.listBuckets();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<ListAllMyBucketsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n");
        xml.append("  <Owner><ID>q1</ID><DisplayName>q1</DisplayName></Owner>\n");
        xml.append("  <Buckets>\n");
        for (String name : names) {
            Instant created = engine.bucketCreatedAt(name);
            xml.append("    <Bucket>\n");
            xml.append("      <Name>").append(escape(name)).append("</Name>\n");
            xml.append("      <CreationDate>")
               .append(ISO.format(created != null ? created : Instant.now()))
               .append("</CreationDate>\n");
            xml.append("    </Bucket>\n");
        }
        xml.append("  </Buckets>\n");
        xml.append("</ListAllMyBucketsResult>");

        sendXml(exchange, StatusCodes.OK, xml.toString());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    static void sendXml(HttpServerExchange exchange, int status, String xml) {
        byte[] body = xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/xml");
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, body.length);
        exchange.getResponseSender().send(java.nio.ByteBuffer.wrap(body));
    }

    public static void sendError(HttpServerExchange exchange, int status, String code, String message) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Error>
                  <Code>%s</Code>
                  <Message>%s</Message>
                </Error>""".formatted(code, escape(message));
        sendXml(exchange, status, xml);
    }

    private static String queryParam(HttpServerExchange exchange, String name) {
        var deque = exchange.getQueryParameters().get(name);
        return (deque == null || deque.isEmpty()) ? null : deque.peekFirst();
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
