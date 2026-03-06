# Q1 — Prochaines améliorations

État actuel : segments append-only, index RocksDB persistant, compaction deux-phases,
réplication synchrone etcd, 54 tests (unit + S3 compliance + cluster).

Périmètre cible : GET, PUT, HEAD, DELETE. Le listing d'objets n'est pas une priorité.

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

HEAD retourne Content-Type, ETag, Content-Length, Last-Modified. Aujourd'hui ces champs
sont soit absents soit fictifs. Il faut persister `(etag, content-type, last-modified)`
par objet pour que HEAD et GET soient cohérents.

Option A — stocker les métadonnées dans la valeur du segment (préfixe fixe avant les bytes
de la valeur). Décodage à chaque GET mais pas de structure supplémentaire.

Option B — RocksDB séparé pour les métadonnées. Découple lecture de métadonnées et lecture
de valeur ; un HEAD n'ouvre jamais le segment.

### 1.3 Réplication des buckets

`createBucket` et `deleteBucket` ne se propagent pas aux followers. Chaque nœud a son
propre `buckets.properties`. Un PUT sur le leader vers un bucket créé localement renvoie
404 sur un follower qui n'a pas ce bucket.

---

## 2. Performance

### 2.1 io_uring pour les lectures et écritures

`Segment.read()` et `Segment.append()` utilisent `FileChannel` (NIO). Sur Linux ≥ 5.1,
io_uring permet des I/O sans syscall par opération grâce au submission ring. Impact
mesurable sur les workloads à haute concurrence (>10 k req/s) — exactement le profil email.

Implémentation : réintroduire `UringFileIO` via Panama FFI (déjà esquissé, supprimé pour
simplifier). Le hotpath 128 KB est dans la plage optimale d'io_uring.

### 2.2 Merge compaction (multi-segments)

La compaction actuelle traite un segment à la fois. Après beaucoup de suppressions, on
peut se retrouver avec des dizaines de petits segments compactés. Merge compaction fusionne
N segments en un seul, réduisant le nombre de fichiers ouverts et améliorant la localité
des lectures séquentielles.

Algorithme : même structure deux-phases mais avec N segments sources → 1 segment cible.
La difficulté est de gérer les cas où la même clé apparaît dans plusieurs segments sources
(garder la version la plus récente selon l'ordre des segments).

---

## 3. Résilience cluster

### 3.1 Erasure coding

Remplacer la réplication full-copy (RF=N copies identiques) par un schéma erasure coding
(ex. RS(4,2) : 4 data shards + 2 parity, tolère 2 pertes pour 50 % d'overhead au lieu
de RF×100 %). Implémentation : bibliothèque Java comme `JavaReedSolomon`. Architecture :
sharding par objet au niveau de `StorageEngine`, reconstruction en cas de read failure.

### 3.2 Leader lease avec fencing token

Le leader election actuel (etcd conditional put) est correct mais ne protège pas contre
un follower lent qui exécute une écriture en retard après avoir perdu le leadership.
Fencing token : le leader étampe chaque write avec le numéro de révision etcd de son lease.
Les followers rejettent les writes dont le token est inférieur au dernier vu.

### 3.3 Reconfiguration dynamique du nombre de partitions

Aujourd'hui `Q1_PARTITIONS` est fixé au démarrage. Une reconfiguration dynamique
(resharding) permettrait de scaler horizontalement sans downtime : split d'une partition
en deux, migration progressive des clés via compaction.

---

## 4. Observabilité

### 4.1 Endpoint Prometheus

`GET /metrics` au format text/plain Prometheus :
- `q1_partition_segment_count{partition="p00"}` — nombre de segments par partition
- `q1_partition_live_keys{partition="p00"}` — estimation via RocksDB
- `q1_compaction_total{partition="p00", result="ok|skipped"}` — compteur par passe
- `q1_compaction_bytes_reclaimed_total` — bytes récupérés par compaction
- `q1_request_duration_seconds{method, status}` — latence HTTP percentiles

### 4.2 Endpoint de santé structuré

`GET /healthz` : état leader/follower par partition, lag de réplication, état etcd.
Utilisable par les load balancers et les sondes Kubernetes.

---

## 5. Sécurité

### 5.1 Signature AWS V4

Actuellement toutes les credentials sont acceptées sans vérification. La validation de
`Authorization: AWS4-HMAC-SHA256 …` permettrait d'intégrer Q1 dans des pipelines
existants sans modifier les clients.

### 5.2 TLS

Undertow supporte HTTPS nativement. Ajouter `Q1_TLS_CERT` / `Q1_TLS_KEY` et configurer
le `XnioSsl` channel listener.

---

## Priorisation suggérée

| Priorité | Item | Effort | Impact |
|----------|------|--------|--------|
| P0 | 1.1 CRC32 sur reads | S | Intégrité des données |
| P0 | 1.2 ETag + métadonnées | M | Correctness HEAD/GET |
| P1 | 1.3 Réplication buckets | S | Correctness cluster |
| P1 | 4.1 Métriques Prometheus | M | Ops |
| P2 | 2.1 io_uring | L | Perf I/O |
| P2 | 2.2 Merge compaction | M | Perf fichiers |
| P3 | 3.1 Erasure coding | XL | Coût stockage |
| P3 | 3.2 Fencing token | M | Correctness cluster |
| P3 | 5.1 Signature V4 | M | Sécurité |
