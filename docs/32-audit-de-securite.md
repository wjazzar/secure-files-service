# 32 — Audit de sécurité : back-end, front-end, infrastructure

> *Analyse : Claude (Opus 5.5), 29/09/2026.* État audité : le code tel qu'il
> était ce jour-là.
> Constats `S-01` à `S-20`, plan de correction `T-01` à `T-15`.
>
> **Audit daté, tenu à jour par son tableau de synthèse (§2) et par le §8.**
> Les rubriques « Où » et « Constat » décrivent le code **à la date de l'audit** :
> le code a changé depuis (c'est l'objet des corrections), les numéros de
> ligne ne tombent donc plus forcément sur le passage décrit, et certains
> fichiers cités n'existent plus. Les renvois à [`06`](06-securite-et-angles-morts.md)
> §9 visent sa version d'alors, qui annonçait une limite de débit, des quotas
> et une analyse des dépendances en CI ; ce document a été relu depuis et dit
> ce qui est livré.

---

## 1. Périmètre et méthode

| Volet | Ce qui a été fait |
|---|---|
| Back-end | Relecture manuelle du code de production : chaînes Spring Security, session du navigateur (connexion, revalidation, déconnexion), contrôleurs, services de dépôt, d'analyse, de promotion et de téléchargement, persistance (JPA, requêtes, migrations), adaptateurs S3 et antivirus, `application.yml` |
| Front-end | Client API, adaptateurs d'authentification, redirections, téléchargement ; recherche des motifs dangereux (`innerHTML`, `eval`, stockage navigateur, navigation vers une URL calculée) ; **build de production produit et inspecté** |
| Infrastructure | `docker-compose.yml`, realm Keycloak, identités SeaweedFS, Dockerfiles, script d'entrée de l'antivirus, CI GitHub |
| Dépendances | `npm audit` : **0 vulnérabilité** (57 dépendances de production, 771 au total). Provenance du paquet `cn` vérifiée : publié par shadcn |
| Secrets | Historique Git complet : aucun `.env`, clé ou certificat jamais commité ; aucun motif de clé (AWS, GitHub, clé privée) |

**Limites** : pas d'analyse des dépendances Maven, faute d'outil installé (c'est
le constat S-11) ; pas de test dynamique contre l'application en marche.

**Gravité**, évaluée *si le service était déployé tel quel hors d'un poste de
développement* :

- **Élevée** : fichiers de tous les utilisateurs ou invariant compromis, sur une
  simple erreur de déploiement ou à partir d'une première faille ;
- **Moyenne** : déni de service accessible à un tiers, ou compromission qui
  exige une position sur le réseau ;
- **Faible** : défense en profondeur, hygiène.

**Statut** : *nouveau* ; *connu* (déjà au README §10) ; *prévu, non fait*
(annoncé par [`06`](06-securite-et-angles-morts.md) §9, absent du code).

---

## 2. Synthèse

**Le cœur tient.** Aucune injection, aucun contournement du cloisonnement par
propriétaire, aucun chemin qui servirait un fichier non analysé. Les défenses
propres au domaine sont en place et testées (§4) : verdict lié au SHA-256,
trois identités de stockage, réponses de téléchargement neutralisées.

Les constats portent sur trois autres terrains :

1. **Des défauts qui échouent ouverts.** Une variable suffisait à couper
   l'authentification (corrigé le 29/09 : le mode est supprimé, ADR-0014) ; les secrets de développement servent de valeurs par
   défaut ; le service se connecte à PostgreSQL en superutilisateur.
2. **La résistance aux abus.** Un visiteur non authentifié peut écrire en base à volonté, un seul
   compte peut remplir la file de tous, un client lent peut occuper les places
   de dépôt.
3. **La chaîne d'approvisionnement.** Ni analyse des dépendances Maven, ni SAST,
   ni CI du front, alors que [`06`](06-securite-et-angles-morts.md) §9 les
   annonçait.

| Gravité | Nombre | Constats |
|---|---|---|
| Élevée | 3 | S-01, S-02, S-03 |
| Moyenne | 8 | S-04 à S-11 |
| Faible | 9 | S-12 à S-20 |

