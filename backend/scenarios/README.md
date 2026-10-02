# Scénarios pas à pas — un par port d'entrée

> **Rédaction : Claude (Opus 5.5)**, session back-end, 27/09/2026, à la demande
> du porteur du projet : « suivre ce qui se passe d'un point de vue code, de
> bout en bout ».

Chaque document suit **un scénario concret**, comme on le suivrait au
débogueur : du déclencheur (requête HTTP, boucle du worker, minuterie, collecte
Prometheus) jusqu'au SQL exécuté et à l'objet lu ou écrit dans le stockage.
Chaque étape pointe **la ligne** où elle se passe.

> Les documents de [`../docs/`](../docs/) (rédigés par Codex) expliquent les
> parcours au niveau des classes, avec leurs raisons. Ceux-ci sont la **trace
> ligne à ligne** : à lire code ouvert à côté.

## Les six scénarios

| # | Port d'entrée | Déclencheur | Scénario suivi |
|---|---|---|---|
| 1 | [`UploadFileUseCase`](1-deposer.md) | `POST /api/v1/files` | Alice dépose `rapport.pdf` (2 Mo) avec une clé d'idempotence |
| 2 | [`QueryFilesUseCase`](2-consulter.md) | `GET /api/v1/files`, `/summary`, `/{id}` | Elle liste ses fichiers en attente, compte, puis interroge le détail |
| 3 | [`ScanFilesUseCase`](3-analyser-et-promouvoir.md) | Boucles du worker (threads virtuels) | Le fichier est pris, analysé par ClamAV, déclaré sain, promu |
| 4 | [`DownloadFileUseCase`](4-telecharger.md) | `GET …/content` (cookie de session ou jeton) | Elle télécharge, reprend sur une plage ; un système tiers lit le même contenu |
| 5 | [`MaintainFilesUseCase`](5-entretenir.md) | `@Scheduled` | Un worker meurt, le *reaper* reprend ; un orphelin est balayé |
| 6 | [`MonitorFilesUseCase`](6-surveiller.md) | Collecte Prometheus, `/actuator/health` | Les jauges et la santé lisent le cœur |

**Ordre de lecture conseillé** : 1 → 3 → 4, c'est la vie d'un fichier. Puis 2,
5 et 6.

## La colonne vertébrale, identique dans les six

```
adaptateur PILOTANT  ─▶  port d'entrée  ─▶  service  ─▶  port de sortie  ─▶  adaptateur PILOTÉ
(web, scheduling,        (…UseCase)         (implémente   (interface)          (persistence, storage,
 metrics)                                    le port)                           antivirus, security)
```

Pour chaque scénario, la carte en tête de document reprend cette colonne avec
les classes réelles. Qui est branché sur qui se lit dans un seul fichier :
[`UseCaseConfiguration`](../src/main/java/com/praxedo/securefiles/config/UseCaseConfiguration.java),
qui publie chaque service **sous son port d'entrée** (lignes 56 à 96).

## Ce que chaque requête HTTP traverse avant le contrôleur

| Ordre | Où | Ce qui se passe |
|---|---|---|
| 1 | Tomcat | La requête est servie par un **thread virtuel** (`spring.threads.virtual.enabled`) |
| 2 | [`RequestIdFilter.doFilterInternal`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/filter/RequestIdFilter.java#L36) | `X-Request-Id` repris ou tiré, renvoyé (l. 41) et mis dans le MDC (l. 42) : il figure dans chaque ligne de journal de la requête |
| 3 | [`SecurityConfiguration`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/config/SecurityConfiguration.java#L77) | Jeton `Bearer` vérifié si la requête porte `Authorization` (l. 77-87), session du navigateur sinon (l. 94-107) ; seuls `/actuator/**` et les points d'entrée de connexion restent ouverts — le téléchargement compris, tout `/api` exige une identité, et aucun réglage ne l'ouvre |
| 4 | Le contrôleur | Il appelle [`CurrentOwner.resolve`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/identity/CurrentOwner.java#L33) : le `sub` de la session (l. 40-43) ou du jeton (l. 45-48) ; sans preuve vérifiée, il échoue (l. 50) |
| ✗ | [`ApiExceptionHandler`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/error/ApiExceptionHandler.java) | Toute exception devient un document RFC 9457 avec son `code` du contrat |

## Suivre au débogueur

Les tests d'intégration démarrent la vraie pile (Testcontainers : PostgreSQL,
SeaweedFS, et ClamAV pour `ScanPipelineTest`). Lancés en mode débogage avec les
points d'arrêt indiqués dans chaque document, ils déroulent exactement ces
scénarios :

| Scénario | Test à lancer en débogage |
|---|---|
| 1 | `UploadApiTest` (`infrastructure/web/file`) |
| 2 | `ReadApiTest` (`infrastructure/web/file`) |
| 3 | `ScanPipelineTest` (racine des tests) — le vrai antivirus |
| 4 | `DownloadApiTest` (`infrastructure/web/file`) |
| 5 | `FilePersistenceTest`, `QuarantineSweeperTest` |
| 6 | `ObservabilityTest` (`infrastructure/metrics/file`) |

Sans débogueur : `docker compose --profile app up -d --build`, puis
`scripts/demo.sh`, et suivre les journaux (`docker logs -f praxedo-backend`).

> **Numéros de ligne** relevés le 27/09, recalés le 01/10 après la revue du
> code, revérifiés un par un le 02/10. Ils dériveront avec le code : le nom de
> la méthode fait foi, la ligne est un raccourci.
