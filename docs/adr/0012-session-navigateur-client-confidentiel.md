# ADR-0012 — Session du navigateur : client confidentiel, cookie seul, Spring Session

- **Statut** : Accepté — signé par le porteur du projet le 2026-10-01 ; rédigé par Claude (Opus 5.5) à la demande du porteur du projet
- **Date** : 2026-09-28 (révisé le même jour : couche maison remplacée par les briques standard)
- **Décision du registre** : D-10 (complète [ADR-0008](0008-authentification-activable.md))
- **Exigences concernées** : EX-02, EX-05

## Contexte

ADR-0008 a livré la v2 côté API : un jeton Keycloak (`Bearer`) vérifié par le
service. Le front devait se brancher avec `keycloak-js` en **client public** :
jetons en mémoire du navigateur, lisibles par tout script qui s'y exécute.

Demande du porteur du projet : **identifiant client + secret**, **cookie
seul** côté navigateur. La RFC 10017 (applications navigateur) recommande
précisément ce modèle — le *backend for frontend* — pour les applications
métier.

Une **première version**, livrée le matin même, gardait le protocole de
Spring Security (`oauth2Login`) mais réécrivait tout ce qui l'entoure :
session maison en base (empreinte SHA-256 du cookie), connexion en cours
scellée en AES-GCM dans un cookie, *refresh* et déconnexion appelés à la main,
compare-and-set pour qu'un seul nœud revalide à la fois — une quarantaine de
classes. Relue par le porteur du projet : **« il y a déjà le client OAuth2 de
Spring qui fait ça »**. La relecture lui a donné raison, et a montré que deux
des arguments qui justifiaient la couche maison ne tenaient pas (voir
*Alternatives*).

## Décision

**Le service est le client confidentiel `praxedo-web` de Keycloak, par le
client OAuth2 de Spring Security, sans rien réécrire de ce qu'il fournit. La
session HTTP est gardée par Spring Session dans PostgreSQL ; le navigateur
n'en a qu'un cookie `HttpOnly`. Les systèmes tiers restent en `Bearer`, sans
état.**

```
Navigateur                     Service (Spring)                               Keycloak
   │ GET /api/v1/auth/login ──▶ state, nonce, PKCE S256 → session (PostgreSQL)
   │ ◀── 302 ─────────────────────────────────────────────────────────────▶ page de connexion
   │ GET /api/v1/auth/callback  state vérifié contre la session de CE navigateur
   │   (cookie de session) ───▶ code + verifier + secret client ────────────▶ /token
   │                            jeton d'identité vérifié (JWKS, iss, aud, exp, nonce)
   │ ◀── 302 + nouveau cookie ─ nouvel identifiant de session (fixation)
   │ GET /api/… (cookie) ─────▶ session relue en base ; jeton d'accès échu :
   │                            refresh + secret client ───────────────────▶ /token
   │ POST /api/v1/auth/logout ▶ session supprimée, cookie effacé
   │ ◀── 200 {logoutUrl} ─────
   │ ── navigation ───────────────────────────────────────────────────────▶ fin de session
   │ ◀── /login?signed-out ────────────────────────────────────────────────
```

Deux chaînes de filtres, sur les **mêmes routes** :

| Requête | Chaîne | Comportement |
|---|---|---|
| Avec un en-tête `Authorization` | Sans état (`oauth2ResourceServer`) | Jeton vérifié sur place ; aucune session, aucun cookie, aucun CSRF ; n'importe quel nœud. En *client credentials*, Keycloak ne délivre pas de jeton de rafraîchissement : rien à renouveler |
| Toute autre | Navigateur (`oauth2Login`, Spring Session) | Cookie `HttpOnly` ; CSRF sur les écritures ; revalidation auprès de Keycloak |

Ce qui est **de Spring** : la redirection, `state`, `nonce`, PKCE, l'échange
du code, la vérification du jeton d'identité, le changement d'identifiant de
session, le CSRF « SPA », le *refresh* (`OAuth2AuthorizedClientManager`), la
déconnexion OpenID Connect (`OidcClientInitiatedLogoutSuccessHandler`), la
session partagée (Spring Session JDBC, son schéma recopié dans `V7`).

Ce qui est **au projet**, et pourquoi Spring ne le fournit pas :

| Pièce | Taille | Raison |
|---|---|---|
| `SignInRequestResolver` | 30 lignes | Adresse fixe `/api/v1/auth/login` (un seul fournisseur) et retour après connexion, assaini (`ReturnTo`) |
| `SessionAuthorizedClients` | 40 lignes | Le dépôt de Spring (`HttpSessionOAuth2AuthorizedClientRepository`) sérialise l'enregistrement du client **avec son secret** : chaque ligne de session en base aurait tenu le secret à côté du jeton de rafraîchissement — la seule paire qui rend ce jeton utilisable. Celui-ci ne range que les jetons |
| `SessionRevalidationFilter` | 60 lignes | Spring renouvelle un jeton quand on le lui demande ; ici, personne ne le demande (le service est son propre serveur de ressources). Le filtre le demande à chaque requête, et décide quoi faire de la réponse (ci-dessous) |
| Réponse JSON de la déconnexion | 10 lignes | Le front se déconnecte par `fetch`, qui ne peut pas suivre une redirection vers une autre origine : l'adresse de fin de session est rendue dans le corps |

**Keycloak reste l'autorité.** À l'échéance du jeton d'accès (5 min), la
requête suivante le renouvelle :

| Réponse de Keycloak | Effet |
|---|---|
| Renouvelé | Nouveaux jetons écrits dans la session partagée ; le nœud suivant les relit |
| `invalid_grant` (déconnecté, désactivé, expiré) | Session fermée ici aussi → `401` |
| Rien (panne, délai, `5xx`) | Session servie sur son dernier verdict, **30 min au plus** ; Keycloak réinterrogé toutes les 30 s, pas à chaque requête |

