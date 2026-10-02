# F-005 — Authentification sans rôle : un espace de fichiers par utilisateur

- **Date** : 2026-09-25
- **Outil** : Claude (Opus 5.5) via Claude Code
- **Suite de** : [F-004](F-004-connexion-profils.md)

## Prompt

> « L'authentification, oui, mais sans rôles : il s'agit seulement d'un
> cloisonnement par utilisateur, chacun dans son propre espace de
> fichiers. »

## Ce qui a été retiré

Tout ce que F-004 avait ajouté pour les rôles :

- contrat : `GET /api/v1/me`, schémas `CurrentUser`, `Permissions`,
  `FileOwner`, champ `FileSummary.owner`, réponses `403` et code
  `FORBIDDEN`, tableau des rôles ;
- interface : profils superviseur et auditeur, colonne « Propriétaire »,
  encart « lecture seule », titre « Fichiers de l'équipe », note « téléchargement
  non autorisé », rôle dans le menu utilisateur ;
- bouchons : permissions par rôle, `downloadable` calculé selon l'appelant.

## Ce qui reste

- Contrat **1.3** : chaque utilisateur authentifié n'accède qu'à ses propres
  fichiers ; le fichier d'un autre répond `404`.
- Page de connexion, fournisseur d'identité simulé, trois comptes
  (`alice`, `bob`, `claire`, mot de passe `demo`), garde de route, menu
  utilisateur, toutes les mesures de sécurité de F-004.

Détail : [`frontend/ARCHITECTURE.md`](../../frontend/ARCHITECTURE.md) §14.7.

## Pourquoi c'est mieux

Conforme à la règle directrice du projet : un élément n'entre que s'il répond
à un besoin nommé. Le besoin est un cloisonnement, pas une gestion de droits.
Le résultat est plus simple à défendre, à implémenter côté back-end (un
filtre sur `owner_id = sub`) et à tester.

## Vérifications

- 103 tests, types, lint, formatage, build.
- Recherche dans le code : aucune trace de rôle ni de permission.
- Navigateur intégré : page de connexion à trois comptes neutres, connexion
  d'Alice (9 fichiers, dépôt disponible, pas de colonne « Propriétaire »).
