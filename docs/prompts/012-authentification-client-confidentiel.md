# 012 — Authentification v2 : le service client confidentiel, le navigateur en cookie seul

- **Date** : 2026-09-28
- **Outil** : Claude Opus 5.5 (Claude Code)
- **Objectif** : brancher la connexion Keycloak de bout en bout — back, front, realm — avec un client confidentiel, un cookie seul côté navigateur et une empreinte en base
- **Phase du projet** : développement transverse (back, front, contrat, infrastructure) — ADR-0012

> **Remplacé le jour même** : la couche de session maison décrite ici a été
> retirée au profit du client OAuth2 de Spring et de Spring Session, après
> relecture par le porteur du projet — voir [013](013-authentification-briques-standard.md).
> Cette entrée reste telle quelle : c'est la trace de ce qui a été corrigé.

## Prompt

> Keycloak est en place : branche maintenant l'authentification de bout en
> bout, côté back-end et côté front-end. Exigences :
>
> - le service est le client confidentiel de Keycloak (identifiant et secret
>   client) ;
> - le navigateur ne détient qu'un cookie, jamais de jeton ;
> - ce que le serveur conserve de la session est haché.

## Interprétation retenue

La consigne tient en trois mots-clés ; chacun a été traduit en un mécanisme
vérifiable, puis consigné dans l'ADR-0012.

| Consigne | Traduction |
|---|---|
| « clientid + secretid » | Le **service** est le client **confidentiel** `praxedo-web` : il échange le code d'autorisation avec son secret (`client_secret_basic`) et le vérificateur PKCE. Un secret ne peut pas vivre dans un navigateur : c'est donc le modèle *backend for frontend* |
| « cookie-only » | Le navigateur ne reçoit **aucun jeton** : un cookie `HttpOnly`, `Secure`, `SameSite=Strict`, préfixé `__Host-`. `getAccessToken()` rend `null` côté front, par construction |
| « hash le retour » | Ce qui est rendu au navigateur (le jeton de session) n'est gardé en base que sous forme d'**empreinte SHA-256**. Ce que Keycloak rend au service (jeton de rafraîchissement) ne peut pas être haché — il doit resservir — il est donc **scellé** (AES-GCM) sous une clé dérivée du cookie. `nonce` et PKCE sont, eux aussi, des empreintes vérifiées au retour |
| « la validation » | À la connexion : jeton d'identité vérifié (signature JWKS, émetteur, audience, échéance, `nonce`). À chaque requête : empreinte → session, puis **revalidation chez Keycloak** à l'échéance du jeton d'accès |

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| Protocole confié à Spring Security (`oauth2Login`), pas réécrit | État, nonce, PKCE, échange du code et vérification OIDC sont exactement ce qu'il ne faut pas écrire soi-même (règle B-11) |
| Connexion en cours **scellée dans un cookie** au lieu de la session HTTP de Spring | Le retour de Keycloak peut arriver sur un autre nœud ; le cookie lie aussi l'essai au navigateur qui l'a commencé (*login CSRF*) |
| Session propre en base (`user_session`) plutôt que Spring Session JDBC | Spring Session stocke l'identifiant de session en clair — le contraire de « hash le retour » |
| Revalidation à l'échéance du jeton d'accès, **compare-and-set** pour qu'une seule requête s'en charge ; rotation des jetons de rafraîchissement dans le realm | Keycloak reste l'autorité (désactivation effective en 5 min) sans rafraîchir à chaque requête ni se marcher dessus entre nœuds |
| Keycloak muet → session gardée jusqu'à son terme (30 min), jamais au-delà | Disponibilité bornée plutôt que déconnexion générale ; compromis écrit, pas découvert |
| CSRF « SPA » de Spring (`XSRF-TOKEN` → `X-XSRF-TOKEN`) **seulement** pour les écritures portées par le cookie | Un appel anonyme doit toujours recevoir le `401` du contrat, un système tiers en `Bearer` n'a rien à forger |
| `praxedo-frontend` (client public avec *password grant*) remplacé par `praxedo-web` et `praxedo-integration` (*client credentials*) | Plus aucun client n'accepte un mot de passe hors de Keycloak ; le chemin `Bearer` reste démontrable |
| Hexagone respecté : domaine (`SessionToken`, `SessionKey`, `UserSession`), quatre ports d'entrée, trois ports de sortie ; Keycloak dans l'adaptateur `security`, SQL dans `persistence`, protocole dans `web` | Les 25 règles ArchUnit passent sans exception ni assouplissement |

## Ce que j'ai rejeté ou corrigé, et pourquoi

| Option | Pourquoi |
|---|---|
| Formulaire de connexion maison + *password grant* avec le secret (lecture possible de « clientid + secretid ») | Exclu par OAuth 2.1 et la RFC 9700 : le mot de passe transiterait par l'application. Le formulaire du mode simulé reste pour les bouchons |
| `keycloak-js` en client public (plan initial du front) | Pas de secret possible, jeton lisible par un script injecté |
| Hacher le jeton de rafraîchissement | Il ne pourrait plus servir ; scellé sous une clé que seul le navigateur détient, il est illisible au repos |
| Découverte OpenID Connect au démarrage | Adresse du navigateur injoignable depuis le conteneur, et démarrage lié à celui de Keycloak ; registration déclarée champ par champ, émetteur compris (sans lui, l'émetteur du jeton d'identité n'était pas vérifié) |
| `OidcIdTokenDecoderFactory` par défaut | Aucun délai sur la lecture des clés (règle B-7) : fabrique maison avec délais, validateurs de Spring |

## Vérifications effectuées

| Point | Résultat |
|---|---|
| Suite back complète (`./mvnw verify`) | 472 tests verts, dont 34 nouveaux d'intégration : `BrowserSessionTest` (Keycloak simulé par WireMock, navigateur joué cookie par cookie), `JdbcSessionStoreTest` (16 requêtes concurrentes, une seule revalidation), `KeycloakIdentityProviderTest` |
| Les refus échouent pour la bonne raison | Journaux relus : `authorization_request_not_found` (pas de cookie, autre `state`, cookie altéré), `invalid_nonce` |
| Front | 118 tests (dont l'adaptateur `session` et la page en mode redirection), lint, types, build ; le test de dérive compare `Session` au contrat |
| Import du realm | Keycloak jetable : secrets résolus depuis l'environnement ; depuis un conteneur, émetteur `localhost:8081` et point de jetons `keycloak:8080` (`KC_HOSTNAME` + canal arrière dynamique) |
| Parcours réel dans un navigateur (conteneurs jetables, pile du poste intacte) | Connexion → Keycloak (PKCE S256) → retour ; seul `XSRF-TOKEN` visible du script, aucun stockage local ; dépôt par cookie + en-tête CSRF, analysé, « Disponible » ; en base, l'empreinte seule ; Bob ne voit rien d'Alice ; deux revalidations avec rotation ; session de Bob fermée dans Keycloak → `401` à la revalidation suivante ; déconnexion → Keycloak redemande le mot de passe |
| Cookie `__Host-` + `Secure` en HTTP local | Accepté par Chromium sur `http://127.0.0.1:5173` : défaut `secure=true` conservé |

Incident de vérification, sans lien avec l'authentification : un premier dépôt
a échoué en `500` parce que le S3 jetable manquait de volumes. Le SDK a
réessayé l'écriture et le flux, non rejouable par conception, a masqué
l'erreur d'origine (`A streamed body cannot be replayed`) : une panne du
stockage remonte en `500 INTERNAL_ERROR` au lieu de `503`. Signalé au porteur
du projet, non corrigé ici.
