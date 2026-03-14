# Q1 — État et TODO

## État actuel (mars 2026)

### Infrastructure
- Segments append-only + index RocksDB persistant (pas de rescan au démarrage)
- Compaction deux phases crash-safe (threshold configurable, log en fin de passe)
- CRC32 vérifié sur `scan()` et `scanStream()` — **pas sur le GET direct**
- 16 partitions par défaut, routage par `hashCode() % N`

### Cluster
- Apache Ratis embarqué (Raft, pas d'etcd externe)
- Mode réplication : log Raft sur chemin d'écriture, proxy transparent vers le leader
- Mode EC (k=2, m=1 en prod) : fan-out direct, Raft hors chemin de données
- En mode EC, n'importe quel nœud accepte les PUT/DELETE (plus de redirect leader)
- Bucket CREATE/DELETE passent par le log Raft dans les deux modes

### Erasure Coding
- Codec RS vendorisé (`io.q1.cluster.erasure`)
- `EcObjectHandler` : encode → fan-out shards → 200 (aucune écriture Raft)
- Placement déterministe par ring (hash(bucket+key) % N, k+m nœuds consécutifs)
- Shard payload auto-descriptif : 8B originalSize + données RS
- Fallback transparent vers plain-replication pour objets pré-EC
- `EcRepairScanner` : scanner background, checkpoint RocksDB, reconstruit les shards manquants

### API S3
- GET, PUT, HEAD, DELETE objets
- ListObjectsV1 + ListObjectsV2 avec `delimiter` (CommonPrefixes), `max-keys`,
  `continuation-token` / `marker` — listing 100% RocksDB, aucun scan segment
- Listing EC transparent : les objets EC apparaissent dans les listings normaux
- Bucket list, create, delete
- `GET /healthz` : JSON `{nodeId, mode, status, partitions}` + champs cluster si applicable ; 200/503
- `s3cmd ls`, `s3cmd ls --recursive`, `s3cmd ls s3://bucket/prefix/` fonctionnels

### Ce qui n'est PAS implémenté
- Snapshots Raft (redémarrage rejoue tout le log depuis le début)
- Cluster dynamique (topology statique via `Q1_PEERS`)
- Signature V4 (toutes les credentials acceptées)
- TLS
- Multipart upload
- Metadata persistante : ETag, Content-Type, Last-Modified, Size réelle en listing EC
- Métriques Prometheus / endpoint `/metrics`
- Re-encodage EC élastique (ajout/suppression de nœuds sans restart)

---

## TODO — par priorité

### ⚡ Quick wins (quelques heures chacun)

**CRC32 sur le GET direct**
`Segment.read()` (appelé à chaque GET) ne vérifie pas le CRC32 stocké dans le header.
Fix : stocker `keyLen` dans `RocksDbIndex.Entry` (20 → 22 bytes, big-endian short)
pour pouvoir recalculer l'offset du header depuis l'offset valeur.
`valueOffset - HEADER_SIZE - keyLen` → lit 4B CRC, vérifie, rejette si mismatch.
Risque actuel : corruption silencieuse entre deux compactions.

**Size réelle dans le listing (mode non-EC)**
`RocksDbIndex.Entry` contient déjà `valueLength`. Il suffit de le remonter dans
`listPaginated()` → `ListResult` → XML `<Size>`. En mode EC la taille vient du shard
(voir "metadata persistante" ci-dessous), donc on peut commencer par non-EC.
`s3cmd ls` affiche actuellement 0 pour tous les objets.

**Filtrer `__q1_ec_shards__` des erreurs visibles**
Le bucket interne n'apparaît pas dans `listBuckets` (pas dans `BucketRegistry`) mais
un client qui fait `GET /__q1_ec_shards__/` reçoit un 404 `NoSuchBucket` qui peut
surprendre. Ajouter un check explicite et retourner 403 `AccessDenied` sur les
buckets préfixés par `__q1_`.

---

### 📦 Court terme (1–3 jours)

**Metadata persistante par objet**
ETag, Content-Type, Last-Modified et surtout la taille originale manquent sur HEAD et listing.
Option retenue : stocker dans une colonne RocksDB dédiée (`metadata` CF ou clé spéciale
`0x01{internalKey}` → `{size:8B}{etag:16B}{contentType:N}`).
- Permet de retourner ETag et Size dans le listing sans lire les segments
- En mode EC, le `put()` shard stocke aussi la metadata → HEAD ne fait plus de shard fetch
- Non bloquant mais agaçant pour `s3cmd sync` qui se base sur ETag+Size

**Snapshots Raft**
Sans snapshot, un nœud qui redémarre rejoue l'intégralité du log depuis l'index 0.
Sur un cluster actif depuis plusieurs mois, ça peut durer des minutes.
Implémenter `StateMachine.takeSnapshot()` (copie des répertoires segments + RocksDB)
et `loadSnapshot()`. Ratis trigger automatiquement selon `log.purge.gap` (configurable).
Priorité haute si le cluster tourne en mode réplication.
En mode EC pur, le log ne contient que les ops bucket → replay trivial.

**Tests EC avec panne réelle**
`EcRepairScanner` est implémenté mais testé uniquement en happy path.
Écrire un `ClusterIT` qui arrête un nœud, vérifie que les GETs reconstituent correctement,
redémarre le nœud, vérifie que le repair scanner reboucle les shards manquants.

---

### 🔧 Moyen terme (1–2 semaines)

**Prometheus `/metrics`**
```
q1_requests_total{method, status}
q1_request_duration_seconds{method} (histogramme)
q1_segment_count{partition}
q1_live_keys{partition}
q1_compaction_runs_total{partition, result}
q1_compaction_bytes_reclaimed_total
q1_raft_term
q1_raft_commit_index
q1_ec_repair_shards_reconstructed_total
```
Undertow a un hook `ExchangeCompletionListener` pour capturer latence et status.
Les métriques storage viennent de RocksDB stats + `Partition.segmentCount()`.

**Reconfiguration dynamique du cluster**
Ratis supporte `RaftClient.admin().setConfiguration(peers)` pour ajouter/retirer des
nœuds sans restart. Câbler ça sur un endpoint admin `PUT /internal/v1/cluster/peers`.
Bloquant pour le re-encodage EC élastique (voir ci-dessous).

**Merge compaction (multi-segments)**
La compaction actuelle traite un segment à la fois. Après de nombreuses compactions,
on accumule des petits segments. Une passe de merge combine N segments → 1,
réduit les file descriptors ouverts et améliore la localité séquentielle.
Même structure deux-phases mais avec N sources → 1 cible.

---

### 🏗️ Long terme

**Re-encodage EC élastique**
Quand un nœud rejoint ou quitte le cluster, les shards existants ne sont pas redistribués.
Le ring change → `computeShardPlacement` retourne de nouveaux nœuds → les anciens
shards sont "orphelins". Solution : déclencher un re-encoding background similaire au
repair scanner mais pour tous les objets dont le placement a changé.
Dépend de la reconfiguration dynamique.

**AWS Signature V4**
Valider `Authorization: AWS4-HMAC-SHA256` avec une liste de clés configurée.
Permet d'intégrer Q1 dans des pipelines existants sans modifier les clients.

**TLS**
Undertow supporte HTTPS nativement. Ajouter `Q1_TLS_CERT` / `Q1_TLS_KEY`
et configurer le `XnioSsl` channel listener.

**Multipart upload**
Nécessaire pour les objets > 5 GB. Protocole S3 en 3 étapes :
`CreateMultipartUpload` → N × `UploadPart` → `CompleteMultipartUpload`.
Stockage intermédiaire des parts dans un bucket interne `__q1_mpu__`.

---

## Hors scope (pour ce projet)

- ListObjectsV2 pagination par token pour très grands buckets avec delimiter et
  forte collapsion de CommonPrefixes (edge case connu, acceptable)
- Versioning S3 (objets immuables avec historique)
- S3 Select / Tagging / ACLs
- io_uring (Panama FFI) — NIO FileChannel est suffisant pour 128 KB
