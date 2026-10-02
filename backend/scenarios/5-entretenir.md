# Scénario 5 — Entretenir : reprise et nettoyage (`MaintainFilesUseCase`)

**Le scénario** : pendant l'analyse d'un fichier, le conteneur du service est
tué (`docker kill`). Le fichier reste en `SCANNING`, avec un bail que plus
personne ne tiendra. Au redémarrage, rien d'autre n'est fait à la main : le
*reaper* le remet en file, un worker le reprend. Par ailleurs, un dépôt
interrompu entre l'écriture de l'objet et celle de la ligne a laissé un
**orphelin** en quarantaine : le balayage le supprime.

Personne n'appelle ce port par HTTP : c'est une **minuterie**.

## La carte

```
[scheduling] MaintenanceScheduler   (actif si praxedo.scheduling.enabled, l.28)
  ├─ reclaimAbandonedWork  @Scheduled toutes les 30 s ...... l.41 ─┐
  ├─ sweepQuarantine       @Scheduled toutes les 10 min .... l.54 ─┼─▶ [port in] MaintainFilesUseCase
  └─ purgeIdempotencyKeys  @Scheduled toutes les 10 min .... l.67 ─┘      └─ [service] FileMaintenanceService
                                                                             ├─ reclaimExpiredLeases l.42 ─▶ [port out] FileWorkQueue ─▶ JdbcFileWorkQueue l.314
                                                                             ├─ sweepQuarantine ..... l.47 ─▶ QuarantineSweeper.sweep l.63
                                                                             │     ├─ [port out] WorkerStorage.listQuarantinedBefore ─▶ S3WorkerStorage l.60
                                                                             │     ├─ [port out] FileCatalog.statusesByObjectKey ──▶ JpaFileCatalog l.209
                                                                             │     └─ [port out] WorkerStorage.deleteQuarantined ──▶ S3WorkerStorage l.50
                                                                             └─ purgeIdempotencyKeys  l.53 ─▶ [port out] IdempotencyStore ─▶ JdbcIdempotencyStore l.111
```

