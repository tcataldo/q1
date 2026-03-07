# Réplication dans Q1

Ce document explique comment les écritures se propagent entre nœuds, ce que voit chaque
nœud en lecture, et comment le rattrapage au démarrage fonctionne.

---

## 1. Anatomie d'un cluster Q1

Chaque nœud possède **localement les N partitions** (16 par défaut). Il n'y a pas de
"partition qui appartient à un nœud" comme dans Kafka. La distinction se fait au niveau du
**leader par partition** :

```
Cluster 3 nœuds, 16 partitions, RF=2

          Partition 0   Partition 1   Partition 2   …
Node A      LEADER        follower      —
Node B      —             LEADER        LEADER
Node C      follower      —             follower    ← RF=2, C est replica assigné de P2
```

Le leader est élu via etcd (transaction conditionnelle `PUT IF VERSION == 0`). La clé
`/q1/partitions/{id}/leader` est éphémère : elle disparaît si le lease du nœud expire,
ce qui déclenche une nouvelle élection.

---

## 2. Assignation déterministe des replicas

### Problème résolu

Sans assignation explicite, le choix des RF-1 followers se ferait au moment de chaque
écriture depuis un `ConcurrentHashMap` à l'ordre non garanti. Résultat : le "même" follower
ne recevait pas nécessairement tous les writes de la même partition.

### Solution : anneau (ring) écrit dans etcd

Les nœuds actifs sont triés par `nodeId` pour former un anneau. Immédiatement après avoir
gagné le leadership d'une partition, le leader prend les RF-1 nœuds **immédiatement
suivants dans l'anneau** (en bouclant) et écrit la liste dans
`/q1/partitions/{id}/replicas` (lié à son lease).

```
Anneau trié : [node-A, node-B, node-C]

  node-A leads P → replica = node-B  (suivant dans l'anneau)
  node-B leads P → replica = node-C
  node-C leads P → replica = node-A  (boucle)
```

```
/q1/partitions/2/leader   → "node-B:10.0.0.2:9000"
/q1/partitions/2/replicas → "node-C:10.0.0.3:9000"
```

### Pourquoi l'anneau et pas "premier trié"

Avec "premier trié non-leader", `node-A` est toujours choisi en premier : il devient
replica de toutes les partitions qu'il ne dirige pas, pendant que `node-C` (dernier) n'est
jamais replica. Avec 3 nœuds RF=2 :

```
"Premier trié" (avant)        Anneau (maintenant)
  node-A  100 % des données     node-A  ~67 % des données
  node-B   67 % des données     node-B  ~67 % des données
  node-C   33 % des données     node-C  ~67 % des données
```

L'anneau garantit que chaque nœud stocke exactement `RF/N` de la totalité des données,
soit `2/3 ≈ 67 %` pour RF=2, N=3.

Tous les nœuds observent cette clé via un unique watch sur le préfixe `/q1/partitions/`
et maintiennent une map locale `partitionReplicas` à jour.

### Cycle de vie de l'assignation

La clé `/replicas` est liée au lease du leader — elle disparaît automatiquement si le
leader tombe. Le nouveau leader recalcule et réécrit l'assignation après son élection.
Pendant l'intervalle (quelques centaines de ms), `followersFor()` bascule sur un calcul
déterministe identique comme fallback.

---

## 3. Chemin d'écriture (PUT / DELETE)

```
Client
  │
  ▼
Node C  ──── pas le leader de la partition 2 ────►  307 Temporary Redirect
                                                         │ Location: http://NodeB/…
                                                         ▼
                                                     Node B (leader de P2)
                                                       │
                                                       ├─ 1. écrit localement (segment append)
                                                       │
                                                       ├─ 2. lit partitionReplicas[2] → [node-A]
                                                       │      fan-out parallèle, header X-Q1-Replica-Write: true
                                                       │
                                                       └─ 3. attend tous les ACKs (5 s max)
                                                              puis répond 200 au client
```

Points clés :

- **307 preserve la méthode** : un DELETE redirigé vers le leader reste un DELETE.
- **`X-Q1-Replica-Write: true`** : empêche le follower de re-répliquer à son tour.
- **Durabilité forte** : le client ne reçoit 200 que quand le leader _et_ les RF-1 followers
  ont confirmé. Si un follower timeout (5 s), le leader retourne 500 au client.
- **Liste stable** : `HttpReplicator` lit `partitionReplicas` qui est maintenu par le watch —
  pas de calcul à la volée, pas d'aléatoire.

---

## 4. Chemin de lecture (GET / HEAD)

```java
// S3Router.java — les lectures ne sont jamais redirigées
case "GET"  -> objectHandler.get(exchange, pp.bucket(), pp.key());
case "HEAD" -> objectHandler.head(exchange, pp.bucket(), pp.key());
```

**N'importe quel nœud répond localement**, sans consulter le leader. C'est une lecture en
**cohérence éventuelle** : un nœud qui n'est pas dans la liste des replicas assignés peut
retourner 404 (ou une version obsolète) pour un objet récemment écrit.

Pour des lectures fortes (lire depuis le leader), il suffirait d'appliquer le même 307 que
pour les writes — le mécanisme est déjà en place dans `S3Router`. Ce n'est pas activé par
défaut pour préserver les performances en lecture.

---

