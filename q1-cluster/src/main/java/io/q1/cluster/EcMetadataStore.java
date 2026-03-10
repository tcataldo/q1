package io.q1.cluster;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * CRUD for per-object {@link EcMetadata} stored in etcd.
 *
 * <h3>etcd key layout</h3>
 * <pre>
 *   /q1/ec-meta/{bucket}/{objectKey}  →  EcMetadata.toWire()
 * </pre>
 *
 * <p>Metadata is written <em>after</em> all shard writes succeed — this is the
 * atomic commit point.  Readers that find no metadata treat the object as written
 * in plain-replication mode (backward compatibility).
 */
public final class EcMetadataStore {

    private static final String PREFIX = "/q1/ec-meta/";
    private static final long   TIMEOUT_S = 5;

    private final Client client;

    public EcMetadataStore(Client client) {
        this.client = client;
    }

    public void put(String bucket, String key, EcMetadata meta) throws Exception {
        client.getKVClient()
                .put(bs(etcdKey(bucket, key)), bs(meta.toWire()))
                .get(TIMEOUT_S, TimeUnit.SECONDS);
    }

    public Optional<EcMetadata> get(String bucket, String key) throws Exception {
        var resp = client.getKVClient()
                .get(bs(etcdKey(bucket, key)))
                .get(TIMEOUT_S, TimeUnit.SECONDS);
        if (resp.getKvs().isEmpty()) return Optional.empty();
        return Optional.of(EcMetadata.fromWire(str(resp.getKvs().get(0).getValue())));
    }

    public void delete(String bucket, String key) throws Exception {
        client.getKVClient()
                .delete(bs(etcdKey(bucket, key)))
                .get(TIMEOUT_S, TimeUnit.SECONDS);
    }

    private static String etcdKey(String bucket, String key) {
        return PREFIX + bucket + "/" + key;
    }

    private static ByteSequence bs(String s) {
        return ByteSequence.from(s, StandardCharsets.UTF_8);
    }

    private static String str(ByteSequence bs) {
        return bs.toString(StandardCharsets.UTF_8);
    }
}
