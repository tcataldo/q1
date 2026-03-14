# Raft dans Q1 — Architecture actuelle

Apache Ratis (Raft embarqué JVM) remplace etcd depuis la migration Q1 v0.1.
Ce document décrit l'état courant, pas la migration historique.

---

## Ce que Raft fait dans Q1

### Mode réplication (EC désactivé)

Raft est **sur le chemin critique** des écritures.

```
PUT /bucket/key
  ├── follower → proxyToLeader() (HTTP transparent, body forwardé, client voit 200)
  └── leader  → cluster.submit(RatisCommand.put/delete)
                  → commit Raft (quorum gRPC)
                  → Q1StateMachine.applyTransaction() sur chaque nœud
                  → engine.put() local sur chaque nœud
```

Ce que Raft apporte en mode réplication :
- **Réplication** : le log remplace l'ancien `HttpReplicator` (fan-out HTTP manuel)
- **Consensus** : un seul leader absorbe les écritures, garantit la cohérence
- **Catchup au redémarrage** : rejoue le log depuis le dernier snapshot appliqué
  (remplace `CatchupManager` + endpoint `/internal/v1/sync/`)
- **Anti split-brain** : écriture impossible sans quorum (⌊N/2⌋+1)

### Mode EC (Q1_EC_K > 0)

Raft est **hors du chemin de données**.

```
PUT /bucket/key (n'importe quel nœud)
  → encode(body) → k+m shards (local, CPU pur)
  → fan-out HTTP vers chaque nœud ring via HttpShardClient
  → aucun cluster.submit(), aucune entrée dans le log Raft
```

Raft fournit uniquement :
- **Topologie** : liste des peers (`config.peers()`) pour `computeShardPlacement`
- **Opérations bucket** : `CREATE_BUCKET` / `DELETE_BUCKET` passent par le log
  (seuls `RatisCommand` à transiter en mode EC)
- **`isClusterReady()`** : vérifie que `activeNodes >= k`

La notion de "leader" est sans sens pour les objets en mode EC.
N'importe quel nœud peut recevoir et traiter un PUT ou DELETE.

---

## Ce que Raft ne fait PAS dans Q1

| Fonctionnalité | État | Alternative |
|---|---|---|
| Reconfiguration dynamique | ❌ cluster statique | `Q1_PEERS` fixe au démarrage |
| Élection par partition | N/A | 1 groupe global, 1 seul leader |
| Routage par partition | N/A | `PartitionRouter` délègue à `isLocalLeader()` global |

---

## Groupe Raft

- **1 groupe global** pour les 16 partitions (pas de groupe par partition)
- `RaftGroupId` : UUID depuis `ClusterConfig.raftGroupId()`, puis `Q1_RAFT_GROUP_ID` env, puis UUID hardcodé
- `RaftPeerId` : `Q1_NODE_ID`
- `RaftPeer` : `host:raftPort` (gRPC)
- Log + snapshots stockés sous `raftDataDir` (configurable, typiquement `$Q1_DATA_DIR/raft/`)
- Quorum : ⌊N/2⌋+1 (ex. 3 nœuds → quorum 2, 5 nœuds → quorum 3)

---

## `RatisCommand` — format binaire

Seul canal de mutations passant par le log.

```
[1B]  type   0x01=PUT  0x02=DELETE  0x03=CREATE_BUCKET  0x04=DELETE_BUCKET
[2B]  longueur bucket (unsigned short)
[N B] bucket (UTF-8)
[2B]  longueur clé (unsigned short)   — absent pour CREATE/DELETE_BUCKET
[N B] clé (UTF-8)                     — absent pour CREATE/DELETE_BUCKET
[8B]  longueur valeur (long)          — PUT uniquement
[N B] valeur                          — PUT uniquement
```

En mode EC, seuls `CREATE_BUCKET` et `DELETE_BUCKET` utilisent ce canal.
Les objets ne transitent jamais par le log Raft en mode EC.

---

## `Q1StateMachine` — machine d'état

```java
applyTransaction(trx):
  switch cmd.type():
    PUT           → engine.put(bucket, key, value)
    DELETE        → engine.delete(bucket, key)
    CREATE_BUCKET → engine.createBucket(bucket)
    DELETE_BUCKET → engine.deleteBucket(bucket)
```

### Snapshots

Implémenté via `SimpleStateMachineStorage` :

- `takeSnapshot()` :
  1. Appelle `engine.sync()` (fsync tous les segments actifs via `Partition.force()`)
  2. Crée un fichier marqueur vide via `snapshotStorage.getSnapshotFile(term, index)`
     (term+index encodés dans le nom, ex. `snapshot.3_10000`)
  3. Met à jour `snapshotStorage.updateLatestSnapshot()`
  4. Retourne l'index du snapshot (Ratis peut purger le log jusqu'à cet index)

- `initialize()` :
  1. Appelle `snapshotStorage.init(storage)`
  2. Si un snapshot existe, appelle `updateLastAppliedTermIndex(term, index)` pour
     fast-forwarder au bon point — Ratis ne rejoue que les entrées postérieures

- Auto-trigger : toutes les 10 000 entrées commitées (configurable via
  `RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold`)

Pourquoi le fichier marqueur est vide : l'état du `StorageEngine` (segments + RocksDB)
est déjà durablement persisté sur disque indépendamment de Raft. Le snapshot ne sert
qu'à indiquer à Ratis à quel (term, index) cet état est cohérent.

---

## Démarrage

```
RatisCluster.start()
  → RaftServer démarre (RECOVER si raftDir non vide, FORMAT sinon)
  → election automatique (pas de waitForLeader explicite)
  → premier leader élu en < 1s sur réseau local

Q1Server.start()
  → port HTTP ouvert immédiatement après Ratis ready
  → isClusterReady() bloque les requêtes client si pas assez de nœuds actifs
```

Un follower qui redémarre charge le dernier snapshot puis rejoue uniquement les entrées
Raft qui le suivent — sans `CatchupManager` ni endpoint `/internal/v1/sync/`.

---

## Variables d'environnement

| Variable | Défaut | Description |
|---|---|---|
| `Q1_PEERS` | _(absent = standalone)_ | `id\|host\|httpPort\|raftPort` par nœud, séparés par virgule |
| `Q1_RAFT_PORT` | `6000` | Port gRPC Raft inter-nœuds |
| `Q1_NODE_ID` | `node-{random8}` | Doit correspondre à un ID dans `Q1_PEERS` |
| `Q1_RAFT_GROUP_ID` | _(UUID hardcodé)_ | Override UUID du groupe Raft |

---

## Limitations connues

### Cluster statique
`Q1_PEERS` est lu au démarrage et ne change pas à chaud.
Ratis supporte `setConfiguration()` pour l'ajout/suppression de nœuds en live
mais ce n'est pas encore câblé.

### Pas de métriques Raft exposées
Le terme Raft courant, l'index de commit et le lag de réplication ne sont pas
exposés dans `/metrics` ou `/healthz`.
