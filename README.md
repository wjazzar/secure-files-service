# Praxedo — service de fichiers sécurisés

Un micro-service qui **reçoit des fichiers, les fait analyser par un antivirus,
et ne sert que ceux qui ont été déclarés sains** — avec une interface React pour
déposer, suivre et télécharger.

> **La garantie** : aucun octet n'est servi sans qu'un antivirus ait analysé
> **ces octets-là**. Elle ne repose pas sur un `if`, mais sur trois mécanismes
> indépendants, dont chacun suffirait à bloquer un fichier non analysé (§4.1).

Test technique — Java 21, Spring Boot 4.1, React 19. Réalisé avec l'aide de
Claude et de ChatGPT : les prompts, les propositions rejetées et les décisions
humaines sont dans [`docs/prompts/`](docs/prompts/) (§11).

| | |
|---|---|
| [1. Le projet](#1-le-projet) | Ce que fait le service, ses composants, ses chiffres |
| [2. Démarrer](#2-démarrer) | Une commande, puis le pas-à-pas |
| [3. Les processus](#3-les-processus--upload-queue-download) | Upload, Queue, Download — chacun relié à sa description générale et technique |
| [4. Les aspects transverses](#4-les-aspects-transverses) | Sécurité, idempotence, file, haute disponibilité, métriques, mémoire, architecture |
| [5. L'infrastructure](#5-linfrastructure) | Les conteneurs, les identités de stockage |
| [6. Capacity planning](#6-capacity-planning--le-goulot-est-lio-physique) | Ce que tient un nœud, et pourquoi le goulot est l'I/O physique |
| [7. Valider les scénarios](#7-valider-les-scénarios--les-scripts) | Les scripts et les tests qui prouvent chaque affirmation |
| [8. Choix et raisons](#8-choix-et-raisons) · [9. Hypothèses](#9-hypothèses) · [10. Limites](#10-limites-connues-et-pistes-damélioration) | Chaque technologie et pourquoi elle, ADR, hypothèses motivées, pistes avec leur déclencheur |

---

## 1. Le projet

L'énoncé demande un service qui :

1. **reçoit et conserve** des fichiers, venus d'utilisateurs ou de systèmes
   tiers, de tailles très variables (jusqu'à **500 Mo**) ;
2. **garantit qu'aucun fichier n'est servi sans avoir été analysé** par un
   antivirus joignable par une API ;
3. **permet de télécharger** les fichiers validés, pour de nombreux
   utilisateurs simultanés.

L'analyse est **asynchrone** : le dépôt répond `202` tout de suite, le fichier
patiente dans une file, un worker le fait analyser puis le **promeut** vers une
zone servable. Le client suit l'état, puis télécharge.

```
                    ┌─────────────────────────── un seul processus Spring Boot ───────────────────────────┐
  POST /files ────▶ │ UPLOAD   dépôt en flux ─▶ quarantaine (S3, identité « ingest » : écriture seule)    │
  (500 Mo max)      │          SHA-256 + taille + type détecté, en un passage                              │
                    │               ▼                                                                      │
                    │ QUEUE    ligne AWAITING_SCAN ─ la base fait file (FOR UPDATE SKIP LOCKED) ─┐         │
                    │          worker : portillon de santé ─▶ claim (bail + jeton) ─▶ ClamAV (API HTTP)    │
                    │          verdict lié au SHA-256 des octets réellement envoyés                        │
                    │          promotion : relecture, re-hachage ─▶ zone servable ─▶ CAS ─▶ AVAILABLE      │
                    │               ▼                                                                      │
  GET  …/content ◀──│ DOWNLOAD identité de l'appelant ─▶ état relu ─▶ zone servable (« delivery » : lecture)│
                    └──────────────────────────────────────────────────────────────────────────────────────┘
                     PostgreSQL 17 · SeaweedFS 4.47 (S3) · ClamAV 1.4.6 · Keycloak 26.4 · React 19
```

**Composants** : l'application, PostgreSQL (métadonnées **et** file de
travail), un stockage objet compatible S3, l'antivirus, l'interface — et
Keycloak pour l'authentification. **Aucun broker** : le cadrage du 21/09 l'a
confirmé, Spring Boot, React et une base de données suffisent.

| En chiffres | |
|---|---|
| Taille maximale | 500 Mo, en flux de bout en bout : **500 Mo déposés, promus et téléchargés à travers un tas de 256 Mo** |
| Débit d'un nœud (2 CPU, 1 Gio, 8 workers, ClamAV réel) | **130 à 160 fichiers/s** (~73 à 90 Mo/s), lag p95 ≤ 1 s — l'hypothèse de 5 000 fichiers/jour en demande 0,058 |
| Goulot mesuré | **L'I/O physique** : la cadence des `fsync` du journal de PostgreSQL, sur un disque partagé avec le stockage objet (§6) |
| Violations de l'invariant | **0**, relu sur toute la durée de chaque campagne de charge |
| Tests | `./mvnw verify` : tests unitaires et d'intégration contre le **vrai** PostgreSQL, le vrai SeaweedFS et le **vrai ClamAV** (EICAR, limites) ; Vitest côté interface |

| Opération | Point d'entrée |
|---|---|
| Déposer (corps binaire brut, idempotent avec `Idempotency-Key`) | `POST /api/v1/files` |
| Suivre (conçu pour l'interrogation : `ETag`, `304`, `Retry-After`) | `GET /api/v1/files/{id}` |
| Lister : pagination, tri, filtre par statut, recherche par nom | `GET /api/v1/files` |
| Compter par statut | `GET /api/v1/files/summary` |
| Télécharger — navigateur (cookie de session) et système tiers (`Bearer`) | `GET /api/v1/files/{id}/content` |

Le contrat [`contracts/openapi.yaml`](contracts/openapi.yaml) est la source de
vérité entre le back et le front ; un test de chaque côté vérifie la
conformité. Ses choix sont expliqués dans [`contracts/README.md`](contracts/README.md).

---

## 2. Démarrer

**Prérequis** : Docker. Node 22.12+ pour l'interface, JDK 21 pour lancer les tests.

```bash
./scripts/start.sh    # base, stockage objet, Keycloak, antivirus, service, puis l'interface branchée dessus
./scripts/check.sh    # chaque brique répond, et fait ce qu'elle promet (isolation, EICAR, jeton…)
./scripts/demo.sh     # le parcours de démonstration de l'API, en moins d'une minute
```

Sous Windows sans Git Bash : `powershell -ExecutionPolicy Bypass -File scripts\start.ps1`
(de même `check.ps1`, `stop.ps1`). **Le pas-à-pas complet, brique par brique,
avec le dépannage : [`README.txt`](README.txt).**

L'interface répond sur <http://127.0.0.1:5173>, l'API sur <http://localhost:8080>,
sa santé et ses métriques sur <http://localhost:8091/actuator/health> (port de
management, jamais celui de l'API), Grafana sur <http://localhost:3000>. Tous
les ports n'écoutent que sur la machine locale. « Se connecter » mène à Keycloak : les
comptes de démonstration sont ceux du realm
([`infra/keycloak/realm-praxedo.json`](infra/keycloak/realm-praxedo.json)), mot
de passe `demo` ; chacun ne voit que ses fichiers. Le
premier démarrage de l'antivirus prend une à deux minutes (chargement des
signatures) ; le service, lui, n'attend pas : les dépôts sont acceptés et
patientent dans la file.

| Pour… | Commande |
|---|---|
| Tout arrêter | `./scripts/stop.sh` — `--purge` efface aussi les données |
| L'API seule, sans l'interface | `./scripts/start.sh --no-front`, ou `docker compose --profile app up -d --build` |
| L'interface seule, sur des bouchons | `cd frontend && npm run dev:mock` |
| L'infrastructure seule, le service dans l'IDE | `docker compose up -d`, puis `cd backend && ./mvnw spring-boot:run` (profil `local` activé d'office) ; depuis l'IDE, activer le profil `local` |
| Après une mise à jour qui touche aux rôles PostgreSQL (01/10) | Une fois : `docker compose down -v` — les rôles ne se créent que sur un volume vide |

---

## 3. Les processus : Upload, Queue, Download

La vie d'un fichier tient en trois processus. Chacun a **deux documents** : le
parcours **général** (quelles classes, dans quel ordre, pourquoi) et le
scénario **technique** (le code suivi ligne à ligne, avec les points d'arrêt à
poser).

| Processus | Déclencheur | Description générale | Description technique | ADR |
|---|---|---|---|---|
| **Upload** — déposer | `POST /api/v1/files` | [`backend/docs/01`](backend/docs/01-deposer-un-fichier.md) | [`backend/scenarios/1`](backend/scenarios/1-deposer.md) | [0002](docs/adr/0002-un-processus-trois-identites.md) |
| **Queue** — analyser et promouvoir | worker (threads virtuels) | [`backend/docs/03`](backend/docs/03-analyser-et-promouvoir.md) | [`backend/scenarios/3`](backend/scenarios/3-analyser-et-promouvoir.md) | [0001](docs/adr/0001-la-base-de-donnees-fait-file.md), [0004](docs/adr/0004-antivirus-par-api-http.md), [0006](docs/adr/0006-promotion-par-relecture-verifiee.md) |
| **Download** — télécharger | `GET /api/v1/files/{id}/content` | [`backend/docs/04`](backend/docs/04-telecharger-un-fichier.md) | [`backend/scenarios/4`](backend/scenarios/4-telecharger.md) | [0013](docs/adr/0013-telechargement-par-l-identite-de-l-appelant.md) |
| Consulter | `GET /api/v1/files`, `/summary`, `/{id}` | [`backend/docs/02`](backend/docs/02-consulter-les-fichiers.md) | [`backend/scenarios/2`](backend/scenarios/2-consulter.md) | [0003](docs/adr/0003-jpa-pour-lire-sql-pour-garantir.md) |
| Reprendre et nettoyer | planificateur | [`backend/docs/05`](backend/docs/05-maintenance-et-reprise.md) | [`backend/scenarios/5`](backend/scenarios/5-entretenir.md) | [0009](docs/adr/0009-journal-d-audit-par-trigger.md) |
| Superviser | Prometheus, `/actuator/health` | [`backend/docs/06`](backend/docs/06-superviser-le-service.md) | [`backend/scenarios/6`](backend/scenarios/6-surveiller.md) | — |

Point d'entrée de lecture : la [vue d'ensemble](backend/docs/00-vue-d-ensemble.md)
(couches, automate d'états, capacités de stockage), puis
[`backend/ARCHITECTURE.md`](backend/ARCHITECTURE.md) pour chaque protocole en
détail.

### 3.1 Upload

1. **Avant de lire le corps** : nom, taille annoncée (`411` sans elle, `413`
   au-delà de 500 Mo), puis **admission** — 51ᵉ dépôt simultané sur le nœud,
   ou 500 fichiers déjà en attente → `429` + `Retry-After`, sans avoir lu
   un octet.
2. **Réservation d'idempotence** (`Idempotency-Key`) : `INSERT … ON CONFLICT`,
   jamais `SELECT` puis `INSERT`.
3. **Écriture en quarantaine, en flux**, par l'identité `ingest` (écriture
   seule) : SHA-256, taille réelle et type détecté calculés **en un passage**.
   Un `Content-Length` mensonger → `400`, l'objet est supprimé.
4. **Une transaction** : la ligne `AWAITING_SCAN` **est** le travail — pas
   d'outbox, rien à synchroniser.
5. `202 Accepted` + `Location`.

**L'ordre est la garantie** : l'objet est écrit avant la ligne. Une panne
entre les deux laisse un orphelin invisible, balayé plus tard ; l'ordre inverse
laisserait une référence visible vers un objet absent.

### 3.2 Queue — analyse et promotion

1. **Portillon de santé** : si l'antivirus ne répond pas, le worker ne prend
   **pas** de travail — aucune tentative n'est consommée pendant la panne.
2. **Claim atomique** : une seule requête sélectionne, verrouille
   (`FOR UPDATE SKIP LOCKED`), prend un **bail** avec un jeton unique et compte
   la tentative.
3. **Analyse en flux** : les octets lus en quarantaine partent vers l'API de
   ClamAV ; le worker hache ce qu'il envoie et lie le verdict à **cette**
   empreinte. Une panne n'est **jamais** un verdict : silence, lenteur ou
   réponse illisible produisent une exception, jamais un « sain ».
4. **Verdict** écrit à condition que le **jeton du bail** soit toujours le
   sien : un worker zombie n'écrit rien.
5. **Promotion vérifiée** : relecture de la quarantaine, re-hachage, écriture
   dans la zone servable par l'identité `worker`, puis bascule
   `PROMOTING → AVAILABLE` par `CAS` sur le jeton. La source est supprimée
   ensuite.
6. **Échecs** : `RETRY_WAIT` avec backoff **persisté** (10 s → 15 min),
   `FAILED_FINAL` après 5 essais ; un bail expiré (nœud mort) est repris par
   le *reaper* toutes les 30 s.

### 3.3 Download

1. **Identité de l'appelant, et elle seule** : le cookie de session du
   navigateur, qui part tout seul avec la navigation native, ou le jeton
   `Bearer` d'un système tiers. **Pas de lien signé** : avec la session par
   cookie, un lien HMAC doublait l'authentification — retiré le 29/09
   ([ADR-0013](docs/adr/0013-telechargement-par-l-identite-de-l-appelant.md)).
2. **État relu au moment de servir**, à chaque requête : le fichier doit être
   à l'appelant (`404` sinon, comme un fichier inconnu) et `AVAILABLE`
   (`409` + code métier sinon).
3. Contenu ouvert par l'identité **`delivery`**, qui n'a **aucun droit** sur la
   quarantaine.
4. **Audit avant le premier octet** : un téléchargement qui ne peut pas être
   tracé n'est pas servi.
5. Réponse écrite à la main, en flux : `attachment`,
   `application/octet-stream`, `nosniff`, `ETag` = SHA-256, reprise par
   `Range`.

L'interface relit le détail au clic, puis confie `links.content` au
gestionnaire de téléchargement du navigateur : rien ne passe par la mémoire
de l'onglet, et un refus s'**explique** au lieu d'échouer en silence.

---

## 4. Les aspects transverses

| Aspect | L'essentiel | Général | Technique |
|---|---|---|---|
| Sécurité | Trois couches indépendantes, isolation par le stockage | [`docs/06`](docs/06-securite-et-angles-morts.md) | [`backend/ARCHITECTURE §13`](backend/ARCHITECTURE.md#13-sécurité) |
| Idempotence | Cinq problèmes distincts, cinq mécanismes | [`docs/04`](docs/04-idempotence.md) | [`backend/ARCHITECTURE §9`](backend/ARCHITECTURE.md#9-idempotence--les-cinq-problèmes-un-par-un) |
| Queue | La base fait file, sans broker | [ADR-0001](docs/adr/0001-la-base-de-donnees-fait-file.md) | [`backend/ARCHITECTURE §7`](backend/ARCHITECTURE.md#7-les-quatre-protocoles-critiques) |
| Haute disponibilité, résilience | Nœuds sans état, chaque dépendance peut tomber | [`docs/05`](docs/05-ha-et-resilience.md) | [`backend/ARCHITECTURE §11`](backend/ARCHITECTURE.md#11-résilience--sans-bibliothèque) |
| Métriques, observabilité | Retard, débit, lag — lus comme un consommateur de broker | [`backend/docs/06`](backend/docs/06-superviser-le-service.md) | [`backend/ARCHITECTURE §12`](backend/ARCHITECTURE.md#12-observabilité) |
| Mémoire, tailles variables | Tout est flux ; mémoire constante mesurée | [ADR-0010](docs/adr/0010-mvc-et-threads-virtuels.md) | [`backend/docs/01`](backend/docs/01-deposer-un-fichier.md), [`04`](backend/docs/04-telecharger-un-fichier.md) |
| Architecture du code | Hexagone vérifié à chaque build | [ADR-0011](docs/adr/0011-architecture-hexagonale-verifiee.md) | [`backend/ARCHITECTURE §3`](backend/ARCHITECTURE.md#3-structure-du-code) |

> Les documents « généraux » de `docs/` sont l'**analyse** faite avant le code,
> relue contre le code le 02/10 : chacun dit ce qui est livré et ce qui a été
> écarté (par exemple : pas de broker, donc pas d'outbox). En cas d'écart,
> c'est [`backend/ARCHITECTURE.md`](backend/ARCHITECTURE.md) qui fait foi.

### 4.1 Sécurité

Trois mécanismes, chacun capable à lui seul de bloquer un fichier non analysé :

| Couche | Mécanisme | Ce qu'il empêche |
|---|---|---|
| **Domaine** | L'automate à 8 états n'autorise `AVAILABLE` qu'avec un verdict `CLEAN` **lié à l'empreinte** du fichier. Les transitions rendent une nouvelle instance : rien ne s'écrit par effet de bord | Un chemin de code qui marquerait disponible un fichier non jugé |
| **Base** | Des contraintes `CHECK` **totales** (écrites avec `IS NOT DISTINCT FROM` : un `CHECK` laisse passer `NULL`) refusent toute ligne `AVAILABLE` sans attestation propre de son propre SHA-256. Le service se connecte avec un rôle qui **lit et écrit des lignes, rien d'autre** : il ne peut ni retirer ces contraintes ni couper le trigger d'audit | Une écriture fautive, quel que soit le code qui l'émet — et même une faille du service |
| **Stockage** | L'identité du téléchargement n'a **aucun** droit sur la quarantaine ; celle du dépôt n'a que l'écriture | Même avec un statut corrompu, le chemin de téléchargement ne *peut pas* lire un fichier non validé |

S'y ajoutent : la **revérification de l'état au moment de servir**, la
**promotion par relecture** (ce qui est servi est prouvé identique à ce qui a
été analysé), et un **journal d'audit** écrit par la base elle-même, en ajout
seul. Un fichier dont le moteur signale qu'il n'a pas pu l'analyser en entier
est classé **non analysable**, jamais servi — deux cas échappent à ce
signalement, mesurés et listés au §10 : les entrées zip64 et les archives
chiffrées.

| Risque | Parade |
|---|---|
| Analyse partielle déclarée saine | `AlertExceedsMax` forcé, ordre des limites imposé au démarrage de l'image, test contre le vrai moteur |
| Contenu interprété par le navigateur | `attachment` + `application/octet-stream` + `nosniff`, toujours ; le type déclaré par le client est ignoré |
| Traversée de chemin par le nom | La clé de stockage est un UUID ; le nom, nettoyé, n'est qu'une métadonnée |
| Énumération des fichiers d'autrui | `404`, comme un fichier inconnu ; jamais `403` |
| Vol de session par un script injecté | Aucun jeton dans le navigateur : cookie `HttpOnly`, `Secure`, `SameSite=Lax`, préfixe `__Host-` ; le service est le **client confidentiel** de Keycloak ([ADR-0012](docs/adr/0012-session-navigateur-client-confidentiel.md)) |
| Écriture forgée par un autre site | Jeton CSRF exigé sur toute écriture portée par le cookie → sinon `403 CSRF_TOKEN_INVALID` |
| Connexion détournée | PKCE `S256` + secret client, `state` et `nonce` vérifiés, nouvel identifiant de session à la connexion, redirection limitée aux chemins locaux |
| Justificatif qui fuit dans une URL | Aucun : ni lien signé ni jeton dans une adresse ([ADR-0013](docs/adr/0013-telechargement-par-l-identite-de-l-appelant.md)) |
| Injection par le tri ou le filtre | Listes blanches ; aucune concaténation |
| Secrets | Aucun dans le code, **pas même en valeur par défaut** : un identifiant manquant fait échouer le démarrage en nommant sa variable. Les valeurs de développement vivent dans le profil `local`, activé exprès (un test le vérifie) |
| Base de données | **Un rôle par usage** : `praxedo_owner` migre, `praxedo_app` exécute le service (lignes seulement), `keycloak` possède sa seule base ; aucun superutilisateur hors administration (un test prouve ce que `praxedo_app` ne peut pas faire) |
| Exposition | Ports publiés sur `127.0.0.1` seulement ; l'actuator sur son propre port (8091), jamais sur celui de l'API |
| URL renvoyées par le serveur | Vérifiées avant d'être suivies par le navigateur : jamais `javascript:` ni un autre site |
| Copie de la base des sessions | **Risque accepté, borné** : l'identifiant de session y est en clair (Spring Session) ; le secret client, sans lequel le jeton de rafraîchissement ne sert à rien, n'y est pas |

### 4.2 Idempotence

| # | Problème | Mécanisme |
|---|---|---|
| 1 | Le client relance un dépôt après un timeout | `Idempotency-Key` : même clé, même requête (nom et taille) → le fichier du premier dépôt, rendu dans son état courant, pendant 24 h ; même clé, autre nom ou autre taille → `422` ; en cours → `409` + `Retry-After`. Tranché par `INSERT … ON CONFLICT`. Limite assumée : un autre contenu de même nom et de même taille n'est pas distingué, faute de lire le corps avant de répondre |
| 2 | Le même contenu déposé deux fois | **Rien en v1** : deux fichiers, deux analyses. Réutiliser un verdict demande la version des signatures et l'âge du verdict (piste, §10) |
| 3 | Deux workers veulent le même travail | Claim `FOR UPDATE SKIP LOCKED` : chaque ligne prise une seule fois |
| 4 | Un worker zombie rend son verdict après l'expiration de son bail | Écriture conditionnée au **jeton** de la prise, pas à l'identifiant du worker |
| 5 | Une promotion interrompue est rejouée | Écriture sous la clé finale, `CAS` sur le jeton, suppression de la source tolérante à l'absence |

### 4.3 Queue — la base fait file

La ligne `stored_file` **est** le travail : l'état et la file ne peuvent pas
diverger, et il n'y a ni outbox ni second système à exploiter. Tout ce qu'un
broker apporterait est déjà là :

| Ce qu'on attend d'une file | Ici |
|---|---|
| Distribution sans doublon entre consommateurs | `FOR UPDATE SKIP LOCKED` |
| Accusé de réception, redistribution si le consommateur meurt | Bail avec échéance (horloge de la base), *reaper* toutes les 30 s |
| Réessais avec backoff, file des messages morts | `RETRY_WAIT` + `next_attempt_at` persistés ; `FAILED_FINAL` après 5 essais |
| Contre-pression | `429` au-delà de 500 fichiers en attente |
| Retard du consommateur | `praxedo_queue_depth`, `…_depth_bytes`, `…_oldest_pending_age_seconds` |

Index **partiels** sur les seuls états actifs : l'index de la file reste
minuscule quel que soit l'historique. Le prix est mesuré : le plafond de la
file est la cadence de validation de la base (§6).

### 4.4 Haute disponibilité et résilience

**Un nœud ne garde rien** (règle B-10) : fichiers dans le stockage objet,
travail et baux dans PostgreSQL (sur **son** horloge), sessions des navigateurs
dans PostgreSQL (Spring Session). N'importe quel nœud répond à n'importe quelle
requête ; un nœud qui meurt voit ses travaux repris à l'échéance de leurs baux.
Plusieurs nœuds ont tourné ensemble pendant les essais de charge (trois nœuds
sur une base, un stockage, un antivirus).

| Dépendance en panne | Ce qui continue |
|---|---|
| Antivirus | **Dépôts et téléchargements** des fichiers déjà disponibles ; la file attend, aucune tentative n'est consommée, tout repart seul |
| Keycloak | Les utilisateurs connectés continuent (30 min au plus sans revalidation) |
| Stockage objet | Les métadonnées restent consultables ; `503` + `Retry-After` pour les contenus |
| Base | Rien — c'est la source de vérité, et c'est assumé ; un verdict déjà acquis est réécrit trois fois avant de s'en remettre au bail |
| Un nœud | Les autres reprennent ses travaux |

**Sans bibliothèque de résilience** : les timeouts sont explicites (connexion
**et** lecture) ; les réessais vivent dans la file, persistés ; un nombre fixe
de boucles borne les analyses (chacune en mène une à la fois : les threads
virtuels n'y changent rien) et un sémaphore borne les dépôts (un thread par
requête : là, la taille d'un pool ne serait plus une limite) ; deux pools de
connexions, pour que l'API ne puisse pas affamer les workers. **Arrêt propre** : les baux sont rendus, la tentative aussi.

### 4.5 Métriques et observabilité

Prometheus (`/actuator/prometheus`), lu comme un consommateur sur un broker :

- **retard** : fichiers en attente, **octets** en attente, âge du plus vieux
  (la métrique d'alerte) ; par fichier, le **lag** du dépôt à l'état final
  (`praxedo_pipeline_lag_seconds`, histogramme : p50/p95/p99) ;
- **débit** : fichiers et **octets** traités par seconde, octets lus par
  l'antivirus, reçus et servis ;
- **santé** : violations de l'invariant (doit rester à zéro, `NaN` si la base
  ne répond pas — jamais un zéro rassurant), durées et verdicts de
  l'antivirus, refus d'admission, verdicts rejetés pour bail perdu, requêtes
  HTTP, JVM, pools.

**Grafana** provisionné (<http://localhost:3000>) : « tient-il la charge ? » en
tête, puis les ressources du nœud et l'antivirus. **Santé** :
`/actuator/health/readiness` pour l'orchestrateur, sur le **port de management
(8091)** — le port de l'API ne sert ni métriques ni sondes ; l'antivirus n'y entre pas
(un nœud sans antivirus sert encore les fichiers sains). **Audit** :
`file_audit_event`, chaque transition par trigger, chaque téléchargement avec
son acteur et sa plage. **Corrélation** : `X-Request-Id` sur chaque réponse,
`fileId` dans les journaux du worker, JSON (ECS) avec le profil `json-logs`.

### 4.6 Mémoire et tailles variables

Tout est flux : ni `byte[]`, ni `MultipartFile`, ni fichier temporaire. Le
dépôt est un corps binaire brut relayé vers le stockage ; l'analyse relit le
stockage vers l'antivirus ; le téléchargement copie le stockage vers la
réponse. **Mesuré à chaque build** : 500 Mo déposés, promus et téléchargés à
travers un tas de 256 Mo (`UploadMemoryTest`, `DownloadMemoryTest`). Spring MVC
et threads virtuels plutôt que WebFlux : un service bloquant par nature,
écrit simplement, borné explicitement : un sémaphore pour les dépôts, un
nombre fixe de boucles pour les analyses.

### 4.7 Architecture du code

**Hexagonale, vérifiée à chaque build.** Les adaptateurs *pilotants* (web,
planification, métriques) n'entrent dans le cœur que par six **ports
d'entrée** ; les services ne connaissent que des **ports de sortie**,
implémentés par les adaptateurs *pilotés* (base, stockage, antivirus). Aucun
adaptateur ne dépend d'un autre. Le domaine n'importe **aucun** framework, JPA
compris (ADR-0015) ; le câblage des ports tient dans un seul paquet, `config`. Code rangé **par concept, puis par nature** —
`web/file/controller`, `application/file/port/in`… ArchUnit fait échouer la
construction à la moindre dérive. Détail :
[`backend/ARCHITECTURE.md` §3](backend/ARCHITECTURE.md#3-structure-du-code).

---

## 5. L'infrastructure

Tout est décrit dans [`docker-compose.yml`](docker-compose.yml) ; chaque brique
est justifiée et vérifiable à la main dans [`infra/README.md`](infra/README.md).

| Conteneur | Image | Port | Rôle |
|---|---|---|---|
| `postgres` | `postgres:17.11-alpine` | 5432 | Métadonnées, file de travail, sessions, audit — et la base de Keycloak. Un rôle par usage : `praxedo_owner` (migrations), `praxedo_app` (le service), `keycloak` |
| `objectstore` | SeaweedFS 4.47, épinglée par digest | 8333 (S3) | Stockage objet compatible S3 : zones `quarantine` et `servable` |
| `antivirus` | dérivée de `clamav-rest` (ClamAV 1.4.6), épinglée par digest | 9000 | Moteur d'analyse **derrière une API HTTP** ; l'image force `AlertExceedsMax` et refuse de démarrer si ses limites sont mal ordonnées |
| `keycloak` | Keycloak 26.4.7 | 8081 | OpenID Connect : realm `praxedo`, utilisateurs de démonstration, client confidentiel du service, système tiers `praxedo-integration` |
| `prometheus`, `grafana` | épinglées | 9090, 3000 | Collecte toutes les 5 s, tableau de bord de capacité provisionné |
| `backend` (profil `app`) | [`backend/Dockerfile`](backend/Dockerfile) | 8080 (API), 8091 (actuator) | Le service ; n'attend **pas** l'antivirus pour démarrer |

**L'isolation vient des identifiants du stockage**
([`infra/seaweedfs/s3-identities.json`](infra/seaweedfs/s3-identities.json)) —
c'est la dernière ligne de défense de la garantie :

| Identité | `quarantine` | `servable` | Utilisée par |
|---|---|---|---|
| `praxedo-ingest` | **écriture seule** | — | Le dépôt |
| `praxedo-worker` | lecture / écriture | **écriture seule** | L'analyse et la promotion |
| `praxedo-delivery` | **aucun accès** | **lecture seule** | Le téléchargement |

Choix écartés, avec leur raison dans [`infra/README.md`](infra/README.md#1-choix-du-stockage-objet) :
MinIO (dépôt communautaire archivé), LocalStack (n'applique pas les
politiques IAM dont dépend l'isolation), SeaweedFS 3.97 (une identité en
lecture seule n'y lisait rien). Les ports de stockage rendent le choix
réversible : passer sur AWS S3 est un changement de configuration.

> ⚠️ Tous les identifiants de cet environnement sont des identifiants de
> développement local, volontairement triviaux et versionnés pour que
> `docker compose up` suffise. Toutes les images sont épinglées par version et
> empreinte, et leurs vulnérabilités connues analysées :
> [`docs/33`](docs/33-analyse-des-dependances.md).

---

## 6. Capacity planning — le goulot est l'I/O physique

Bilan complet, chiffres et protocole :
[`docs/capacity-planning/README.md`](docs/capacity-planning/README.md).
Campagnes et preuves brutes : [`load/`](load/README.md).

**L'hypothèse du projet — ~50 dépôts simultanés, ~5 000 fichiers par jour —
vaut 0,058 fichier/s.** Un seul nœud tient plus de 2 000 fois ce volume : ce
sont les **rafales**, la **taille** et la **nature** des documents qui
dimensionnent, pas le volume quotidien.

| Nœud (ClamAV réel, mélange 128 Kio / 512 Kio / 2 Mio) | Tenu | Lag p95 | Dépôt p95 | Ce qui borne |
|---|---|---|---|---|
| 1 CPU / 1 Gio, 4 workers | 80 fichiers/s | 1,3 s | 36 ms | le processeur |
| 2 CPU / 1 Gio, 4 workers | 100 fichiers/s | 1,2 s | 28 ms | les workers |
| 2 CPU / 1 Gio, 8 workers | **160 fichiers/s** (~90 Mo/s) | **0,8 s** | **47 ms** | **le disque de la base** |
| 3 nœuds de 2 CPU | 176 fichiers/s | — | — | **le même disque** |

**Rafale** : 50 dépôts simultanés de 2 Mio sont acceptés 50/50, p95 de 1,2 à
1,4 s sur un nœud de 2 CPU.

### Pourquoi l'I/O physique

« La base fait file » : **le plafond de la file est la cadence à laquelle
PostgreSQL valide ses transactions**, et chaque validation attend un `fsync`
de son journal (WAL) sur le disque. Un fichier coûte quatre écritures (dépôt,
claim, verdict, passage à `AVAILABLE`), soit **~2 `fsync`** après regroupement.
Sur le banc, ce disque est **partagé avec le stockage objet**, qui y écrit 100 à
150 Mo/s, et avec les checkpoints de PostgreSQL. Mesure directe
(`pg_test_fsync`) :

| État du disque | `fsync`/s | Fichiers/s permis | Observé |
|---|---:|---:|---:|
| Au repos | 497 | ~250 | — |
| Pendant une campagne | 220–330 | 110–165 | **160–176** |
| Écriture concurrente continue | 60 | ~30 | 40–60, à l'effondrement |

- L'application n'attend pas ses verrous (0 attente de verrou) ni ne garde ses
  transactions : elle **attend le disque** — jusqu'à 49 sessions à la fois en
  attente d'écriture du journal, et toutes les instructions de plus d'une
  seconde sont des `COMMIT`.
- **Ajouter des nœuds n'ajoute pas de capacité de validation** : trois nœuds
  font à peine mieux qu'un (176 contre 160 fichiers/s), ils ajoutent surtout
  des connexions qui se disputent le journal.
- **Au-delà du plafond, le débit s'effondre au lieu de plafonner** : les
  validations ralentissent, les connexions sont gardées plus longtemps, les
  pools se remplissent. Le mécanisme est compris, la parade identifiée
  (délester quand la base ralentit, §10), pas encore réalisée.

**Ce qui déplace le plafond** : un disque dédié à la base (le `fsync` d'un SSD
de serveur est typiquement dix à quarante fois plus court que sur ce banc),
le regroupement des validations (`commit_delay`) et des checkpoints plus
espacés ; des validations asynchrones pour les étapes que la file sait
rejouer (claim, verdict) — **jamais** pour l'insertion du dépôt (la promesse
du `202`) ni pour le passage à `AVAILABLE`.

### Les autres consommateurs

À 100 fichiers/s : **ClamAV 2,5 cœurs**, SeaweedFS 1,7 cœur et **236 Mo/s** de
réseau, le service 1,4 cœur, PostgreSQL 0,4 cœur. L'application ne représente
qu'un quart du processeur consommé ; le trafic du stockage vaut ~4 fois le
volume déposé (dépôt, relecture pour l'analyse, relecture et écriture pour la
promotion).

### Règle de dimensionnement

| Composant | Règle |
|---|---|
| Nœuds applicatifs (2 CPU / 1 Gio, 8 workers) | `plafond(pointe en fichiers/s / 130)`, et `plafond(pointe en Mo/s / 73)` : le plus grand des deux |
| Base | ~2,5 `fsync` par fichier à la pointe, **sur un disque qui ne sert qu'à elle** |
| ClamAV | ~2,5 cœurs par 100 fichiers/s — sur octets aléatoires, **à remesurer sur un corpus réel** |
| Stockage objet | réseau ≥ 4 × le débit de dépôt ; capacité ≥ 2 × le volume en transit |
| Connexions PostgreSQL | 10 + workers + 2 par nœud : ~4 nœuds à 8 workers sous la limite par défaut de 100 |

**Deux réserves** conditionnent ces chiffres : le **corpus** (octets aléatoires,
peu coûteux pour ClamAV ; des archives ou des PDF déplaceraient son plafond) et
le **banc** (une seule machine Docker Desktop pour tous les composants :
au-dessus de ~160 fichiers/s, on mesure son disque, pas le service).

---

## 7. Valider les scénarios : les scripts

Chaque affirmation de ce document a sa commande. Les scripts `.sh` tournent
sous Linux, macOS et Git Bash ; `start`, `check` et `stop` existent aussi en
`.ps1`.

| Scénario | Commande | Ce que ça prouve |
|---|---|---|
| Tout démarre | `./scripts/start.sh` | Base, stockage, Keycloak, antivirus, service, interface — relançable sans risque |
| Chaque brique fait ce qu'elle promet | `./scripts/check.sh` | Schéma migré ; **la livraison ne peut pas lire la quarantaine** ; EICAR détecté par l'antivirus ; jeton délivré au système tiers ; service prêt ; interface branchée |
| **Upload → Queue → Download** | `./scripts/demo.sh` | Un fichier sain déposé, analysé, promu, **téléchargé à l'identique** (SHA-256 comparés) ; EICAR détecté, **jamais servi** (`409 FILE_INFECTED`) ; ce qu'en a vu Prometheus |
| Résilience | `./scripts/demo.sh --resilience` | Antivirus arrêté : le dépôt est accepté, **aucune tentative n'est consommée** ; au retour de l'antivirus, le fichier est analysé sans intervention |
| Tailles variables | `./scripts/demo.sh --big` | 500 Mo aller-retour, relus à l'identique |
| Capacité d'un nœud | `node load/run-capacity.mjs real-antivirus-confirmation` | Paliers k6 à débit contrôlé, rapport Prometheus « tient / ne tient pas », conteneurs, base — preuves archivées dans `load/results/` |
| Le goulot | `node load/run-capacity.mjs scale-2cpu-8w scale-3nodes-2cpu` | La série 4 : un puis trois nœuds, base instrumentée (`fsync`, attentes du journal, sessions bloquées) |
| Sans le coût de l'antivirus | `node load/run-capacity.mjs instant-clean-confirmation` | Le plafond du code, du stockage et de la base seuls (profil `capacity` requis : aucune variable isolée ne coupe l'antivirus) |
| Un essai rapide | `docker compose --profile load run --rm k6`, puis `node load/report.mjs` | Sur l'environnement déjà démarré, suivi dans Grafana |
| **Les garanties, dans le code** | `cd backend && ./mvnw verify` | Voir le tableau suivant (~3 min, Docker requis) |
| L'interface | `cd frontend && npm test` | Vitest : règles F-1 à F-15, schémas Zod confrontés au contrat |
| Tout arrêter | `./scripts/stop.sh` (`--purge` : données comprises) | — |

Ce que `./mvnw verify` vérifie, contre les **vrais** composants
(Testcontainers : PostgreSQL, SeaweedFS, ClamAV) :

| Ce qui est vérifié | Comment |
|---|---|
| Les couches ne dépendent que vers l'intérieur ; l'hexagone ; la disposition du code | ArchUnit — une violation volontaire est bien attrapée |
| Les contraintes `CHECK` résistent aux `NULL` | Injection systématique sur PostgreSQL réel |
| Claim concurrent : chaque ligne prise une seule fois ; un worker zombie n'écrit rien | 8 fils, 8 lignes ; verdict avec un jeton périmé |
| L'identité de téléchargement ne peut pas lire la quarantaine | Contre un vrai SeaweedFS |
| Chaque mode de panne de l'antivirus produit le bon état | WireMock : silence, lenteur, coupure, corps illisible, `412`, `413`, limite dépassée |
| Les limites du vrai moteur ; son angle mort zip64 | Contre le vrai ClamAV |
| Chaque point d'interruption de la promotion converge | Un test par interstice |
| Idempotence du dépôt, admission, `Content-Length` mensonger | Contre la vraie pile HTTP |
| Téléchargement : chaque refus, les plages, l'audit ; le cookie seul suffit ; sans identité, `401` | `DownloadApiTest`, `BrowserSessionTest`, `OidcSecurityTest` |
| Le service respecte le contrat | Un test lit `openapi.yaml` |
| Mémoire constante | 500 Mo à travers un tas de 256 Mo, dans une exécution dédiée |
| Bout en bout | Fichier sain téléchargé à l'identique ; EICAR bloqué, jamais servi |

Pour suivre un scénario au débogueur, chaque document de
[`backend/scenarios/`](backend/scenarios/README.md) indique le test à lancer et
les points d'arrêt à poser.

---

## 8. Choix et raisons

### 8.1 Les technologies, et pourquoi chacune

Règle suivie : **un composant n'entre que s'il répond à un besoin que rien de
déjà présent ne couvre.** Chaque version est épinglée ; leur analyse de
vulnérabilités est dans [`docs/33`](docs/33-analyse-des-dependances.md).

**Le service**

| Technologie | Version | À quoi elle sert | Pourquoi elle |
|---|---|---|---|
| Java | 21 (LTS) | Le langage du service | Imposé par l'énoncé ; version LTS, avec les threads virtuels |
| Spring Boot | 4.1.1 | Le socle : configuration, injection, serveur HTTP | Imposé par l'énoncé ; dernière version stable |
| Spring MVC + threads virtuels | — | L'API HTTP | Le service est bloquant par nature (flux vers le stockage et l'antivirus) : un thread virtuel par requête suffit, sans la complexité de WebFlux ([ADR-0010](docs/adr/0010-mvc-et-threads-virtuels.md)) |
| Spring Data JPA (Hibernate) | — | Les lectures : liste, recherche, détail | Lisible et paginé sans code répétitif |
| `JdbcClient` (SQL écrit à la main) | — | Les trois instructions qui portent la garantie : prise d'un fichier dans la file, écritures conditionnelles | Elles n'ont pas d'équivalent JPA (`FOR UPDATE SKIP LOCKED`, mise à jour conditionnée au jeton) ([ADR-0003](docs/adr/0003-jpa-pour-lire-sql-pour-garantir.md)) |
| Flyway | — | Les migrations du schéma | Le schéma est versionné avec le code ; Hibernate ne le touche jamais |
| Records Java | 21 | La validation de la configuration au démarrage, et des paramètres | Chaque réglage se vérifie dans le constructeur de son record : une valeur invalide arrête le service tout de suite, sans bibliothèque de validation |
| AWS SDK v2 (S3) + client HTTP Apache 5 | 2.55.6 | L'accès au stockage objet | L'API S3 est le standard : passer sur AWS S3 ou un autre fournisseur est un changement de configuration. Le client Apache est déclaré pour fixer explicitement les délais de connexion **et** de lecture |
| Maven Wrapper | 3.9 | La construction | Rien à installer sur le poste : `./mvnw` télécharge la bonne version de Maven |

**Les données et le stockage**

| Technologie | Version | À quoi elle sert | Pourquoi elle |
|---|---|---|---|
| PostgreSQL | 17.11 | Métadonnées, **file de travail**, sessions, journal d'audit | Une seule base fait aussi la file (`SKIP LOCKED`) : pas de broker à exploiter ([ADR-0001](docs/adr/0001-la-base-de-donnees-fait-file.md)). Ses contraintes `CHECK` et ses triggers portent une partie de la garantie ; `pg_trgm` et `unaccent` servent la recherche |
| SeaweedFS | 4.47 | Le stockage objet compatible S3 (quarantaine, zone servable) | Il applique des **droits par identité**, dont dépend l'isolation de la quarantaine. MinIO n'est plus distribué (dépôt archivé), LocalStack n'applique pas ces droits, SeaweedFS 3.97 ne savait pas faire de lecture seule |

**La sécurité**

| Technologie | Version | À quoi elle sert | Pourquoi elle |
|---|---|---|---|
| Keycloak | 26.4.7 | Le fournisseur d'identité (OpenID Connect) | Standard, open source, en Java, prêt à l'emploi en conteneur ; utilisateurs de démonstration et systèmes tiers déclarés dans un realm versionné |
| Spring Security (client et serveur de ressources OAuth2) | — | La connexion du navigateur, la vérification des jetons des systèmes tiers | Le protocole est fourni par Spring, pas réécrit : PKCE, `state`, `nonce`, vérification de signature, d'émetteur et d'audience ([ADR-0012](docs/adr/0012-session-navigateur-client-confidentiel.md)) |
| Spring Session JDBC | — | Les sessions du navigateur, en base | Une session vue par tous les nœuds, sans composant de plus : la base est déjà là |

**L'antivirus**

| Technologie | Version | À quoi elle sert | Pourquoi elle |
|---|---|---|---|
| ClamAV, derrière `clamav-rest` | 1.4.6 / 0.6.6 | L'analyse des fichiers | L'énoncé demande « un antivirus disponible via une API » : moteur open source, exposé par une API HTTP, en conteneur. Image dérivée pour forcer l'alerte quand une limite est atteinte ([ADR-0004](docs/adr/0004-antivirus-par-api-http.md), [ADR-0005](docs/adr/0005-ordre-des-limites-de-l-antivirus.md)) |

**L'observabilité**

| Technologie | Version | À quoi elle sert | Pourquoi elle |
|---|---|---|---|
| Micrometer + Spring Boot Actuator | — | Les métriques et les sondes de santé | Intégrés à Spring ; métriques métier (retard de la file, verdicts) à côté des métriques techniques |
| Prometheus | 3.5.5 (LTS) | La collecte des métriques | Le standard du format exposé par Micrometer ; ligne à support long |
| Grafana | 12.1.10 | Le tableau de bord de capacité | Provisionné depuis le dépôt : le tableau est là dès le premier démarrage |
| k6 | 1.2.3 | Les essais de charge | Scénarios scriptés, débit piloté par paliers, en conteneur ([`docs/capacity-planning`](docs/capacity-planning/README.md)) |

**L'interface**

| Technologie | Version | À quoi elle sert | Pourquoi elle |
|---|---|---|---|
| React + TypeScript | 19 / 6.0 | L'interface | React est imposé par l'énoncé ; TypeScript strict pour que le contrat d'API soit vérifié à la compilation |
| Vite | 8 | Le serveur de développement et la construction | Démarrage instantané, et un proxy qui met l'interface et l'API sur **la même origine** : condition du cookie de session, sans CORS |
| TanStack Query | 5 | L'état venant du serveur : cache, suivi des analyses par interrogation | Le suivi d'un fichier jusqu'à son verdict est son cas d'usage exact ; aucun état serveur recopié à la main |
| TanStack Table | 9 | Le tableau paginé, trié, filtré | Logique de tableau sans imposer de rendu : le style reste celui de l'application |
| axios | 1 | Les appels à l'API | Il donne la **progression d'envoi** d'un fichier (par `XMLHttpRequest`, ce que `fetch` ne sait pas faire), sans le charger en mémoire |
| Zod | 4 | La validation de chaque réponse de l'API et de la configuration | Une réponse inattendue est refusée à l'entrée au lieu de casser l'affichage ; les types en sont dérivés |
| React Router | 7 | La navigation, l'état de la liste dans l'URL | Une adresse partageable ; ligne 7 maintenue, la 8 n'apporte rien ici |
| shadcn/ui (Base UI) + Tailwind CSS | 4 | Les composants et le style | Composants accessibles copiés dans le dépôt, donc modifiables ; style par classes, thèmes clair et sombre par variables |
| lucide-react, sonner | — | Icônes, notifications | Ceux de shadcn |

**Les tests et la qualité**

| Technologie | Version | À quoi elle sert | Pourquoi elle |
|---|---|---|---|
| JUnit 5 + AssertJ | — | Les tests du service | Fournis par Spring Boot |
| Testcontainers | 2.0.5 | Les tests contre le **vrai** PostgreSQL, le vrai SeaweedFS, le vrai ClamAV | Les garanties testées sont des comportements de ces moteurs (`SKIP LOCKED`, droits du stockage, limites de l'antivirus) : un double ne les prouverait pas |
| WireMock | 3.13.2 | Les pannes de l'antivirus et un Keycloak simulé | Les pannes (lenteur, coupure, réponse illisible) ne se provoquent pas sur un vrai moteur |
| ArchUnit | 1.5.1 | Les règles de l'architecture hexagonale | Une règle d'architecture qui ne casse pas la construction finit par être contournée ([ADR-0011](docs/adr/0011-architecture-hexagonale-verifiee.md)) |
| Vitest + Testing Library | 5 / 16 | Les tests de l'interface | Même configuration que Vite ; tests écrits du point de vue de l'utilisateur |
| MSW | 2 | L'API simulée, dans les tests et dans le navigateur | Le front se développe et se teste sans le service, sur le contrat |
| ESLint + Prettier | 9 / 3 | Le style et les règles du front | Règles d'import entre couches, accessibilité (`jsx-a11y`), format uniforme |
| Trivy | 0.75 | L'analyse des vulnérabilités | Un seul outil pour le JAR, les dépendances npm et les images ([`docs/33`](docs/33-analyse-des-dependances.md)) |

**L'environnement**

| Technologie | Version | À quoi elle sert | Pourquoi elle |
|---|---|---|---|
| Docker Compose | v2 | Tout l'environnement en une commande | Le service et ses dépendances se lancent et se vérifient avec `scripts/start` et `scripts/check`, sous Windows, Linux ou macOS |

### 8.2 Les décisions d'architecture

Chaque décision structurante a son ADR ([`docs/adr/`](docs/adr/)), relu et
signé par le porteur du projet (statut *Accepté*, 01/10).

| Choix | Pourquoi | Ce qu'il coûte | ADR |
|---|---|---|---|
| **La base fait file**, aucun broker | Cadrage du 21/09 : Spring Boot, React et une base de données suffisent. L'état et le travail sont la même ligne : pas d'outbox, pas de divergence | La file charge la base principale ; son plafond est la cadence de validation (§6) | [0001](docs/adr/0001-la-base-de-donnees-fait-file.md) |
| **Un processus, trois identités de stockage** | La séparation qui compte est celle du stockage ; trois déploiements pour ce service seraient de la cérémonie | Un nœud porte les trois rôles | [0002](docs/adr/0002-un-processus-trois-identites.md) |
| **JPA pour lire, SQL pour garantir** | Lectures lisibles ; le claim et les écritures conditionnelles n'ont pas d'équivalent JPQL | Deux styles d'accès aux données | [0003](docs/adr/0003-jpa-pour-lire-sql-pour-garantir.md) |
| **Antivirus par API HTTP** (ClamAV + clamav-rest) | L'énoncé le demande ; moteur remplaçable derrière un port | Un enrobage tiers sur le chemin de sécurité, épinglé et testé | [0004](docs/adr/0004-antivirus-par-api-http.md) |
| **`MaxFileSize` > `MaxScanSize`** | **Mesuré** : dans l'autre sens, une archive est tronquée sans alerte et déclarée saine | Des archives plus longues à analyser | [0005](docs/adr/0005-ordre-des-limites-de-l-antivirus.md) |
| **Promotion par relecture**, sans zone temporaire | Une copie serveur ne rend pas d'empreinte ; le point de validation est la ligne | Une lecture de plus par fichier sain | [0006](docs/adr/0006-promotion-par-relecture-verifiee.md) |
| **Téléchargement par l'identité de l'appelant**, servi par le service | Une seule preuve (cookie ou `Bearer`) : le lien HMAC doublait l'authentification ; une URL présignée ne revérifierait pas l'état | La bande passante traverse le service | [0013](docs/adr/0013-telechargement-par-l-identite-de-l-appelant.md) (remplace [0007](docs/adr/0007-liens-hmac-servis-par-le-service.md)) |
| **Authentification toujours exigée**, sans mode anonyme | Le cadrage ne l'exige pas ; le porteur du projet la livre, et sans réglage pour la couper : une configuration qui ouvre l'API à tous n'est pas livrée. Toute la suite de tests passe par un Keycloak simulé | Keycloak indispensable, même en local | [0014](docs/adr/0014-authentification-toujours-exigee.md) (remplace [0008](docs/adr/0008-authentification-activable.md)) |
| **Session du navigateur par le service** (client confidentiel, cookie `HttpOnly`, Spring Session JDBC) ; **`Bearer` sans état** pour les systèmes tiers | Aucun jeton lisible par un script ; aucun état dans un nœud ; protocole et session standard, pas réécrits | Une lecture en base par requête du navigateur, un en-tête CSRF sur les écritures | [0012](docs/adr/0012-session-navigateur-client-confidentiel.md) |
| **Audit par trigger, en ajout seul** | Aucun chemin de code ne peut changer un statut sans trace | De la logique en PL/pgSQL | [0009](docs/adr/0009-journal-d-audit-par-trigger.md) |
| **MVC + threads virtuels**, bornes explicites (sémaphore des dépôts, boucles d'analyse en nombre fixe) | Un service bloquant par nature ; mémoire constante mesurée ; une seule borne par ressource, là où rien d'autre ne borne | Surveiller l'épinglage des threads porteurs | [0010](docs/adr/0010-mvc-et-threads-virtuels.md) |
| **Hexagone vérifié** | Chaque adaptateur se remplace seul ; le cœur se teste sans eux | Une interface par cas d'usage, une racine de composition à tenir | [0011](docs/adr/0011-architecture-hexagonale-verifiee.md) |
| **Domaine sans annotation de persistance** : l'entité JPA est distincte de l'agrégat | Le domaine se compile sans aucune bibliothèque ; l'invariant est vérifié par le constructeur pour toute instance, relue comprise ; le dépôt Spring Data est en lecture seule par construction | Une classe miroir de 25 colonnes et un *mapper*, couverts par un test d'aller-retour | [0015](docs/adr/0015-domaine-sans-annotation-de-persistance.md) |

Écartés, et pourquoi (dans chaque ADR et dans [`docs/prompts/`](docs/prompts/)) :
WebFlux, un broker, une bibliothèque de résilience (la file porte déjà réessais
et backoff), MinIO, LocalStack, Tika (une dépendance pour une donnée purement
descriptive), les modules Maven multiples, les URL présignées, le lien signé.

---

## 9. Hypothèses

L'énoncé demande de formuler et documenter les hypothèses. Chacune dit **ce qui
l'a motivée**.

| Hypothèse | Ce qui l'a motivée |
|---|---|
| **500 Mo maximum**, sans envoi par morceaux | Cadrage du 21/09 : pas de fichiers de plusieurs Go. La limite d'admission est alignée sur ce que l'antivirus analyse en entier |
| **~50 dépôts simultanés au pic, ~5 000 fichiers par jour** | Volumétrie non imposée : ordre de grandeur plausible pour une équipe métier. Dimensionnement mesuré au §6 |
| **Analyse asynchrone** | Confirmée lors du cadrage. Une analyse de 500 Mo ne tient pas dans le temps d'une requête HTTP |
| **Un nœud pour l'exercice, plusieurs à penser** | Précisé lors du cadrage. D'où : stockage objet partagé, file en base sans verrou distribué, horloge de la base pour les baux, sessions en base |
| **Le service est déployé derrière une passerelle d'API** (ou un reverse proxy) qui porte TLS, la limite de débit par client et les délais de lecture | C'est le cas de la plupart des services en production. Une limite de débit sur plusieurs nœuds exige des compteurs partagés (Redis, ou la passerelle elle-même) : le service ne reconstruit pas ce que la passerelle fait déjà |
| **Un dépôt arrive à ~1 Mbit/s au moins** : son corps doit être reçu en 60 s + 8 s par Mio annoncé (~68 min pour 500 Mo), sinon `408` | Un délai de lecture ne mesure que le silence entre deux paquets : sans échéance, un client au compte-gouttes garde sa place sur le nœud indéfiniment (audit S-06, relecture R-007). Le seuil est large à dessein : il borne un abus sans couper un réseau lent |
| **Authentification non indispensable** pour l'exercice (cadrage du 21/09) | Livrée quand même, et toujours exigée : décision du porteur du projet. Aucun réglage ne la coupe (ADR-0014) |
| **L'interface et l'API partagent une origine** | Condition du cookie de session et de l'absence de CORS : proxy Vite en développement, service ou reverse proxy en livraison |
| **Keycloak peut tomber sans déconnecter personne** | Une session sans verdict reste servie 30 min au plus ; au-delà, reconnexion |
| **Un espace de fichiers par utilisateur**, sans partage ni rôle | Contrat 1.3 : le propriétaire est le `sub` du jeton ; un fichier d'autrui répond `404`, jamais `403` |
| **Le type déclaré par le client est ignoré** ; tout est servi en `application/octet-stream`, `attachment`, `nosniff` | Un fichier sain pour l'antivirus peut rester dangereux une fois *interprété* par un navigateur |
| **Les fichiers infectés sont conservés en quarantaine**, jamais servis, sans purge automatique | Auditabilité avant tout ; la politique de rétention est une décision métier (`D-07`) |
| **Aucune déduplication de verdict par empreinte** | Correcte seulement si elle tient compte de la version des signatures et de l'âge du verdict ; mal faite, c'est une faille (`D-08`) |
| **Performance de l'antivirus secondaire** | Consigne du porteur du projet : il en faut un, joignable par une API |
| **L'antivirus est hors périmètre** : un composant fourni, de confiance, sur un réseau privé. Son durcissement (canal chiffré et authentifié vers le moteur, image amont) n'est pas traité | Décision du porteur du projet. L'énoncé délègue l'analyse à « un antivirus disponible via une API » : le sujet est ce que le service fait de son verdict, pas le moteur. Seule précaution prise : son port n'écoute que sur la machine locale |
| **Pas de mise en production** : l'interface est servie par le serveur de développement Vite, lancé par `scripts/start` (`.sh` ou `.ps1`) ou depuis l'IDE (`npm run dev`) | Le meilleur rapport avec les contraintes de l'exercice : une commande, multiplateforme, et la même origine que l'API par le proxy de Vite. Un build de production existe et ne contient aucun bouchon ; qui le servirait, avec quels en-têtes (CSP), est une décision de mise en production, hors périmètre |
| **Pas de tests de bout en bout dans un navigateur** (Playwright) | Décision du porteur du projet : les bouchons de l'interface (MSW), `scripts/check` et `scripts/demo.sh` couvrent le parcours |
| **Pas d'intégration continue** | Exercice, décision du porteur du projet : les tests tournent sur le poste (`./mvnw verify`, `npm test`), et l'analyse des dépendances a été faite une fois ([`docs/33`](docs/33-analyse-des-dependances.md)) |

### Ce que les mesures ont changé

Une garantie non mesurée est une intention. Plusieurs mesures ont **contredit**
ce que la documentation supposait :

| Mesure | Résultat | Conséquence |
|---|---|---|
| ⭐ **Limites de l'antivirus** | Avec `MaxFileSize` ≤ `MaxScanSize`, une entrée d'archive de 600 Mo est **tronquée sans alerte** : archive déclarée saine | Limites réordonnées, garde au démarrage de l'image, test contre le vrai moteur ([B-008](docs/prompts/B-008-limites-antivirus-mesurees.md)) |
| ⭐ **Angle mort du moteur** | Une entrée compressée derrière un en-tête local zip64 **n'est pas analysée** : EICAR passe | Caractérisé par un test, documenté en risque résiduel (§10) |
| ⭐ **Capacité** | Au-delà de la saturation, le débit utile tombait de moitié ; une copie refusée laissait un fichier 15 min en `PROMOTING` | Admission bornée avant la lecture du corps, deux pools, bail proportionnel à la taille, réessais du SDK décidés par identité ([capacity planning](docs/capacity-planning/README.md#ce-qui-a-été-corrigé-en-cours-de-route)) |
| **Mémoire** | 500 Mo **déposés, promus et téléchargés** à travers un tas de 256 Mo | Exécution de test dédiée : la preuve tourne à chaque build |
| SeaweedFS 3.97 | Une identité en lecture seule ne pouvait **rien** lire | Montée en 4.47 : sinon, il fallait donner l'écriture au téléchargement |
| Authentification contre le vrai Keycloak | Émetteur vu du navigateur ≠ adresse vue du conteneur | Deux réglages distincts ; un utilisateur ne voit rien des fichiers d'un autre |

---

## 10. Limites connues et pistes d'amélioration

Chaque limite dit **ce qui la ferait lever**.

| Limite | Déclencheur | Piste |
|---|---|---|
| ⭐ **Angle mort zip64 du moteur** | Dès que des archives venues de l'extérieur sont attendues | Politique d'archives côté service (refuser ou classer « non analysable » ce que le moteur ne sait pas ouvrir), second moteur, signalement à l'équipe ClamAV |
| ⭐ **Archive chiffrée déclarée saine** : le moteur ne peut pas l'ouvrir et, tel qu'il est configuré, ne le signale pas (`AlertEncryptedArchive` désactivé) — mesuré le 02/10 : `200` pour une archive dont l'entrée est marquée chiffrée | Dès que des archives venues de l'extérieur sont attendues | Activer `AlertEncryptedArchive` dans l'image, et classer `Heuristics.Encrypted.*` « non analysable » (le motif `ENCRYPTED_ARCHIVE` existe déjà au contrat), avec un test contre le vrai moteur |
| ⭐ **Au-delà du plafond de la base, le débit s'effondre** au lieu de plafonner | Toute exploitation où la base peut ralentir | Délester quand l'attente d'une connexion dépasse un seuil ; disque dédié, validations regroupées (§6) |
| Montée en charge horizontale non démontrée sur ce banc | Avant d'annoncer un débit à plusieurs nœuds | Campagne de contrôle : trois nœuds, base sans attente du disque |
| Corpus de charge non représentatif (octets aléatoires) | Avant une capacité de production | Rejouer avec des PDF, documents Office et archives anonymisés ; remesurer ClamAV |
| Admission bornée en nombre de fichiers, pas en octets | Des dépôts volumineux en volume | Borne sur `praxedo_queue_depth_bytes`, qui existe déjà |
| **Limitation acceptée** : pas de limite de débit par client, y compris sur `/api/v1/auth/login`, qui crée une session en base à chaque appel, même sans compte | Service exposé sans passerelle d'API (§9) | Limite par IP et par principal à la passerelle. Dans le service, il faudrait des compteurs partagés entre nœuds (Redis) ; pour la connexion, ne créer la session qu'au retour de Keycloak |
| **Limitation acceptée** : pas de quota par utilisateur. Un seul compte peut remplir la file commune (500 fichiers) et faire refuser les dépôts des autres ; une limite de débit ne l'empêche pas, elle le ralentit seulement | Un client qui monopolise la file, ou des clients d'importance différente | Borne de fichiers en attente par propriétaire, comptée dans PostgreSQL (l'index `(owner_id, status)` existe), donc partagée entre nœuds sans composant de plus ; plafond d'octets stockés par propriétaire |
| Un dépôt lent garde sa place jusqu'à son **échéance** (60 s + 8 s par Mio annoncé), pas au-delà ; mais un client qui annonce 500 Mo et envoie juste au-dessus de ~1 Mbit/s la garde ~68 min | Plusieurs clients qui monopolisent les 50 places d'un nœud à ce rythme | Quota de dépôts simultanés par propriétaire (compté en base, comme le quota de fichiers en attente) ; débit minimal à la passerelle |
| Les téléchargements traversent le service | Débit sortant supérieur à ce qu'un nœud sert | URL présignée émise **après** revérification, ou CDN |
| Pas de lien de partage | Partager un fichier avec quelqu'un sans compte | Une fonctionnalité à part entière : lien explicite, révocable, audité — pas un détail d'authentification |
| Pas de réanalyse quand les signatures évoluent | Exigence de conformité, ou menace découverte après coup | Nouvel état `RESCANNING`, réanalyse périodique des fichiers disponibles |
| Pas de déduplication des verdicts | Mêmes fichiers déposés massivement | Réutiliser un verdict par SHA-256, conditionné à la version des signatures et à l'âge |
| Pas de rétention des fichiers infectés | Politique de rétention définie | Purge planifiée, l'audit restant |
| Images d'outillage local avec des failles connues : Keycloak 26.4.7 (7 critiques), Grafana (5), `aws-cli` | Toute exposition hors du poste | Keycloak sur sa ligne courante, Grafana sur une ligne maintenue, création des zones sans `aws-cli` ([`docs/33`](docs/33-analyse-des-dependances.md)) |
| Durée des opérations de stockage non mesurée | Stockage soupçonné d'être le goulot | Décorateur des ports de stockage, sur le modèle de celui de l'antivirus |
| Au-delà de 500 Mo, rien | Fichiers plus gros | Envoi reprenable (tus) et limites de l'antivirus revues **ensemble** — jamais en découpant pour l'analyse |
| Révocation dans Keycloak effective en 5 min | Besoin de révocation immédiate | Déconnexion par canal arrière d'OpenID Connect, avec un registre des sessions partagé entre nœuds |
| Identifiant de session en clair en base (Spring Session) | Exigence « rien d'exploitable dans une copie de la base » | N'y ranger que son empreinte, ou chiffrer les attributs au repos |
| Une lecture de la table des sessions par requête du navigateur | Latence de la base visible, ou trafic navigateur très élevé | Spring Session sur Redis (même API, aucun changement de code applicatif) |

---

## 11. Travail avec l'IA

Le projet a été conduit avec Claude et ChatGPT, en **double analyse
indépendante** fusionnée par le porteur du projet
([`docs/CONFRONTATION.md`](docs/CONFRONTATION.md)). Le journal
[`docs/prompts/`](docs/prompts/) garde, pour chaque étape, le prompt, ce qui a
été retenu, **ce qui a été rejeté**, et ce qui a été vérifié.

Les décisions restées humaines se voient dans le journal : un seul module Maven
et un seul processus, le retour de JPA après contradiction argumentée, un
antivirus joignable par une API, l'authentification ajoutée alors que le
cadrage ne l'exigeait pas, le retrait du lien signé. L'IA a proposé, mesuré et écrit ; elle
n'a pas tranché.

---

## 12. Arborescence

```
PRAXEDO/
├── README.md                 ce document
├── README.txt                le guide de démarrage, brique par brique
├── docker-compose.yml        l'environnement complet (profil « app » pour le service, « load » pour k6)
├── contracts/                openapi.yaml, le contrat d'API — et ses raisons (README.md)
├── backend/                  Spring Boot, hexagonal
│   ├── ARCHITECTURE.md       chaque protocole en détail
│   ├── docs/                 les processus, un document par cas d'usage (général)
│   └── scenarios/            le code suivi ligne à ligne, un scénario par port d'entrée (technique)
├── frontend/                 React 19 + Vite — ARCHITECTURE.md, AGENTS.md
├── infra/                    antivirus (image dérivée), stockage, Keycloak, PostgreSQL, Prometheus, Grafana
├── load/                     essais de capacité : k6, lanceur, rapports, résultats
├── scripts/                  start, check, stop (bash et PowerShell) ; demo.sh
└── docs/                     énoncé, analyses, ADR, capacity planning, journal des prompts
```
