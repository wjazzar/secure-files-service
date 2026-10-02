# F-008 — Relecture du design et corrections

- **Date** : 2026-10-01
- **Outil** : Claude (Opus 5.5) via Claude Code
- **Objectif** : vérifier que le design du 30/09 n’a rien cassé, puis corriger ce qui a été trouvé
- **Phase du projet** : Front-end
- **Suite de** : [F-006](F-006-design-connexion-et-themes.md), [F-007](F-007-themes-liste-fichiers.md)

## Prompts

Relecture, au moment de clôturer le projet :

> Nous clôturons le projet : liste ce qui reste à faire, les décisions non
> prises et ce qui doit être clôturé. Le design a été modifié entre-temps :
> vérifie aussi qu'il n'a pas introduit de régression.

Puis, devant la liste des sept défauts relevés :

> Corrige tout de suite les sept défauts de design.

## Ce que la relecture a trouvé

Le design de F-006 et F-007 avait été réalisé avec un autre outil (Codex). Il
laissait le comportement intact : types, lint, 125 tests et compilation
passaient, les règles F-1 à F-15 n’étaient pas touchées. La relecture a porté
sur ce que ces contrôles ne voient pas.

| # | Défaut | Comment il a été trouvé |
|---|---|---|
| 1 | Thème sombre : icônes blanches sur pastel dans le parcours du fichier (1,4 à 1,7:1) | Calcul des contrastes des deux palettes, puis confirmation à l’écran |
| 2 | Thème sombre : icône du verdict et nom de la menace illisibles (1,1 à 1,25:1), sur l’écran de la démonstration EICAR | Idem |
| 3 | Deux systèmes de style : 1 265 lignes de CSS écrasaient des classes Tailwind restées dans le JSX ; sélecteurs par position dans le DOM ; palette définie deux fois | Lecture du diff |
| 4 | Thème clair : bleu pétrole à 4,48:1 sur le fond, pour 4,5 exigés | Calcul |
| 5 | Textes de 8 à 11 px | Lecture des feuilles, puis mesure dans le navigateur |
| 6 | Éclair de thème clair au chargement pour une préférence sombre | Lecture du code : la classe était posée dans un effet, après le premier rendu, lui-même retardé par l’authentification |
| 7 | Un `h2` avant le `h1` sur la page de connexion | Lecture du code |

## Ce qui a été fait

- **1 et 2** : `--solid-foreground` (blanc en clair, bleu nuit en sombre) pour
  tout texte posé sur un aplat de statut ; `bg-card` à la place de `bg-white`.
  Plus aucune couleur littérale dans les composants.
- **3** : une règle, écrite en tête des deux feuilles — une propriété, un seul
  endroit. Les classes utilitaires gardent la structure et le comportement ;
  la feuille porte l’habillage, sur des classes nommées. Les utilitaires
  écrasés sont retirés du JSX, les sélecteurs par position remplacés, la
  palette de la connexion ramenée à celle du thème.
- **4** : `--primary` passe à `#077689` (4,9:1 sur le fond). Le survol des
  boutons d’action s’éloigne de la couleur de leur texte au lieu d’éclaircir.
- **5** : plancher à 12 px. Deux ajustements pour téléphone : le filet de
  l’accroche et les chevrons du parcours disparaissent sous 540 px.
- **6** : `public/theme-init.js`, chargé de façon bloquante par `index.html`.
- **7** : le slogan devient un paragraphe.

Détail : [`frontend/ARCHITECTURE.md`](../../frontend/ARCHITECTURE.md) §14.10.

## Ce que j’ai rejeté ou corrigé, et pourquoi

- **Convertir les deux feuilles en classes Tailwind** : écarté. La page de
  connexion est cohérente en elle-même, et le défaut n’était pas l’existence
  d’une feuille mais le fait qu’elle contredisait le JSX. Une frontière écrite
  règle le problème sans réécrire 1 200 lignes d’un rendu validé.
- **Mettre les feuilles dans `@layer components`** : écarté. Les classes
  utilitaires des primitives shadcn l’emporteraient alors sur l’habillage, et
  il faudrait réécrire chaque primitive. Hors couche, et dit comme tel.
- **Un script en ligne dans `index.html`** pour le thème : écarté au profit
  d’un fichier. Un script en ligne interdit une `Content-Security-Policy`
  stricte, que l’audit de sécurité prévoit (S-13).
- **`text-primary-foreground` sur les aplats de statut** : c’est ce que F-007
  avait fait pour la file d’envoi. La couleur tombe juste dans les deux thèmes,
  mais par coïncidence ; remplacée par une variable qui dit ce qu’elle est.
- **Un écart signalé à tort lors de la relecture** : la mention de marque
  Praxedo existe bien, dans le pied de page de l’interface. Elle manque
  seulement au README.

## Vérifications effectuées

- **Le nettoyage ne change pas le rendu.** Avant toute modification, les
  styles calculés et les dimensions de chaque élément ont été enregistrés sur
  dix états (connexion, liste, détail ; clair et sombre ; 1280, 800 et
  375 px), puis comparés après le nettoyage. Écarts constatés, tous attendus :
  1 px d’espacement dans les badges du tableau, 4 px de hauteur de titre sur
  téléphone, deux marges sans effet.
- **Contrastes** recalculés sur les deux palettes ; les pastilles et le nom de
  la menace sont relus à l’écran sur un fichier EICAR en thème sombre
  (9,7:1).
- **Tailles** : aucun texte rendu sous 12 px, aucun débordement horizontal, à
  1280, 800, 375 et 320 px.
- **Thème** : la classe `dark` est présente alors que l’application n’a
  encore rien dessiné.
- **128 tests** (trois nouveaux, sur le script de thème), TypeScript, ESLint
  et compilation de production passent, ainsi que Prettier sur les fichiers
  modifiés. `npm run format:check` signale encore huit fichiers que ce
  changement ne touche pas : des fins de ligne CRLF, contenu identique.

## Ce qui reste

Les tests de
bout en bout et l’audit axe (Playwright) ne sont toujours pas en place : ils
auraient attrapé les défauts 1, 2 et 4.
