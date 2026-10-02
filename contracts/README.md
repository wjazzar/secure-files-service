# Contrats d'API

> **Analyse : Claude (Opus 5)** — 2026-09-21 — brouillon soumis au porteur du
> projet. Statut du contrat : `1.10.0-draft`.
>
> **Révision du 2026-10-01, 1.10** (revue du code) : le code de raison
> `SCANNER_REJECTED_REQUEST` est retiré de `StatusReasonCode`. Le service ne
> l'a jamais produit : une réponse de l'antivirus qui n'est pas un verdict est
> une panne, réessayée, puis `SCAN_ATTEMPTS_EXHAUSTED`. L'énumération reste
> extensible : un client qui reçoit un code inconnu l'affiche comme tel.
>
> **Révision du 2026-10-01, 1.9** (audit S-19, clôture) : sur
> `GET /files`, une page commence **au plus à 10 000 fichiers** de profondeur
> (`page × size ≤ 10 000`, `size` pris après son plafond) ; au-delà,
> `400 INVALID_PARAMETER`. Un `OFFSET` profond lit et jette toutes les lignes
> qui précèdent : on affine par `q` ou `status` plutôt que de paginer aussi
> loin. Même révision : la recherche `q` ignore **les accents** en plus de la
> casse (« releve » trouve « Relevé »). Aucun chemin, schéma ni champ ne change.
>
> **Révision du 2026-09-30, 1.8** (relecture du back-end, remarque R-007) :
> **`408 UPLOAD_TOO_SLOW`** sur `POST /files`. Le corps d'un dépôt doit arriver
> dans un délai proportionnel à sa taille annoncée (un plancher, plus un temps
> par Mio). Le délai de lecture du connecteur ne mesure que le silence entre
> deux paquets : un client qui envoie au compte-gouttes le contournait et
> gardait sa place sur le nœud indéfiniment. Même révision : le `Retry-After`
> de suivi (`202` du dépôt, `200` et désormais `304` du détail) est
> **proportionnel à la taille** du fichier — 2 s, plus 0,05 s par Mio, au plus
> 30 s (remarque R-001). Ajouts purs : aucun chemin, schéma ni champ existant ne
> change.
>
> **Révision du 2026-09-29, 1.7** (ADR-0014, décision du porteur du projet) :
> **authentification toujours exigée**. `{}` est retiré de la liste globale
> `security` : plus d'accès anonyme, sous aucun réglage. Aucun chemin, schéma
> ni champ ne change. Section concernée : §2.10.
>
> **Révision du 2026-09-29, 1.6** (ADR-0013, décision du porteur du projet) :
> **plus de lien signé**. Avec la session par cookie, un lien de
> téléchargement HMAC doublait l'authentification. Le navigateur télécharge
> par navigation sur `GET /files/{fileId}/content`, comme un système tiers
> avec son jeton. `POST /files/{fileId}/download-link`,
> `GET /downloads/{token}`, le schéma `DownloadLink` et le code
> `DOWNLOAD_LINK_INVALID` sont retirés. Sections concernées : §1, §2.11.
>
> **Révision du 2026-09-28** (ADR-0012) : v2 du navigateur par **cookie de
> session** — le service est le client confidentiel de Keycloak. Opérations
> `auth` ajoutées, schéma `Session`, code `CSRF_TOKEN_INVALID`. Sections
> concernées : §1, §2.10.
>
> **Révision du 2026-09-25** (décisions du porteur du projet, voir
> [`../frontend/ARCHITECTURE.md`](../frontend/ARCHITECTURE.md) §0 et §11) :
> authentification **prévue** (v2, Keycloak) mais non exigée en v1,
> `GET /limits` supprimé, pagination par numéro de page, lien de
> téléchargement signé. Sections concernées : §1, §2.5, §2.6, §2.10, §2.11.

[`openapi.yaml`](openapi.yaml) est **la source de vérité unique** entre le
back-end et le front-end (approche *contract-first*) :

