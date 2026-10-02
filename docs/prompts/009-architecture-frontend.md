# 009 — Architecture du front-end

- **Date** : 2026-09-25
- **Outil** : Claude (Opus 5.5) via Claude Code
- **Phase du projet** : préparation du développement front-end

## Prompts

1. « Planifions l'architecture du front-end. Propose et justifie, avant
   toute ligne de code : la consommation du contrat et des API, la
   validation des données, l'organisation du code (interfaces, packages) et
   les frameworks. »
2. Arbitrages du porteur du projet après la première proposition :
   axios plutôt qu'`openapi-fetch` ; Zod conservé pour la validation ; styles
   façon shadcn/ui (classes utilitaires) ; police simple chargée sans
   contrainte RGPD ; produit nommé **Praxedo** avec son logo ;
   **authentification Keycloak** ; recherche et pagination du tableau avec
   **TanStack Table** ; suppression de `GET /api/v1/limits`. Demande de vérifier
   **au moins trois fois** les bonnes pratiques d'organisation du code, puis
   de produire un `architecture.md` à valider.

## Méthode

Quatre recherches indépendantes menées en parallèle par des sous-agents :

| Recherche | Sources |
|---|---|
| Organisation n° 1 | `bulletproof-react` : documentation et code réel |
| Organisation n° 2 | Feature-Sliced Design (docs officielles et critiques), blog de TkDodo |
| Organisation n° 3 | react.dev, docs shadcn/ui, docs React Router, `shadcn-admin` |
| Faits techniques | Registre npm, RFC 10017, docs Keycloak, Zod 4, TanStack Table v9 |

## Faits établis qui ont modifié le plan

- **TanStack Table v9** est stable depuis le 04/08/2026 ; le guide *Data Table*
  de shadcn la cible.
- **React Router 8** exige Node ≥ 22.22 ; le poste est en 22.16.
- **TypeScript 7** n'est pas encore supporté par `typescript-eslint` → TS 6.0.
- **shadcn** utilise Base UI par défaut depuis juillet 2026.
- **RFC 10017** (BCP, août 2026) : le BFF est fortement recommandé pour les
  applications métier ; le client 100 % navigateur est déconseillé → choix
  argumenté dans le document, BFF en piste d'amélioration.
- Un lien natif ne peut pas porter de jeton `Bearer` → **lien de
  téléchargement signé à durée courte**.
- Un `z.enum` strict sur le statut violerait la règle F-2 → motif tolérant
  `z.string().pipe(z.enum([...]).catch('UNKNOWN'))`, testé sur Zod 4.6.

## Livrable

[`frontend/ARCHITECTURE.md`](../../frontend/ARCHITECTURE.md) — **proposition
à valider** : stack et versions, organisation du code, couche API, arbre de
composants, hooks, authentification, flux, règles F-1 à F-15, modifications du
contrat demandées au back-end, points à valider, lots F0 à F6.

## Décisions restées humaines

Les sept points du §12 du document (version de Node, pagination par numéro de
page, client public plutôt que BFF, Base UI ou Radix, logo, service du front,
budget).
