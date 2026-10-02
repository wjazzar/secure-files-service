# 013 — Authentification : la couche maison remplacée par le client OAuth2 de Spring et Spring Session

- **Date** : 2026-09-28
- **Outil** : Claude Opus 5.5 (Claude Code)
- **Objectif** : remettre en question la session du navigateur livrée le matin même ([012](012-authentification-client-confidentiel.md)), puis la remplacer par les briques standard
- **Phase du projet** : développement transverse (back, front, contrat, realm) — ADR-0012 réécrit

## Prompts

> Pourquoi avoir réécrit à la main la gestion de l'authentification ? Le
> client OAuth2 de Spring Security couvre déjà ce besoin :
> https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html

> Pourquoi propager la session entre les nœuds ? Avec un état porté par un
> cookie sans état, scellé par la même clé sur tous les nœuds, n'importe quel
> nœud peut valider la requête.

> Le rafraîchissement remet cette approche en cause : un jeton rafraîchi par
> un nœud doit être connu de tous les autres. Que proposes-tu ? Qu'apporterait
> Spring Session ?

> D'accord pour ne pas révoquer le jeton de rafraîchissement à chaque usage
> […]. En revanche, la session ne doit pas devenir obligatoire pour les
> systèmes tiers : l'API programmable doit rester utilisable avec un jeton
> `Bearer`, sans session.

> La conception me convient : applique-la et finalise l'ensemble. […]

## Le raisonnement, étape par étape

| Étape | Position | Ce qui l'a fait évoluer |
|---|---|---|
| 1. Constat | Le protocole était bien celui de Spring (`oauth2Login`), mais **tout ce qui l'entoure** avait été réécrit : ~40 fichiers, ~2 300 lignes, ~1 400 lignes de tests | Relecture du code, fichier par fichier |
| 2. Les arguments de l'ADR | Deux sur trois ne tenaient pas : « la session HTTP impose l'affinité » est faux avec Spring Session ; « Spring Session stocke les jetons en clair » se contredit (le jeton de rafraîchissement ne sert à rien sans le secret) | Confrontation des arguments entre eux |
| 3. Cookie sans état (proposition du porteur) | Techniquement valable — et il n'y aurait rien à propager. Mais : pas de révocation côté serveur, 4 Ko, clé partagée à faire tourner | Le porteur du projet l'écarte |
| 4. Le *refresh* concurrent | Mis en évidence : avec la rotation (`revokeRefreshToken: true`, réutilisation 0), deux nœuds qui renouvellent la même session → le second reçoit `invalid_grant` et déconnecte l'utilisateur | Lecture du realm |
| 5. Spring Session | **Ne règle pas** ce problème — aucun verrou, et il existe même sur un seul nœud. Ce qu'il apporte : une seule copie de la session (rien à propager), déconnexion immédiate, cookie minuscule, aucune clé à distribuer | Précision apportée avant que le porteur n'en fasse l'argument décisif |
| 6. Rotation | Désactivée, décision du porteur : elle protège un jeton volé chez un client **public** ; ici il ne quitte pas le serveur et ne vaut rien sans le secret (RFC 9700 : rotation exigée pour les clients publics seulement) | Décision humaine |
| 7. L'API programmable | Inquiétude du porteur : une session obligerait les systèmes tiers à en avoir une. Non : **deux chaînes de filtres** sur les mêmes routes — `Authorization` → sans état, sinon → session | Prouvé ensuite par un test : un appel `Bearer` ne crée ni session ni cookie |

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| Client OAuth2 de Spring pour tout le protocole, *refresh* (`OAuth2AuthorizedClientManager`) et déconnexion OpenID Connect (`OidcClientInitiatedLogoutSuccessHandler`) compris | C'est l'objet de la remarque du porteur : ne rien réécrire de ce qu'il fournit |
| Spring Session JDBC, schéma officiel recopié dans `V7` | La session dans PostgreSQL, déjà présent ; une dépendance, pas de code |
| Deux chaînes : `Bearer` sans état, navigateur avec session | Les systèmes tiers ne touchent jamais une session |
| Cookie `SameSite=Lax` (au lieu de `Strict`) | La session garde désormais la connexion en cours : le retour de Keycloak, navigation venue d'un autre site quand Keycloak est sur un autre domaine, doit la retrouver. Le CSRF reste la parade principale |
| Déconnexion : `200 {logoutUrl}` au lieu de `204` | Un `fetch` ne suit pas une redirection vers une autre origine ; l'interface navigue vers l'adresse. Contrat passé en 1.5 |

## Ce que j'ai rejeté ou corrigé, et pourquoi

