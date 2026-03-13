# Migration etcd → Apache Ratis

## Objectif

Supprimer la dépendance à etcd (processus externe) en embarquant Apache Ratis (Raft en
Java pur) directement dans le JVM Q1. Résultat : un cluster Q1 auto-suffisant, sans
infrastructure tierce à déployer.

---

## Décision d'architecture : 1 groupe Raft global

### Options envisagées

| Option | Description | Verdict |
|---|---|---|
| **1 groupe global** | Un seul groupe Raft pour les 16 partitions | **Retenu** |
| 1 groupe par partition | 16 groupes Raft indépendants | Inutilement complexe |
| N groupes pour N partitions | Compromis | Complexité intermédiaire sans avantage |

### Pourquoi 1 groupe global

Aujourd'hui chaque écriture est déjà redirigée en 307 vers le leader de partition, ce
qui signifie qu'en pratique un seul nœud absorbe les écritures pour chaque partition à
la fois. Avec un groupe Raft global, un seul leader élit pour tout le cluster, ce qui
est équivalent en termes de débit tout en étant beaucoup plus simple.

Avantages concrets :
- Une seule élection, une seule connexion gRPC inter-nœuds
- Pas de `electionLoop` × 16 partitions
- Pas de `partitionLeaders` / `partitionReplicas` maps à synchroniser
- La notion de quorum (⌊N/2⌋+1) remplace entièrement `Q1_RF`

### Ce que Raft apporte nativement

- **Élection** : algorithme Raft, pas de `tryAcquireLeadership` / etcd transaction
- **Réplication** : le log Raft est le mécanisme de réplication — `HttpReplicator` disparaît
- **Rejeu au redémarrage** : un nœud redémarré rejoue le log Raft — `CatchupManager` disparaît
- **Anti split-brain** : garanti par Raft (quorum obligatoire pour écrire)

---

## Inventaire des fichiers

### Supprimés

| Fichier | Raison |
|---|---|
| `q1-cluster/.../EtcdCluster.java` | Remplacé par `RatisCluster` |
| `q1-cluster/.../HttpReplicator.java` | Le log Raft remplace le fan-out HTTP |
| `q1-cluster/.../CatchupManager.java` | Ratis gère le rejeu et la synchronisation |
| `q1-cluster/.../Replicator.java` | Interface plus nécessaire |
| `q1-api/.../handler/SyncHandler.java` | L'endpoint `/internal/v1/sync/` disparaît |

### Créés

| Fichier | Rôle |
|---|---|
| `q1-cluster/.../RatisCommand.java` | Value object + encodage binaire des commandes |
| `q1-cluster/.../Q1StateMachine.java` | Machine d'état Raft : applique les commandes au `StorageEngine` |
| `q1-cluster/.../RatisCluster.java` | Remplace `EtcdCluster` : cycle de vie du `RaftServer`, API de routage |

### Modifiés

| Fichier | Changement |
|---|---|
| `q1-cluster/.../ClusterConfig.java` | `etcdEndpoints` + `leaseTtlSeconds` → `peers` + `raftPort` |
| `q1-cluster/.../PartitionRouter.java` | Dépend de `RatisCluster` au lieu de `EtcdCluster` |
| `q1-api/.../handler/ObjectHandler.java` | Suppression de `Replicator` et `isReplicaWrite` |
| `q1-api/.../S3Router.java` | Suppression du header `X-Q1-Replica-Write`, constructeur EC adapté |
| `q1-api/.../Q1Server.java` | `Q1_ETCD`/`Q1_RF` → `Q1_PEERS`/`Q1_RAFT_PORT`, plus de `CatchupManager` |
| `pom.xml` | `jetcd-core` → `ratis-server` + `ratis-grpc` |
| `q1-cluster/pom.xml` | Idem |
| `q1-tests/.../ClusterIT.java` | Testcontainers etcd → nœuds Ratis in-process |
| `q1-ansible/roles/q1/templates/q1.env.j2` | `Q1_ETCD`/`Q1_RF` → `Q1_PEERS`/`Q1_RAFT_PORT` |
| `q1-ansible/site.yml` | Suppression du rôle `etcd` |

---

## Nouveaux fichiers en détail

### `RatisCommand.java`

Encode toutes les mutations qui transitent par le log Raft.

```
Format binaire :
[1B]  type  0x01=PUT  0x02=DELETE  0x03=CREATE_BUCKET  0x04=DELETE_BUCKET
[2B]  longueur du bucket (unsigned short)
[N B] bucket (UTF-8)
[2B]  longueur de la clé (unsigned short) — absent pour CREATE/DELETE_BUCKET
[N B] clé (UTF-8)                         — absent pour CREATE/DELETE_BUCKET
[8B]  longueur de la valeur (long)         — PUT uniquement
[N B] valeur                               — PUT uniquement
```