Le planificateur décide **quand**, le service décide **quoi**. Chaque tâche est
dans un `try/catch` : une panne est journalisée, la tâche repassera au tour
suivant ([`MaintenanceScheduler` l. 47-49](../src/main/java/com/praxedo/securefiles/infrastructure/scheduling/file/scheduler/MaintenanceScheduler.java#L47)).

## 5a — Le *reaper* : un worker mort n'est pas un incident

| Où | Ce qui se passe |
|---|---|
| [`MaintenanceScheduler.reclaimAbandonedWork`](../src/main/java/com/praxedo/securefiles/infrastructure/scheduling/file/scheduler/MaintenanceScheduler.java#L41) | Toutes les 30 s (`praxedo.scheduling.reaper-interval`) → `maintenanceUseCase.reclaimExpiredLeases()` (l. 43) |
| [`FileMaintenanceService.reclaimExpiredLeases`](../src/main/java/com/praxedo/securefiles/application/file/service/FileMaintenanceService.java#L42) | Passe les réglages du worker (5 tentatives, backoff 10 s → 15 min) |
| [`JdbcFileWorkQueue.reclaimExpiredLeases`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JdbcFileWorkQueue.java#L313) | **Une instruction** : toute ligne `SCANNING`/`PROMOTING` dont le bail a expiré (l. 332-333) → `RETRY_WAIT` avec un délai exponentiel et du *jitter*, calculé **en SQL** (l. 322-324), ou `FAILED_FINAL` si les tentatives sont épuisées (l. 317-320). Le bail est effacé |
| base | Le trigger écrit `LEASE_EXPIRED`, **attribué au `reaper`** et non au worker disparu ([`V6__audit.sql`](../src/main/resources/db/migration/V6__audit.sql#L31)) |
| ensuite | Le fichier redevient éligible : une boucle du worker le reprend ([scénario 3](3-analyser-et-promouvoir.md), étape 2) |

**Pourquoi aucun verrou distribué** : l'instruction est conditionnelle. Lancée
en même temps sur trois nœuds, elle modifie chaque ligne une seule fois. Et le
worker gelé qui se réveillerait après coup ne peut plus rien écrire : ses
écritures exigent son jeton de bail, qui n'existe plus
([scénario 3](3-analyser-et-promouvoir.md), étape 4).

## 5b — Le balayage de la quarantaine

[`QuarantineSweeper.sweep`](../src/main/java/com/praxedo/securefiles/application/file/service/QuarantineSweeper.java#L63)

| Ligne | Ce qui se passe |
|---|---|
| [64-67](../src/main/java/com/praxedo/securefiles/application/file/service/QuarantineSweeper.java#L64) | Les objets de la quarantaine plus vieux que **deux heures** (`orphan-age`), 500 au plus, **après la dernière clé du passage précédent** ([`S3WorkerStorage.listQuarantinedBefore`](../src/main/java/com/praxedo/securefiles/infrastructure/storage/file/adapter/S3WorkerStorage.java#L60) : `ListObjectsV2` en ordre de clé, page après page). Deux heures : plus long que le dépôt le plus lent accepté, ce que le service vérifie au démarrage — un objet en cours de réception n'est jamais pris pour un orphelin. La reprise empêche les fichiers bloqués, gardés comme preuve, de masquer les orphelins rangés après eux ; en fin de liste, le passage suivant repart du début |
| [71-72](../src/main/java/com/praxedo/securefiles/application/file/service/QuarantineSweeper.java#L71) | Une seule requête pour connaître l'état de toutes ces clés ([`JpaFileCatalog.statusesByObjectKey`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JpaFileCatalog.java#L209) → [repository l. 83](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/repository/StoredFileJpaRepository.java#L83)) |
| [75-81](../src/main/java/com/praxedo/securefiles/application/file/service/QuarantineSweeper.java#L75) | Supprimé si **aucune ligne** (l'orphelin de notre scénario) ou si la ligne est `AVAILABLE` (la source que la promotion n'a pas pu effacer). **Tout le reste est gardé** : un fichier en attente d'analyse, et les fichiers infectés ou non analysables, conservés en quarantaine sans jamais être servis |

## 5c — La purge des clés d'idempotence

[`FileMaintenanceService.purgeIdempotencyKeys`](../src/main/java/com/praxedo/securefiles/application/file/service/FileMaintenanceService.java#L53) →
[`JdbcIdempotencyStore.purge`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/idempotency/adapter/JdbcIdempotencyStore.java#L111) :
`DELETE` des clés expirées (24 h) et des réservations `IN_PROGRESS` vieilles
de deux heures — celles d'un nœud mort pendant l'envoi, qui bloqueraient sinon le
réessai du client. Deux heures : plus long que le dépôt le plus lent accepté,
pour ne jamais purger la clé d'un envoi encore en cours.

## Ce qui a changé, au bout du scénario

| Où | Quoi |
|---|---|
| `stored_file` | Le fichier abandonné : `SCANNING` → `RETRY_WAIT` (puis repris) ; tentative comptée — un crash consomme une tentative, un arrêt propre non |
| `file_audit_event` | `LEASE_EXPIRED  SCANNING → RETRY_WAIT  by reaper` |
| Seau `quarantine` | L'orphelin a disparu |

## Points d'arrêt conseillés

`MaintenanceScheduler.reclaimAbandonedWork` l. 43 ·
`QuarantineSweeper.sweep` l. 64 · `JdbcIdempotencyStore.purge` l. 113.

> Les intervalles (30 s, 10 min) rendent le débogueur peu pratique : les tests
> ci-dessous appellent directement les méthodes.

## Rejouer

`FilePersistenceTest` (bail expiré repris, worker zombie qui n'écrit rien),
`AuditTrailTest` (`LEASE_EXPIRED` attribué au *reaper*), `QuarantineSweeperTest`
(orphelins et sources supprimés, tout le reste gardé),
`JdbcIdempotencyStoreTest` (purge).
