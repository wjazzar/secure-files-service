# ADR-0013 — Téléchargement par l'identité de l'appelant, sans lien signé

- **Statut** : Accepté — signé par le porteur du projet le 2026-10-01 ; décision du porteur du projet (29/09), rédigé par Claude (Opus 5.5)
- **Date** : 2026-09-29
- **Décision du registre** : D-05 (remplace [ADR-0007](0007-liens-hmac-servis-par-le-service.md))
- **Exigences concernées** : EX-03, EX-04

## Contexte

ADR-0007 servait le navigateur par un **lien HMAC de 60 s**, demandé au clic.
Il répondait à une contrainte réelle au moment où il a été écrit : la v2
prévoyait un jeton dans le navigateur (`keycloak-js`, client public), et une
navigation native (`<a href>`) ne peut pas porter d'en-tête `Authorization`.
Lire 500 Mo par `fetch` les chargerait dans l'onglet.

ADR-0012 a changé la donne : le service est devenu le client confidentiel de
Keycloak, et le navigateur n'a plus qu'un **cookie de session `HttpOnly`**,
qui part tout seul avec chaque navigation vers l'origine du service. Un
téléchargement authentifié demandait alors **deux preuves** : la session pour
obtenir le lien, puis le lien lui-même. Le porteur du projet a refusé cette
double authentification.

## Décision

Un seul chemin de sortie : `GET /api/v1/files/{id}/content`, et **l'identité
de l'appelant est la seule preuve** — le cookie de session pour le
navigateur, le jeton `Bearer` pour un système tiers.

- Le navigateur y va par **navigation native** : son gestionnaire de
  téléchargement écrit le flux sur disque, la mémoire de l'onglet reste
  constante.
- Le service relit l'état **au moment de servir**, à chaque requête, comme
  avant ; il sert lui-même le contenu, avec l'identité de stockage
  `delivery`.
- Un `GET` ne demande pas de jeton CSRF : il ne modifie rien, et une
  navigation provoquée par un autre site ne donne pas le contenu à ce site.
- L'interface relit `GET /files/{id}` au clic, pour **expliquer** un refus
  ou une session expirée au lieu d'un téléchargement en échec. La garantie,
  elle, ne dépend pas de cette relecture.

Retirés : `POST /files/{id}/download-link`, `GET /downloads/{token}`, le
schéma `DownloadLink`, le code `DOWNLOAD_LINK_INVALID` (contrat 1.6), le port
`DownloadLinkSigner` et son adaptateur HMAC, le secret
`DOWNLOAD_LINK_SECRET`, l'événement d'audit `DOWNLOAD_LINK_ISSUED`.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| Garder le lien HMAC en plus de la session (ADR-0007) | Double authentification pour une preuve que le cookie apporte déjà ; un second secret à distribuer à chaque nœud et à faire tourner ; un justificatif dans l'URL (historique, journaux) |
| URL présignée du stockage | Le stockage ignore l'automate : un lien émis avant un changement d'état resterait valable ; l'adresse du stockage serait publiée |
| `fetch` + `Blob` | 500 Mo dans la mémoire de l'onglet |

## Conséquences

**Positives** — une seule preuve, un seul chemin, un seul ensemble de tests ;
plus de secret de signature, donc rien de plus à partager entre nœuds ; plus
de justificatif dans une URL ; le contrat perd deux opérations et un code
d'erreur.

**Négatives** — la bande passante des téléchargements traverse toujours le
service (inchangé). Un téléchargement ne peut plus être délégué à un outil
extérieur au navigateur (gestionnaire de téléchargement tiers, `curl` copié
depuis l'interface) : il lui faut la session ou un jeton. Une session
expirée pendant un très long téléchargement n'interrompt pas le flux en cours,
mais une **reprise** par plage demande une session valide.

**Ce qui la remettrait en cause** — un besoin de **partage** d'un fichier
avec quelqu'un qui n'a pas de compte, ou de délégation à un outil tiers : il
faudrait alors un lien de partage explicite, révocable et audité — une
fonctionnalité, pas un détail d'authentification. Ou un débit sortant
supérieur à ce qu'un nœud sert : URL présignée émise **après**
revérification, ou CDN.
