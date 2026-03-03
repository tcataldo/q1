# q1-api — Serveur HTTP S3-compatible

Dépend de `q1-core` et `q1-cluster`. C'est le point d'entrée du processus.

## Responsabilités

- Serveur HTTP Undertow sur virtual threads
- Routage path-style S3 (`/{bucket}/{key}`)
- Opérations objets : GET, PUT, HEAD, DELETE
- Opérations buckets : create, list, delete, list-buckets
- Endpoint de synchronisation interne : `/internal/v1/sync/`
- Redirection 307 des non-leaders vers le leader

## Routage

```
GET  /                         → listBuckets
PUT  /{bucket}                 → createBucket
GET  /{bucket}?prefix=…        → listObjects
DELETE /{bucket}               → deleteBucket
PUT  /{bucket}/{key}           → put (+ réplication si leader)
GET  /{bucket}/{key}           → get
HEAD /{bucket}/{key}           → head
DELETE /{bucket}/{key}         → delete (+ réplication si leader)
GET  /internal/v1/sync/{p}     → SyncHandler (endpoint cluster interne)
```

## Modes de démarrage

**Standalone** (`Q1_ETCD` absent) :
```
Q1Server(engine, port)
```
Toutes les requêtes sont servies localement, pas de réplication.

**Cluster** (`Q1_ETCD` présent) :
```
Q1Server(engine, cluster, partitionRouter, replicator, port)
```
Séquence de démarrage :
1. `EtcdCluster.start()` — lease + élections
2. `CatchupManager.catchUp(engine)` — sync des partitions en retard
3. `Q1Server.start()` — ouvre le port HTTP

## Fichiers clés

| Fichier | Rôle |
|---|---|
| `Q1Server.java` | Entry point, `main()`, construction selon les env vars |
| `S3Router.java` | Parse l'URL, détecte le mode replica, redirige les non-leaders |
| `handler/ObjectHandler.java` | PUT/GET/HEAD/DELETE objets + appel du replicator |
| `handler/BucketHandler.java` | Ops sur les buckets + helpers `sendXml`, `sendError` |
| `handler/SyncHandler.java` | Sert les streams de sync aux followers |

## Points d'attention

- `exchange.startBlocking()` obligatoire avant `exchange.getInputStream()` (Undertow)
- `BucketHandler.sendError` doit être `public` (appelé depuis `S3Router` dans un autre package)
- Le header `X-Q1-Replica-Write: true` court-circuite la réplication et le routage
- Les écritures sur les buckets (create/delete) ne sont **pas routées** par partition (pas de `leaderBaseUrl`)
  → les buckets doivent être créés sur chaque nœud séparément (non-répliqué pour l'instant)

## AWS SDK — gotcha

L'AWS SDK v2 utilise `aws-chunked` encoding par défaut pour les PUTs. Notre serveur lit
le body brut sans décoder ce format. Il faut désactiver côté client :
```java
S3Configuration.builder()
    .chunkedEncodingEnabled(false)
    ...
```

## Variables d'env

Voir `q1-cluster/CLAUDE.md` ou la section correspondante dans le CLAUDE.md racine.

## TODO

- [ ] Validation de signature SigV4 (actuellement toute clé/secret est acceptée)
- [ ] Content-Type et Last-Modified persistés (actuellement calculés à la volée)
- [ ] Multipart upload (> 5 GB)
- [ ] `ListObjectsV2` pagination avec `continuation-token`
- [ ] Rate limiting / circuit breaker sur les endpoints publics
