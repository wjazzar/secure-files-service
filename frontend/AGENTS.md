# frontend/AGENTS.md — Contexte du front-end

> **À lire après [`../AGENTS.md`](../AGENTS.md)**, qui fixe la nature du projet
> (test technique, pas un projet client) et les règles communes.
>
> **L'architecture détaillée est dans [`ARCHITECTURE.md`](ARCHITECTURE.md)** :
> stack et versions, organisation du code, couche API, composants, hooks,
> authentification, flux. Ce fichier ne garde que l'essentiel pour travailler.
>
> Écrit le 2026-09-25, relu contre le code le 02/10 : **front livré**. Il
> n'existe aucun mode sans authentification (ADR-0014) : `session` par
> défaut, `mock` sur les bouchons.

---

## 1. Rôle du front dans l'exercice

Le cœur évalué est le service back-end (garantie antivirus, flux,
résilience). Le front est la **vitrine des compétences React** et le support
de la démonstration : on y voit un fichier passer de « en attente » à
« disponible », et un fichier EICAR rester bloqué.

Deux exigences :

1. **Ne rien affaiblir de la garantie de sécurité** : aucune logique de
   téléchargeabilité côté client, aucun fichier chargé en mémoire.
2. **Montrer un React de niveau senior** : état serveur géré proprement,
   découpage par fonctionnalité, accessibilité, tests.

---

## 2. Périmètre