## 5. Catchup au démarrage

Quand un nœud redémarre, `CatchupManager.catchUp()` s'exécute **avant** l'ouverture du
port HTTP.

### Algorithme

```
Pour chaque partition p de 0 à N-1 :
  si je suis leader de p      → skip (j'ai déjà les données)
  si je ne suis pas replica   → skip (cette partition ne me concerne pas)
  sinon :
    state = engine.partitionSyncState(p)   // (segmentId, byteOffset) locaux
    GET /internal/v1/sync/{p}?segment={s}&offset={o}  → leader de p

    204 → déjà à jour, rien à faire
    200 → stream binaire de records depuis (s, o) → applySyncStream()
    erreur → loggé, le nœud démarre quand même
```

### Catchup incrémental

`SyncState(segmentId, byteOffset)` est la position de la dernière écriture appliquée.
Le follower envoie sa position locale, le leader répond uniquement avec le delta.
Un nœud qui avait 90 % des données ne retransmet pas les 90 %.

### Pourquoi les nœuds ne finissent pas tous avec tout

Avec l'assignation stable, un nœud **sait** pour quelles partitions il est replica.
Il ne synce que celles-là. Node C (assigné replica de P2 mais pas de P0 ni P1) ne
demande pas de sync à Node A ou Node B pour P0/P1 — ses données locales pour ces
partitions restent à leur état (potentiellement vide si jamais assigné).

```
Node C redémarre après un arrêt :

  isLocalLeader(P0) = false, isAssignedReplica(P0) = false → skip
  isLocalLeader(P1) = false, isAssignedReplica(P1) = false → skip
  isLocalLeader(P2) = false, isAssignedReplica(P2) = true  → sync depuis Node B
  …
```

### Séquence complète au démarrage en mode cluster

```
1. EtcdCluster.start()
     ├── grant lease (TTL 10 s, keepalive en fond)
     ├── registerSelf() → /q1/nodes/{nodeId}
     ├── loadExistingNodes() + loadExistingLeaders() + loadExistingReplicas()
     ├── watchNodes() + watchPartitions()   ← un seul watch sur /q1/partitions/ préfixe
     └── electionLoop(p) pour chaque partition (virtual threads)
           └── si élu : writeReplicas(p) → /q1/partitions/{p}/replicas

2. CatchupManager.catchUp(engine)
     ├── waitForElections() — jusqu'à 3 s pour leaders + assignations connues
     └── syncPartition() uniquement pour les partitions où isAssignedReplica() = true

3. Q1Server.start()  ← le port HTTP s'ouvre seulement ici
```

---

## 6. Scénario concret : 3 nœuds, RF=2

```
Nœuds actifs triés : [node-A, node-B, node-C]
Partition 2 leader : node-B  → replica = [node-A]  (RF-1=1, premier non-leader trié)

Écriture de "emails/msg-42" :
  → hache sur partition 2
  → node-C redirige en 307 vers node-B
  → node-B écrit localement
  → node-B lit partitionReplicas[2] = [node-A]
  → node-B réplique vers node-A
  → 200 OK au client

Lecture depuis node-C :
  → node-C lit son index local → 404
  → (l'objet existe sur B et A)

node-C redémarre :
  → loadExistingReplicas() : partitionReplicas[2] = [node-A] ← node-C n'y est pas
  → isAssignedReplica(2) = false → skip
  → node-C ne demande pas de sync pour P2

node-A redémarre (replica assigné de P2) :
  → isAssignedReplica(2) = true
  → SyncState locale = (1, 0) si jamais synchronisé, ou dernière position connue
  → GET /internal/v1/sync/2?segment=1&offset=0 → node-B
  → node-B stream les records depuis l'origine
  → node-A : "emails/msg-42" dans son index
  → node-A ouvre son port HTTP → GET retourne 200 OK
```

---

## 7. Limites actuelles et TODO

| Problème | Impact | Statut |
|---|---|---|
| Lectures non routées vers le leader | Un GET peut retourner 404 alors que l'objet existe | connu |
| Buckets non répliqués | `createBucket` / `deleteBucket` non fan-outé | TODO |
| Pas de détection du lag de réplication | Impossible de savoir si un follower est en retard | TODO |
| Pas de back-pressure | Si un follower est lent, le leader bloque pendant 5 s puis échoue | TODO |

### Rééquilibrage du leadership

Après que les élections se stabilisent, `rebalance()` s'assure qu'aucun nœud ne dirige
plus de `⌈P/N⌉` partitions :

1. Compter combien de partitions ce nœud dirige
2. Si `count > ⌈P/N⌉` : supprimer la clé leader etcd pour l'excédent
3. Un **cooldown** (2× lease TTL) empêche `electionLoop` de recandidater immédiatement
4. Les autres nœuds voient le DELETE via watch et gagnent ces partitions

`rebalance()` est appelé :
- Par `CatchupManager` après que les élections ont convergé (au démarrage)
- Par `watchNodes` quand un nouveau nœud rejoint (le cluster se ré-équilibre à chaud)

### Résumé : join/leave

Quand un nœud rejoint ou quitte, `watchNodes()` déclenche deux choses :
- `refreshReplicasForLeadPartitions()` — met à jour les listes de replicas
- `rebalance()` — rééquilibre les leaderships si nécessaire
