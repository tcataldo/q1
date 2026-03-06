# Segment Compaction — Design Plan

## Problème

Les fichiers segments sont append-only. Deux événements produisent des "dead bytes" :

- **Tombstone** : un DELETE écrit un enregistrement TOMBSTONE mais laisse l'ancienne valeur DATA en place.
- **Overwrite** : un PUT sur une clé existante écrit un nouveau DATA record ; l'ancien reste dans son segment.

Sans compaction, les segments grossissent indéfiniment. Pour 60 M emails avec un taux de suppression réaliste de 20 %, des centaines de GiB de morts peuvent s'accumuler. La compaction récupère cet espace et améliore la localité des lectures.

---

## Objectifs

1. **Correction** — aucune perte de donnée vivante, pas de corruption d'index.
2. **Concurrence** — les lectures et écritures ne sont pas bloquées (sauf un bref instant au commit).
3. **Crash-safety** — un crash à n'importe quelle étape laisse le système dans un état cohérent, sans nécessiter de rescan complet au redémarrage.
4. **Minimalité** — compacter uniquement ce qui en vaut la peine (seuil configurable).

---

## Périmètre

- **Segments scellés uniquement** : le segment actif reçoit des écritures en cours ; on ne le touche jamais.
- **Une partition à la fois** : chaque `Partition` possède son propre compacteur, les 16 partitions peuvent tourner en parallèle.
- **Background thread** : la compaction tourne dans un `ScheduledExecutorService` virtuel-thread-compatible, sans bloquer le pool de requêtes.

---

## Décisions de design clés

### Calcul du ratio de données mortes

Pour chaque segment scellé, on calcule :

```
live_bytes(segmentId) = Σ entry.valueLength() pour toutes les entrées RocksDB
                        où entry.segmentId() == segmentId
dead_ratio = 1 - live_bytes / segment.size()
```

L'itération de l'index RocksDB se fait **hors du writeLock** (lecture seule, RocksDB est thread-safe). Le résultat est un snapshot approximatif — il sera re-vérifié au commit.

Cette itération coûte O(nb_clés_vivantes) avec ~20 bytes/clé lus depuis le cache RocksDB. Pour 60 M clés → ~1,2 Go de données d'index : quelques secondes acceptables pour un processus de fond.

### Snapshot des segments scellés (étape préliminaire, sous writeLock)

`candidates()` doit d'abord obtenir la liste des segments scellés de manière sûre.
`segments` (ArrayList) est muté par `maybeRoll()` sous `writeLock` ; lire sa taille sans lock
expose une race sur la référence `active`. La séquence correcte :

```java
List<Integer> sealedIds;
writeLock.lock();
try {
    // segments.getLast() == active → on l'exclut
    sealedIds = segments.subList(0, segments.size() - 1)
                        .stream().map(Segment::id).toList();
} finally {
    writeLock.unlock();  // libéré avant toute I/O
}
// Phase 1 entière hors lock : itération RocksDB + lectures segments
```

Le lock n'est tenu que le temps de copier une liste d'entiers (~µs) — aucune I/O sous lock.
Garantit que le segment actif n'est jamais inclus, même si un rollover survient pendant la passe.

### Sélection des candidats

