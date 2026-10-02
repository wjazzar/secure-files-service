# F-003 — Ergonomie des filtres et haut de page

- **Date** : 2026-09-25
- **Outil** : Claude (Opus 5.5) via Claude Code
- **Suite de** : [F-002](F-002-design-enrichi.md)

## Prompt

> « Nettement mieux : les panneaux gauche et droit fonctionnent bien. Deux
> points restent à revoir : le haut de page manque de lisibilité, et l'on ne
> voit pas quel filtre est sélectionné. L'ergonomie est prioritaire : un
> filtre doit s'appliquer en un clic, et son état doit être visible. »

## Réponse

Détail et justification : [`frontend/ARCHITECTURE.md`](../../frontend/ARCHITECTURE.md) §14.6.

- Haut de page compact ; garantie en pipeline d'une ligne.
- Un seul mécanisme de filtre : **onglets de statut** en un clic, onglet
  sélectionné rempli en bleu nuit, compteurs intégrés.
- Recherche placée dans l'en-tête de la carte ; état vide avec « Voir tous
  les fichiers ».

## Vérifications

- Navigateur intégré, bureau et mobile : sélection visible, URL correcte,
  onglets sur deux lignes en mobile sans défilement horizontal.
- **Défaut trouvé par un test ajouté** : la recherche se réappliquait après
  une réinitialisation (valeur debouncée périmée). Corrigé avec
  `useEffectEvent`, puis vérifié dans le navigateur.
- **Incident d'environnement** : le serveur lancé hors de Claude écoutait
  uniquement en IPv6 (`::1`), et le navigateur intégré ne pouvait plus
  charger les modules ni enregistrer le service worker de MSW. Le serveur est
  désormais fixé sur `127.0.0.1`, et le démarrage ne produit plus de page
  blanche si MSW échoue.
- `typecheck`, `lint`, 79 tests et `build` passent.
