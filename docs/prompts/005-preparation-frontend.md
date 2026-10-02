# 005 — Préparation du front-end : contexte, contrat, design

- **Date** : 2026-09-21
- **Outil** : Claude (Opus 5) via Claude Code, + un agent design dédié
- **Phase du projet** : front-end — préparation

## Prompt

> Passons au front-end. Son périmètre est simple, mais le contrat doit
> s'enrichir des API de liste. Prépare :
>
> 1. le dossier `frontend/` et son contexte détaillé ;
> 2. le contrat d'API nécessaire à l'implémentation, partagé avec le
>    back-end ;
> 3. un premier draft de design, confié à un agent dédié. Pour l'identité
>    visuelle, appuie-toi sur Praxedo : relève sur leur site leurs couleurs,
>    leur typographie et leurs produits, et adapte l'interface à leur univers.

## Recherche sur Praxedo

Lecture de praxedo.com avec le navigateur intégré, **sans interagir avec la
bannière de cookies** (lecture du DOM et des styles uniquement).

- Palette nommée dans les variables CSS du site : `rouge-praxedo` #E91B31,
  `bleu-praxedo` #33ADFF, `light-bleu-praxedo` #D6EFFF, `dark-blue-text`
  #0A2D40, `bleu-fonce-header` #08293C, `gris-praxedo` #566277, `medium-grey`
  #C5D1D9, `grey-push` #F2F4F8, `light-grey` #FAFAFC.
- Typographie : Niveau Grotesk (commerciale, non réutilisable).
- Style : boutons à angles droits, bouton principal rouge, texte bleu nuit.
- Produit : logiciel SaaS de gestion d'interventions terrain (planification,
  application mobile des techniciens, portail client, rapports
  d'intervention). Le service de fichiers de l'exercice s'insère naturellement
  dans ce portail client.

## Livrables produits

| Fichier | Contenu |
|---|---|
| `contracts/openapi.yaml` | Contrat OpenAPI 3.1 : dépôt, **liste paginée par curseur**, **compteurs par statut**, détail, téléchargement, limites |
| `contracts/README.md` | Justification de chaque choix du contrat, conséquences pour le back-end, points ouverts |
| `frontend/AGENTS.md` | Contexte du front : périmètre v1, contrats, 12 règles non négociables, comportements, stack, organisation, tests, definition of done |
| `frontend/design/` | Premier draft de design — produit par l'agent dédié ; retiré le 01/10, l'interface livrée le remplace |

## Décisions proposées (à valider)

- Contrat placé à la racine (`contracts/`) : il appartient ni au back ni au front.
- Statut public = projection stable de l'automate interne ; `downloadable` et
  `terminal` calculés par le serveur ; énumérations extensibles.
- Pagination par curseur plutôt que par numéro de page (stabilité quand des
  fichiers arrivent en continu).
- Nouvel endpoint `/limits` pour refuser un fichier trop gros avant l'envoi.
- Front : envoi par `XMLHttpRequest` avec l'objet `File`, téléchargement par
  lien natif — jamais de fichier en mémoire.

## Précautions

- Dépôt public : le design s'inspire de la charte sans reprendre le logo, le
  nom commercial ni les visuels de Praxedo.
- Contrat vérifié par motif (22 références internes résolues) ; aucun
  validateur OpenAPI exécuté à ce stade.

## Décisions restées humaines

Validation du contrat et du périmètre v1 ; choix du nom de produit et des
arbitrages de design proposés par l'agent.
