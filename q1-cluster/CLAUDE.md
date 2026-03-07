# q1-cluster — Coordination de cluster

Dépend uniquement de `q1-core`. Pas de dépendance sur `q1-api`.

## Responsabilités

- Élection de leader par partition via etcd
- Enregistrement des nœuds actifs (présence)
- Réplication synchrone des écritures vers les followers
- Routage des requêtes vers le bon nœud leader

## Topologie etcd

```
/q1/nodes/{nodeId}              →  "id:host:port"            (éphémère, lié au lease)
/q1/partitions/{id}/leader      →  "id:host:port"            (éphémère, gagnant de l'élection)
/q1/partitions/{id}/replicas    →  "id:host:port,…"          (éphémère, écrit par le leader)
```

## Élection par partition

Transaction conditionnelle : `PUT IF VERSION == 0`. Un seul nœud peut écrire la clé leader
à la fois (le lease garantit l'exclusivité). Si le leader tombe, etcd supprime la clé et un autre
nœud peut gagner.

## Assignation des replicas (ring)

Après avoir gagné l'élection d'une partition, le leader calcule ses RF-1 replicas par un
**anneau** sur les nœuds actifs triés par `nodeId` : les RF-1 nœuds immédiatement suivants
dans l'anneau (en bouclant). Écrit dans `/q1/partitions/{id}/replicas` lié au lease.

```
Anneau [A, B, C], RF=2 :  A leads → replica B | B leads → replica C | C leads → replica A
```

Avantage : chaque nœud stocke exactement `RF/N` des données (équilibré), contrairement au
"premier trié" qui surcharge le nœud lexicographiquement le plus petit.

Quand un nœud rejoint ou part (`watchNodes`), le leader recalcule et réécrit l'assignation
pour toutes les partitions qu'il dirige (`refreshReplicasForLeadPartitions`).

## Réplication (HttpReplicator)

1. Leader écrit localement
2. Fan-out parallèle vers les RF-1 nœuds de `partitionReplicas` avec header `X-Q1-Replica-Write: true`
3. Attend tous les ACKs avant de répondre au client (durabilité forte)

Le header `X-Q1-Replica-Write` empêche les followers de re-répliquer.
Les non-leaders retournent **307 Temporary Redirect** vers le leader.

## Catchup au démarrage (CatchupManager)

1. Attend jusqu'à 3s que les élections et assignations se stabilisent
2. Pour chaque partition où `isAssignedReplica()` est vrai : `GET /internal/v1/sync/{p}?segment={s}&offset={o}`
3. 200 → stream de bytes bruts à appliquer via `Segment.scanStream()` + `engine.applySyncStream()`
4. 204 → déjà à jour
5. Partitions non assignées → skippées (le nœud ne doit pas les avoir)
6. Échec → loggé, le nœud démarre quand même

## Fichiers clés

| Fichier | Rôle |
|---|---|
| `EtcdCluster.java` | Lease, keepalive, élection, watches sur les changements de leader |
| `PartitionRouter.java` | Même hash que `StorageEngine` ; `leaderBaseUrl()`, `followersFor()` |
| `HttpReplicator.java` | Fan-out HTTP async vers les followers, header `X-Q1-Replica-Write` |
| `CatchupManager.java` | Sync des partitions en retard au démarrage |
| `NodeId.java` | `record(id, host, port)` ; sérialisation `id:host:port` |
| `ClusterConfig.java` | Config immuable avec builder |

## Variables d'env (démarrage via Q1Server)

| Variable | Défaut | Description |
|---|---|---|
| `Q1_NODE_ID` | `node-{random8}` | Identifiant unique du nœud |
| `Q1_HOST` | `localhost` | Hostname/IP annoncé |
| `Q1_PORT` | `9000` | Port HTTP |
| `Q1_ETCD` | _(vide = standalone)_ | Endpoints etcd séparés par virgules |
| `Q1_RF` | `1` | Facteur de réplication |
| `Q1_PARTITIONS` | `16` | Nombre de partitions |

## API jetcd 0.7.7 — points d'attention

- `CmpTarget.version(0L)` — factory method, pas de constante `VERSION`
- `keepAlive(long, StreamObserver<LeaseKeepAliveResponse>)` — prend un `StreamObserver`
- `CloseableClient` est dans `io.etcd.jetcd.support` (non couvert par `io.etcd.jetcd.*`)

## TODO

- [ ] Réplication des opérations sur les buckets (create/delete bucket n'est pas répliqué)
- [ ] Réplication asynchrone optionnelle (mode "eventually consistent" avec RF configurable)
- [ ] Back-pressure si les followers sont trop lents
- [ ] Métriques : lag de réplication, nombre d'élections gagnées/perdues
