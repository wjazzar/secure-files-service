# B-004 — API de lecture : liste, détail, compteurs

- **Date** : 2026-09-27
- **Outil** : Claude Opus 5 (sous-agent), puis Claude Opus 5.5 (reprise et relecture)
- **Objectif** : `GET /api/v1/files`, `GET /api/v1/files/{id}`, `GET /api/v1/files/summary`
- **Phase du projet** : back-end, étape 1 de la suite du cœur

## Prompt

> Confie l'étape 1 — l'API de lecture : liste, détail, compteurs — à un
> sous-agent, avec un cadrage complet. Je relirai le résultat.

Le sous-agent a reçu un cadrage complet (contrat, règles B-1 à B-12, pièges
connus, interdits). **Il a été interrompu par la limite de session** avant la
fin : code écrit, tests de persistance écrits, tests HTTP absents. La reprise a
été faite par la session principale, avec relecture intégrale.

## Ce que le sous-agent a bien fait

- **La traduction statut public → états internes vit dans le domaine**
  (`PublicStatus.internalStates()`), et elle est calculée en lisant
  `FileStatus` à l'envers : il n'existe pas de seconde table à maintenir, donc
  aucune dérive possible.
- **Le propriétaire est obligatoire par construction** : `FileQuery` ne peut
  pas être construit sans lui. Le cloisonnement n'est pas un paramètre qu'une
  implémentation pourrait oublier.
- **Pagination à nous** (`PageQuery`, `PageResult`) : la règle ArchUnit
  interdit `org.springframework..` dans `application`, donc `Page`/`Pageable`
  de Spring Data y sont bannis. Il a respecté la règle au lieu de la contourner.
- **Recherche écrite pour l'index** : `lower(original_filename) like lower(:p)`
  est l'expression exacte sur laquelle l'index trigramme de `V3` est construit ;
  les jokers `%` et `_` saisis par l'utilisateur sont échappés.
- Un identifiant mal formé répond `404` et non `400` : il désigne un fichier
  qui ne peut pas exister, et la réponse doit être identique à celle d'un
  fichier inconnu ou appartenant à autrui.

## Ce que j'ai corrigé en relecture

| Défaut | Correction |
|---|---|
| **Écart au contrat** : un fichier dont la promotion a échoué garde en base le verdict sain de sa dernière analyse, et l'API le publiait à côté d'un statut `FAILED` | Le verdict n'est publié que pour les états qu'il explique (`PROMOTING`, `AVAILABLE`, `INFECTED`, `UNSCANNABLE`). Le `switch` est exhaustif sans `default` : un nouvel état ne compile pas tant qu'il n'est pas classé. Test dédié |
| Un test de compteurs créait le fichier « en attente » **avant** de faire progresser les autres : la file rend le plus ancien, la fixture aurait refusé | Ordre corrigé, conformément à la règle documentée en tête de `FileFixtures` |
| Assertion `containsOnlyKeys((Object[]) …)` qui ne compilait pas | Corrigée |

## Ce que j'ai ajouté

- **`ReadApiTest`** : 19 tests en **HTTP réel** (port aléatoire), pas via un
  dispatcher simulé — requêtes conditionnelles et documents d'erreur sont
  exactement ce qu'un simulacre réussit quand la vraie pile échoue.
- **`ContractConformanceTest`** : 12 tests qui **lisent `contracts/openapi.yaml`**
  et comparent champ par champ les records de réponse, les énumérations, les
  codes d'erreur émis, la liste blanche des tris, la taille de page par défaut
  et son plafond. La règle B-12 devient une vérification.

## Vérifications effectuées

| Point | Résultat |
|---|---|
| Suite complète | **204 tests verts** |
| Conformité au contrat | 12/12 du premier coup |
| Recherche `LIKE … ESCAPE` et filtre `IN` sur énumérations PostgreSQL natives, via JPQL | Fonctionnent |
| Flux `ETag` → `304` sans corps, puis `200` après une transition | Vérifié en HTTP |
| Tri hors liste blanche (y compris une tentative d'injection) | `400 INVALID_PARAMETER`, `application/problem+json` |
