# 008 — Plan de développement et contexte back-end

- **Date** : 2026-09-23
- **Outil** : Claude (Opus 5) via Claude Code
- **Phase du projet** : passage du cadrage au développement

## Prompt

> Nous passons au développement, en deux sessions parallèles : l'une pour le
> front-end, l'autre pour le back-end. Prépare ce qu'il faut pour qu'elles
> avancent sans se gêner : le contexte du back-end, la répartition des
> fichiers entre les sessions, l'ordre des lots avec leur définition de
> « terminé », et les points d'intégration.

## Limite rencontrée

Cette version de Claude Code **ne dispose d'aucun outil pour créer une
session** : les outils de gestion de session permettent de lister, renommer,
archiver, envoyer un message, mais pas d'en ouvrir une. Les deux sessions sont
donc créées à la main par le porteur du projet, avec des amorces préparées.

## Livrables produits

| Fichier | Contenu |
|---|---|
| `backend/AGENTS.md` | Contexte back-end, qui manquait : cadrage confirmé, architecture en 4 modules, automate à 8 états, **les 4 protocoles critiques** (ingestion, claim, verdict avec jeton, promotion reprenable), base et corrections obligatoires, antivirus, résilience sans bibliothèque, 12 règles, tests, definition of done |
| `docs/30-plan-de-developpement.md` (retiré le 01/10 : `backend/PLAN.md` et `frontend/ARCHITECTURE.md` en tiennent lieu) | Répartition des fichiers entre les deux sessions, initialisation de Git, ordre de marche, lots B0-B7 et F0-F5 avec leur definition of done, point d'intégration bloquant, budget, **amorces de session à copier-coller**, risques |

## Points de vigilance identifiés

- **Deux sessions, un seul dossier** : sans règle de propriété des fichiers,
  elles se marchent dessus. D'où le tableau de répartition et l'initialisation
  de Git en préalable.
- **Collision de numéros dans le journal des prompts** : déjà survenue (deux
  entrées `008`). Préfixes `B-` et `F-` adoptés.
- **Le dépôt n'est toujours pas initialisé** alors que le livrable exige un
  dépôt public et que deux chantiers vont écrire en parallèle.
- **Les spikes ne doivent pas être sautés** : plafonds réels de l'antivirus et
  choix du stockage objet local. Une erreur sur l'un des deux coûte une
  journée.

## Décisions restées humaines

Création effective des deux sessions, initialisation de Git, choix du stockage
objet local après le spike.
