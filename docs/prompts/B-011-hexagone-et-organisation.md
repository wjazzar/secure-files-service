# B-011 — Hexagone vérifié et organisation par concept, sur tout le code

- **Date** : 2026-09-27
- **Outil** : Claude Opus 5.5
- **Objectif** : de vrais ports d'entrée, et une organisation par concept puis par nature dans toutes les couches
- **Phase du projet** : back-end, relecture du porteur du projet

## Prompt

> Deux problèmes d'architecture :
>
> 1. **L'organisation par concept n'est pas appliquée partout.** Le
>    téléchargement porte sur un fichier : il relève du concept « fichier ».
>    Persistance, ordonnancement, sécurité, stockage, antivirus : la même
>    séparation doit valoir dans toutes les couches.
> 2. **Les ports d'entrée manquent** dans `application`. Sans eux, on ne voit
>    pas ce que le code offre : chaque contrôleur doit appeler un port
>    d'entrée qui nomme le cas d'usage.
>
> L'architecture doit être hexagonale, et vérifiée par des tests, pas
> seulement déclarée.

## Ce que l'audit des dépendances a montré

Pire que l'absence de ports d'entrée :

| Constat | Violation |
|---|---|
| Contrôleurs → `UploadFileService`, `FileQueryService`, `DownloadService` | Un adaptateur pilotant voyait des services concrets |
| `MaintenanceScheduler` → `FileWorkQueue`, `IdempotencyStore` | Un adaptateur pilotant **contournait le cœur** pour piloter des ports de sortie |
| `OperationalMetrics`, `InvariantHealthIndicator` → `OperationalQueries` (persistance) | Un adaptateur en appelait un autre |
| Web ↔ sécurité | Deux adaptateurs dépendant l'un de l'autre |

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| **Six ports d'entrée**, un par intention d'un acteur : `UploadFileUseCase`, `QueryFilesUseCase`, `DownloadFileUseCase`, `ScanFilesUseCase`, `MaintainFilesUseCase`, `MonitorFilesUseCase` | Ce que le cœur offre se lit dans un dossier. Un par méthode aurait multiplié les fichiers sans rien clarifier |
| Deux services nouveaux : `FileMaintenanceService`, `FileMonitoringService` ; un port de sortie nouveau : `OperationalReadings` | Pour que la planification et les métriques passent **par** le cœur |
| Sécurité HTTP (configuration, OIDC, propriétaire courant) **dans l'adaptateur web** | Elle appartient à l'entrée HTTP ; l'adaptateur `security` ne garde que la signature des liens (piloté) |
| **Téléchargement sous `file`**, partout | Il sert un fichier. `DownloadController` → `FileDownloadController`, comme ses voisins |
| Renommages vers la nature : `ScanWorker` → `FileScanService`, `PromotionService` → `FilePromotionService`, `DownloadService` → `FileDownloadService`, `OperationalQueries` → `JdbcOperationalReadings` | Le nom dit ce que c'est, comme le dossier |
| **Par concept, puis par nature, dans toutes les couches** : `domain/file/{model, valueobject, exception}`, `domain/owner/valueobject`, `application/file/{port/in, port/out, service, model, exception}`, `infrastructure/<adaptateur>/<concept>/<nature>` | La demande du porteur du projet, appliquée sans exception ; `common/` pour ce que les concepts partagent |
| **`HexagonalArchitectureTest`** (8 règles) et **`CodeLayoutRulesTest`** (11 règles) | « Validée et vérifiée » : la construction échoue si l'hexagone ou la disposition sont rompus |
| Déplacements par un script générique + `git mv` | 113 classes : paquets, imports explicites **et implicites**, renommages, tests en miroir — historique conservé |

## Ce que j'ai vérifié

| Point | Résultat |
|---|---|
| **Sonde volontaire** : un contrôleur qui dépend d'un service, d'un port de sortie et d'un autre adaptateur | Attrapée par **trois** règles, puis retirée — les règles ne sont pas décoratives |
| Suite complète | **400 tests verts**, preuves mémoire comprises ; aucun changement de comportement |

## Ce que j'ai rejeté

| Option | Pourquoi |
|---|---|
| `Architectures.onionArchitecture()` d'ArchUnit | Des règles explicites disent chacune ce qu'elles protègent ; le message d'échec le répète |
| Autoriser l'exception web → sécurité dans les règles | Une exception dans la règle, c'est la règle qui cède ; déplacer la sécurité HTTP dans le web était juste |
| Garder `scan` comme concept séparé dans le domaine | Un verdict atteste le contenu d'un fichier : il appartient au concept `file`, comme le téléchargement |
