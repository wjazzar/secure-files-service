# B-013 — Scénarios pas à pas, un par port d'entrée

- **Date** : 2026-09-27
- **Outil** : Claude Opus 5.5
- **Objectif** : permettre au porteur du projet de suivre le code ligne à ligne, de bout en bout, pour l'analyser en profondeur
- **Phase du projet** : back-end, préparation de la revue du code

## Prompt

> Complète cette documentation par un scénario pas à pas pour chaque port
> d'entrée : un cas concret suivi ligne à ligne, du déclencheur jusqu'au SQL
> et au stockage, branches d'erreur comprises. Le but : pouvoir analyser le
> code en profondeur en le suivant.

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| Un dossier à part, **`backend/scenarios/`**, plutôt que de compléter `backend/docs/` | `backend/docs/` a été rédigé par Codex (B-012). La règle du projet interdit de mélanger deux sources dans un document ; les deux se complètent : Codex explique les parcours au niveau des classes, ceux-ci sont la **trace ligne à ligne** |
| **Un scénario concret** par port d'entrée (Alice dépose `rapport.pdf`…), pas une description générale | On suit une exécution comme au débogueur : des données précises rendent chaque branche lisible |
| Pour chaque document : une **carte** (couche par couche, avec les numéros de ligne), un **pas à pas** en tableaux (ligne → ce qui se passe, SQL et appels S3/HTTP compris), les **branches d'erreur**, **ce qui a changé** en base et dans le stockage, des **points d'arrêt** et les **tests** qui rejouent le scénario | C'est ce qu'il faut pour relire le code en profondeur et pour le défendre |
| Numéros de ligne relevés sur l'arbre de travail **modifications en cours comprises** (nettoyage du code mort, contrôleurs retouchés) | Des lignes relevées sur le dernier commit auraient été fausses le jour même |

## Ce que j'ai vérifié

| Point | Résultat |
|---|---|
| Existence de chaque fichier lié et de chaque ancre `#Lxx` | 236 liens, aucun cassé |
| Les liens nommés `Classe.méthode` tombent sur la déclaration | 99 ancres contrôlées ; les six qui visaient l'annotation `@Override` au lieu de la déclaration ont été recalées |
| Les tests cités dans « Rejouer » existent encore | Oui, malgré les retouches récentes des tests |
| Un fichier déposé est-il aussitôt éligible au worker ? | Oui : `next_attempt_at` prend sa valeur par défaut en base (`clock_timestamp()`), l'entité ne la porte pas — vérifié dans `V2__stored_file.sql` |

## Ce que j'ai rejeté

| Option | Pourquoi |
|---|---|
| Modifier les documents de Codex | Mélange de sources, et leur angle (le « pourquoi ») est différent |
| Des diagrammes de séquence seuls | Ils disent qui appelle qui, pas ce que fait chaque ligne ; Codex en fournit déjà |
| Relever les lignes sur le dernier commit | L'arbre de travail contient des modifications non commitées d'autres sessions |