| Fonctionnalité | État |
|---|---|
| Dépôt multiple (glisser-déposer + sélecteur), progression, annulation, relance avec la même `Idempotency-Key`, refus avant envoi (> 500 Mo, vide) | ✅ v1 |
| Tableau paginé : recherche par nom, onglets de statut en un clic avec compteurs, tri, taille de page, état dans l'URL | ✅ v1 |
| Suivi automatique (polling) jusqu'au verdict, annonces `aria-live` | ✅ v1 |
| Panneau de détail : verdict, explication des états bloqués, métadonnées | ✅ v1 |
| Téléchargement par navigation native (état relu au clic, cookie de session, sans lien signé — ADR-0013) | ✅ |
| Page de connexion + un espace de fichiers par utilisateur (mode simulé) | ✅ — voir `ARCHITECTURE.md` §14.7 |
| Authentification Keycloak | ✅ **par défaut** (28/09, ADR-0012) : adaptateur `session` — le service est le client confidentiel, le navigateur n'a qu'un cookie `HttpOnly` ; `npm run dev` (le service exige toujours l'authentification) |
| Tests de bout en bout Playwright + axe | Écartés (décision du 01/10) |
| Suppression, réanalyse, temps réel (SSE) | Hors périmètre |

---

## 3. Contrat

**Source de vérité : [`../contracts/openapi.yaml`](../contracts/openapi.yaml)**
(version `1.10.0-draft`), justifiée dans
[`../contracts/README.md`](../contracts/README.md).

- Le front **reflète** le contrat en schémas Zod (`src/api/files/files-schemas.ts`,
  `src/api/problem-schema.ts`). Les types en sont dérivés (`z.infer`).
- `files-schemas.contract.test.ts` compare ces schémas au contrat : toute
  dérive fait échouer les tests.
- Le contrat appartient à la session back-end : un manque se **signale**, il
  ne se corrige pas depuis le front.

---

## 4. Règles non négociables

Détail et justification : [`ARCHITECTURE.md`](ARCHITECTURE.md) §9. Une
violation est un défaut bloquant en revue.

| # | Règle |
|---|---|
| F-1 | « Télécharger » dépend **uniquement** de `downloadable` |
| F-2 | Valeur inconnue (statut, raison, code) → affichage neutre, jamais d'erreur |
| F-3 | Envoi : le `File` tel quel (axios, adaptateur `xhr`), jamais lu en mémoire |
| F-4 | Téléchargement : navigation native vers `links.content`, jamais `fetch` + `Blob` |
| F-5 | État du fichier relu **au clic**, pour expliquer un refus (la garantie reste au serveur) |
| F-6 | Une `Idempotency-Key` par fichier, réutilisée à chaque relance |
| F-7 | `X-File-Name` encodé par `encodeURIComponent` |
| F-8 | Taille vérifiée avant envoi ; le serveur reste l'autorité |
| F-9 | Noms de fichiers en texte brut ; `dangerouslySetInnerHTML` interdit (ESLint) |
| F-10 | Aucune règle métier dans le front |
| F-11 | Toute chaîne visible dans `src/i18n/` : `messages.ts`, et `mock-messages.ts` pour le seul mode bouchon, absent de tout build |
| F-12 | WCAG 2.1 AA : clavier, focus visible, `aria-live`, jamais la couleur seule |
| F-13 | Aucun jeton persistant ; avec la session du service, aucun jeton du tout côté navigateur (cookie `HttpOnly`) |
| F-14 | `axios` seulement dans `src/api/` (ESLint) ; l'authentification seulement dans `src/lib/auth/` |
| F-15 | Toute réponse d'API validée par son schéma Zod |

---

## 5. Commandes

```bash
npm install
npm run dev:mock      # interface seule, API simulée par MSW (aucun back requis)
npm run dev           # interface + proxy /api vers http://localhost:8080, connexion Keycloak par le service (défaut)
npm run test          # Vitest
npm run lint          # ESLint (dont les règles d'import entre couches)
npm run typecheck     # TypeScript strict
npm run build
```

Comptes du mode bouchon (mot de passe `demo`) : `alice`, `bob`, `claire`.
Chacun ne voit que son propre espace de fichiers. Un clic sur la page de
connexion suffit.

Scénarios du mode bouchon, choisis par le nom du fichier déposé : `eicar` →
menace détectée ; `chiffre` / `encrypted` → non analysable ; `echec` /
`fail` → échec de l'analyse ; `surcharge` → `429` ; tout autre nom →
disponible après environ 6 s. Ces comptes et ces scénarios sont ceux du
fournisseur simulé ; ils n'existent dans aucun build.

---

## 6. Design

- Style **shadcn/ui** (primitives Base UI) + Tailwind v4, palettes claire et
  sombre communes dans `src/styles/globals.css`. Onglets de statut en un clic
  et frise du parcours : voir `ARCHITECTURE.md` §14.5 et §14.6. L’espace de
  fichiers reprend l’apparence de la connexion depuis le 30/09 (§14.9).
- Marque **Praxedo** : logo officiel récupéré sur praxedo.com, tracé repris
  dans `src/components/brand/praxedo-logo.tsx`. Le pied de page de
  l'interface précise que la marque appartient à son propriétaire et n'est
  utilisée que pour ce test technique.
- Police **Inter** (Google Fonts).
- Connexion redessinée le 30/09 : illustration SVG animée et **thème clair
  par défaut**, selon la préférence du porteur du projet. Sélecteur
  Clair / Sombre partagé avec l’espace de fichiers et choix mémorisé.
  Une seule palette, dans les variables de `styles/globals.css` ; habillage
  de la connexion dans `features/auth/styles/login.css`, de l’espace de
  fichiers dans `styles/workspace.css` ; détails en `ARCHITECTURE.md` §14.8–14.10.
- **Règle des feuilles d’habillage** (01/10) : une propriété, un seul endroit.
  Structure et comportement en classes utilitaires dans le JSX ; surfaces,
  bordures, rayons et tailles de texte dans la feuille, sur une classe nommée,
  jamais sur une position dans le DOM. Aucune couleur littérale (`text-white`),
  aucun texte sous 12 px.
- Couleurs de statut, contrastes WCAG et microcopie : `ARCHITECTURE.md` §8 et
  §14 ; libellés dans `src/i18n/messages.ts`.

---

## 7. Definition of done

- [x] Schémas Zod alignés sur le contrat, vérifiés par un test de dérive
- [x] Règles F-1 à F-15 respectées ; tests des règles critiques verts
- [x] Parcours sur bouchons : dépôt → suivi → disponible → téléchargement ;
      EICAR → « Menace détectée », aucun bouton de téléchargement
- [x] `typecheck`, `lint`, `test` et `build` passent
- [ ] Parcours vérifié sur le vrai back-end, dont un fichier de 500 Mo sans
      hausse de la mémoire de l'onglet
- [ ] ~~Playwright (parcours réels) + audit axe~~ — écarté le 01/10
- [x] v2 : Keycloak, par le service (client confidentiel, cookie `HttpOnly`) — vérifié dans un navigateur contre un vrai Keycloak