- le back-end l'implémente et un test vérifie que l'API réelle y est conforme ;
- le front-end le **reflète en schémas Zod** (validation de chaque réponse,
  types dérivés) ; un test échoue si les deux divergent ;
- les deux équipes peuvent avancer en parallèle, le front sur des bouchons
  (MSW) conformes au contrat.

Il est placé à la racine, et non dans `frontend/` ou `backend/`, parce qu'il
n'appartient à aucun des deux.

---

## 1. Opérations

| Méthode | Chemin | Rôle | Nouveau pour l'interface |
|---|---|---|---|
| `POST` | `/api/v1/files` | Déposer un fichier (corps binaire en flux) | |
| `GET` | `/api/v1/files` | **Lister** : pagination par numéro de page, tri, filtre par statut, recherche par nom | ✅ |
| `GET` | `/api/v1/files/summary` | **Compter** les fichiers par statut (compteurs des filtres) | ✅ |
| `GET` | `/api/v1/files/{fileId}` | Métadonnées et statut (conçu pour le suivi par polling) | |
| `GET` | `/api/v1/files/{fileId}/content` | **Télécharger** — le navigateur par navigation (cookie de session), un système tiers avec son jeton | |
| `GET` | `/api/v1/auth/login` | **Navigation** du navigateur vers la connexion Keycloak | ✅ |
| `GET` | `/api/v1/auth/callback` | Retour de Keycloak, ouverture de la session (cookie `HttpOnly`) | ✅ |
| `GET` | `/api/v1/auth/session` | Qui est connecté (`401` sinon) ; émet le cookie `XSRF-TOKEN` | ✅ |
| `POST` | `/api/v1/auth/logout` | Déconnexion ici, puis adresse de fin de session Keycloak | ✅ |

Le seul chemin qui sert du contenu (`/content`) relit l'état servable **au
moment de servir**, à chaque requête : rien de ce qui a été dit avant ne vaut
permission.

**Priorités** : le cadrage plaçait la liste et la recherche en bonus
([`../docs/28-precisions-de-cadrage.md`](../docs/28-precisions-de-cadrage.md)) ;
le porteur du projet les a **intégrées au cœur** le 2026-09-25 : le tableau
paginé est l'écran de suivi.

**Hors contrat** : suppression de fichier, réanalyse, notification par webhook
— écartés du périmètre (§4). Les sondes de santé
(`/actuator/health/*`) relèvent de l'exploitation, pas du contrat.

---

## 2. Choix structurants et leurs raisons

### 2.1 Un statut public, projection de l'automate interne

Le back-end fait tourner un automate plus riche que ce que voit le client
(attente de nouvelle tentative, promotion vers la zone servable, échec
définitif… — voir [`../docs/chatgpt/21-invariant-domaine-et-donnees.md`](../docs/chatgpt/21-invariant-domaine-et-donnees.md) §4).
L'API n'en expose qu'une **projection stable** :

| États internes | Statut public |
|---|---|
| `AWAITING_SCAN`, `RETRY_WAIT` | `PENDING` |
| `SCANNING`, `PROMOTING` | `SCANNING` |
| `AVAILABLE` | `AVAILABLE` |
| `INFECTED` | `INFECTED` |
| `UNSCANNABLE` | `UNSCANNABLE` |
| `FAILED_FINAL` | `FAILED` |

**Pourquoi** : l'automate interne pourra évoluer (ajout d'un état de
réanalyse, par exemple) sans casser un seul client.

### 2.2 `downloadable` et `terminal` calculés par le serveur

Le client ne déduit **jamais** la téléchargeabilité du statut. Il lit
`downloadable`. De même, il arrête de suivre un fichier quand `terminal` vaut
`true`.

**Pourquoi** : c'est le principe de refus par défaut appliqué au contrat. Si
un état est ajouté demain, un client qui testerait `status === 'AVAILABLE'`
continuerait de fonctionner, mais un client qui déduirait des règles
dupliquerait la logique du serveur et divergerait tôt ou tard. Les
énumérations sont déclarées **extensibles** : un client doit tolérer une
valeur inconnue.

