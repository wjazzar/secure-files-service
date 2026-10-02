# 28 — Précisions de cadrage : les contraintes du 21/09

> Le 21/09/2026, le porteur du projet a fixé de nouvelles contraintes, issues
> de ses échanges avec le recruteur, sur les sept points que l'énoncé laissait
> ouverts. Elles font référence : toute hypothèse antérieure qui les contredit
> est caduque. Les hypothèses qui en découlent sont dans le README (§9).

---

## Synthèse

| # | Sujet | Contrainte | Décision qui en découle |
|---|---|---|---|
| 1 | Taille des fichiers | Quelques Mo en usage courant ; jusqu'à 200 à 500 Mo à prévoir ; pas de fichiers de plusieurs Go, pas d'envoi par morceaux | 500 Mo pour l'admission **et** pour l'analyse, `413` au-delà ; un dépôt est une seule requête, lue en flux |
| 2 | Volumétrie | Aucun chiffre imposé : une hypothèse à poser, documenter et tenir | ~50 dépôts simultanés, ~5 000 fichiers par jour ; capacité mesurée ([`capacity-planning`](capacity-planning/README.md)) |
| 3 | Déploiement et stockage | Plusieurs nœuds à prévoir dans l'architecture, sans avoir à le démontrer ; stockage partagé de type objet plutôt que disque local | Stockage objet compatible S3 dès le départ ; aucun état local dans un nœud |
| 4 | Analyse | Asynchrone | `202` au dépôt, puis suivi par l'API ; aucun mode synchrone |
| 5 | Authentification | Non indispensable pour l'exercice | Livrée quand même, et toujours exigée ([ADR-0014](adr/0014-authentification-toujours-exigee.md)) |
| 6 | Outils | Spring Boot, React et une base de données suffisent ; tout ajout reste possible s'il est justifié | Aucun broker : la base fait file ([ADR-0001](adr/0001-la-base-de-donnees-fait-file.md)) |
| 7 | Interface | Le parcours dépôt → suivi → téléchargement suffit ; liste, tri et recherche sont un plus | Le parcours d'abord ; le tableau paginé (recherche, filtre, tri) ensuite |

---

## 1 — Taille : 500 Mo, en une requête

À 500 Mo, l'antivirus peut analyser **tout ce que le service accepte** : la
limite d'admission et la limite d'analyse deviennent la même valeur. Au-delà,
le dépôt est refusé à la réception (`413 FILE_TOO_LARGE`).

- L'état `UNSCANNABLE` reste nécessaire, mais seulement pour ce que l'analyse
  découvre : récursion trop profonde, volume décompressé, contenu que le
  moteur ne sait pas lire. Plus jamais pour la taille du fichier déposé.
  (Une archive chiffrée, elle, n'est pas signalée par le moteur tel qu'il est
  configuré : [`06`](06-securite-et-angles-morts.md) §2.)
- Le flux reste **obligatoire** : 500 Mo en mémoire, multipliés par quelques
  dépôts simultanés, feraient tomber le service.
- Les limites de ClamAV ont été relevées et **mesurées**
  ([ADR-0005](adr/0005-ordre-des-limites-de-l-antivirus.md)).

## 2 — Volumétrie : une hypothèse, puis une mesure

| Paramètre | Hypothèse | Conséquence |
|---|---|---|
| Dépôts simultanés en pointe | ~50 | Borne explicite par nœud ; au-delà, `429` sans lire le corps |
| Fichiers déposés par jour | ~5 000 | La file en base suffit largement ; aucun broker |
| Taille | ~5 Mo en moyenne, 500 Mo au plus | ~25 Go de stockage brut par jour |
| Analyses simultanées | 4 par nœud | Protège l'antivirus, la ressource rare |
| Délai de mise à disposition | < 1 min pour un fichier courant | Métrique d'alerte : âge du plus ancien fichier en attente |

## 3 — Stockage objet, pensé pour plusieurs nœuds

Le système de fichiers local est écarté au profit d'un **stockage objet
compatible S3**.

| Point | Conséquence |
|---|---|
| Isolation quarantaine / zone servable | Deux zones et trois identités aux droits distincts : celle qui sert les fichiers n'a aucun droit sur la quarantaine ([ADR-0002](adr/0002-un-processus-trois-identites.md)) |
| Promotion | Copie relue et vérifiée par son empreinte, puis bascule d'état ([ADR-0006](adr/0006-promotion-par-relecture-verifiee.md)) |
| Plusieurs nœuds | Naturel : aucun état en mémoire entre deux requêtes, aucun fichier temporaire partagé, prise des travaux atomique en base, sessions en base |
| Produit | SeaweedFS : il applique des droits par identité, dont dépend l'isolation ([`infra/README.md`](../infra/README.md)) |

## 4 — Analyse asynchrone

Le dépôt répond `202 Accepted`, puis le client suit l'état par l'API
(`ETag`, `Retry-After`). Pas de mode synchrone ni de webhook.

## 5 — Authentification

Non exigée pour l'exercice. Elle est livrée quand même : Keycloak, session du
navigateur tenue par le service, jeton pour les systèmes tiers
([ADR-0012](adr/0012-session-navigateur-client-confidentiel.md)), et toujours
exigée, sans réglage pour la couper
([ADR-0014](adr/0014-authentification-toujours-exigee.md)).

## 6 — Outils : la sobriété

Les composants qui ne sont pas écrits en Java sont permis (une base de données
l'est déjà), mais chaque ajout doit être justifié. D'où :

- **aucun broker** : la base porte la file (`FOR UPDATE SKIP LOCKED`) ;
- le stockage objet, seul composant ajouté au-delà de la base, répond à la
  contrainte n°3 ;
- Keycloak et la supervision répondent à des besoins nommés : identité,
  mesure de la capacité.

## 7 — Interface : le parcours d'abord

| Fonctionnalité | Priorité |
|---|---|
| Déposer un ou plusieurs fichiers : progression, annulation, erreurs | Cœur |
| Suivre l'état des fichiers déposés | Cœur |
| Télécharger un fichier disponible ; détail et verdict | Cœur |
| Liste paginée, filtre par statut, recherche, tri | Plus, intégré une fois le parcours terminé |
