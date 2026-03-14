# Réplication dans Q1

> **Note :** Ce document décrit l'architecture Raft actuelle (post-etcd).
> L'ancienne architecture (etcd + HttpReplicator + CatchupManager + SyncHandler)
> a été supprimée. Voir git log pour l'historique.

---

## Mode réplication (EC désactivé)

### Chemin d'écriture

```
Client PUT /bucket/key
  │
  ├── nœud follower → proxyToLeader() (proxy HTTP transparent, body forwardé)
  │                                    le client voit directement la réponse du leader
  │
  └── leader → cluster.submit(RatisCommand.put(bucket, key, value))
                 → commit Raft (quorum gRPC : ⌊N/2⌋+1 ACKs requis)
                 → Q1StateMachine.applyTransaction() sur chaque nœud
                 → engine.put(bucket, key, value) sur chaque nœud localement
                 → 200 OK au client
```

**Garanties :**
- Le client reçoit 200 seulement après commit sur le quorum
- Pas de split-brain possible (Raft garantit l'unicité du leader)
- Le follower ne re-réplique pas (plus de header `X-Q1-Replica-Write`)

### Chemin de lecture

Les GET et HEAD sont servis localement par n'importe quel nœud sans consulter le leader.
Lecture **éventuellement cohérente** : un nœud en retard peut retourner 404 ou une
version obsolète sur une écriture très récente.

### Démarrage / catchup

Un nœud qui redémarre rejoue le log Raft depuis l'index appliqué lors du dernier arrêt.
Il n'y a pas de `CatchupManager` ni d'endpoint `/internal/v1/sync/` :
Ratis gère le rattrapage automatiquement via `InstallSnapshot` (non implémenté)
ou la retransmission des entrées de log manquantes.

> **Limitation :** Sans snapshot Raft implémenté, le replay repart de l'index 0
> à chaque redémarrage. Voir NEXT.md §snapshots.

---

## Mode EC (Q1_EC_K > 0)

Le log Raft n'est **pas** sur le chemin de données des objets. Voir ERASURECODING.md.

Les seuls `RatisCommand` émis en mode EC sont `CREATE_BUCKET` et `DELETE_BUCKET`.

---

## Variables d'environnement

| Variable | Description |
|---|---|
| `Q1_PEERS` | `id\|host\|httpPort\|raftPort` par nœud, virgule-séparé |
| `Q1_RAFT_PORT` | Port gRPC Raft (défaut 6000) |
| `Q1_NODE_ID` | Doit correspondre à un ID dans `Q1_PEERS` |

Absent `Q1_PEERS` → mode standalone, toutes les requêtes servies localement.
