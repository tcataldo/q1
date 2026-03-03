There's S3, Cloudflare has R2, here is Q1.

I want to build an S3 compatible object store. It is focused on small
files for emails. Out of 60 million of messages, 80% of them would be
under 128k.

I would use large append only blocks with hash on keys to compute the
partition of keys. Tombstone management would be needed to cleanup or
compact blocks with a configurable %age of deletions.

We want a big focus on efficient IOs. 4k IOs are not fine most of the
time. Consider the holy grail is at 128k ios.

I want to use maven, java25, virtual threads. Foreign api to use
liburing / iouring is a must. etcd would be ok to manage partition
leaders and active nodes.

I don't need everything at the beginning. GET, PUT, HEAD (exists),
DELETE for objets and bucket create, list, delete.

We want to run our tests with amazon sdk to ensure compliance on the
parts we support.

I don't need to manage a hierarchy. '/' in keys are ok but they don't
need to map to directories on filesystems.

We could use erasure coding for redundancy but it is not mandatory.

I want node elasticity. single node is fine, 2 nodes is mirroring, 3
nodes is where things get interesting and replication factor is
configured.