API :
```java
record RatisCommand(Type type, String bucket, String key, byte[] value) {
    enum Type { PUT, DELETE, CREATE_BUCKET, DELETE_BUCKET }

    ByteString encode();
    static RatisCommand decode(ByteString bytes);
}
```

### `Q1StateMachine.java`

```java
public final class Q1StateMachine extends BaseStateMachine {

    private StorageEngine engine; // injecté après construction via setEngine()

    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
        RatisCommand cmd = RatisCommand.decode(
                trx.getStateMachineLogEntry().getLogData());
        switch (cmd.type()) {
            case PUT           -> engine.put(cmd.bucket(), cmd.key(), cmd.value());
            case DELETE        -> engine.delete(cmd.bucket(), cmd.key());
            case CREATE_BUCKET -> engine.createBucket(cmd.bucket());
            case DELETE_BUCKET -> engine.deleteBucket(cmd.bucket());
        }
        updateLastAppliedTermIndex(trx.getLogEntry().getTerm(),
                                   trx.getLogEntry().getIndex());
        return CompletableFuture.completedFuture(Message.EMPTY);
    }
}
```

Pas de snapshot dans la version initiale : le log Raft est rejoué intégralement au
redémarrage. Le support des snapshots est reporté (voir section "Hors scope").

### `RatisCluster.java`

```java
public final class RatisCluster implements Closeable {

    // Construit et démarre le RaftServer + RaftClient
    public void start() throws IOException;

    // True si ce nœud est le leader Raft courant
    public boolean isLocalLeader();

    // URL HTTP S3 du leader courant (absent = ce nœud est le leader)
    public Optional<String> leaderHttpBaseUrl();

    // Soumet une commande au log Raft et bloque jusqu'au commit
    // Lève IOException si le nœud n'est pas le leader ou si le quorum échoue
    public void submit(RatisCommand cmd) throws IOException;

    // Liste des nœuds configurés (utile pour computeShardPlacement EC)
    public Collection<NodeId> activeNodes();

    @Override public void close();
}
```

Détails d'implémentation :
- `RaftGroupId` : UUID fixe, identique sur tous les nœuds (mis dans la config)
- `RaftPeerId` : `nodeId` (string)
- `RaftPeer` : `(id, host:raftPort)` — protocole gRPC
- Le `RaftClient` est recréé si la connexion au leader est perdue
- Port Raft distinct du port S3 HTTP (`Q1_RAFT_PORT`, défaut `6000`)
- Répertoire de log Raft : `$Q1_DATA_DIR/raft/` (séparé des segments)

---

## Modifications des fichiers existants

### `ClusterConfig.java`

```java
// Avant
public record ClusterConfig(
    NodeId self, List<String> etcdEndpoints, int replicationFactor,
    int numPartitions, int leaseTtlSeconds, EcConfig ecConfig)

// Après
public record ClusterConfig(
    NodeId self, List<NodeId> peers, int numPartitions,
    int raftPort, EcConfig ecConfig)
```

`replicationFactor` et `leaseTtlSeconds` sont supprimés.
`etcdEndpoints` (strings) devient `peers` (liste de `NodeId` avec raftPort).

### `PartitionRouter.java`

```java
// Avant : délègue à EtcdCluster (élection par partition)
public PartitionRouter(EtcdCluster cluster)

// Après : délègue à RatisCluster (leader global)
public PartitionRouter(RatisCluster cluster)

// isLocalLeader(bucket, key) → cluster.isLocalLeader()  (global, plus de partitionId)
// leaderBaseUrl(bucket, key) → cluster.leaderHttpBaseUrl()
// followersFor(bucket, key)  → SUPPRIMÉ (Raft gère la réplication)
```

### `ObjectHandler.java`

```java
// Avant : constructeur cluster avec Replicator
public ObjectHandler(StorageEngine engine, Replicator replicator)

// Après : constructeur cluster avec RatisCluster
public ObjectHandler(StorageEngine engine, RatisCluster cluster)
```

**PUT (avant) :**
```java
engine.put(bucket, key, value);                      // écriture locale
if (!isReplicaWrite && replicator != null)
    replicator.replicateWrite(bucket, key, value);   // fan-out HTTP
```

**PUT (après) :**
```java
if (cluster != null) {
    cluster.submit(RatisCommand.put(bucket, key, value)); // Raft commit (bloquant)
} else {
    engine.put(bucket, key, value);                       // standalone
}
```

La signature `put(exchange, bucket, key, isReplicaWrite)` devient
`put(exchange, bucket, key)` — le paramètre `isReplicaWrite` disparaît partout.

Idem pour `delete()`.

### `S3Router.java`

