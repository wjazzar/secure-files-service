# F-001 — Front-end v1 (sans authentification)

- **Date** : 2026-09-25
- **Outil** : Claude (Opus 5.5) via Claude Code
- **Phase du projet** : développement front-end
- **Suite de** : [009](009-architecture-frontend.md) (architecture validée)

## Prompt

> « Lance le développement de l'interface, selon l'architecture validée.
> Reprends le logo officiel de Praxedo. Livre une première version sans
> authentification, mais prévue dès maintenant dans le contrat, pour qu'elle
> s'ajoute ensuite sans rupture. »

## Ce qui a été fait

| Étape | Détail |
|---|---|
| Contrat | `openapi.yaml` passé en **1.1.0-draft** : sécurité `[{}, bearerAuth]` (anonyme en v1, jeton en v2), `401`/`403` déclarés partout, cloisonnement par propriétaire décrit, `/limits` supprimé, pagination par numéro de page (forme `PagedModel`), tri en liste blanche, lien de téléchargement signé (`POST /files/{id}/download-link`, `GET /downloads/{token}`), `Cache-Control: no-cache` |
| Logo | SVG officiel extrait de l'en-tête de praxedo.com |
| Socle | Vite 8, React 19.3, TypeScript 6.0 strict, Tailwind 4.3, shadcn (Base UI, preset Nova), ESLint 9 avec règles d'import entre couches, Prettier |
| Code | Couche API (axios + Zod), file d'envois en classe pure, tableau TanStack Table v9 en mode serveur, état dans l'URL, panneau de détail en route enfant, téléchargement par lien signé, région `aria-live` |
| Bouchons | MSW conforme au contrat, scénarios choisis par le nom du fichier (EICAR, archive chiffrée, échec, surcharge) |
| Tests | 77 tests Vitest ; le test de dérive du contrat a été vérifié par mutation (ajout d'un statut au contrat → échec attendu) |
| Vérification | Parcours dans le navigateur intégré, bureau et mobile |

## Défauts trouvés en vérifiant, et corrigés

1. **Sens des couches inversé** : `lib/messages` et `lib/query-client`
   importaient `api/`. Déplacés dans `i18n/` et `api/`.
2. **Polling qui ralentissait pour un fichier neuf** : le délai reposait sur
   le nombre total de rafraîchissements de la requête, jamais remis à zéro.
   Il repose désormais sur l'ancienneté du dernier changement de statut.
3. **URL au-delà de la dernière page** : affichait « Aucun fichier » et une
   plage incohérente. Ramène désormais à la dernière page.
4. **Panneau de détail** trop étroit : les classes de base de shadcn
   (`data-[side=right]:…`) l'emportaient sur les nôtres.
5. **Libellés anglais** hérités de shadcn (« Close ») : traduits.
6. **Téléchargement en mode bouchon** : le service worker de MSW n'intercepte
   pas les navigations ; le bouchon renvoie une URL `blob:`, sans changer le
   code de l'interface.

## Points de vigilance

- **Paquet `cn`** importé par les composants shadcn récents : provenance
  vérifiée avant de l'accepter (dépôt `shadcn-ui/cn`, même mainteneur que
  shadcn). Il remplace `clsx` et `tailwind-merge`.
- **React Router 7** et non 8 : Node 22.16 est installé, RR 8 exige
  22.22. Décision laissée au porteur du projet.
- **Git toujours non initialisé** : aucun commit n'a été fait.

## Décisions restées humaines

Version de Node (RR 8), service du build en livraison (Spring Boot ou
serveur séparé), moment de la v2 (Keycloak).