### 2.3 Dépôt en corps binaire, sans multipart

`POST` reçoit le contenu brut (`application/octet-stream`), le nom dans
l'en-tête `X-File-Name` (UTF-8 encodé en pourcentage), la taille dans
`Content-Length` (obligatoire, vérifiée pendant la lecture).

**Pourquoi** : le multipart oblige le serveur à mettre en tampon
(fichiers temporaires par défaut sous Spring), ce qui double les écritures
disque sur les gros fichiers. Côté navigateur, `XMLHttpRequest.send(file)`
envoie un objet `File` directement, en flux depuis le disque, avec suivi de
progression.

### 2.4 `202 Accepted` au dépôt

La ressource existe, mais sa finalité — être téléchargeable — n'est pas
atteinte : l'analyse reste à faire. `202` le dit dès la première réponse.
`201` resterait défendable (la ressource est créée) ; l'enjeu est faible.
Ce qui compte vraiment : `Location`, `downloadable: false` et `Retry-After`.

### 2.5 Pagination par numéro de page, tri borné

`GET /api/v1/files?page=0&size=20&sort=uploadedAt,desc` renvoie
`{ content, page: { size, number, totalElements, totalPages } }`, la forme
native du `PagedModel` de Spring Data. `page` commence à **0**, comme Spring
Data et TanStack Table ; l'interface affiche « page 1 ».

**Pourquoi ce choix remplace le curseur de la version 1.0** :

| | Numéro de page (retenu) | Curseur (keyset) |
|---|---|---|
| « Page 3 sur 12 », accès direct, taille de page | ✅ | ❌ |
| Nombre total pour un tableau paginé | ✅ | ❌ |
| Coût en base sur une très grande table | ❌ croît avec l'`offset` | ✅ constant |
| Stabilité quand des fichiers arrivent | ❌ décalage possible | ✅ |

Le curseur visait un « Charger plus » sur une liste globale. Le besoin est
désormais un **tableau paginé**, et chaque utilisateur ne voit que
**ses** fichiers : les volumes par propriétaire restent faibles. Le tri est
limité à six valeurs (`uploadedAt`, `filename`, `sizeBytes`, dans les deux
sens) et toujours complété par `id` : l'ordre est total. Une page commence
au plus à 10 000 fichiers de profondeur (`page × size`, révision 1.9) :
au-delà, `400 INVALID_PARAMETER`.

**Piste d'amélioration** : revenir au keyset si un propriétaire dépasse
quelques dizaines de milliers de fichiers, ou pour un défilement infini.

### 2.6 Limite de taille : 500 Mo, sans endpoint dédié

`GET /limits` a été **supprimé** (décision du porteur du projet). Le serveur
refuse tout fichier de plus de **500 Mo** par `413 FILE_TOO_LARGE` dès la
lecture de `Content-Length`. L'interface porte la même valeur en
configuration (`VITE_MAX_UPLOAD_BYTES`) pour refuser **avant** d'envoyer :
plusieurs navigateurs signalent une coupure réseau, et non le `413`, quand le
serveur répond avant la fin d'un gros envoi. Le serveur reste l'autorité.

L'antivirus est configuré pour analyser jusqu'à cette même taille : **tout
fichier accepté est analysable**.

### 2.7 Téléchargement : `409` + code métier, jamais `403` ni `503`

| Situation | Réponse |
|---|---|
| Fichier inconnu (ou inaccessible) | `404 FILE_NOT_FOUND` |
| Analyse pas terminée | `409 FILE_NOT_READY` + `Retry-After` |
| Menace détectée | `409 FILE_INFECTED` |
| Non analysable | `409 FILE_UNSCANNABLE` |
| Échec définitif de l'analyse | `409 FILE_SCAN_FAILED` |
| Service réellement indisponible | `503 SERVICE_UNAVAILABLE` |

