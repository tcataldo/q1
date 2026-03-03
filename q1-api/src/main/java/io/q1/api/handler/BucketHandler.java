package io.q1.api.handler;

import io.q1.core.StorageEngine;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    public BucketHandler(StorageEngine engine) {
        this.engine = engine;
    }

    public void createBucket(HttpServerExchange exchange, String bucket) {
        if (engine.createBucket(bucket)) {
            exchange.getResponseHeaders().put(Headers.LOCATION, "/" + bucket);
            exchange.setStatusCode(StatusCodes.OK);
        } else {
            // Bucket already exists — S3 returns 200 if the requester owns it
            exchange.setStatusCode(StatusCodes.OK);
        }
        exchange.endExchange();
    }

    public void deleteBucket(HttpServerExchange exchange, String bucket) {
        if (!engine.bucketExists(bucket)) {
            sendError(exchange, StatusCodes.NOT_FOUND, "NoSuchBucket",
                    "The specified bucket does not exist.");
            return;
        }
        // TODO: reject non-empty buckets (requires listing — skip for now)
        engine.deleteBucket(bucket);
        exchange.setStatusCode(StatusCodes.NO_CONTENT);
        exchange.endExchange();
    }

    public void listObjects(HttpServerExchange exchange, String bucket) {
        if (!engine.bucketExists(bucket)) {
            sendError(exchange, StatusCodes.NOT_FOUND, "NoSuchBucket",
                    "The specified bucket does not exist.");
            return;
        }

        String prefix = queryParam(exchange, "prefix");
        String delimiter = queryParam(exchange, "delimiter");
        int maxKeys = 1000;
        try {
            String mk = queryParam(exchange, "max-keys");
            if (mk != null) maxKeys = Integer.parseInt(mk);
        } catch (NumberFormatException ignored) {}

        List<String> keys = engine.list(bucket, prefix);
        boolean truncated = keys.size() > maxKeys;
        if (truncated) keys = keys.subList(0, maxKeys);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n");
        xml.append("  <Name>").append(escape(bucket)).append("</Name>\n");
        xml.append("  <Prefix>").append(prefix == null ? "" : escape(prefix)).append("</Prefix>\n");
        xml.append("  <MaxKeys>").append(maxKeys).append("</MaxKeys>\n");
        xml.append("  <IsTruncated>").append(truncated).append("</IsTruncated>\n");
        for (String key : keys) {
            xml.append("  <Contents>\n");
            xml.append("    <Key>").append(escape(key)).append("</Key>\n");
            xml.append("    <LastModified>").append(ISO.format(Instant.now())).append("</LastModified>\n");
            xml.append("    <ETag>\"\"</ETag>\n");
            xml.append("    <StorageClass>STANDARD</StorageClass>\n");
            xml.append("  </Contents>\n");
        }
        xml.append("</ListBucketResult>");

        sendXml(exchange, StatusCodes.OK, xml.toString());
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

    static void sendError(HttpServerExchange exchange, int status, String code, String message) {
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
