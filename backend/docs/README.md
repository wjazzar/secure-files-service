# Parcours techniques du back-end

Cette documentation explique le code du back-end **par cas d'utilisation**.
Elle complète [`ARCHITECTURE.md`](../ARCHITECTURE.md), qui décrit les choix
d'architecture, en répondant ici à une autre question : _quelles classes sont
appelées, dans quel ordre, et pourquoi ?_

Écrite le 27 septembre 2026, elle suit le code depuis ; relue contre lui le
2 octobre. Le contrat HTTP reste la
source de vérité externe : [`contracts/openapi.yaml`](../../contracts/openapi.yaml).

## Point de départ recommandé

1. Lire la [vue d'ensemble](00-vue-d-ensemble.md) pour comprendre les couches,
   les ports et l'automate d'états.
2. Suivre le parcours qui vous intéresse de son adaptateur pilotant jusqu'aux
   adaptateurs pilotés.
3. Ouvrir les tests indiqués en fin de document : ils montrent les variantes et
   les pannes mieux qu'un parcours nominal isolé.

## Cas d'utilisation

| Parcours | Déclencheur | Port d'entrée | Service d'application | Résultat principal |
|---|---|---|---|---|
| [Déposer un fichier](01-deposer-un-fichier.md) | `POST /api/v1/files` | `UploadFileUseCase` | `UploadFileService` | Objet en quarantaine et ligne `AWAITING_SCAN` |
| [Consulter les fichiers](02-consulter-les-fichiers.md) | `GET /api/v1/files`, `/summary`, `/{id}` | `QueryFilesUseCase` | `FileQueryService` | Page, compteurs ou détail appartenant au demandeur |
| [Analyser et promouvoir](03-analyser-et-promouvoir.md) | Boucles internes du worker | `ScanFilesUseCase` | `FileScanService`, puis `FilePromotionService` | Verdict terminal ou fichier `AVAILABLE` |
| [Télécharger](04-telecharger-un-fichier.md) | `GET /api/v1/files/{id}/content`, avec l'identité de l'appelant | `DownloadFileUseCase` | `FileDownloadService` | Flux complet ou partiel depuis la zone servable |
| [Réparer et nettoyer](05-maintenance-et-reprise.md) | Planificateur interne | `MaintainFilesUseCase` | `FileMaintenanceService`, `QuarantineSweeper` | Baux repris, orphelins supprimés, clés purgées |
| [Superviser le service](06-superviser-le-service.md) | Scrape métrique et health check | `MonitorFilesUseCase` | `FileMonitoringService` | Profondeur de file, âge d'attente, violations |

Les remarques relevées pendant la relecture guidée sont conservées à part dans
[`07-remarques-relecture.md`](07-remarques-relecture.md), afin de ne pas
confondre le comportement actuel avec les améliorations décidées ; chaque
remarque y porte son arbitrage et ce qui a été fait.

## La chaîne commune à tous les parcours

```text
adaptateur pilotant
  contrôleur HTTP / worker / scheduler / métriques
                    │
                    ▼
             port d'entrée
                    │
                    ▼
       service d'application
                    │
                    ▼
             port de sortie
                    │
                    ▼
adaptateur piloté
  PostgreSQL / stockage S3 / antivirus / Keycloak
```

Les adaptateurs ne se connaissent pas entre eux. Leur assemblage est visible
dans le paquet [`config`](../src/main/java/com/praxedo/securefiles/config/package-info.java),
à commencer par [`UseCaseConfiguration`](../src/main/java/com/praxedo/securefiles/config/UseCaseConfiguration.java).
Les règles ArchUnit de
[`HexagonalArchitectureTest`](../src/test/java/com/praxedo/securefiles/architecture/HexagonalArchitectureTest.java)
et [`LayeringRulesTest`](../src/test/java/com/praxedo/securefiles/architecture/LayeringRulesTest.java)
empêchent les raccourcis entre couches.

## Deux notions à ne pas confondre

- Un **verdict** (`CLEAN`, `INFECTED`, `UNSCANNABLE`) décrit ce que
  l'antivirus a conclu sur une empreinte SHA-256 précise.
- Un **état** (`AWAITING_SCAN` à `AVAILABLE`) décrit où en est le fichier dans
  le service. Un verdict `CLEAN` mène d'abord à `PROMOTING`; seul `AVAILABLE`
  autorise le téléchargement.

## Règle de mise à jour

Quand un parcours change, mettre à jour en même temps :

1. son document dans ce dossier ;
2. le tableau de cet index si la chaîne d'appel change ;
3. le contrat OpenAPI si le comportement HTTP change ;
4. l'ADR concerné si la décision structurante change.