**Pourquoi** : `403` signifie un refus d'autorisation, pas un verdict ; `503`
décrit une indisponibilité du service, pas l'état d'un fichier. Le détail
métier passe par le `code` (RFC 9457), stable et exploitable par programme.
Issu de la confrontation avec la contre-analyse ChatGPT
([`../docs/26-reponse-a-la-contre-analyse.md`](../docs/26-reponse-a-la-contre-analyse.md) §3.5).

### 2.8 Contenu toujours servi en pièce jointe

`Content-Type: application/octet-stream`, `Content-Disposition: attachment`,
`X-Content-Type-Options: nosniff`. Un fichier sain pour l'antivirus peut
rester dangereux s'il est interprété par le navigateur (HTML ou SVG avec du
script). On ne l'affiche donc jamais, on le télécharge.

### 2.9 Suivi par polling avec `ETag`

`GET /api/v1/files/{fileId}` renvoie un `ETag` ; le client le renvoie dans
`If-None-Match` et reçoit un `304` sans corps tant que rien n'a changé.
`Retry-After` indique le rythme de suivi conseillé, proportionnel à la taille
du fichier (2 s, plus 0,05 s par Mio, 30 s au plus — révision 1.8). Le suivi
par SSE ou webhook n'est pas au contrat.

---

### 2.10 Authentification toujours exigée (révision 1.7)

La liste globale `security` vaut `[{sessionCookie: []}, {bearerAuth: []}]` :
l'une des deux preuves (Keycloak, OpenID Connect) est obligatoire sur chaque
opération, sauf les points d'entrée de la connexion eux-mêmes. Il n'existe
**aucun accès anonyme**, sous aucun réglage (ADR-0014). `401 UNAUTHENTICATED`
est déclaré sur chaque opération, et le cloisonnement par propriétaire est
décrit (un fichier d'autrui répond `404`).

Jusqu'à la révision 1.6, la liste contenait aussi l'exigence vide `{}` :
l'accès anonyme de la v1, que le service ouvrait sur un simple réglage. Il
est retiré — aucun chemin, schéma ni champ n'a eu à changer.

**Deux preuves, jamais mêlées (révision 1.4, ADR-0012)** :

| Appelant | Preuve | Pourquoi |
|---|---|---|
| L'interface web | Cookie de session `HttpOnly` (`sessionCookie`) | Le service est le **client confidentiel** de Keycloak (identifiant + secret, code d'autorisation + PKCE). Aucun jeton n'atteint le navigateur ; la session est gardée par le service, partagée par tous ses nœuds |
| Un système tiers | Jeton `Bearer` (`bearerAuth`) | *Client credentials* : pas de navigateur, pas de cookie. Une requête qui porte `Authorization` n'ouvre jamais de session |

Un cookie part tout seul avec chaque requête : toute écriture qu'il
authentifie doit renvoyer le cookie `XSRF-TOKEN` dans l'en-tête
`X-XSRF-TOKEN`, sinon `403 CSRF_TOKEN_INVALID` — le seul `403` de l'API. La
connexion n'est pas un appel d'API mais une **navigation** vers
`GET /api/v1/auth/login` ; `GET /api/v1/auth/session` dit ensuite qui est
connecté. La déconnexion (`POST /api/v1/auth/logout`) ferme la session ici et
répond **`200` avec l'adresse** de fin de session de Keycloak, que l'interface
suit : un `fetch` ne peut pas suivre une redirection vers une autre origine
(révision 1.5).

**Cloisonnement (contrat 1.3)** : pas de rôle. Chaque utilisateur
authentifié possède un **espace de fichiers privé** : il ne voit, ne suit et
ne télécharge que ses propres fichiers. Le fichier d'un autre répond `404`,
comme un fichier inconnu. Côté back-end, c'est un filtre `owner_id = sub`
sur chaque requête ; aucun `403` d'autorisation.

### 2.11 Téléchargement : l'identité de l'appelant, et elle seule (révision 1.6)