Parmi les segments scellés snapshotés ci-dessus, un segment est candidat si
`dead_ratio > Q1_COMPACT_THRESHOLD` (défaut : **0,5**).
On trie les candidats par `dead_ratio` décroissant (les plus dégradés d'abord) et on limite à
`Q1_COMPACT_MAX_SEGMENTS` par passe (défaut : **4**).

### Algorithme de compaction d'un segment

```
Phase 1 — Scan (sans lock)
  1. Itérer RocksDB pour collecter tous les (key, Entry) pointant sur ce segment.
  2. Pour chaque clé vivante, lire la valeur depuis l'ancien segment.
  3. Écrire dans un fichier temporaire segment-NNNNNNNNNN.q1.compact
     (même format binaire que les segments normaux).
  4. Accumuler une liste de mouvements : (key, old_entry, new_entry).

Phase 2 — Commit (sous writeLock, durée ~ms)
  5. Pour chaque mouvement :
     - Vérifier que index.get(key).equals(old_entry) — la clé peut avoir été
       mise à jour ou supprimée entre la Phase 1 et ici.
     - Si valide : ajouter (key → new_entry) dans un WriteBatch RocksDB.
  6. Appliquer le WriteBatch atomiquement.
  7. Renommer segment-NNNNNNNNNN.q1.compact → segment-NNNNNNNNNN.q1
     (le nouveau ID est choisi avant la Phase 1).
  8. Marquer l'ancien segment comme mort :
     renommer l'ancien fichier en segment-NNNNNNNNNN.q1.dead
  9. Retirer l'ancien segment de `segments` et `byId`.
 10. Ajouter le nouveau segment à `segments` et `byId`.
```

### Crash safety

| Crash au moment | État sur disque | État au redémarrage |
|-----------------|-----------------|---------------------|
| Phase 1 (écriture .compact) | .compact partiel | `openSegments()` ignore `*.compact` → supprimé |
| Entre WriteBatch et rename (étape 7) | .compact complet, index à jour | rename manquant → .compact détecté, renommé |
| Entre rename et .dead (étape 8) | ancien .q1 + nouveau .q1 | index pointe sur nouveau ; ancien = 0 live → compacté au prochain tour |
| Après .dead, avant suppression | .dead sur disque | `openSegments()` ignore `*.dead` → supprimé |

Règle d'or : **le WriteBatch RocksDB est l'opération de commit**. Une fois appliqué, l'index est la vérité. Tout fichier orphelin est nettoyé au démarrage.

### Ordonnancement du commit

Le **writeLock de Partition** est acquis uniquement pour les étapes 5–10.
Les lectures (`get`, `exists`) et les streams de sync (`openSyncStream`) sont concurrents — ils accèdent à `byId` via ConcurrentHashMap, ce qui reste cohérent pendant toute la transition.

### Vérification de fraîcheur des clés (étape 5)

```java
RocksDbIndex.Entry current = index.get(key);
if (current == null)                          continue; // supprimée
if (!current.equals(oldEntry))               continue; // déplacée / écrasée
// ↑ couvre : même segmentId mais offset différent (overwrite in-place impossible
//            en append-only, mais défense en profondeur)
batch.put(key, newEntry);
```

---

## Nouvelles variables d'environnement

| Variable | Défaut | Description |
|---|---|---|
| `Q1_COMPACT_THRESHOLD` | `0.5` | dead_ratio minimum pour déclencher la compaction |
| `Q1_COMPACT_INTERVAL_S` | `300` | intervalle de vérification en secondes |
| `Q1_COMPACT_MAX_SEGMENTS` | `4` | max segments compactés par partition par passe |

---

## Plan d'implémentation

### Nouveaux fichiers

#### `q1-core/src/main/java/io/q1/core/CompactionStats.java`
```
record CompactionStats(
    int segmentId,
    long originalSize,
    long liveBytes,
    int keysCompacted,
    int keysSkipped   // clés invalidées entre scan et commit
)
```

#### `q1-core/src/main/java/io/q1/core/Compactor.java`
Classe interne à `Partition`, package-private. Responsabilités :
- `List<Integer> candidates(double threshold)` — retourne les segmentIds candidats triés
- `CompactionStats compact(int segmentId)` — exécute les deux phases
- Gère les fichiers `.compact` et `.dead`

### Modifications de `Partition.java`

- Ajouter `Compactor compactor` (initialisé dans le constructeur).
- Exposer `compactSegment(int segmentId)` (délègue à `Compactor`).
- `openSegments()` : ignorer `*.compact` et supprimer `*.dead` au démarrage.
- Recovery startup : si un `.compact` existe avec un ID correspondant à un WriteBatch déjà commité (index pointe dessus), le renommer ; sinon le supprimer. Voir ci-dessous.

#### Recovery `.compact` au démarrage

```
Pour chaque fichier segment-NNN.q1.compact trouvé dans le répertoire :
  Ouvrir en lecture, vérifier que le premier record est valide (MAGIC ok).
  Chercher dans RocksDB si des entrées pointent sur cet ID.
  → Oui : renommer en segment-NNN.q1 (le commit avait réussi mais le rename a manqué).
  → Non : supprimer (phase 1 incomplète ou WriteBatch non commité).
```

### Modifications de `StorageEngine.java`

- Ajouter `ScheduledExecutorService compactionScheduler` (virtual thread pool).
- Au démarrage : planifier `runCompaction()` toutes les `Q1_COMPACT_INTERVAL_S` secondes.
- `runCompaction()` : pour chaque partition, appeler `partition.compactIfNeeded(threshold, maxSegments)`.
- Dans `close()` : `compactionScheduler.shutdownNow()`.

### Modifications de `CLAUDE.md`

Retirer le TODO "Segment compaction" de la section roadmap, ajouter à la section existante.

---

## Cas limites

| Cas | Comportement attendu |
|-----|---------------------|
| Segment actif | Jamais sélectionné (exclu explicitement) |
| Segment avec 0 clé vivante | dead_ratio = 1,0 → candidat prioritaire ; Phase 2 : WriteBatch vide + suppression directe |
| Compaction simultanée de deux segments qui partagent des clés | Impossible — une clé ne peut appartenir qu'à un seul segment à la fois dans l'index |
| Clé déplacée vers un segment en cours de compaction | L'index pointe sur le segment actif (plus récent) → vérif de fraîcheur la skip |
| Crash en milieu de Phase 1 | Fichier `.compact` partiel → supprimé au démarrage |
| Compaction d'un segment déjà compacté | `candidates()` le retrouve avec dead_ratio = 0 → pas candidat |

---

## Stratégie de test

### Tests unitaires (`PartitionTest`)

- `compactionRemovesTombstones` : PUT + DELETE × N, vérifier que compaction réduit la taille sur disque.
- `compactionPreservesLiveData` : PUT 100 clés, DELETE 50, compacter, vérifier que les 50 restantes sont lisibles.
- `compactionHandlesConcurrentWrite` : compaction + PUT concurrent sur la même clé → ni perte ni corruption.
- `compactionSkipsActiveSegment` : l'active segment n'est jamais compacté même si `dead_ratio` > threshold.
- `compactionCrashRecovery` : simuler un crash après Phase 1 (fichier `.compact` sur disque) → redémarrage propre.

### Tests d'intégration (`S3CompatibilityIT`)

Vérifier que les 12 tests existants passent après N cycles de compaction forcés.

---

## Ce que ce plan ne couvre pas (hors périmètre initial)

- **Compaction multi-segments** : fusionner plusieurs petits segments en un grand (merge compaction). Utile pour réduire le nombre de fichiers ouverts, mais complexité supplémentaire.
- **Tiered compaction** (style LSM) : inutile ici car les segments sont écrits par ordre chronologique, pas par niveau.
- **Erasure coding** : post-RF, sans lien avec la compaction locale.
