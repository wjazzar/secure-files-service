# F-006 — Nouveau design de connexion et choix du thème

- **Date** : 2026-09-30
- **Outil** : Codex
- **Objectif** : refaire la page de connexion, puis ajouter un thème clair confortable et un choix d’apparence mémorisé.
- **Phase du projet** : Front-end

## Prompts

> Refais le design de la page de connexion : l'actuel ne me convient pas. Je
> veux une proposition créative, avec une animation discrète, un alignement
> soigné, et une inspiration tirée des interfaces futuristes actuelles. Elle
> doit marquer.

Retour du porteur du projet pendant la réalisation :

> Le rendu sombre est réussi, mais je préfère un thème clair et simple.
> Ajoute un choix de thème, avec un thème clair, confortable pour les yeux.

## Proposition réalisée et correction retenue

- Première proposition : composition bleu nuit et cyan, coffre holographique dessiné en SVG, orbites lentes, panneau de connexion et profils de démonstration alignés.
- Après le retour : **clair par défaut**, fond blanc cassé, accents bleu pétrole et carte claire ; la version sombre reste disponible.
- Sélecteur **Clair / Sombre** partagé par la connexion et l’espace de fichiers. Seule la préférence visuelle est conservée dans `localStorage`. Si le stockage est bloqué, le changement reste fonctionnel pendant la session.
- Palette de connexion isolée dans `features/auth/styles/login.css` ; variantes du reste de l’interface dans les variables sémantiques de `styles/globals.css`.
- Illustration SVG et animations CSS, sans dépendance supplémentaire. Pause accessible ; respect de `prefers-reduced-motion`, de `prefers-reduced-transparency` et solution de repli sans flou de fond.
- Textes centralisés dans `i18n/messages.ts`. Les mêmes composants de connexion sont conservés pour le formulaire simulé et la redirection vers le fournisseur d’identité.

## Sources consultées

- [Aurora Glass — Superdesign](https://superdesign.dev/styles/glassmorphism/login) : hiérarchie du formulaire, surfaces translucides et focus visible.
- [Futuristic AI Login Experience — Behance](https://www.behance.net/gallery/245384265/Futuristic-AI-Login-Experience-Smart-Access-UI) : direction futuriste sobre.

L’illustration et la composition sont réalisées dans le dépôt ; aucun visuel tiers n’est repris.

## Ce qui a été corrigé ou écarté

- Le sombre initial n’est plus le thème par défaut, conformément à la préférence explicite du porteur du projet.
- Pas de moteur 3D ni de bibliothèque d’animation : SVG et CSS suffisent à l’effet demandé.
- Pas de faux indicateur d’état du service ; la visualisation est décorative.
- Les grands effets visuels disparaissent sur les petits écrans pour laisser la place à la connexion.
- Mention de connexion chiffrée retirée du mode simulé : elle ne serait pas exacte sur un serveur de développement HTTP.

## Vérifications effectuées

- Suite front : **125 tests passent**, dont cinq tests de préférence d’apparence (défaut clair, clavier, restauration, valeur invalide et stockage bloqué).
- TypeScript et ESLint : passent.
- Compilation de production : passe.
- Vérification dans le navigateur : les deux palettes, pause effective des trois animations décoratives, préférence conservée après actualisation, rendu à 1280 × 720, 390 × 844 et 320 × 740, sans débordement horizontal.
- Les tests existants de connexion, de redirection et de cloisonnement par utilisateur restent verts.