| Point | Pourquoi |
|---|---|
| Le dépôt de jetons par défaut (`HttpSessionOAuth2AuthorizedClientRepository`) | **Trouvé en lisant les sources de Spring Security** : il sérialise `ClientRegistration`, **secret client compris**, dans chaque session — donc dans chaque ligne en base, à côté du jeton de rafraîchissement. Remplacé par `SessionAuthorizedClients` (40 lignes) qui ne range que les jetons ; un test vérifie que le secret n'est pas dans la table |
| `JdbcOAuth2AuthorizedClientService` | Clé par utilisateur, pas par session : deux navigateurs du même utilisateur s'écraseraient leurs jetons |
| Garder la rotation avec un verrou PostgreSQL | Une cinquantaine de lignes pour une protection sans objet chez un client confidentiel |
| Ma première réponse : « le package ne partage pas la session → Spring Session » | Juste, mais présentée comme la solution au *refresh*, ce qu'elle n'est pas ; corrigé explicitement dans l'échange suivant |
| **Défaut introduit par la séparation des chaînes**, trouvé en exécutant contre le vrai service | Chaque `401` renvoie, dans son défi `WWW-Authenticate`, vers la métadonnée RFC 9728 (`/.well-known/oauth-protected-resource`). Sans en-tête `Authorization`, cette requête tombait dans la chaîne du navigateur et recevait… `401`. L'adresse est désormais routée vers la chaîne sans état ; un test suit le lien du défi |

## Ce qui reste au projet, et pourquoi Spring ne le fournit pas

`SignInRequestResolver` (adresse fixe, retour assaini), `SessionAuthorizedClients`
(pas de secret en base), `SessionRevalidationFilter` (personne ne demande le
renouvellement quand le service est son propre serveur de ressources ; politique
en cas de panne de Keycloak : dernier verdict servi 30 min au plus, réinterrogé
toutes les 30 s), la réponse JSON de la déconnexion. Le reste est supprimé :
domaine et cas d'usage de session, stockage JDBC maison, chiffrement AES-GCM,
connexion scellée, appels à Keycloak écrits à la main, purge planifiée.

## Vérifications effectuées

| Point | Résultat |
|---|---|
| Sources de Spring Security 7.1.1 et Spring Session 4.1.1 lues avant d'écrire | Comportement exact du manager, du *refresh*, des codes d'erreur retirés, de la déconnexion OIDC, de la purge de Spring Session (son propre ordonnanceur, sans effet sur celui de l'application) |
| Suite back complète (`./mvnw clean verify`) | **446 tests + 2 preuves mémoire**, `BUILD SUCCESS` ; ArchUnit (25 règles) sans exception |
| `BrowserSessionTest` réécrit (20 tests, Keycloak simulé par WireMock) | Paramètres de la redirection, échange du code avec secret et PKCE, **nouvel identifiant de session**, *login CSRF*, `state` et `nonce` faux, redirection ouverte, secret absent de la base, session supprimée en base → `401`, CSRF (écriture et déconnexion), renouvellement **une seule fois** puis relu depuis la base, `invalid_grant` → `401`, Keycloak en panne → servi sans le réinterroger, adresse de fin de session |
| Les refus échouent pour la bonne raison | Journaux relus : `authorization_request_not_found`, `invalid_nonce`, `invalid_grant`, `invalid_token_response` |
| `SessionRevalidationFilterTest` (7 tests, horloge fixe) | Borne des 30 min sans verdict, délai de 30 s entre deux essais, absence de jeton de rafraîchissement |
| `OidcSecurityTest` | Un appel `Bearer` — dépôt, liste, session, jeton forgé — **ne pose aucun cookie et ne crée aucune session** ; la métadonnée du défi `401` répond sans jeton |
| Front | 120 tests, types, lint ; déconnexion qui suit l'adresse rendue, message au retour de Keycloak ; test de dérive du contrat étendu à `Logout` |
| Piège de construction | Un premier lancement a échoué sur deux migrations `V7` : l'ancienne restait dans `target/`. `clean` systématique ensuite |
| **Parcours réel dans un navigateur**, contre un vrai Keycloak (conteneurs jetables : Keycloak et PostgreSQL à part, pile du poste intacte, stockage objet non sollicité) | Connexion → Keycloak (PKCE S256) → retour, entre `localhost:18081` et `127.0.0.1:5173`, **deux sites différents** : le cookie `SameSite=Lax` passe, là où `Strict` aurait échoué. Script : seul `XSRF-TOKEN` visible, aucun stockage local. En base : la session au nom du `sub`, **aucune ligne contenant le secret client**. Déconnexion → page de fin de session Keycloak → `/login?signed-out` (l'adresse avec sa requête est acceptée telle quelle), sessions fermées des deux côtés, mot de passe redemandé. Jetons d'une minute : renouvelés et réécrits en base à la requête suivante. Session d'Alice fermée par l'administrateur Keycloak → `401 UNAUTHENTICATED` à la requête suivante, journal « ended by Keycloak » |