Lire le contenu par `fetch` chargerait jusqu'à 500 Mo dans la mémoire de
l'onglet : le navigateur télécharge donc par **navigation native** sur
`GET /files/{fileId}/content`, et son gestionnaire de téléchargement écrit le
flux sur disque. Son cookie de session `HttpOnly` part tout seul ; un `GET`
n'a pas besoin du jeton CSRF. Un système tiers appelle la même adresse avec
son jeton `Bearer`.

Jusqu'à la révision 1.5, le navigateur demandait d'abord un **lien signé
HMAC** de 60 s. C'était nécessaire tant que la v2 prévoyait un jeton dans le
navigateur (un lien natif ne porte pas d'en-tête `Authorization`) ; depuis la
session par cookie (ADR-0012), ce lien doublait l'authentification — un
second secret à gérer, partager entre nœuds et faire tourner, pour une preuve
que le cookie apporte déjà. Retiré (ADR-0013).

L'interface relit d'abord le détail du fichier (`downloadable`) pour pouvoir
**expliquer** un refus ou une session expirée, au lieu d'un téléchargement en
échec ; la garantie, elle, reste côté serveur, au moment de servir.

## 3. Conséquences pour le back-end

| Opération | Point d'attention |
|---|---|
| `GET /files` | Index `(owner_id, uploaded_at DESC, id DESC)` ; `Pageable` de Spring Data, tri limité à la liste blanche du contrat |
| `GET /files?q=` | Recherche « contient » insensible à la casse et aux accents : index trigramme (`pg_trgm`) sur l'expression `unaccent` + `lower` ; sans lui, balayage de table |
| `GET /files?status=` | Filtre sur le statut public → traduction vers l'ensemble d'états internes correspondant |
| `GET /files/summary` | `GROUP BY` sur l'état ; acceptable à cette échelle, à mettre en cache ou à tenir à jour par compteurs si la table grossit |
| `GET /files/{id}/content` | Requête `Range` (reprise) ; lecture seule sur la zone servable |

La topologie de déploiement (un seul nœud, ou rôles séparés) **n'affecte pas
le contrat** : le client voit un point d'entrée unique.

---

## 4. Points ouverts

| Point | Dépend de |
|---|---|
| ~~Suppression d'un fichier (`DELETE`)~~ | **Clos** (01/10) : non. Le parcours attendu est dépôt → suivi → téléchargement ; les fichiers infectés restent comme preuve |
| ~~Notification des systèmes tiers (webhook)~~ | **Clos** (01/10) : non, le suivi par polling (`ETag`, `Retry-After`) suffit |
| Authentification et cloisonnement | **Clos** : toujours exigée (§2.10, ADR-0014), avec Keycloak — navigateur par cookie de session (ADR-0012), systèmes tiers par jeton |
| Taille minimale acceptée (aujourd'hui : fichier vide refusé, `EMPTY_FILE`) | Expliqué au porteur du projet le 01/10, en attente de sa confirmation |
| ~~`201` ou `202` au dépôt~~ | **Clos** : `202`, l'analyse restant à faire (§2.4) |
| ~~Exposer `maxScannableBytes`~~ | **Clos** : à 500 Mo, limite d'admission et limite d'analyse sont confondues |
| ~~Recherche insensible aux accents~~ | **Clos** (1.9) : `unaccent` côté PostgreSQL, index trigramme sur la même expression |

---

## 5. Validation du contrat

En place :

- **test de dérive** côté front (`frontend/src/api/files/files-schemas.contract.test.ts`) : énumérations, tris et champs obligatoires comparés aux schémas Zod ;
- **test de conformité** côté back (`ContractConformanceTest`) : statuts,
  raisons, codes d'erreur, tris, taille de page et champs de chaque réponse
  comparés au contrat. Il compare les types du service au contrat ; il ne
  valide pas chaque réponse HTTP contre le schéma.

Pas en place : le **lint** du contrat (une intégration continue le porterait ;
il n'y en a pas : exercice), et aucun validateur OpenAPI n'a été exécuté.
Vérifié le 02/10 : les 68 références internes (`$ref`) du document sont
résolues.
