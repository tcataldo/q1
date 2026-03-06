# q1-core — Storage Engine

Module autonome : pas de dépendances internes Q1.

## Responsabilités

Tout ce qui touche au stockage physique des données :
- Écriture / lecture / suppression d'objets
- Fichiers segment append-only avec index en mémoire
- Abstraction I/O (`FileIO` / `FileIOFactory`, NIO par défaut)
- API de synchronisation pour le catchup cluster

## Format des segments

```
[4B] MAGIC    0x51310001
[1B] FLAGS    0x00=DATA | 0x01=TOMBSTONE
[2B] KEY_LEN  unsigned short
[8B] VAL_LEN  long (0 pour les tombstones)
[4B] CRC32    couvre flags + key bytes + value bytes
[KEY_LEN B]   clé (UTF-8)
[VAL_LEN B]   valeur (absent pour tombstones)
```

- Rollover à **1 GiB** (constante `Partition.MAX_SEGMENT_SIZE`)
- Nommage : `segment-0000000001.q1`, `segment-0000000002.q1`, …

## Clé interne

`bucket + '\x00' + objectKey` — le null-byte est le séparateur (impossible dans une clé S3 valide).

## Partitionnement

`Math.abs(fullKey.hashCode()) % numPartitions` — cohérent avec `PartitionRouter` dans q1-cluster.

## Fichiers clés

| Fichier | Rôle |
|---|---|
| `StorageEngine.java` | Point d'entrée : route les ops vers la bonne partition |
| `Partition.java` | Gère un répertoire de segments + l'index en mémoire |
| `Segment.java` | Lecture/écriture d'un fichier segment ; `scanStream()` pour le réseau |
| `SegmentIndex.java` | `ConcurrentHashMap<String, Entry(segId, valueOffset, valueLen)>` |
| `BucketRegistry.java` | Registre des buckets, persisté dans `buckets.properties` |
| `SyncState.java` | `record(partitionId, segmentId, byteOffset)` — position de replication |
| `io/FileIO.java` | Interface I/O (read, write, size, force, close) |
| `io/NioFileIOFactory.java` | Implémentation NIO par défaut |

## Tests unitaires

```bash
mvn test -pl q1-core
# 27 tests : SegmentTest (9), PartitionTest (13), StorageEngineSyncTest (5)
```

## TODO

- [ ] Vérification du CRC32 à la lecture (le header le stocke, on ne le vérifie pas encore)
- [ ] Compaction : supprimer les tombstones quand le ratio de suppressions dépasse un seuil configurable
- [ ] Métriques : taux de compaction, taille des segments, nombre de clés par partition
- [ ] Pagination `listObjectsV2` (continuation token)
