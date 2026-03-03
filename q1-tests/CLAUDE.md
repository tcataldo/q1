# q1-tests — Tests d'intégration

Module test uniquement — pas de code de production.

## Lancer les tests

```bash
# Tout (unit + IT) depuis la racine
mvn verify -pl q1-core,q1-cluster,q1-api,q1-tests --also-make

# Uniquement les tests unitaires q1-core
mvn test -pl q1-core

# Uniquement S3CompatibilityIT
mvn verify -pl q1-core,q1-cluster,q1-api,q1-tests --also-make -Dit.test="S3CompatibilityIT"

# Uniquement ClusterIT
mvn verify -pl q1-core,q1-cluster,q1-api,q1-tests --also-make -Dit.test="ClusterIT"
```

## Comptage actuel

| Suite | Classe | Tests |
|---|---|---|
| Unit | `SegmentTest` | 9 |
| Unit | `PartitionTest` | 13 |
| Unit | `StorageEngineSyncTest` | 5 |
| IT | `S3CompatibilityIT` | 12 |
| IT | `ClusterIT` | 4 |
| **Total** | | **43** |

## S3CompatibilityIT

- Démarre un `Q1Server` **in-process** en mode standalone sur le port 19000
- Pilotée par l'**AWS SDK v2** (`software.amazon.awssdk:s3`)
- `chunkedEncodingEnabled(false)` obligatoire (sinon le SDK envoie `aws-chunked`)
- Les tests 404 génèrent des traces de stack dans les logs — c'est normal (le SDK lève une exception)

Opérations couvertes : createBucket (idempotence), PUT/GET objet, GET binaire 128 KiB,
HEAD exist/missing, DELETE, GET missing, listObjectsV2, clés avec `/`, overwrite, objet vide.

## ClusterIT

- 2 nœuds in-process (ports 19200 et 19201)
- **etcd via Testcontainers** : `gcr.io/etcd-development/etcd:v3.5.17`
  - Commande : `etcd --listen-client-urls=http://0.0.0.0:2379 --advertise-client-urls=http://0.0.0.0:2379`
  - (bitnami/etcd n'est pas disponible dans cet environnement)
- 4 partitions, RF=2, lease TTL=5s
- Attente de 4s pour la stabilisation des élections avant démarrage des nœuds

Scénarios couverts :
- `replicationOnWrite` — PUT sur un nœud, GET sur l'autre → mêmes données
- `nonLeaderRedirects` — PUT sur non-leader → 307 avec header Location
- `deleteReplicatedToFollower` — DELETE répliqué, les 2 nœuds retournent 404
- `headOnBothNodes` — HEAD retourne 200 sur les 2 nœuds après PUT

**Point d'attention** : les buckets doivent être créés sur **chaque nœud séparément**
(les opérations bucket ne sont pas répliquées). `createBucket()` tourne sur PORT0 et PORT1.

## TODO

- [ ] Test de compaction : vérifier que les tombstones sont bien nettoyés
- [ ] Test d'élasticité : ajout d'un 3e nœud à chaud
- [ ] Test de tolérance aux pannes : arrêt du leader en cours de réplication
- [ ] Test de catchup : nœud qui redémarre en retard et se resynchronise
- [ ] Benchmark : latence P50/P99 sur PUT/GET pour des objets de 1KB, 32KB, 128KB
