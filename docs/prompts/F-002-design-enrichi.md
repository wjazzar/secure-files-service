# F-002 — Design enrichi

- **Date** : 2026-09-25
- **Outil** : Claude (Opus 5.5) via Claude Code
- **Suite de** : [F-001](F-001-frontend-v1.md)

## Prompt

> « La base est bonne. Le design, en revanche, reste trop minimaliste :
> travaille-le davantage. »

## Réponse

Le détail des ajouts est dans
[`frontend/ARCHITECTURE.md`](../../frontend/ARCHITECTURE.md) §14.5. Principe
suivi : rester un **portail professionnel** (lisible, sobre, charte Praxedo),
et faire porter au design le message de l'exercice, la garantie « aucun
fichier servi sans analyse » : bandeau en trois étapes, verdict et frise du
parcours dans le détail.

## Vérifications

- Parcours contrôlé dans le navigateur, sur bureau (1 440 px) et en largeur
  mobile (375 px) : fichier sain, EICAR, envoi en cours, cartes de synthèse.
- Défauts trouvés et corrigés : colonne « Déposé le » coupée (le tableau
  débordait de sa carte), en-tête de la file d'envois qui passait à la ligne,
  libellés des cartes tronqués sur mobile, étapes trop hautes sur mobile.
- Nouveau test : un clic sur la carte « Bloqués » filtre le tableau sur
  `INFECTED`, `UNSCANNABLE` et `FAILED`.
- `typecheck`, `lint`, 78 tests et `build` passent.
