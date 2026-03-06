# Q1 — Prochaines améliorations

État actuel : segments append-only, index RocksDB persistant, compaction deux-phases,
réplication synchrone etcd, 54 tests (unit + S3 compliance + cluster).

---

## 1. Corrections de correctness

### 1.1 CRC32 sur le chemin de lecture chaud

`Segment.read()` (appelé par chaque GET) ne vérifie pas le CRC32 stocké dans le header.
La vérification existe dans `scan()` et `scanStream()` mais pas sur la lecture directe.
Problème : corruption silencieuse si un secteur flip entre deux compactions.

Blocage : l'offset stocké dans l'index pointe sur les *value bytes*, pas sur le header.
Il faut aussi stocker `keyLen` dans l'entrée index pour pouvoir relire le header complet,
ou recalculer l'offset header à partir de `valueOffset - HEADER_SIZE - keyLen`.

Solution la moins invasive : stocker `keyLen` dans `RocksDbIndex.Entry` (20 → 22 bytes),
ce qui permet de retrouver l'offset du header et de vérifier le CRC à la lecture.

### 1.2 ETag et métadonnées par objet

Actuellement, chaque PUT retourne un ETag fictif. AWS SDK et les proxies s'appuient sur
l'ETag pour la validation de cache et les copies server-side (`x-amz-copy-source`).
Il faut persister `(etag, content-type, content-length, last-modified)` par objet.

Option A — stocker les métadonnées dans la valeur du segment (préfixe fixe avant les bytes
de la valeur). Impact : décode à chaque GET.

Option B — RocksDB séparé (ou column family) pour les métadonnées. Découple lecture de
métadonnées et lecture de valeur ; lisible sans ouvrir le segment.

### 1.3 Réplication des buckets

`createBucket` et `deleteBucket` ne se propagent pas aux followers. Chaque nœud a son
propre `buckets.properties`. Les clients qui créent un bucket sur le leader et listent sur
un follower obtiennent un 404.

---

## 2. Performance

### 2.1 io_uring pour les lectures et écritures

`q1-uring` a été supprimé mais l'idée reste valide. `Segment.read()` et `Segment.append()`
utilisent `FileChannel` (NIO). Sur Linux ≥ 5.1, io_uring permet des I/O sans syscall par
opération grâce au submission ring. Impact mesurable sur les workloads à haute concurrence
(>10 k req/s) avec de nombreux petits fichiers — exactement le profil email.

Implémentation : réintroduire `UringFileIO` via Panama FFI (déjà esquissé avant
la suppression). Le hotpath reste 128 KB, ce qui est dans la plage optimale d'io_uring.

### 2.2 Listing optimisé avec index secondaire

`StorageEngine.list()` scanne les 16 partitions RocksDB en série. Pour 60 M clés avec des
listings fréquents (`ListObjects` sur un bucket), c'est O(clés_bucket) par requête.

Option A — index secondaire `(bucket → sorted set of keys)` dans un RocksDB dédié.
Maintenu en sync dans le même WriteBatch que l'index primaire. `list()` lit depuis l'index
secondaire directement. Coût : ~2× les writes index, réduit le listing à O(résultat).

Option B — column family RocksDB séparée par bucket. Similaire mais isolé par bucket.

### 2.3 Merge compaction (multi-segments)

La compaction actuelle traite un segment à la fois. Après beaucoup de suppressions, on
peut se retrouver avec des dizaines de petits segments compactés. Merge compaction fusionne
N segments en un seul, réduisant le nombre de fichiers ouverts et améliorant la localité.

Algorithme : même structure deux-phases mais avec N segments sources → 1 segment cible.
La difficulté est de gérer les cas où la même clé apparaît dans plusieurs segments sources
(garder la version la plus récente selon l'ordre des segments).

---

## 3. Opérations S3 manquantes

### 3.1 `ListObjectsV2` avec pagination

Actuellement `list()` retourne tout sans pagination. L'API S3 impose `max-keys` (défaut
1 000) et un `continuation-token` pour les listings qui dépassent ce seuil.

Implémentation : `RocksDbIndex.keysWithPrefix` accepte un `startAfter` et un `limit`.
Le `continuation-token` est simplement la dernière clé encodée en base64.

### 3.2 Multipart upload

