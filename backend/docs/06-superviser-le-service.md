# Cas d'utilisation — superviser le service

## Objectif

La supervision répond à trois questions :

1. le travail s'accumule-t-il ?
2. depuis combien de temps le plus ancien fichier attend-il ?
3. l'invariant entre état métier et zone de stockage est-il toujours respecté ?

Ce parcours est en lecture seule. Il n'est pas utilisé pour décider du verdict
d'un fichier ni pour modifier l'automate.

## Chaîne d'appel

```text
OperationalMetrics / InvariantHealthIndicator
  └─ MonitorFilesUseCase
      └─ FileMonitoringService
          ├─ FileCatalog.countPendingFiles()
          └─ OperationalReadings
              └─ JdbcOperationalReadings
```

[`OperationalMetrics`](../src/main/java/com/praxedo/securefiles/infrastructure/metrics/file/binder/OperationalMetrics.java)
est un adaptateur pilotant Micrometer.
[`InvariantHealthIndicator`](../src/main/java/com/praxedo/securefiles/infrastructure/metrics/file/health/InvariantHealthIndicator.java)
est un adaptateur pilotant Actuator. Tous deux passent par
[`MonitorFilesUseCase`](../src/main/java/com/praxedo/securefiles/application/file/port/in/MonitorFilesUseCase.java),
et non directement par les adaptateurs de persistance.

## Mesures calculées depuis PostgreSQL

| Métrique | Calcul | Interprétation |
|---|---|---|
| `praxedo.queue.depth` | nombre de fichiers non terminaux | volume de travail encore à effectuer |
| `praxedo.queue.depth.bytes` | taille cumulée de ces fichiers | le retard en octets : c'est lui, divisé par le débit, qui dit le temps pour le résorber |
| `praxedo.queue.oldest_pending_age` | âge du plus ancien fichier actif | détecte une file qui ne progresse plus |
| `praxedo.invariant.violations` | lignes où `AVAILABLE` et `SERVABLE` divergent | doit toujours rester à zéro |

Les gauges sont recalculées au moment du scrape. Si PostgreSQL ne répond pas,
elles valent `NaN`, jamais `0` : une mesure inconnue ne doit pas ressembler à
un système sain.

La requête de violation est :

```sql
SELECT count(*)
FROM stored_file
WHERE (status = 'AVAILABLE') IS DISTINCT FROM (storage_area = 'SERVABLE');
```

`IS DISTINCT FROM` produit toujours vrai ou faux, même en présence de `NULL`.
Il est cohérent avec les contraintes totales du schéma.

## Health check de l'invariant

`/actuator/health` passe à `DOWN` si le nombre de violations est différent de
zéro. Cet indicateur ne fait volontairement pas partie de la readiness : tous
les nœuds lisent la même base, les retirer tous du trafic empêcherait aussi le
téléchargement de fichiers valides. L'anomalie doit alerter un humain sans
transformer automatiquement un défaut partiel en indisponibilité totale.

## Mesures des parcours métier

D'autres adaptateurs enregistrent les flux sans modifier les services :

| Métrique | Produit par | Sens |
|---|---|---|
| `praxedo.upload.bytes` | `FileUploadController` | octets reçus, acceptés ou refusés |
| `praxedo.download.bytes` | `FileDownloadController` | octets réellement servis |
| `praxedo.admission.rejected{reason}` | `ApiExceptionHandler` | dépôts refusés par `429` : file pleine (`too_many_pending_files`) ou nœud plein (`too_many_concurrent_uploads`) |
| `praxedo.scan.duration{result}` | `MeteredAntivirusScanner` | durée des analyses par verdict |
| `praxedo.scan.verdict{result}` | `MeteredAntivirusScanner` | nombre de verdicts de chaque type |
| `praxedo.scan.bytes{result}` | `MeteredAntivirusScanner` | octets lus par le moteur pour rendre ses verdicts |
| `praxedo.scan.failures` | `MeteredAntivirusScanner` | appels antivirus terminés sans verdict |
| `praxedo.scan.inflight` | `MeteredAntivirusScanner` | analyses en cours sur ce nœud |
| `praxedo.antivirus.available` | `MeteredAntivirusScanner` | dernier résultat du portillon de santé |
| `praxedo.scan.verdict.rejected` | `JdbcFileWorkQueue` | verdicts de workers ayant perdu leur bail |
| `praxedo.work.technical_failures` | `JdbcFileWorkQueue` | analyses ou promotions replanifiées |
| `praxedo.pipeline.lag{outcome}` | `JdbcFileWorkQueue` | du dépôt à l'état final, par fichier (histogramme) |
| `praxedo.pipeline.completed.bytes{outcome}` | `JdbcFileWorkQueue` | octets des fichiers arrivés à leur état final : le débit, par la taille |

Toutes les séries de verdict sont enregistrées à zéro au démarrage. Un tableau
de bord distingue ainsi « aucun verdict pour le moment » de « métrique absente ».

## Corrélation des journaux

[`RequestIdFilter`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/filter/RequestIdFilter.java)
associe un `requestId` à chaque requête. Pendant un traitement asynchrone,
`FileScanService` place le `fileId` dans le MDC. Le profil `json-logs` produit
des journaux ECS exploitables par un collecteur.

Le journal PostgreSQL `file_audit_event` complète les logs :

- un trigger enregistre chaque insertion et transition d'état dans la même
  transaction ;
- l'application ajoute les contenus servis (`DOWNLOAD_SERVED`), avant le
  premier octet envoyé ;
- des triggers interdisent `UPDATE`, `DELETE` et `TRUNCATE` sur le journal.

## Exposition

La configuration expose `health`, `info`, `metrics` et `prometheus` via
Actuator, sur un **port de management distinct** de celui de l'API
(`management.server.port`, 8091 par défaut — audit S-08). Les sondes y
répondent sans jeton, pour que l'orchestrateur et Prometheus puissent les
lire ; sur le port de l'API, `/actuator` ne sert rien (`401` sans preuve
d'identité, `404` avec). `docker-compose.yml` ne publie ce port que sur
`127.0.0.1`.

## Tests qui racontent ce parcours

- [`ObservabilityTest`](../src/test/java/com/praxedo/securefiles/infrastructure/metrics/file/ObservabilityTest.java) : gauges, compteurs, Prometheus, health et request ID.
- [`AuditTrailTest`](../src/test/java/com/praxedo/securefiles/infrastructure/persistence/file/AuditTrailTest.java) : événements et caractère append-only.
- [`StoredFileConstraintsTest`](../src/test/java/com/praxedo/securefiles/infrastructure/persistence/file/StoredFileConstraintsTest.java) : contraintes qui rendent les violations normalement impossibles.