| ID | Gravité | Constat | Statut | Tâche |
|---|---|---|---|---|
| S-01 | Élevée | Le mode sans authentification s'active par une seule variable | **corrigé** (29/09) | ADR-0014 |
| S-02 | Élevée | Les secrets de développement sont les valeurs par défaut du service | **corrigé** (01/10) : aucun défaut, profil `local` | T-03 |
| S-03 | Élevée | Service, Flyway et Keycloak partagent un rôle PostgreSQL superutilisateur | **corrigé** (01/10) : trois rôles, `DatabaseRolesTest` | T-08 |
| S-04 | Moyenne | Chaque appel non authentifié à `/api/v1/auth/login` écrit une session en base | **accepté** | passerelle, README §10 |
| S-05 | Moyenne | Aucun quota ni limite de débit par utilisateur | **accepté** | passerelle, README §10 |
| S-06 | Moyenne | Un dépôt lent garde sa place indéfiniment | **corrigé** (30/09) : échéance de transfert, `408 UPLOAD_TOO_SLOW` | T-13 |
| S-07 | Moyenne | Le canal vers l'antivirus n'est ni chiffré ni authentifié | **hors périmètre** (01/10) : l'antivirus est un composant fourni (README §9) ; port sur 127.0.0.1 | T-01 |
| S-08 | Moyenne | L'actuator (métriques) est public | **corrigé** (01/10) : port 8091 | T-09 |
| S-09 | Moyenne | Compose publie tous les ports sur toutes les interfaces, identifiants triviaux | **corrigé** (01/10) : `127.0.0.1` | T-01 |
| S-10 | Moyenne | Un verdict n'est jamais réexaminé | connu | piste |
| S-11 | Moyenne | Ni analyse de dépendances, ni SAST, ni CI du front ; CI sans moindre privilège | **traité autrement** (01/10) : CI retirée (exercice), analyse ponctuelle et correctifs ([`33`](33-analyse-des-dependances.md)) | ~~T-07~~, T-14 |
| S-12 | Faible | Le build de production embarque les bouchons et démarre en mode anonyme | **corrigé** (29/09, puis 01/10 : plus aucun bouchon, formulaire ni libellé de démonstration dans un build, quels que soient son mode et son dossier) | T-06 |
| S-13 | Faible | Pas de CSP ni d'hébergement défini pour l'interface ; polices chez Google | **hors périmètre** (01/10) : pas de mise en production, interface servie par Vite (README §9) | ~~T-15~~ |
| S-14 | Faible | Le front suit des URL reçues du serveur sans en vérifier le schéma | **corrigé** (01/10) | T-06 |
| S-15 | Faible | Le signataire de liens HMAC, retiré par l'ADR-0013, est toujours câblé | **corrigé** (29/09) | ~~T-04~~ |
| S-16 | Faible | Jetons OIDC sérialisés en Java, en clair, dans la table des sessions | connu, précisé | piste |
| S-17 | Faible | Fichiers infectés conservés sans limite ; aucun effacement possible | connu | piste |
| S-18 | Faible | Réponse de téléchargement sans `Content-Security-Policy: sandbox` | **en attente** : décision du porteur du projet | T-05 |
| S-19 | Faible | Numéro de page sans borne (`OFFSET` profond) | **corrigé** (01/10) : contrat 1.9 | T-05 |
| S-20 | Faible | Rien n'est prêt pour TLS derrière un mandataire ; realm de développement | nouveau | piste |

> **Clôture du 01/10.** Décisions du porteur du projet : les lots 1 et 2
> sont faits, sauf l'en-tête `sandbox` du téléchargement (S-18), en attente
> de sa décision. L'antivirus (S-07) et la mise en production de l'interface
> (S-13) sont hors périmètre. La CI est retirée : l'exercice n'en a pas
> besoin, et l'analyse des dépendances a été faite une fois, avec ses
> correctifs ([`33`](33-analyse-des-dependances.md)). Restent des pistes,
> documentées : S-10, S-16, S-17, S-20.

---

## 3. Constats détaillés

Chemins abrégés : `web/…`, `application/…` = `backend/src/main/java/com/praxedo/securefiles/…`.

### S-01 — Le mode sans authentification s'active par une seule variable · Élevée

