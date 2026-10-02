# F-004 — Page de connexion et profils d'accès

- **Date** : 2026-09-25
- **Outil** : Claude (Opus 5.5) via Claude Code
- **Suite de** : [F-003](F-003-ergonomie-filtres.md)

## Prompt

> « Réalise la page de connexion (identifiant et mot de passe). Tant que
> Keycloak n'est pas branché, simule le fournisseur d'identité, avec des
> accès différents selon l'utilisateur. »

## Décision prise en autonomie (le « comment »)

Le formulaire est réel **en mode simulé**. Avec Keycloak, le même écran sera
le thème Keycloak : une application ne doit jamais collecter le mot de passe
elle-même (grant « password » exclu par OAuth 2.1 et la RFC 10017).
L'adaptateur d'authentification expose `loginMode: 'form' | 'redirect'`.

## Réalisé

Détail : [`frontend/ARCHITECTURE.md`](../../frontend/ARCHITECTURE.md) §14.7.

- Contrat **1.2** : `GET /api/v1/me` (utilisateur, rôles, permissions),
  `FileSummary.owner`, trois rôles documentés, `downloadable` calculé pour
  l'appelant.
- Fournisseur d'identité simulé (MSW) : connexion, session SSO, déconnexion
  avec révocation, verrouillage après 5 échecs.
- API simulée qui applique les droits : périmètre, `403` sur dépôt et
  téléchargement, `404` sur le fichier d'un autre.
- Page de connexion (panneau de marque, formulaire accessible, comptes de
  démonstration en un clic), garde de route, menu utilisateur, encart
  « lecture seule », colonne « Propriétaire ».

## Défauts trouvés et corrigés en vérifiant

1. La garde de route perdait l'adresse de retour après une déconnexion.
2. L'information « Vous êtes déconnecté » portait le rôle `alert` (celui de
   shadcn par défaut) : passé à `status`.
3. Initiales des propriétaires réduites à une lettre : la barre oblique
   inverse de l'expression régulière avait été perdue lors d'une
   modification scriptée. La fonction, présente en trois exemplaires, est
   désormais factorisée (`lib/initials.ts`) et testée.
4. Flèche du bouton « Se connecter » placée avant le texte.

## Vérifications

- 109 tests, types, lint, formatage, build.
- Navigateur intégré : mauvais mot de passe, connexion en un clic (David,
  puis Bob), session conservée au rechargement, déconnexion par le menu,
  page de connexion en largeur mobile.