- Suppression de la vérification du header `X-Q1-Replica-Write` (`isReplicaWrite`)
- Le constructeur EC remplace `EtcdCluster` par `RatisCluster`
- La logique de routage (307) reste identique : utilise `router.leaderBaseUrl()`

### `Q1Server.java`

**Séquence de démarrage (cluster mode) :**

```
// Avant
EtcdCluster cluster = new EtcdCluster(cfg);
cluster.start();                              // lease, élections, watches
new CatchupManager(cluster).catchUp(engine); // sync des partitions en retard
server.start();                               // HTTP port ouvert

// Après
RatisCluster cluster = new RatisCluster(cfg, stateMachine);
cluster.start();    // RaftServer démarre, élection automatique
server.start();     // HTTP port ouvert dès que le serveur Raft est prêt
                    // (Ratis bloque implicitement jusqu'à ce qu'un leader existe)
```

---

## Variables d'environnement

| Variable | Avant | Après |
|---|---|---|
| `Q1_ETCD` | `http://etcd-1:2379,…` | **Supprimée** |
| `Q1_RF` | `3` | **Supprimée** (quorum = ⌊N/2⌋+1) |
| `Q1_PEERS` | — | `node1\|host1\|9000\|6000,node2\|host2\|9000\|6000,…` |
| `Q1_RAFT_PORT` | — | `6000` (défaut) |
| `Q1_NODE_ID` | inchangé | inchangé |
| `Q1_HOST` | inchangé | inchangé |
| `Q1_PORT` | inchangé | inchangé |
| `Q1_DATA_DIR` | inchangé | inchangé |
| `Q1_PARTITIONS` | inchangé | inchangé |

Format de `Q1_PEERS` : `id|host|httpPort|raftPort` séparé par virgules.
Exemple : `node1|10.0.0.1|9000|6000,node2|10.0.0.2|9000|6000,node3|10.0.0.3|9000|6000`

Le nœud courant doit apparaître dans `Q1_PEERS` (identifié par `Q1_NODE_ID`).

---

## Maven

### `pom.xml` (parent)

```xml
<!-- Supprimer -->
<jetcd.version>0.7.7</jetcd.version>

<!-- Ajouter -->
<ratis.version>3.1.0</ratis.version>
```

```xml
<!-- Supprimer du dependencyManagement -->
<dependency>
    <groupId>io.etcd</groupId>
    <artifactId>jetcd-core</artifactId>
    <version>${jetcd.version}</version>
</dependency>

<!-- Ajouter au dependencyManagement -->
<dependency>
    <groupId>org.apache.ratis</groupId>
    <artifactId>ratis-server</artifactId>
    <version>${ratis.version}</version>
</dependency>
<dependency>
    <groupId>org.apache.ratis</groupId>
    <artifactId>ratis-grpc</artifactId>
    <version>${ratis.version}</version>
</dependency>
```

### `q1-cluster/pom.xml`

```xml
<!-- Supprimer -->
<dependency>
    <groupId>io.etcd</groupId>
    <artifactId>jetcd-core</artifactId>
</dependency>

<!-- Ajouter -->
<dependency>
    <groupId>org.apache.ratis</groupId>
    <artifactId>ratis-server</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.ratis</groupId>
    <artifactId>ratis-grpc</artifactId>
</dependency>
```

---

## Tests

### Tests unitaires (`q1-core`) — inchangés

Les 27 tests `SegmentTest`, `PartitionTest`, `StorageEngineSyncTest` ne touchent pas au
cluster et continuent à passer sans modification.

### `ClusterIT.java`

Remplacement de Testcontainers etcd par des nœuds Ratis in-process :

```java
// Avant
@Container
static GenericContainer<?> etcd = new GenericContainer<>("gcr.io/etcd-development/etcd:v3.5.17")

// Après
// Pas de container Docker — 2 RaftServer in-process sur des ports temporaires
// RatisTestUtils.startCluster(2) → List<RatisCluster> avec ports aléatoires
```

Les 4 scénarios existants sont conservés à l'identique :
- `replicationOnWrite` : PUT sur nœud A, GET sur nœud B → même données
- `nonLeaderRedirects` : PUT sur le follower → 307 vers le leader
- `deleteReplicatedToFollower` : DELETE répliqué, les deux nœuds retournent 404
- `headOnBothNodes` : HEAD retourne 200 sur les deux nœuds après PUT

Avantage : plus de dépendance Docker pour les tests cluster.

### `ErasureCoderTest.java` — inchangé

---

## Ansible

### `site.yml`

```yaml
# Avant
roles: [java, etcd, q1]

# Après
roles: [java, q1]
```

Le répertoire `q1-ansible/roles/etcd/` est supprimé.

### `q1-ansible/roles/q1/templates/q1.env.j2`

