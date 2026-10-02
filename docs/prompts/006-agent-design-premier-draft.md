# 006 — Agent design : premier draft de l'interface

- **Date** : 2026-09-21
- **Outil** : Claude (Opus 5), agent dédié lancé en arrière-plan depuis Claude Code
- **Phase du projet** : front-end — design

## Prompt confié à l'agent (résumé)

Brief complet transmis à l'agent (texte intégral dans la session) :

- contexte : test technique pour Praxedo, service de fichiers analysés par
  antivirus, interface React de type portail ;
- charte relevée sur praxedo.com (variables CSS) : `rouge-praxedo` #E91B31,
  `bleu-praxedo` #33ADFF, `dark-blue-text` #0A2D40, gris et fonds associés,
  angles droits, Niveau Grotesk (commerciale, à remplacer) ;
- contraintes : dépôt public → ni logo, ni nom commercial, ni visuels de
  Praxedo ; nom de produit neutre ;
- problème à trancher explicitement : le rouge de marque sert au bouton
  principal chez Praxedo mais doit signifier « menace » dans l'application ;
- statuts publics du contrat, champs, fonctionnalités (dépôt, liste paginée,
  filtres, recherche, détail, états vides et d'erreur) ;
- livrables : `DESIGN.md`, `tokens.css`, `maquette.html` autonome.

L'agent a été interrompu par une limite d'usage, puis relancé avec son
contexte pour terminer l'alignement de la maquette sur `contracts/openapi.yaml`,
apparu pendant son travail.

> Le draft (`frontend/design/`) a été retiré du dépôt le 01/10 : l'interface
> livrée et [`frontend/ARCHITECTURE.md`](../../frontend/ARCHITECTURE.md) le
> remplacent.

## Ce qui a été produit

- **Écluse** comme nom de produit ;
- action principale en bleu nuit, **rouge réservé au danger**, ambre pour les
  autres blocages ;
- **Hanken Grotesk** en remplacement de la police commerciale ;
- 38 couples de contraste WCAG calculés, tous conformes AA ;
- maquette avec les six statuts, envois en cours, panneau de détail, états
  vide / chargement / erreur / aucun résultat, statut inconnu.

## Vérifications faites par l'orchestrateur (et non reprises de l'agent)

| Vérification | Résultat |
|---|---|
| Le bouton « Télécharger » ne dépend que de `downloadable` | Oui — les comparaisons à `'AVAILABLE'` ne figurent que dans le faux serveur de la maquette |
| Cohérence ligne à ligne : statut « Disponible » ⇔ bouton de téléchargement | 20 lignes, 12 disponibles, 12 boutons, aucun écart |
| Variables CSS de la maquette identiques à `tokens.css` | 168 variables, aucune différence |
| Erreurs dans la console du navigateur | Aucune |
| Rendu visuel (liste, zone d'envoi, détail d'un fichier infecté) | Conforme, capture faite dans le navigateur intégré |

## Points soulevés par l'agent et suites données

| Point | Suite |
|---|---|
| `docs/08-contrat-api.md` périmé | Remplacé par `contracts/openapi.yaml`, puis retiré du dépôt |
| Limite d'analyse de l'antivirus absente de `/limits` | Ajouté aux points ouverts du contrat, lié à `D-15` |
| Recherche insensible aux accents | Ajouté aux points ouverts du contrat |
| Relance d'analyse, visibilité du nom de la menace, mode sombre, panneau modal ou non | Laissés ouverts, décision du porteur du projet |

## Décisions restées humaines

Validation du nom de produit, de la décision sur le rouge, de la police et du
draft dans son ensemble.
