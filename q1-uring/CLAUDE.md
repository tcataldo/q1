# q1-uring — Backend io_uring (Panama FFI)

Module optionnel. Dépend de `q1-core` pour les interfaces `FileIO` / `FileIOFactory`.

## Objectif

Remplacer les `FileChannel` NIO par des I/O asynchrones via **liburing** (io_uring Linux),
en visant des lectures/écritures en **128 KiB** (sweet spot du workload email).

## Activation

Au démarrage du serveur, passer `UringFileIOFactory` à `StorageEngine` :
```java
StorageEngine engine = new StorageEngine(dataDir, numPartitions, new UringFileIOFactory());
```
Actuellement ce câblage n'est pas fait dans `Q1Server.main()` — c'est un TODO.

## Structure

| Fichier | Rôle |
|---|---|
| `IoUring.java` | Bindings Panama FFI vers liburing (`io_uring_queue_init/exit`, `prep_read/write`, `submit`, `wait_cqe`) |
| `UringFileIO.java` | Implémente `FileIO` ; utilise FFI pour `open(2)`, `lseek(2)`, `fsync(2)`, `close(2)` et io_uring pour read/write |
| `UringFileIOFactory.java` | Owns `IoUring ring` + mutex ; implémente `FileIOFactory` + `Closeable` |

## Points techniques Panama FFI

- `IoUring` alloue la `struct io_uring` (216 bytes) dans une `Arena` confinée
- `UringFileIO.read/write` : alloue une `Arena` confinée par appel, copie entre heap `ByteBuffer` et off-heap `MemorySegment` via `MemorySegment.copy()`
- `open(2)` est variadique → `Linker.Option.firstVariadicArg(2)` pour le 3e argument `mode`
- La synchronisation du ring est coarse-grained (un `Object ringLock` dans la factory) — à raffiner

## Prérequis

- Linux kernel ≥ 5.1 (io_uring)
- `liburing` installée (`/usr/lib/x86_64-linux-gnu/liburing.so` ou équivalent)
- JVM lancée avec `--enable-native-access=ALL-UNNAMED`

## TODO

- [ ] Câbler `UringFileIOFactory` dans `Q1Server.main()` (via env var `Q1_IO=uring`)
- [ ] Écrire `UringFileIOTest` — vérifier les mêmes cas que `NioFileIO` (round-trip, force, size)
- [ ] Benchmark comparatif NIO vs io_uring sur des blocs de 4KB, 32KB, 128KB
- [ ] Améliorer la synchronisation du ring (un ring par thread virtuel ?)
- [ ] Gérer les erreurs retournées par `io_uring_wait_cqe` (res < 0 = errno)
- [ ] Support de l'I/O vectorisée (`prep_readv` / `prep_writev`) pour les lectures multi-segments
