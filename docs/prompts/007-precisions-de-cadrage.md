# 007 — Nouvelles contraintes du 21/09 : ce qu'elles tranchent

- **Date** : 2026-09-23 (contraintes fixées le 2026-09-21)
- **Outil** : Claude (Opus 5) via Claude Code
- **Phase du projet** : cadrage — clôture

## Contexte

Le porteur du projet a fixé, le 21/09, de nouvelles contraintes issues de ses
échanges avec le recruteur, sur les sept points que l'énoncé laissait ouverts.
Synthèse et conséquences : [`../28-precisions-de-cadrage.md`](../28-precisions-de-cadrage.md).

## Prompt

> Voici de nouvelles contraintes, issues de mon échange avec le recruteur :
>
> 1. **Taille** : quelques Mo en usage courant, jusqu'à 500 Mo à prévoir ; pas
>    de fichiers de plusieurs Go, pas d'envoi par morceaux.
> 2. **Volumétrie** : aucun chiffre imposé ; à poser en hypothèse, à
>    documenter et à tenir.
> 3. **Déploiement** : plusieurs nœuds à prévoir dans l'architecture, sans
>    avoir à le démontrer ; un stockage partagé de type objet plutôt que le
>    disque local.
> 4. **Analyse** : asynchrone.
> 5. **Authentification** : non indispensable pour l'exercice.
> 6. **Outils** : Spring Boot, React et une base de données suffisent ; tout
>    ajout reste possible s'il est justifié.
> 7. **Interface** : le parcours dépôt → suivi → téléchargement suffit ;
>    liste, tri et recherche sont un plus.
>
> Intègre-les au cadrage : ce qu'elles confirment, ce qu'elles invalident, et
> les documents à corriger. Signale tout ce qui reste ambigu au lieu de le
> trancher en silence.

## Ce que les contraintes changent

| Contrainte | Effet |
|---|---|
| Taille jusqu'à 500 Mo, pas d'envoi par morceaux | Limite d'admission = limite d'analyse (500 Mo) ; le désaccord `D-15` avec la contre-analyse ChatGPT disparaît |
| Volumétrie non imposée | Hypothèse chiffrée à poser, à documenter et à tenir |
| Stockage objet type S3 | **Inverse la décision `P-08`** : le système de fichiers local est écarté ; l'isolation passe aux identifiants |
| Analyse asynchrone | `D-06` clos |
| Authentification non indispensable | Lecture retenue à ce stade : pas d'authentification, hypothèse documentée (révisée ensuite : ADR-0014) |
| Spring Boot + React + une base suffisent | Confirme la sobriété et l'absence de broker |
| Parcours dépôt → suivi → téléchargement ; liste en plus | Réordonne le périmètre du front ; les API de liste restent au contrat mais passent après |

## Ce que j'ai signalé plutôt que de le masquer

- **Le plafond par défaut de ClamAV reste inférieur à 500 Mo** : la
  configuration devra être relevée et versionnée, et la mémoire mesurée au
  spike.
- **Le statut du projet MinIO reste non vérifié** (affirmation de la
  contre-analyse ChatGPT, non corroborée). À trancher avant de choisir le
  stockage objet local.

## Répercussions appliquées

Contrat, contexte du front, catalogue des problématiques (`P-08`, `P-19`),
`AGENTS.md`, index de la documentation.

## Décisions restées humaines

Composition locale (`D-01`), choix du stockage objet local, conservation ou non
de la topologie à trois rôles, réponse HTTP sur fichier infecté (`D-09`).
