# Usage d'etcd dans Q1

etcd est un store clé-valeur distribué avec consensus fort (Raft). Q1 l'utilise
exclusivement comme **coordinateur de cluster** — jamais comme store de données
utilisateur. Toutes les données S3 restent dans les segments append-only.

---

## Ce qu'etcd fait aujourd'hui

### 1. Membership — `/q1/nodes/{nodeId}`

Chaque nœud écrit sa clé de présence au démarrage, **liée à son lease** (TTL 10s,
renouvelé par keepalive). Si le nœud disparaît, le lease expire et la clé est
automatiquement supprimée par etcd. C'est la liste canonique des nœuds actifs.

```
/q1/nodes/node0  →  "node0:localhost:9000"   (éphémère)
/q1/nodes/node1  →  "node1:localhost:9001"   (éphémère)
```

Tous les nœuds regardent ce préfixe avec un watch. Dès qu'un nœud arrive ou part,
chacun met à jour sa map `activeNodes` locale.

### 2. Élection de leader par partition — `/q1/partitions/{id}/leader`

Transaction conditionnelle : `PUT IF VERSION == 0` (la clé n'existe pas encore).
Un seul nœud peut écrire, les autres attendent la disparition de la clé pour recampaigner.
La clé est éphémère (liée au lease du leader) : si le leader tombe, etcd la supprime
automatiquement et une nouvelle élection démarre.

```
/q1/partitions/0/leader  →  "node0:localhost:9000"   (éphémère, lease node0)
/q1/partitions/1/leader  →  "node1:localhost:9001"   (éphémère, lease node1)
```

Garantie : **au plus un leader par partition à tout instant** (split-brain impossible).

### 3. Assignation des replicas — `/q1/partitions/{id}/replicas`

Après avoir gagné l'élection, le leader calcule ses RF-1 followers par un anneau sur
`activeNodes` triés par `nodeId`. Il écrit la liste, aussi liée à son lease.

```
/q1/partitions/0/replicas  →  "node1:localhost:9001"   (éphémère, lease node0)
```

Utilisation :
- `HttpReplicator` sait vers qui fan-outer les écritures synchrones
- `CatchupManager` sait quelles partitions synchroniser au démarrage
- `rebalance()` redistribue quand un nœud rejoint

---

## Critique du design EC initial — et pourquoi le design simplifié est meilleur

### Le design initial (supprimé)

> Stocker un morceau avec la même clef sur chaque node, récupérer les blobs sur les
> nœuds vivants et reconstituer via EC.

C'est plus simple et **c'est correct**. Le design initial utilisait etcd pour stocker
des métadonnées EC par objet (`EcMetadataStore`). Voici pourquoi c'était inutile.

### Ce que le design initial ajoutait inutilement

L'entrée etcd pour l'EC contient trois types d'information :

| Info | Nécessaire via etcd ? | Alternative |
|---|---|---|
| Quel shard est sur quel nœud | **Non** | L'anneau déterministe donne la même réponse |
| Taille originale (pour enlever le padding) | **Non** | 8 octets dans l'en-tête du shard |
| k et m | **Non** | Config globale du cluster (fixe au démarrage) |

Autrement dit : **l'entrée etcd est redondante**. Elle encode des informations que
l'on peut soit calculer (anneau), soit embarquer directement dans le payload.

### Conséquences du design actuel

1. **etcd est sur le chemin critique de chaque GET et PUT EC** — un round-trip réseau
   supplémentaire, et si etcd est surchargé ou temporairement indisponible, les lectures
   d'objets EC échouent alors que les nœuds de données sont parfaitement sains.

2. **Risque de fuite de métadonnées** — un crash entre l'écriture des shards et l'écriture
   etcd laisse des shards orphelins visibles (pas de metadata → objet invisible). Un crash
   après l'écriture etcd mais avant tous les shards rend l'objet présent mais illisible. Les
   deux sont gérables mais ajoutent de la complexité.

3. **Overhead etcd** — pour 60M emails, 60M clés etcd supplémentaires. etcd n'est pas
   conçu pour des dizaines de millions de clés persistantes.

### Le design simplifié (sans etcd sur le chemin des données)

Chaque shard est stocké avec un **micro en-tête de 9 octets** :

```
[1B]  shard index (0..k+m-1)
[8B]  taille originale de l'objet (big-endian long)
[...] données du shard (padding zéro si nécessaire)
```

k et m sont connus globalement (config `Q1_EC_K` / `Q1_EC_M`).

**Write** (identique à maintenant, sauf la dernière étape) :
1. `computeShardPlacement(bucket, key)` → anneau → k+m nœuds
2. Encoder → k+m shards
3. Fan-out : `PUT /internal/v1/shard/{bucket}/{key}` sur chaque nœud cible
   (le nœud sait son index grâce à sa position dans le placement)
4. **Pas d'écriture etcd.** C'est tout.

**Read** (depuis n'importe quel nœud) :
1. `computeShardPlacement(bucket, key)` → même anneau → mêmes k+m nœuds
2. `GET /internal/v1/shard/{bucket}/{key}` sur les k+m nœuds en parallèle
3. Chaque réponse contient l'en-tête → shard index + taille originale
4. Reconstituer avec k shards présents minimum
5. **Pas de lecture etcd.** C'est tout.

L'anneau étant **déterministe et calculable indépendamment par tout nœud** à partir de la
liste des nœuds actifs (lue une seule fois depuis etcd au démarrage, puis maintenue par
watch), le chemin de données n'a plus besoin d'etcd.

### Comparaison des deux designs

| | Design actuel (etcd metadata) | Design simplifié (en-tête shard) |
|---|---|---|
| Lecture etcd sur GET | Oui (1 round-trip) | Non |
| Écriture etcd sur PUT | Oui (commit point) | Non |
| Résilience si etcd ↓ | Lectures/écritures EC échouent | Lectures/écritures EC continuent |
| Shard orphelin après crash | Possible (nettoyage manuel) | Impossible (pas de metadata à nettoyer) |
| Overhead etcd | O(objets EC) — potentiellement 60M clés | O(1) — seules elections/nodes/replicas |
| Changement de k/m | Par objet (chaque metadata a k et m) | Config globale uniquement |
| Complexité code | EcMetadataStore + 60M clés etcd | En-tête 9B dans ShardHandler |

### Le seul vrai trade-off

Le design simplifié perd la **flexibilité de changer k et m à chaud** pour des objets
individuels. Tous les objets utilisent les mêmes k/m. Mais l'utilisateur a
explicitement dit « nœuds statiques fixés à l'init » — cette contrainte rend le changement
de k/m de toute façon hors-scope pour l'instant.

### Conclusion

La règle est : etcd pour la **coordination** (qui est leader ? qui est vivant ?),
les segments pour les **données** (les shards eux-mêmes). `EcMetadataStore` et les
clés `/q1/ec-meta/` ont été supprimés. **Migration terminée.**

---

## Résumé du layout etcd post-migration

```
/q1/nodes/{nodeId}              →  "id:host:port"        éphémère, lease
/q1/partitions/{id}/leader      →  "id:host:port"        éphémère, lease
/q1/partitions/{id}/replicas    →  "id:host:port,…"      éphémère, lease
```

C'est tout. L'EC n'ajoute aucune clé persistante dans etcd.
