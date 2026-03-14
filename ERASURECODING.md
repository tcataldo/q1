# Erasure Coding dans Q1

## Motivation

Avec la réplication pure (RF=3), overhead stockage ×3. Reed-Solomon :

| Config | Nœuds | Pannes tolérées | Overhead |
|---|---|---|---|
| RF=3 (réplication) | 3 | 1 | ×3.0 |
| k=2, m=1 | 3 | 1 | ×1.5 |
| k=3, m=2 | 5 | 2 | ×1.67 |
| k=4, m=2 | 6 | 2 | ×1.5 |

Le codec RS est vendorisé dans `io.q1.cluster.erasure.{Galois,Matrix,ReedSolomon}`,
compatible Backblaze JavaReedSolomon, sans dépendance Maven externe.

---

## Configuration

| Variable | Défaut | Description |
|---|---|---|
| `Q1_EC_K` | `0` (désactivé) | Nombre de data shards |
| `Q1_EC_M` | `2` | Nombre de parity shards |
| `Q1_EC_MIN_SIZE` | `0` | Objets sous ce seuil → écriture locale plain (sans EC) |
| `Q1_REPAIR_INTERVAL_S` | `60` | Intervalle du scanner de réparation (secondes) |
| `Q1_REPAIR_BATCH_SIZE` | `200` | Clés inspectées par passe de réparation |

**Prérequis :** `k + m` nœuds actifs au démarrage (`isClusterReady()` bloque sinon).

---

## Architecture

```
Client PUT → n'importe quel nœud (EcObjectHandler)
  1. encode(body) → k data shards + m parity shards
  2. buildPayload : prepend 8B originalSize (big-endian long)
  3. fan-out de chaque payload vers le nœud ring correspondant
     → local : engine.put("__q1_ec_shards__", shardKey, payload)
     → distant : httpShardClient.putShard(node, idx, bucket, key, payload)
  4. attend tous les ACKs (timeout 5s)
  5. 200 OK — aucune écriture dans le log Raft

Client GET → n'importe quel nœud (EcObjectHandler)
  1. computeShardPlacement (ring déterministe, pas de lookup Raft)
  2. fetch k+m payloads en parallèle (null si absent/erreur)
  3. si tous null → fallback engine.get() (objet pré-EC en plain replication)
  4. si < k payloads présents → 503 ServiceUnavailable
  5. ReedSolomon.decodeMissing() sur les shards présents
  6. tronquer au originalSize extrait du header → retourner
```

---

## Format du shard payload

Chaque shard stocké est auto-descriptif :

```
[8B]  originalSize (big-endian long)
[…]   données shard RS (zero-paddé à shardSize)
```

Ni etcd ni Raft ne sont consultés pour résoudre le schéma EC.
La taille originale est dans chaque shard, le placement est calculé localement.

---

## Placement déterministe

`RatisCluster.computeShardPlacement(bucket, key)` :

1. Trier les peers par `nodeId` (lexicographique) → ring
2. `anchor = Math.abs((bucket + '\0' + key).hashCode()) % N`
3. Sélectionner `k+m` nœuds consécutifs depuis `anchor` (wrapping)

Résultat stable pour une topologie fixe. Distribue équitablement les shards
sur l'ensemble du cluster. Tous les nœuds calculent le même placement
indépendamment, sans coordination.

---

## Stockage des shards

```
Bucket interne : __q1_ec_shards__    (jamais dans BucketRegistry)
Clé            : {userBucket}/{objectKey}/{shardIndex:02d}
Clé interne    : __q1_ec_shards__\x00{userBucket}/{objectKey}/{shardIndex:02d}
```

Exemple : `s3://emails/msg-42`, shard 1 :
```
__q1_ec_shards__\x00emails/msg-42/01
```

Réutilise l'infrastructure segment/RocksDB existante sans changement de format.

---

## Scanner de réparation (EcRepairScanner)

Scanner background dans `q1-api`, lancé au démarrage si EC activé.

**Algorithme :**
- Round-robin sur les 16 partitions
- Pour chaque clé de `__q1_ec_shards__` : extrait le bucket/key/shardIdx
- Vérifie l'existence de chaque shard via HEAD sur le nœud ring correspondant
- Si un shard manque : reconstruit depuis les k présents → pousse sur le nœud cible
- Checkpoint persisté en RocksDB (clé `0x00rchk`) pour reprise après restart

**Variables :**
- `Q1_REPAIR_INTERVAL_S` (défaut 60s) — délai entre passes
- `Q1_REPAIR_BATCH_SIZE` (défaut 200) — clés par batch

> **Limitation :** testé uniquement en happy-path. Voir NEXT.md pour les tests
> de panne réelle.

---

## Listing des objets EC

`StorageEngine.listPaginated()` scanne `__q1_ec_shards__` avec le préfixe
`{bucket}/` et extrait les clés d'objets uniques (strip `{bucket}/` + `/{shardIdx}`).
Les objets EC apparaissent dans les listings S3 normaux sans configuration.

---

## Compatibilité

Les objets écrits avant l'activation d'EC n'ont aucun shard sur aucun nœud.
`EcObjectHandler` détecte ça (tous les fetch retournent null/404) et bascule
sur `StorageEngine.get()` en fallback. Transparent pour le client.

---

## Limitations

- **Cluster statique** : `k+m` doit être ≤ nœuds actifs au démarrage.
  Re-encodage élastique (ajout/suppression de nœud) non implémenté.
- **Objets < `Q1_EC_MIN_SIZE`** : écriture locale plain, sans EC ni réplication.
- **Size en listing** : toujours 0 pour les objets EC. La taille originale est
  dans le header du shard mais n'est pas extraite au listing (trop coûteux).
  Fix prévu via metadata persistante (voir NEXT.md).
- **Repair scanner** : non validé sur panne de nœud permanente.