Les objets > 5 GB requièrent l'API multipart (`CreateMultipartUpload`, `UploadPart`,
`CompleteMultipartUpload`, `AbortMultipartUpload`). Les parts sont stockées temporairement
(dans un répertoire dédié par upload ID) puis assemblées à `Complete`.

Complexité principale : atomicité du `Complete` (toutes les parts ou aucune).

### 3.3 Object copy server-side (`PUT … x-amz-copy-source`)

Opération S3 fréquente (déplace des objets sans passer les bytes par le client).
Nécessite les ETags corrects (cf. 1.2).

### 3.4 Conditional requests (`If-Match`, `If-None-Match`)

`GET` et `PUT` conditionnels basés sur l'ETag. Utilisés par les SDKs pour le
optimistic locking. Nécessite les ETags persistants (cf. 1.2).

---

## 4. Résilience cluster

### 4.1 Erasure coding

Remplacer la réplication full-copy (RF=N copies identiques) par un schéma erasure coding
(ex. RS(4,2) : 4 data shards + 2 parity, tolère 2 pertes pour 50 % d'overhead au lieu
de RF×100 %). Implémentation : bibliothèque Java comme `JavaReedSolomon`. Architecture :
sharding par objet au niveau de `StorageEngine`, reconstruction en cas de read failure.

### 4.2 Leader lease avec fencing token

Le leader election actuel (etcd conditional put) est correct mais ne protège pas contre
un follower lent qui exécute une écriture en retard après avoir perdu le leadership.
Fencing token : le leader étampe chaque write avec le numéro de révision etcd de son lease.
Les followers rejettent les writes dont le token est inférieur au dernier vu.

### 4.3 Reconfiguration dynamique du nombre de partitions

Aujourd'hui `Q1_PARTITIONS` est fixé au démarrage. Ajouter des partitions nécessite un
arrêt complet. Une reconfiguration dynamique (resharding) permettrait de scaler
horizontalement sans downtime : split d'une partition en deux, migration progressive des
clés via compaction.

---

## 5. Observabilité

### 5.1 Endpoint Prometheus

`GET /metrics` au format text/plain Prometheus :
- `q1_partition_segment_count{partition="p00"}` — nombre de segments par partition
- `q1_partition_live_keys{partition="p00"}` — estimation via RocksDB
- `q1_compaction_total{partition="p00", result="ok|skipped"}` — compteur par passe
- `q1_compaction_bytes_reclaimed_total` — bytes récupérés par compaction
- `q1_request_duration_seconds{method, status}` — latence HTTP percentiles

### 5.2 Endpoint de santé structuré

`GET /healthz` : état leader/follower par partition, lag de réplication, état etcd.
Utilisable par les load balancers et les sondes Kubernetes.

---

## 6. Sécurité

### 6.1 Signature AWS V4

Actuellement toutes les credentials sont acceptées sans vérification. La validation de
`Authorization: AWS4-HMAC-SHA256 …` permettrait d'intégrer Q1 dans des pipelines qui
utilisent les SDKs AWS en mode production (IAM, STS, etc.).

Implémentation : validation HMAC-SHA256 du canonical request côté serveur. Les clés
d'accès / secrets sont configurables via env vars ou un fichier de credentials.

### 6.2 TLS

Undertow supporte HTTPS nativement. Ajouter `Q1_TLS_CERT` / `Q1_TLS_KEY` et
configurer le `XnioSsl` channel listener.

---

## Priorisation suggérée

| Priorité | Item | Effort | Impact |
|----------|------|--------|--------|
| P0 | 1.2 ETag + métadonnées | M | Correctness S3 |
| P0 | 3.1 Pagination ListObjects | S | Correctness S3 |
| P1 | 1.1 CRC32 sur reads | S | Correctness données |
| P1 | 1.3 Réplication buckets | S | Correctness cluster |
| P1 | 5.1 Métriques Prometheus | M | Ops |
| P2 | 2.1 io_uring | L | Perf I/O |
| P2 | 2.2 Index listing optimisé | L | Perf listing |
| P2 | 3.2 Multipart upload | L | Complétude S3 |
| P3 | 4.1 Erasure coding | XL | Coût stockage |
| P3 | 2.3 Merge compaction | M | Perf fichiers |
| P3 | 6.1 Signature V4 | M | Sécurité |
