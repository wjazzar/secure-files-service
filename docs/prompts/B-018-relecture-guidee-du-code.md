# B-018 — Relecture guidée du code : neuf remarques, arbitrées puis appliquées

- **Date** : 2026-09-30
- **Outil** : Claude Code (Claude Opus 5.5)
- **Objectif** : donner un avis sur les remarques de relecture du porteur du
  projet (`backend/docs/07-remarques-relecture.md`), puis les appliquer
- **Phase du projet** : back-end, qualité du code avant la livraison

## Prompts

> J'ai relu le code et consigné mes remarques. Donne ton avis sur chacune
> avant toute modification.

> Applique les actions. Pour R-003, je retiens l'option 2 : corriger le
> problème d'architecture.

> Pour R-001 : même si l'interface ne s'en sert pas, un client d'API en
> profitera. Un `Retry-After` adapté a du sens ; qu'en penses-tu ?

> Implémente la règle.

## Ce qui s'est passé

| Remarque | Avis rendu | Ce qui a été fait |
|---|---|---|
| R-001 `Retry-After` selon la taille | D'abord : gain faible (l'interface suit la liste, « un `304` ne coûte rien »). **Révisé** sur l'objection du porteur : le `304` relit la ligne et vérifie le jeton, et les systèmes tiers de l'énoncé interrogent chaque fichier | Règle unique `PollingHeaders` : 2 s + 0,05 s/Mio, au plus 30 s, arrondie au plus proche ; sur le `202`, le `200` et le `304` ; front inchangé |
| R-002 `files` → `storedFileRepository` | D'accord | Fait |
| R-003 `StoredFile` entité JPA dans le domaine | Diagnostic juste. L'incohérence était dans la documentation (la règle ArchUnit portait l'exception honnêtement). Option 1 recommandée | **Option 2 retenue par le porteur** : agrégat pur, `StoredFileEntity`, *mapper*, dépôt Spring Data en lecture seule ; ADR-0015 |
| R-004 `FileQuery` → `FileSearchCriteria` | Le nom actuel suit le vocabulaire *Command/Query* (`UploadCommand`, `QueryFilesUseCase`) ; l'objet sert aussi aux compteurs | Nom gardé, Javadoc explicite |
| R-005 `wanted` | D'accord | `requestedPublicStatuses`, et `internalStates` en face |
| R-006 Point de composition | D'accord, en ne déplaçant que le câblage des ports ; piège : un paquet hors de toute couche échappe silencieusement à `layeredArchitecture()` | Paquet `config`, couche `composition` étendue, règle nouvelle : tout `@Bean` qui fournit un port est déclaré dans `config` |
| R-007 Borne globale des appels S3 | Juste, à préciser : côté worker la correction n'est pas en jeu (jeton), côté dépôt le risque réaliste est le client au compte-gouttes ; `apiCallTimeout` ne couvre pas la lecture d'un `ResponseInputStream` (vérifié dans la Javadoc du SDK 2.55.6) | `DeadlineInputStream` : dépôt borné à 60 s + 8 s/Mio (`408 UPLOAD_TOO_SLOW`, contrat 1.8, front), analyse et promotion bornées par leur bail. Corrige l'audit S-06 |
| R-008 Idempotence | Déplacer le port et l'adaptateur ensemble, ou aucun des deux ; garder le nom | Les deux déplacés sous le concept `idempotency` |
| R-009 `UploadAdmission` | D'accord ; problème plus profond : un même concept sous trois noms (`countActiveWork`, `maxPendingFiles`, statut public `PENDING`) | Vocabulaire unifié (« pending » = non terminal, défini une fois), `PendingFileCountCache` extrait, test concurrent |

## Décisions restées humaines

- **R-003, option 2** : du porteur, contre la recommandation de l'assistant
  (option 1, jugée suffisante pour l'exercice). L'argument du porteur : une
  architecture hexagonale affichée ne peut pas porter d'exception dans son
  domaine.
- **R-001, un `Retry-After` adapté pour l'API** : du porteur. L'avis rendu
  l'avait écarté ; l'objection (« ça peut servir en API ») était juste.
- **R-007, S-06** : l'audit du 29/09 classait le dépôt lent en limitation
  acceptée, confiée à la passerelle. L'avis rendu recommandait une échéance
  côté service ; le porteur a demandé d'appliquer les actions proposées. Le
  seuil (~1 Mbit/s) est une hypothèse posée au README §9, à confirmer.

## Ce que j'ai rejeté ou corrigé, et pourquoi

- **« Un `304` ne coûte presque rien »**, mon argument contre R-001 : faux.
  Il épargne le corps, pas la vérification du jeton ni la lecture de la
  ligne, puisque l'`ETag` est la version de cette ligne. Corrigé après
  l'objection du porteur ; en l'implémentant, relevé aussi que le `304` ne
  portait pas le `Retry-After` — un client qui revalide ne l'aurait presque
  jamais vu.

- **Recalculer les ancres des scénarios par le seul diff** : les liens vers
  `UploadFileService`, `FileScanService` et `ApiExceptionHandler` étaient déjà
  décalés dans `HEAD` (jusqu’à une quinzaine de lignes). Le recalcul automatique aurait
  propagé l'erreur ; ces ancres ont été reprises une par une, puis toutes
  vérifiées contre le code.
- **Un `apiCallTimeout` sur les clients S3** : la Javadoc du SDK dit que les
  opérations en flux ne sont pas couvertes une fois la réponse rendue ; il
  n'aurait pas borné ce que la remarque visait.
- **Garder `activeWorkCount` comme proposé dans la remarque** : il aurait
  prolongé le mélange des deux vocabulaires.

## Vérifications effectuées

- suite complète au vert : 469 tests et 2 tests de mémoire, contre PostgreSQL,
  SeaweedFS et ClamAV réels (`mvnw verify`) ; 478 et 2 après la révision de
  R-001 ;
- front : vérification des types, 120 tests (dont la dérive contrat/schémas),
  ESLint et Prettier sur les fichiers modifiés ;
- nouvelles règles ArchUnit vérifiées au vert ; chaque ancre de ligne des
  scénarios relue contre le code.
