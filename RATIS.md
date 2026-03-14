# Raft dans Q1 — Architecture actuelle

Apache Ratis (Raft embarqué JVM) remplace etcd depuis la migration Q1 v0.1.
Ce document décrit l'état courant, pas la migration historique.

---

## Ce que Raft fait dans Q1

### Mode réplication (EC désactivé)

Raft est **sur le chemin critique** des écritures.

```
PUT /bucket/key
  ├── follower → proxyToLeader() (HTTP transparent, pas de redirect 307)
  └── leader  → cluster.submit(RatisCommand.put/delete)
                  → commit Raft (quorum gRPC)
                  → Q1StateMachine.applyTransaction() sur chaque nœud
                  → engine.put() local sur chaque nœud
```

Ce que Raft apporte en mode réplication :
- **Réplication** : le log remplace l'ancien `HttpReplicator` (fan-out HTTP manuel)
- **Consensus** : un seul leader absorbe les écritures, garantit la cohérence
- **Catchup au redémarrage** : rejoue le log depuis le dernier index appliqué
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
| Snapshots Raft | ❌ non implémenté | log rejoué depuis le début au restart |
| Reconfiguration dynamique | ❌ cluster statique | `Q1_PEERS` fixe au démarrage |
| Élection par partition | N/A | 1 groupe global, 1 seul leader |
| Routage par partition | N/A | `PartitionRouter` délègue à `isLocalLeader()` global |

---

## Groupe Raft

- **1 groupe global** pour les 16 partitions (pas de groupe par partition)
- `RaftGroupId` : UUID fixe identique sur tous les nœuds (hardcodé dans `ClusterConfig`)
- `RaftPeerId` : `Q1_NODE_ID`
- `RaftPeer` : `host:raftPort` (gRPC)
- Log stocké sous `$Q1_DATA_DIR/raft/`
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

Pas de snapshot implémenté : `takeSnapshot()` / `loadSnapshot()` non surchargés.
Au redémarrage, Ratis rejoue l'intégralité du log depuis l'index 0.

---

## Démarrage

```
RatisCluster.start()
  → RaftServer démarre, élection automatique (pas de waitForLeader explicite)
  → premier leader élu en < 1s sur réseau local

Q1Server.start()
  → port HTTP ouvert immédiatement après Ratis ready
  → isClusterReady() bloque les requêtes client si pas assez de nœuds actifs
```

Pas de `CatchupManager`. Un follower qui redémarre rejoue le log Raft
et converge automatiquement vers l'état du leader.

---

## Variables d'environnement

| Variable | Défaut | Description |
|---|---|---|
| `Q1_PEERS` | _(absent = standalone)_ | `id\|host\|httpPort\|raftPort` par nœud, séparés par virgule |
| `Q1_RAFT_PORT` | `6000` | Port gRPC Raft inter-nœuds |
| `Q1_NODE_ID` | `node-{random8}` | Doit correspondre à un ID dans `Q1_PEERS` |

Exemple `Q1_PEERS` :
```
q1-01|q1-01|9000|6000,q1-02|q1-02|9000|6000,q1-03|q1-03|9000|6000
```

---

## Limitations connues

### Pas de snapshot Raft
Sans `takeSnapshot()`, le log est rejoué depuis le début à chaque redémarrage.
Sur un cluster en production avec des mois d'activité, le temps de démarrage
croît linéairement avec le volume d'écritures historiques.

Solution : implémenter `StateMachine.takeSnapshot()` (sérialise les segments +
index RocksDB courants) et `loadSnapshot()`. Ratis déclenche automatiquement
un snapshot quand le log dépasse un seuil configurable.

### Cluster statique
`Q1_PEERS` est lu au démarrage et ne change pas à chaud.
Ratis supporte `setConfiguration()` pour l'ajout/suppression de nœuds en live
mais ce n'est pas encore câblé.

### Pas de métriques Raft exposées
Le terme Raft courant, l'index de commit et le lag de réplication ne sont pas
exposés dans `/metrics` ou `/healthz`.
