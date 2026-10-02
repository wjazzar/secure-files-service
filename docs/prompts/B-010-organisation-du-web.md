# B-010 — Organiser l'adaptateur web par concept, puis par nature

- **Date** : 2026-09-27
- **Outil** : Claude Opus 5.5
- **Objectif** : rendre le paquet `infrastructure/web` lisible d'un coup d'œil
- **Phase du projet** : back-end, relecture du porteur du projet

## Prompt

> Relecture de l'adaptateur web. Le code est bien avancé, mais
> `infrastructure/web` mélange contrôleurs, DTO (`FileSummaryResponse`…) et
> exceptions : on ne distingue pas leur rôle au premier coup d'œil.
> Organise-le par concept, puis par nature (contrôleur, DTO, mapper,
> exception), comme tu l'as fait pour l'antivirus. Le découpage par concept
> doit tenir si d'autres sujets que les fichiers s'ajoutent.

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| **Par concept d'abord** (`file`, `download`, `common`), **puis par nature** (`controller`, `dto`, `mapper`, `exception`, `header`, `error`, `filter`, `io`) | Le concept dit *de quoi* parle le code, la nature dit *ce que c'est*. Un nouveau sujet reçoit son dossier et les mêmes sous-dossiers |
| `common/` pour ce qui est partagé : traduction des erreurs, filtre de corrélation, flux de comptage | Ces classes n'appartiennent à aucun concept ; les ranger sous `file` aurait menti |
| `FileResponses` renommé **`FileResponseMapper`** | Le nom doit dire la nature, comme le dossier |
| **`DownloadHeaders`** extrait de `DownloadController` | `Content-Disposition` et `Range` sont deux règles HTTP délicates, testées à part : elles méritent leur classe, et le contrôleur redevient un contrôleur |
| **`WebLayoutRulesTest`** (ArchUnit) | Une convention écrite seulement dans un document s'érode ; celle-ci fait échouer la construction |
| Tests déplacés **en miroir** | Retrouver le test d'une classe sans chercher |

## Ce que j'ai rejeté

| Option | Pourquoi |
|---|---|
| Par nature seulement (`web/controller`, `web/dto`, …) | Mélange les sujets dès qu'il y en a plus d'un : ce que le porteur du projet demandait justement d'éviter |
| Par concept seulement (`web/file/*` à plat) | Ne répond pas à « est-ce un contrôleur, un DTO, une exception ? » |

## Vérifications effectuées

| Point | Résultat |
|---|---|
| Déplacements par `git mv` | Historique conservé (renommages détectés) |
| Aucun changement de comportement | **387 tests verts** (381 + 6 règles de disposition), preuves mémoire comprises |