**Pas de rotation des jetons de rafraîchissement** (`revokeRefreshToken:
false`). Deux requêtes d'un même navigateur, sur deux nœuds, peuvent
renouveler la même session au même instant : avec la rotation, la seconde
reçoit `invalid_grant` et déconnecte l'utilisateur à tort. Spring Session ne
l'empêche pas — il n'a aucun verrou —, et le problème existe même sur un seul
nœud. La rotation protège un jeton volé chez un **client public** ; ici, il ne
quitte pas le serveur et ne sert à rien sans le secret client. La RFC 9700
n'exige la rotation que pour les clients publics.

| Menace | Parade |
|---|---|
| XSS qui vole la session | Aucun jeton en JavaScript ; cookie `HttpOnly` (le script ne voit que `XSRF-TOKEN`) |
| Code d'autorisation intercepté | PKCE `S256` **et** secret client pour l'échanger |
| Retour de connexion forgé (*login CSRF*), jeton rejoué | `state` lié à la session du navigateur qui a commencé ; `nonce` vérifié |
| Fixation de session | Nouvel identifiant de session à la connexion ; l'ancien n'ouvre plus rien |
| Écriture forgée par un autre site | `X-XSRF-TOKEN` exigé sur toute écriture portée par le cookie (`403 CSRF_TOKEN_INVALID`), y compris la déconnexion ; cookie `SameSite=Lax` en seconde barrière |
| Redirection ouverte après connexion | Seul un chemin local est suivi, sinon `/` |
| Cookie posé par un sous-domaine voisin | Préfixe `__Host-` (impose `Secure`, `Path=/`, aucun `Domain`) |
| Utilisateur désactivé ou déconnecté dans Keycloak | Revalidation à l'échéance du jeton d'accès : perte d'accès en 5 min au plus |
| Plusieurs nœuds | Rien en mémoire : la session, la connexion en cours et les jetons vivent dans PostgreSQL |
| Déconnexion incomplète | Déconnexion OpenID Connect (RP-initiated) : Keycloak ferme sa session, la connexion suivante redemande le mot de passe |
| Copie de la base | **Risque accepté** : l'identifiant de session y est en clair, une copie fraîche permet d'usurper une session encore active. Le secret client n'y est pas (voir `SessionAuthorizedClients`) : les jetons de rafraîchissement copiés ne servent à rien ; les jetons d'accès du navigateur ne portent pas l'audience de l'API |

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| **Première version : session maison** (empreinte du cookie, connexion scellée, *refresh* à la main, compare-and-set) | Réécrit ce que Spring fournit. Ses deux arguments tombaient : « la session HTTP impose l'affinité » est vrai de la session **en mémoire**, faux avec Spring Session ; « Spring Session stocke les jetons en clair » se contredisait, puisque le jeton de rafraîchissement ne sert à rien sans le secret. Reste un vrai gain — l'identifiant de session haché — qui ne justifie pas une quarantaine de classes : listé en piste |
| Session **sans état** : tout dans un cookie scellé, clé commune aux nœuds | Envisagée avec le porteur du projet, écartée par lui : aucune révocation côté serveur (un cookie copié vaut jusqu'à son échéance), limite de 4 Ko, clé partagée à distribuer et à faire tourner |
| `keycloak-js`, client public, jetons en mémoire | Aucun secret possible dans un navigateur ; un script injecté lit le jeton ; contraire à la demande |
| Formulaire maison + *password grant* avec secret client | Exclu par OAuth 2.1 et la RFC 9700 : le mot de passe transite par l'application, ni SSO ni second facteur possibles |
| Garder la rotation, sérialiser le *refresh* (verrou PostgreSQL par session) | Une cinquantaine de lignes pour une protection sans objet chez un client confidentiel |
| `JdbcOAuth2AuthorizedClientService` pour les jetons | Clé par **utilisateur**, pas par session : deux navigateurs du même utilisateur s'écraseraient leurs jetons |
| Déconnexion de serveur à serveur (POST avec le jeton de rafraîchissement) | Propre à Keycloak ; la déconnexion OpenID Connect est standard et fournie par Spring |

## Conséquences

**Positives** — le protocole et la session sont ceux de Spring, pas du
projet : moins de code à défendre (environ 2 300 lignes et 1 400 lignes de
tests maison retirées) ; aucun jeton dans le navigateur ; aucun état dans un
nœud ; les systèmes tiers ne touchent jamais une session. Vérifié de bout en
bout contre un vrai Keycloak : connexion entre deux sites, renouvellement,
fin de session décidée par Keycloak, déconnexion OpenID Connect
([journal 013](../prompts/013-authentification-briques-standard.md)).

**Négatives** — deux tables (Spring Session) et une lecture en base par
requête du navigateur ; l'identifiant de session en clair en base ; un appel à
Keycloak par session active toutes les 5 min ; les écritures portent un
en-tête CSRF ; le realm doit être réimporté (`docker compose down -v`) ; la
connexion suppose que l'interface et l'API partagent une origine (proxy Vite
en développement, service en livraison).

**Ce qui la remettrait en cause** — une interface servie depuis une autre
origine que l'API (CORS avec identifiants, cookie `SameSite=None`) ; un besoin
de révocation immédiate (déconnexion par canal arrière d'OpenID Connect,
`oidcLogout().backChannel()`, avec un registre des sessions partagé) ; une
exigence « rien d'exploitable dans une copie de la base » (identifiant de
session haché, ou attributs chiffrés au repos).