- **Où** : [`SecurityConfiguration.java:79`](../backend/src/main/java/com/praxedo/securefiles/infrastructure/web/common/config/SecurityConfiguration.java#L79),
  [`application.yml:199`](../backend/src/main/resources/application.yml#L199).
- **Constat** : `SECURITY_MODE=none` installe une chaîne `permitAll`, et tous les
  fichiers appartiennent alors à `anonymous`. Rien ne le signale au démarrage.
- **Risque** : une variable recopiée d'un environnement de démonstration expose
  les fichiers de tous les utilisateurs, en lecture et en téléchargement, à
  quiconque atteint le port.
- **Incohérence** : le projet a déjà la bonne garde ailleurs.
  `ANTIVIRUS_MODE=instant-clean` n'agit que si le profil `capacity` est actif
  **aussi** ([`AntivirusConfiguration.java`](../backend/src/main/java/com/praxedo/securefiles/config/AntivirusConfiguration.java)).
  L'authentification, dont l'enjeu est au moins égal, n'a pas cette garde.
- **Correction** : exiger un profil explicite (`local`) en plus de la variable ;
  refuser le démarrage sinon ; avertissement au démarrage quand le mode est actif.
- **Corrigé le 29/09, autrement** ([ADR-0014](adr/0014-authentification-toujours-exigee.md)) :
  plutôt que de garder le mode derrière un profil, le porteur du projet l'a
  **supprimé**. Plus de `praxedo.security.mode` ni de `SECURITY_MODE`, plus de
  chaîne `permitAll`, plus d'utilisateur `anonymous` ; sans preuve vérifiée,
  `CurrentOwner` échoue. Le contrat perd l'exigence vide `{}` (1.7), les
  scripts `--no-auth`, l'interface son mode `none`. Toute la suite de tests
  passe désormais par un Keycloak simulé.

### S-02 — Les secrets de développement sont les valeurs par défaut · Élevée

- **Où** : [`application.yml:22`](../backend/src/main/resources/application.yml#L22) (base),
  [`:142-148`](../backend/src/main/resources/application.yml#L142) (trois identités S3),
  [`:219`](../backend/src/main/resources/application.yml#L219) (secret client OIDC).
- **Constat** : chaque valeur sensible a un repli `${VAR:valeur-de-dev}`, et ces
  valeurs sont publiées dans le dépôt (`infra/seaweedfs/s3-identities.json`,
  `docker-compose.yml`). Seul le secret OIDC déclenche un avertissement ; aucun
  n'empêche le démarrage.
- **Risque** : un déploiement où une variable manque démarre quand même, avec un
  identifiant connu de tous. Si l'infrastructure a été provisionnée depuis les
  fichiers du dépôt (realm importé, identités S3), ce sont les vrais secrets.
  C'est l'interdit n°5 d'`AGENTS.md` sous une autre forme : **une valeur par
  défaut est un secret en dur.**
- **Correction** : déplacer les valeurs de développement dans
  `application-local.yml`. Sans ce profil, une propriété sensible absente fait
  échouer le démarrage (`${DB_PASSWORD}`, sans repli). Compose et les scripts
  activent `local`.

### S-03 — Un rôle PostgreSQL unique, superutilisateur, partagé avec Keycloak · Élevée

- **Où** : [`docker-compose.yml:52`](../docker-compose.yml#L52) et
  [`:179`](../docker-compose.yml#L179) (`KC_DB_USERNAME: ${POSTGRES_USER}`),
  [`:289-291`](../docker-compose.yml#L289).
- **Constat** : l'image officielle crée `POSTGRES_USER` **en superutilisateur**.
  Le service (requêtes et Flyway) et Keycloak se connectent tous deux avec ce
  rôle. Le README §10 note « un seul rôle pour les migrations et
  l'application », mais ne dit ni qu'il est superutilisateur, ni qu'il est
  partagé avec le fournisseur d'identité.
- **Risque** : une première faille dans le service (injection future,
  dépendance vulnérable) devient une compromission totale :
  - le service peut retirer les contraintes `CHECK` et le trigger d'audit.
    L'invariant « structurellement impossible à violer » ne tient alors que par
    la discipline du code ;
  - il lit la base de Keycloak : empreintes de mots de passe, secrets des
    clients ;
  - un superutilisateur exécute des commandes sur l'hôte de la base
    (`COPY … TO PROGRAM`).
- **Correction** : trois rôles. `praxedo_owner` pour Flyway, propriétaire du
  schéma. `praxedo_app` pour l'exécution : `SELECT`, `INSERT`, `UPDATE`,
  `DELETE` sur les seules tables utiles, aucun DDL. `keycloak`, propriétaire de
  sa seule base. Aucun superutilisateur hors administration.

### S-04 — Chaque appel non authentifié à `/api/v1/auth/login` écrit une session · Moyenne

- **Où** : [`SignInRequestResolver.java:50`](../backend/src/main/java/com/praxedo/securefiles/infrastructure/web/session/signin/SignInRequestResolver.java#L50),
  [`SecurityConfiguration.java:119`](../backend/src/main/java/com/praxedo/securefiles/infrastructure/web/common/config/SecurityConfiguration.java#L119).
- **Constat** : le point d'entrée de connexion est ouvert (il doit l'être) et
  crée une session HTTP : la destination de retour, puis la requête
  d'autorisation de Spring Security. Avec Spring Session JDBC, chaque appel
  **insère une ligne en base**, qui vit 30 minutes. Aucune limite de débit.
- **Risque** : un script sans compte remplit `SPRING_SESSION` et occupe le pool
  de connexions de l'API (10 par nœud). C'est un déni de service non
  authentifié : il atteint les utilisateurs connectés et, par la base, les
  workers.
- **Correction** : limiter les appels par adresse IP à cet endroit (mandataire
  frontal ou filtre). Mieux : ne rien écrire en base avant le retour de
  Keycloak, en gardant la requête d'autorisation dans un cookie chiffré de
  courte durée. La session ne naît alors qu'au rappel.

### S-05 — Aucun quota ni limite de débit par utilisateur · Moyenne

- **Où** : [`UploadAdmission.java:57-61`](../backend/src/main/java/com/praxedo/securefiles/application/file/service/UploadAdmission.java#L57),
  [`application.yml:114-121`](../backend/src/main/resources/application.yml#L114).
- **Constat** : les deux bornes d'admission sont **globales** (fichiers en
  attente, dépôts simultanés par nœud). [`06`](06-securite-et-angles-morts.md)
  §9 prévoyait une limite de débit par principal et des quotas : ni l'une ni
  les autres n'existent.
- **Risque** : un seul compte, ou un système tiers mal réglé, remplit la file
  (500 fichiers) et fait répondre `429` à tous les autres. Le volume stocké par
  utilisateur est illimité.
- **Correction** : borne de fichiers en attente **par propriétaire** (l'index
  `idx_file_owner_status` existe déjà ; même mise en cache que la borne
  globale), plafond d'octets par propriétaire, limite de débit par principal.
  Nouveau code d'erreur au contrat.

### S-06 — Un dépôt lent garde sa place indéfiniment · Moyenne

- **Où** : [`UploadAdmission.java:57`](../backend/src/main/java/com/praxedo/securefiles/application/file/service/UploadAdmission.java#L57).
- **Constat** : une place de dépôt (50 par nœud) est tenue tant que le corps
  arrive. Seul le délai d'inactivité du connecteur s'applique, d'un octet au
  suivant. Aucun délai total, aucun débit minimal.
- **Risque** : cinquante connexions qui envoient un octet de temps en temps
  ferment les dépôts d'un nœud (« slowloris ») : sans compte en v1, avec un
  seul compte en v2.
- **Correction** : un débit minimal mesuré sur le flux (par exemple 64 Kio/s
  après une période de grâce), ou un délai total proportionnel à la taille
  annoncée. À défaut, le délai de lecture du corps du mandataire frontal.
- **Corrigé le 30/09** (relecture du back-end, R-007), par le délai total : le
  corps doit arriver dans 60 s + 8 s par Mio annoncé
  (`praxedo.upload.transfer-deadline`), vérifié avant chaque lecture par
  `DeadlineInputStream` ; au-delà, `408 UPLOAD_TOO_SLOW`, objet supprimé,
  place rendue. Contrat 1.8. Reste ouvert : à ce rythme minimal, un client qui
  annonce 500 Mo garde sa place ~68 min — le quota par propriétaire (S-05)
  en est le complément.

### S-07 — Le canal vers l'antivirus n'est ni chiffré ni authentifié · Moyenne

- **Où** : [`HttpAntivirusScanner.java:108`](../backend/src/main/java/com/praxedo/securefiles/infrastructure/antivirus/file/adapter/HttpAntivirusScanner.java#L108),
  [`docker-compose.yml:252`](../docker-compose.yml#L252).
- **Constat** : un `200` sur `/scanHandlerBody` vaut `CLEAN`. L'appel se fait en
  HTTP clair, sans authentification, et le port 9000 est publié sur l'hôte.
- **Risque** : qui se place entre le service et le moteur (DNS, réseau partagé,
  conteneur voisin) répond `200` à tout et blanchit n'importe quel fichier. Les
  contrôles d'intégrité (SHA-256, lecture complète) n'arrêtent pas un faux
  moteur qui lit tout avant de répondre. Qui atteint le port peut aussi saturer
  le moteur, goulot du service.
- **Correction** : ne publier le port qu'en boucle locale (T-01). En
  production : réseau privé et TLS mutuel, ou jeton porté par un mandataire
  devant `clamav-rest`.

### S-08 — L'actuator est public · Moyenne

- **Où** : [`SecurityConfiguration.java:95`](../backend/src/main/java/com/praxedo/securefiles/infrastructure/web/common/config/SecurityConfiguration.java#L95)
  et [`:118`](../backend/src/main/java/com/praxedo/securefiles/infrastructure/web/common/config/SecurityConfiguration.java#L118),
  [`application.yml:88`](../backend/src/main/resources/application.yml#L88).
- **Constat** : `/actuator/**` est ouvert dans les deux chaînes, sur le port de
  l'API : `metrics`, `prometheus` et `info` se lisent sans compte. Connu (README
  §10).
- **Risque** : reconnaissance sans authentification. Profondeur de la file,
  volumes, taux de refus, versions : un observateur voit quand le service est
  saturé.
- **Correction** : `management.server.port` distinct et non publié ; seules les
  sondes `health` restent joignables par l'orchestrateur.

### S-09 — Compose publie tous les ports sur toutes les interfaces · Moyenne

- **Où** : [`docker-compose.yml`](../docker-compose.yml) lignes 58, 108-109,
  193-194, 252, 328, 351, 377.
- **Constat** : `"5432:5432"` et les autres écoutent sur `0.0.0.0`. Derrière :
  PostgreSQL `praxedo/praxedo` en superutilisateur (S-03), la clé
  d'administration S3, Keycloak `admin/admin`, Grafana `admin/admin` avec accès
  anonyme, l'antivirus, Prometheus.
- **Risque** : sur un réseau partagé (Wi-Fi public, réseau d'entreprise,
  démonstration), un voisin lit la base, la quarantaine et
  les fichiers servables, ou prend la main sur Keycloak.
- **Correction** : préfixer chaque port par `127.0.0.1:` ; ne publier que ce
  qu'un développeur utilise depuis l'hôte.

### S-10 — Un verdict n'est jamais réexaminé · Moyenne

- **Constat** : un fichier `AVAILABLE` le reste, quelles que soient les
  signatures publiées depuis. Connu (README §10, [`06`](06-securite-et-angles-morts.md) §10).
- **Risque** : un maliciel inconnu le jour du dépôt est servi indéfiniment, même
  après que le moteur a appris à le reconnaître.
- **Correction** (piste) : état `RESCANNING` et réanalyse périodique des fichiers
  disponibles, ou réanalyse au téléchargement quand l'attestation dépasse un âge.

### S-11 — Chaîne d'approvisionnement et CI · Moyenne

- **Où** : `.github/workflows/backend-ci.yml` (CI retirée depuis, voir [`33`](33-analyse-des-dependances.md)),
  [`docker-compose.yml:28`](../docker-compose.yml#L28) et
  [`:168`](../docker-compose.yml#L168).
- **Constat** :
  - aucune analyse des dépendances Maven (OWASP Dependency-Check, Trivy, OSV),
    pourtant annoncée en [`06`](06-securite-et-angles-morts.md) §9 ;
  - aucun SAST (CodeQL, Semgrep), aucune détection de secrets (gitleaks) ;
  - aucune CI pour le front (lint, types, tests, `npm audit`) ;
  - pas de bloc `permissions:` : `GITHUB_TOKEN` reçoit les droits par défaut du
    dépôt ;
  - actions épinglées par étiquette (`@v5`), pas par empreinte ; images
    `postgres:17-alpine` et `keycloak:26.4` non épinglées, alors que les autres
    le sont.
- **Risque** : une dépendance vulnérable ou une action compromise entre sans que
  rien ne le signale.
- **Correction** : T-07, puis T-14.

### S-12 — Le build de production embarque les bouchons · Faible

- **Où** : [`main.tsx:11`](../frontend/src/main.tsx#L11),
  [`env.ts:33`](../frontend/src/config/env.ts#L33),
  `frontend/public/mockServiceWorker.js`.
- **Constat vérifié** : `vite build` produit `browser-*.js` (431 Ko : MSW, faux
  fournisseur d'identité, comptes de démonstration) et copie
  `mockServiceWorker.js` à la racine. La condition `env.VITE_API_MOCKING` est
  évaluée à l'exécution, après validation Zod : Rollup ne peut pas éliminer
  l'import. De plus, sans `.env.production`, `VITE_AUTH_MODE` vaut `none` : le
  build de production démarre en mode anonyme.
- **Risque** : faible, car le serveur refuse tout appel sans session. Mais une
  configuration oubliée donne une interface qui n'envoie jamais à la connexion,
  et du code de test est servi en production, dont un service worker capable
  d'intercepter tout `fetch` s'il était enregistré.
- **Correction** : conditionner l'import par une constante de build
  (`import.meta.env.MODE !== 'production'`) ; exclure le service worker du
  build ; `session` par défaut ; build refusé en `production` si le mode n'est
  pas `session`.
- **En partie corrigé le 29/09** ([ADR-0014](adr/0014-authentification-toujours-exigee.md)) :
  le mode `none` n'existe plus et `session` est le défaut — un build sans
  configuration envoie à la connexion. Reste à exclure les bouchons du build.
- **Corrigé le 01/10** : tout le code du mode bouchon dépend de
  `import.meta.env.DEV`, une constante fausse dans **tout** build — MSW, faux
  fournisseur d'identité, formulaire et comptes de démonstration (chargés à la
  demande), libellés (`i18n/mock-messages.ts`), liens `blob:`. Le greffon de
  build refuse tout mode autre que `session` et retire le service worker du
  dossier de sortie réel, quel qu'il soit. Vérifié : aucune de ces chaînes dans
  `dist/`, y compris en `--mode session --outDir` ailleurs.

### S-13 — Pas de CSP ni d'hébergement défini pour l'interface · Faible

- **Où** : [`index.html:7-11`](../frontend/index.html#L7).
- **Constat** : le mode de service de l'interface en production n'est pas défini
  (seul le mandataire de Vite existe). Il n'y a donc ni
  `Content-Security-Policy`, ni `Referrer-Policy`, ni `frame-ancestors`. Les
  polices viennent de `fonts.googleapis.com`.
- **Risque** : aucune seconde barrière si une XSS apparaissait (aujourd'hui,
  React échappe tout et aucun `dangerouslySetInnerHTML` n'existe). L'adresse IP
  de chaque visiteur part chez Google, un sujet RGPD.
- **Correction** : polices auto-hébergées ; décider qui sert l'interface (ADR),
  avec une CSP stricte : `default-src 'self'; object-src 'none';
  base-uri 'none'; form-action 'self'; frame-ancestors 'none'`.

### S-14 — Des URL reçues du serveur sont suivies sans contrôle · Faible

- **Où** : [`session-adapter.ts:117`](../frontend/src/lib/auth/session-adapter.ts#L117)
  (`logoutUrl` → `window.location.assign`),
  [`files-schemas.ts:73`](../frontend/src/api/files/files-schemas.ts#L73)
  (`links.content` → `href`).
- **Constat** : les schémas Zod acceptent toute chaîne.
- **Risque** : si le serveur, un mandataire ou un bouchon renvoyait
  `javascript:…`, le navigateur l'exécuterait dans l'origine de l'application.
  Défense en profondeur : le serveur est de confiance.
- **Correction** : `links.content` commence par `/api/v1/files/` ; `logoutUrl`
  est en `https:` (ou `http:` en local), vers l'origine connue de Keycloak.

### S-15 — Le signataire de liens HMAC est toujours câblé · Faible

- **Où** : anciennement `DownloadLinkConfiguration`,
  `HmacDownloadLinkSigner`, `DownloadLinkSigner`, `IssuedLink` et
  `DownloadLinkResponse`.
- **Constat** : l'ADR-0007 est remplacé par l'ADR-0013 et plus rien n'utilise le
  signataire. Le bean est pourtant créé à chaque démarrage, avec un secret
  aléatoire et l'avertissement `No DOWNLOAD_LINK_SECRET configured`.
- **Risque** : aucun direct. Mais le journal réclame un secret pour une fonction
  qui n'existe plus, et un relecteur prendra ce code de sécurité mort pour du
  code actif.
- **Correction (29/09)** : les cinq types et leur test ont été supprimés ; le
  démarrage ne crée plus de signataire et ne réclame plus
  `DOWNLOAD_LINK_SECRET`.

### S-16 — Jetons sérialisés en clair dans la table des sessions · Faible

- **Où** : [`SessionAuthorizedClients.java`](../backend/src/main/java/com/praxedo/securefiles/infrastructure/web/session/signin/SessionAuthorizedClients.java),
  `V7__browser_session.sql`.
- **Constat** : le bon choix est déjà fait. Le secret client n'est pas stocké, le
  jeton de rafraîchissement seul est donc inutilisable, et le jeton d'accès de
  `praxedo-web` ne porte pas l'audience de l'API (seul `praxedo-integration` a
  le mapper), donc il ne se rejoue pas en `Bearer`. Restent, en sérialisation
  Java et en clair : les jetons, et le jeton d'identité avec nom et courriel. Le
  README §10 mentionne l'identifiant de session, pas le contenu.
- **Risque** : une copie de la base livre des données personnelles ; qui peut
  écrire dans la table déclenche une désérialisation Java arbitraire dans le
  service.
- **Correction** (piste) : sérialiseur JSON de Spring Security pour les
  attributs de session, chiffrement au repos ; compléter la ligne du README.

### S-17 — Rétention sans fin, effacement impossible · Faible

- **Où** : [`QuarantineSweeper.java:65`](../backend/src/main/java/com/praxedo/securefiles/application/file/service/QuarantineSweeper.java#L65).
- **Constat** : les fichiers infectés restent en quarantaine comme preuve (D-07),
  sans purge ; aucun fichier ne peut être supprimé par son propriétaire. Connu
  pour la rétention (README §10).
- **Risque** : du maliciel s'accumule sur le stockage ; le droit à l'effacement
  (RGPD) ne peut pas être honoré.
- **Correction** (piste) : purge planifiée, l'audit restant ; suppression par le
  propriétaire.

### S-18 — Téléchargement sans `Content-Security-Policy: sandbox` · Faible

- **Où** : [`FileDownloadController.java:80`](../backend/src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileDownloadController.java#L80).
- **Constat** : `application/octet-stream`, `attachment` et `nosniff` suffisent
  dans les navigateurs actuels.
- **Risque** : si un intermédiaire réécrit `Content-Type` ou
  `Content-Disposition`, une page HTML ou SVG jugée saine par l'antivirus
  s'exécuterait dans l'origine de l'application.
- **Correction** : `Content-Security-Policy: default-src 'none'; sandbox` et
  `Cross-Origin-Resource-Policy: same-origin` sur la réponse de contenu.

### S-19 — Numéro de page sans borne · Faible

- **Où** : [`FileQueryController.java`](../backend/src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java) (`pageNumber`).
- **Constat** : `page` n'a pas de borne haute : `OFFSET page × size`, au coût
  linéaire, plus un `count(*)` par page.
- **Risque** : des requêtes coûteuses en boucle, limitées aux fichiers de
  l'appelant.
- **Correction** : refuser `page × size > 10 000` (`400`) ; pagination par clé en
  piste.

### S-20 — Rien n'est prêt pour la production TLS · Faible

- **Constat** :
  - pas de `server.forward-headers-strategy` : derrière un mandataire TLS,
    `{baseUrl}` vaut `http://…` et `request.isSecure()` est faux ; pas de HSTS ;
  - realm de développement : `sslRequired: none`, aucune politique de mot de
    passe, comptes de démonstration au mot de passe `demo` non temporaire,
    Keycloak en `start-dev`, `admin/admin`.
- **Risque** : le jour du déploiement, soit la connexion casse (redirection
  refusée par Keycloak), soit on active les en-têtes transférés sans liste de
  mandataires de confiance, et le client choisit son `Host`.
- **Correction** (piste) : profil `prod` avec
  `forward-headers-strategy: native` et mandataires de confiance, HSTS ; realm
  de production distinct (`sslRequired: all`, politique de mots de passe, aucun
  compte de démonstration).

---

## 4. Ce qui tient — vérifié

| Domaine | Constat |
|---|---|
| Injection SQL | Requêtes paramétrées partout ; tri par liste blanche ; motif `LIKE` échappé (`JpaFileCatalog`) |
| Cloisonnement | Toute lecture exige un propriétaire (`FileQuery`, `findByIdAndOwnerId`) ; fichier d'autrui et identifiant invalide reçoivent le même `404` |
| Invariant | Verdict lié au SHA-256 des octets analysés, lecture intégrale vérifiée, copie re-hachée avant `AVAILABLE` |
| Stockage | Trois identités aux droits disjoints ; la livraison ne lit pas la quarantaine (testé) ; clients S3 non injectables |
| Téléchargement | `application/octet-stream`, `attachment`, `nosniff`, `no-store` ; `Content-Disposition` RFC 6266 échappé |
| Nom de fichier | Séparateurs, caractères de contrôle et de formatage, surcharges bidirectionnelles retirés ; jamais une clé |
| Session | Cookie `__Host-`, `HttpOnly`, `Secure`, `SameSite=Lax` ; aucun jeton dans le navigateur ; PKCE S256, `state`, `nonce` ; secret client absent des sessions |
| CSRF | Jeton exigé pour toute écriture portée par le cookie ; chaîne `Bearer` sans état, séparée |
| Redirections | Retour après connexion réduit à un chemin local, côté serveur (`ReturnTo`) et côté front (`safeRedirect`) |
| Jetons d'API | Signature, émetteur, audience et expiration vérifiés |
| Erreurs | RFC 9457 sans détail interne ; `X-Request-Id` validé avant d'entrer dans les journaux |
| Appels sortants | Délais de connexion **et** de lecture partout : S3, antivirus, Keycloak — et PostgreSQL depuis la revue du 01/10 (§8, V-01) |
| Antivirus | `AlertExceedsMax` ; limite atteinte → `UNSCANNABLE`, jamais `CLEAN` ; `406` illisible → `INFECTED` ; `instant-clean` exige le profil `capacity` |
| Conteneur | Service en utilisateur non root ; images de build épinglées par empreinte |
| Front | Aucun `innerHTML` ni `eval`, aucun jeton en stockage navigateur ; `axios` seul client HTTP, avec délai |

---

## 5. Plan de correction

Propriétaires selon la règle d'[`AGENTS.md`](../AGENTS.md) §4.1 : **B** back-end
(`backend/`, `contracts/`, et l'infrastructure qu'il exploite), **F** front-end,
**A** architecture (`docs/`, README). Effort : XS < 30 min · S ≤ 2 h ·
M ≈ ½ jour.

### Lot 1 — Défauts sûrs et gains immédiats (≈ 1 jour)

| Tâche | Constat | Travail | Prop. | Effort | Critère d'acceptation |
|---|---|---|---|---|---|
| T-01 | S-09, S-07 | Tous les ports de compose en `127.0.0.1:` | B | XS | `docker compose ps` n'affiche aucun `0.0.0.0` ; `scripts/check.*` passe |
| ~~T-02~~ | S-01 | **Sans objet** : le mode `none` est supprimé au lieu d'être gardé (ADR-0014, 29/09) | B | — | Aucune propriété, variable ni option de script ne coupe l'authentification ; toute la suite de tests s'authentifie |
| T-03 | S-02 | Valeurs de développement dans `application-local.yml` ; aucun repli pour un secret dans `application.yml` ; compose et scripts activent `local` | B | S | Sans `local` ni variables, le démarrage échoue en nommant la variable manquante |
| ~~T-04~~ | S-15 | **Fait (29/09)** : signataire HMAC, types et test supprimés | B | XS | Build et ArchUnit verts ; plus d'avertissement `DOWNLOAD_LINK_SECRET` |
| T-05 | S-18, S-19 | En-têtes `CSP sandbox` et `CORP` sur le contenu ; borne `page × size ≤ 10 000` au contrat et au code | B | S | Tests d'en-têtes ; `400` au-delà de la borne |
| T-06 | S-12, S-14 | Bouchons exclus du build de production ; ~~`session` par défaut~~ (fait, ADR-0014) ; schémas des URL resserrés | F | S | `dist/` sans `mock-idp` ni `mockServiceWorker.js` ; tests des schémas |
| T-07 | S-11 | `permissions: contents: read` ; job front (lint, types, tests, `npm audit --omit=dev`) ; Dependabot (maven, npm, docker, actions) | B + F | S | CI verte sur une branche d'essai |

### Lot 2 — Moindre privilège (≈ 1 jour)

| Tâche | Constat | Travail | Prop. | Effort | Critère d'acceptation |
|---|---|---|---|---|---|
| T-08 | S-03 | Rôles `praxedo_owner` (Flyway, `spring.flyway.user`), `praxedo_app` (DML seul), `keycloak` ; script d'init et `GRANT` en migration | B | M | Test Testcontainers : `praxedo_app` ne peut ni retirer une contrainte ni un trigger, ni ouvrir la base `keycloak` |
| T-09 | S-08 | `management.server.port` distinct ; Prometheus, `HEALTHCHECK` et scripts suivent | B | S | `GET :8080/actuator/prometheus` → `404` ; tableau Grafana toujours alimenté |
| T-10 | S-07 | Piste README : TLS mutuel ou jeton entre le service et le moteur | A | XS | Ligne au README §10, avec son déclencheur |

### Lot 3 — Résistance aux abus (≈ 1,5 jour) — *limitation acceptée*

> **Arbitrage du porteur du projet (29/09)** : S-04, S-05 et S-06 sont des
> limitations acceptées. Le service est supposé déployé derrière une
> passerelle d'API qui porte la limite de débit et les délais de lecture
> (README §9). Une limite de débit sur plusieurs nœuds exigerait des compteurs
> partagés, donc Redis ou la passerelle. Les trois limites figurent au README
> §10 avec leur déclencheur. Les tâches ci-dessous restent la marche à suivre
> si le service devait être exposé sans passerelle.
>
> **Révision du 30/09** (relecture du back-end, R-007) : S-06 est finalement
> corrigé dans le service. Une échéance de transfert ne demande ni compteur
> partagé ni dépendance, et le service ne doit pas compter sur la passerelle
> pour libérer ses propres places de dépôt.
>
> Nuance consignée au README : le quota par propriétaire (T-12) n'a pas besoin
> de Redis. Le compte vit dans PostgreSQL, qui est déjà partagé entre les
> nœuds. Et la passerelle ralentit un client sans l'empêcher de remplir la file.

La limitation de débit demande une dépendance (Bucket4j, déjà nommée en
[`06`](06-securite-et-angles-morts.md) §9) : justification écrite exigée
(interdit n°8).

| Tâche | Constat | Travail | Prop. | Effort | Critère d'acceptation |
|---|---|---|---|---|---|
| T-11 | S-04 | Limite par IP sur `/api/v1/auth/login` ; option : requête d'autorisation en cookie chiffré, session créée au rappel seulement | B | M | Au-delà du seuil depuis une même IP → `429` ; lignes `SPRING_SESSION` bornées |
| T-12 | S-05 | Bornes de fichiers en attente et d'octets par propriétaire ; limite de débit par principal ; nouveau code d'erreur au contrat, puis libellé côté front | B, puis F | M | Test à deux propriétaires : le premier est saturé, le second admis |
| T-13 | S-06 | ~~Débit minimal sur le corps d'un dépôt, après une période de grâce ; place rendue à la coupure~~ **Fait le 30/09** : échéance totale proportionnelle à la taille annoncée | B | S | Un client au goutte-à-goutte est coupé et sa place rendue (`UploadFileServiceTest`) |

### Lot 4 — Chaîne d'approvisionnement (≈ ½ jour)

| Tâche | Constat | Travail | Prop. | Effort | Critère d'acceptation |
|---|---|---|---|---|---|
| T-14 | S-11 | Analyse des dépendances (OSV-Scanner ou Trivy sur `pom.xml` et `package-lock.json`, Trivy sur l'image) ; CodeQL (Java, TypeScript) ; gitleaks ; actions épinglées par empreinte ; images `postgres`, `keycloak`, `aws-cli` épinglées par digest | B | M | Rapport de chaque outil en CI ; échec sur vulnérabilité haute non acceptée |

### Lot 5 — L'interface en production (≈ ½ jour et un ADR)

| Tâche | Constat | Travail | Prop. | Effort | Critère d'acceptation |
|---|---|---|---|---|---|
| T-15 | S-13 | Polices auto-hébergées ; ADR : qui sert l'interface (le service ou un serveur statique) et avec quels en-têtes (CSP, `Referrer-Policy`, `Permissions-Policy`, HSTS) | F + A | S + ADR | Aucune requête tierce au chargement ; en-têtes vérifiés par un test |

### Lot 6 — Pistes à documenter, pas à implémenter dans l'exercice

À porter au README §10 avec leur déclencheur, selon la convention
d'[`AGENTS.md`](../AGENTS.md) §6 : S-10 (existe) ; S-16 (compléter : jetons,
données personnelles, désérialisation Java) ; S-17 (existe, ajouter
l'effacement à la demande) ; S-20 (nouvelle ligne) ; S-07 en production (T-10).
S-04 et S-05 y sont déjà, comme limitations acceptées (lot 3) ; S-06 est
corrigé depuis le 30/09.

### Ordre et dépendances

```
T-01 ──┬──> T-08   le script d'init des rôles modifie compose
       └──> T-09   Prometheus et le HEALTHCHECK changent de port
T-02 + T-03        ensemble : même profil « local », mêmes scripts
T-12               contrat d'abord (B), libellé ensuite (F)
T-07 ──> T-14      même workflow
```

---

## 6. Recommandation pour l'exercice

**Faire les lots 1 et 2 avant la livraison (≈ 2 jours).** Ils coûtent peu, et
chacun rend vraie une affirmation que le dépôt fait déjà :

- « aucun secret en dur » (interdit n°5) ne l'est qu'après T-03 ;
- « l'invariant est structurellement impossible à violer » suppose que le
  service ne puisse pas retirer ses propres contraintes : c'est T-08 ;
- la séparation des droits, appliquée au stockage par trois identités, s'étend
  alors à la base. L'argument devient cohérent de bout en bout.

**Le lot 3 est une limitation acceptée**, déléguée à la passerelle d'API
(README §9 et §10). L'argument tient pour la limite de débit et les délais
de lecture. Pour le quota par propriétaire, la bonne réponse est plutôt : « faisable en base sans composant de plus, hors périmètre de
l'exercice ».

Le lot 4 est peu coûteux et visible. Le lot 5 demande une décision
d'architecture. Le lot 6 se documente.

---

## 7. Refaire les contrôles

```bash
# Dépendances du front
cd frontend && npm audit --omit=dev

# Aucun bouchon dans le build de production (après T-06 : aucune sortie)
cd frontend && npx vite build && grep -l "mock-idp" dist/assets/*.js

# Aucun port publié sur toutes les interfaces (après T-01 : aucune sortie)
docker compose ps --format '{{.Ports}}' | grep 0.0.0.0

# Aucun fichier sensible jamais commité
git log --all --diff-filter=A --name-only --pretty=format: | grep -Ei "\.env|\.pem$|\.key$"
```

---

## 8. Revue du code du 01/10 — constats complémentaires

Une revue complète du code, faite après la clôture des lots 1 et 2, a relevé
ce que l'audit n'avait pas vu. Tout est corrigé, chaque correction avec son
test.

| # | Gravité | Constat | Correction |
|---|---|---|---|
| V-01 | Moyenne | Aucun délai de lecture vers PostgreSQL : le pilote attend sans fin par défaut, aucun `statement_timeout` n'était posé ; une base figée bloquait le fil appelant pour toujours (règle B-7) | Connexion 5 s, requête 15 s annulée par le serveur, socket 30 s, sur les deux pools (`DatabaseTimeouts`) ; valeur relue sur le serveur par un test |
| V-02 | Moyenne | Le balayage de la quarantaine repartait de la première clé à chaque passe : au-delà de 500 fichiers bloqués gardés comme preuve, les orphelins rangés après eux n'étaient plus jamais examinés, et le stockage grossissait sans borne | Reprise après la dernière clé du passage précédent (`startAfter`) |
| V-03 | Moyenne | Une déconnexion refusée par le service (jeton CSRF, erreur, réseau) était annoncée réussie dans l'interface, alors que la session restait ouverte : sur un poste partagé, un rechargement reconnectait l'utilisateur | L'utilisateur reste connecté et en est averti ; seul un `200` (ou un `401` : plus de session) déconnecte |
| V-04 | Faible | Le code `error` du retour de connexion, choisi par l'appelant, était journalisé tel quel : un saut de ligne y forgeait de fausses entrées | Seul un code court est journalisé, sinon `unrecognised` |
| V-05 | Faible | `HEAD` sur le contenu exécutait tout le téléchargement : objet lu en entier, audité comme un téléchargement | `405`, avant l'ouverture de l'objet |
| V-06 | Faible | Orphelins et clés d'idempotence purgés après 1 h, moins que le dépôt le plus lent accepté (≈ 68 min) : un dépôt lent pouvait échouer à la fin et un réessai créer un doublon | Deux heures, et un démarrage refusé si ces délais ne dépassent pas le dépôt le plus lent |
| V-07 | Faible | Toute erreur de persistance à l'insertion devenait « clé déjà prise » (`500` trompeur) ; une base injoignable à l'ouverture d'une transaction donnait `500` au lieu de `503` | Seule une violation d'unicité est un doublon ; `CannotCreateTransactionException` → `503` |
| V-08 | Faible | Build de l'interface : la garde dépendait du nom du mode et d'un dossier écrit en dur ; formulaire, comptes et libellés de démonstration restaient dans le build | Tout le mode bouchon dépend de `import.meta.env.DEV` ; garde appliquée à tout build (S-12) |
| V-09 | Faible | Retour après connexion côté interface plus permissif que la règle du serveur (`/%09/…`) | Même règle que `ReturnTo` : ni contrôle, ni espace, ni `\`, 2 048 caractères au plus |
| V-10 | Faible | L'identité `worker` pouvait lire et lister la zone servable sans en avoir besoin | Écriture seule sur la zone servable |