```bash
# Avant
Q1_ETCD={{ q1_etcd_endpoints }}
Q1_RF={{ q1_rf }}

# Après
Q1_PEERS={{ q1_peers }}   # ex: node1|10.0.0.1|9000|6000,node2|…,node3|…
Q1_RAFT_PORT={{ q1_raft_port }}
```

### `q1-ansible/group_vars/all.yml`

```yaml
# Supprimer
q1_rf: 3

# Ajouter
q1_raft_port: 6000
q1_peers: "{{ groups['q1'] | map('extract', hostvars, ['q1_node_id', 'ansible_host']) | ... }}"
```

La valeur de `q1_peers` est construite dynamiquement depuis l'inventaire Ansible
(template Jinja2 sur le groupe `q1`).

---

## Plan d'implémentation (ordre recommandé)

### Étape 1 — Maven
- `pom.xml` : remplacer `jetcd` par `ratis-server` + `ratis-grpc`
- `q1-cluster/pom.xml` : idem

### Étape 2 — `RatisCommand`
- Création de `RatisCommand.java`
- Tests unitaires d'encodage/décodage (round-trip PUT, DELETE, CREATE_BUCKET)

### Étape 3 — `Q1StateMachine`
- Création de `Q1StateMachine.java` (extends `BaseStateMachine`)
- Test unitaire : appliquer une séquence de commandes → vérifier l'état du StorageEngine

### Étape 4 — `RatisCluster`
- Création de `RatisCluster.java`
- Suppression de `EtcdCluster.java`, `HttpReplicator.java`, `CatchupManager.java`, `Replicator.java`
- Mise à jour de `ClusterConfig.java`

### Étape 5 — `PartitionRouter`
- Remplacement de la dépendance `EtcdCluster` par `RatisCluster`

### Étape 6 — `ObjectHandler`
- Suppression de `Replicator` et `isReplicaWrite`
- `put()` / `delete()` → `cluster.submit()`

### Étape 7 — `S3Router` + `Q1Server`
- Suppression du header `X-Q1-Replica-Write`
- Suppression de `SyncHandler`
- Mise à jour du `main()` : `Q1_PEERS` / `Q1_RAFT_PORT`, plus de `CatchupManager`

### Étape 8 — `ClusterIT`
- Suppression de la dépendance Testcontainers etcd
- Remplacement par des nœuds Ratis in-process

### Étape 9 — Ansible
- Suppression du rôle `etcd` et de `site.yml`
- Mise à jour du template `q1.env.j2` et `group_vars/all.yml`

### Étape 10 — Documentation
- Mise à jour de `CLAUDE.md`, `q1-cluster/CLAUDE.md`, `q1-api/CLAUDE.md`

---

## Hors scope (version initiale)

### Snapshots Raft

Sans snapshot, Ratis rejoue le log depuis le début au redémarrage d'un nœud. Pour un
cluster en production avec des mois de données, cela serait prohibitif. La solution est
d'implémenter `StateMachine.takeSnapshot()` (sérialise l'état courant des segments) et
`loadSnapshot()` (restaure cet état sur un nouveau nœud), déclenchés automatiquement par
Ratis selon un seuil de taille de log configurable.

Ce travail est reporté à une PR dédiée.

### Reconfiguration dynamique des membres

Ratis supporte l'ajout/suppression de nœuds à chaud via `setConfiguration()`. Non
implémenté dans cette version : le cluster est statique (membres connus au démarrage).

### Bucket replication via Raft

Les opérations `createBucket` / `deleteBucket` sont aujourd'hui non répliquées.
Avec Ratis elles peuvent passer par le log (`CREATE_BUCKET` / `DELETE_BUCKET` commands)
naturellement — c'est inclus dans `RatisCommand` mais la wire-up dans `BucketHandler`
est à faire séparément.

---

## Résumé de l'impact

| Dimension | Avant (etcd) | Après (Ratis) |
|---|---|---|
| Infrastructure externe | etcd cluster (3 nœuds) | Aucune |
| Déploiement | Ansible : java + etcd + q1 | Ansible : java + q1 |
| Réplication | HTTP fan-out (`HttpReplicator`) | Log Raft (gRPC) |
| Élection | 16 élections etcd indépendantes | 1 élection Raft globale |
| Catchup au démarrage | `CatchupManager` HTTP | Rejoue log Raft automatiquement |
| Config cluster | `Q1_ETCD` + `Q1_RF` | `Q1_PEERS` + `Q1_RAFT_PORT` |
| Dépendance Maven | `jetcd-core` (gRPC+protobuf) | `ratis-server` + `ratis-grpc` |
| Tests cluster | Testcontainers Docker | In-process (pas de Docker) |
| Lignes supprimées (cluster) | ~1 100 (EtcdCluster + HttpReplicator + CatchupManager) | — |
| Lignes ajoutées | ~600 (RatisCluster + Q1StateMachine + RatisCommand) | — |
