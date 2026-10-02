# F-007 — Thèmes clair et sombre de la liste de fichiers

- **Date** : 2026-09-30
- **Outil** : Codex
- **Objectif** : appliquer le design de connexion à l’espace de fichiers en conservant ses composants.
- **Phase du projet** : Front-end

## Prompt

> Applique le même design, en versions claire et sombre, à la page de liste
> des fichiers, en conservant ses composants.

## Réalisation

- Palettes communes accordées à la connexion : blanc cassé, surfaces claires et bleu pétrole ; bleu nuit et cyan dans la variante sombre.
- Habillage des composants existants dans `styles/workspace.css` : en-tête, menu utilisateur, introduction, dépôt, recherche, onglets, tableau, pagination et panneau de détail.
- Bordures douces, cartes arrondies, fond discret et boutons cohérents. Couleurs de statut conservées, accompagnées de leur libellé et de leur icône ; couleurs d’icônes de fichiers adaptées aux deux thèmes.
- Sélecteur Clair / Sombre partagé et préférence mémorisée comme sur la connexion. Libellés accessibles explicites pour les boutons réduits à une icône sur téléphone.
- Même comportement des composants : dépôt et progression, filtres, recherche, tri, pagination, détail et téléchargement natif. Aucune modification de la décision `downloadable`.
- Adaptation mobile : dépôt au-dessus du tableau, défilement horizontal contenu dans le tableau ; aucun débordement horizontal de la page.
- Aucun ajout de dépendance. Décor statique ; préférences de mouvement réduit et de transparence réduite respectées.

## Vérifications

- Suite front complète : **125 tests passent**. Les cinq tests du sélecteur de thème repassent après l’ajout des libellés accessibles sur mobile.
- TypeScript, ESLint et compilation de production : passent.
- Inspection dans le navigateur des deux palettes sur ordinateur et téléphone (390 × 844 et 320 × 740).
- Filtre « Bloqués » : seul le fichier non analysable est affiché. Son détail s’ouvre avec les explications, le parcours et les métadonnées ; aucun téléchargement n’est proposé.
- Aperçus enregistrés des deux thèmes ; les composants de la liste restent en place.
