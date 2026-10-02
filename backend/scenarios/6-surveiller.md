# Scénario 6 — Surveiller (`MonitorFilesUseCase`)

**Le scénario** : Prometheus collecte `/actuator/prometheus` toutes les
quelques secondes ; un orchestrateur interroge `/actuator/health`. Ils veulent
savoir combien de fichiers attendent, depuis quand le plus ancien attend, et si
l'invariant tient toujours.

## La carte

```
GET /actuator/prometheus
└─ Micrometer évalue les jauges enregistrées au démarrage
   └─ [metrics] OperationalMetrics.bindTo ................................... l.37
      ├─ praxedo.queue.depth ............... l.38 ─┐
      ├─ praxedo.queue.depth.bytes ......... l.41 ─┤
      ├─ praxedo.queue.oldest_pending_age .. l.45 ─┼─▶ [port in] MonitorFilesUseCase
      └─ praxedo.invariant.violations ...... l.50 ─┘      └─ [service] FileMonitoringService
                                                             ├─ queueDepth ........ l.26 ─▶ [port out] FileCatalog.countPendingFiles ─▶ JpaFileCatalog l.203
                                                             ├─ queueBytes ........ l.31 ─▶ [port out] OperationalReadings ─▶ JdbcOperationalReadings.pendingBytes
                                                             ├─ oldestPendingAge .. l.36 ─▶ [port out] OperationalReadings ─▶ JdbcOperationalReadings l.50
                                                             └─ invariantViolations l.41 ─▶ [port out] OperationalReadings ─▶ JdbcOperationalReadings l.35
GET /actuator/health
└─ [metrics] InvariantHealthIndicator.doHealthCheck ......................... l.28
   └─ [port in] MonitorFilesUseCase.invariantViolations  (même chemin)
```

## Pas à pas

### Étape 1 — Des jauges calculées au moment de la collecte

[`OperationalMetrics.bindTo`](../src/main/java/com/praxedo/securefiles/infrastructure/metrics/file/binder/OperationalMetrics.java#L37)
est appelé une fois, au démarrage : il **enregistre** quatre jauges dont la
valeur est une fonction. C'est à chaque collecte que Micrometer appelle ces
fonctions — donc le port d'entrée.

| Ligne | Jauge | Chemin |
|---|---|---|
| [38-40](../src/main/java/com/praxedo/securefiles/infrastructure/metrics/file/binder/OperationalMetrics.java#L38) | `praxedo.queue.depth` | [`FileMonitoringService.queueDepth`](../src/main/java/com/praxedo/securefiles/application/file/service/FileMonitoringService.java#L26) → [`JpaFileCatalog.countPendingFiles`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JpaFileCatalog.java#L203) : le même comptage que la contre-pression du [scénario 1](1-deposer.md) |
| [41-44](../src/main/java/com/praxedo/securefiles/infrastructure/metrics/file/binder/OperationalMetrics.java#L41) | `praxedo.queue.depth.bytes` — le retard en octets | [`queueBytes`](../src/main/java/com/praxedo/securefiles/application/file/service/FileMonitoringService.java#L31) → `JdbcOperationalReadings.pendingBytes` : `sum(size_bytes)` sur les états non terminaux. Divisé par le débit en octets, il dit le temps pour résorber le retard |
| [45-49](../src/main/java/com/praxedo/securefiles/infrastructure/metrics/file/binder/OperationalMetrics.java#L45) | `praxedo.queue.oldest_pending_age` — **la métrique d'alerte** | [`oldestPendingAge`](../src/main/java/com/praxedo/securefiles/application/file/service/FileMonitoringService.java#L36) → [`JdbcOperationalReadings.oldestPendingAge`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JdbcOperationalReadings.java#L50) : `clock_timestamp() - min(uploaded_at)` sur les états non terminaux (l. 52). Une file qui grossit, c'est de la charge ; une file qui **ne bouge plus**, c'est une panne — et c'est cette jauge qui le dit |
| [50-53](../src/main/java/com/praxedo/securefiles/infrastructure/metrics/file/binder/OperationalMetrics.java#L50) | `praxedo.invariant.violations` — **doit rester à zéro** | [`invariantViolations`](../src/main/java/com/praxedo/securefiles/application/file/service/FileMonitoringService.java#L41) → [`JdbcOperationalReadings.invariantViolations`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JdbcOperationalReadings.java#L35) : `WHERE (status = 'AVAILABLE') IS DISTINCT FROM (storage_area = 'SERVABLE')` (l. 38) |
| [56-63](../src/main/java/com/praxedo/securefiles/infrastructure/metrics/file/binder/OperationalMetrics.java#L56) | toutes | `safely` : si la base ne répond pas, la jauge vaut **`NaN`** (« inconnu »), jamais un zéro rassurant |

### Étape 2 — La santé

[`InvariantHealthIndicator.doHealthCheck`](../src/main/java/com/praxedo/securefiles/infrastructure/metrics/file/health/InvariantHealthIndicator.java#L28) :
0 violation → `UP` ; sinon `DOWN` avec le nombre. Volontairement **hors** du
groupe de disponibilité (`/actuator/health/readiness`) : tous les nœuds lisent
la même base, et retirer tout le service de la rotation couperait aussi les
fichiers sains. L'alarme sonne, la décision reste humaine.

## Les autres métriques : mesurées dans les adaptateurs, sans port

Elles ne demandent rien au cœur : ce sont des effets de bord des adaptateurs
eux-mêmes.

| Métrique | Où |
|---|---|
| `praxedo.scan.duration`, `.verdict`, `.bytes`, `.failures`, `.inflight`, `praxedo.antivirus.available` | [`MeteredAntivirusScanner`](../src/main/java/com/praxedo/securefiles/infrastructure/antivirus/file/adapter/MeteredAntivirusScanner.java), décorateur du port `AntivirusScanner` |
| `praxedo.scan.verdict.rejected`, `praxedo.work.technical_failures`, `praxedo.pipeline.lag` (du dépôt à l'état final, par fichier), `praxedo.pipeline.completed.bytes` (le débit) | [`JdbcFileWorkQueue`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JdbcFileWorkQueue.java#L84) |
| `praxedo.upload.bytes`, `praxedo.download.bytes` | Les contrôleurs de dépôt et de téléchargement |
| `praxedo.admission.rejected{reason}` | [`ApiExceptionHandler`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/error/ApiExceptionHandler.java#L149), sur chaque `429` : file pleine (l. 149) ou nœud plein (l. 130) |

## Points d'arrêt conseillés

`OperationalMetrics.bindTo` l. 38 (le *lambda*, pas l'enregistrement) ·
`JdbcOperationalReadings.invariantViolations` l. 36 ·
`InvariantHealthIndicator.doHealthCheck` l. 30.

## Rejouer

`ObservabilityTest` : jauges qui suivent la base, invariant à zéro, octets
comptés, endpoint Prometheus et santé, `X-Request-Id`.
