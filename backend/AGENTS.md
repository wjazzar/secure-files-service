# backend/AGENTS.md — Contexte du back-end

> **À lire après [`../AGENTS.md`](../AGENTS.md)**, qui fixe la nature du projet
> (test technique, pas un projet client) et les règles communes.
> Ce fichier ne contient que ce qui est propre au back-end.
>
> **L'architecture détaillée est dans [`ARCHITECTURE.md`](ARCHITECTURE.md)** :
> versions figées, structure du code, schéma exact, protocoles, couche HTTP, tests. Elle
> **tranche** ce que ce fichier laissait ouvert (§0 de ce document-là).
> Le plan d'exécution lot par lot est dans [`PLAN.md`](PLAN.md).
>
> **Analyse : Claude (Opus 5)** — 2026-09-23, tenu à jour jusqu'au 01/10,
> relu contre le code le 02/10. Le back-end est livré.

---

## 1. Rôle du back-end

**C'est le cœur du projet** : ce sont les garanties du service qui comptent,
plus que l'interface.

Tout découle d'un invariant unique :

> **Au moment d'autoriser une réponse de téléchargement, l'objet doit être
> `AVAILABLE` et porter une attestation `CLEAN` liée à son SHA-256.**

---

## 2. Cadrage précisé le 21/09

Référence : [`../docs/28-precisions-de-cadrage.md`](../docs/28-precisions-de-cadrage.md).

| Point | Valeur | Conséquence back-end |
|---|---|---|
| Taille max | **500 Mo**, pas d'envoi par morceaux | Limite d'admission = limite d'analyse. Au-delà : `413` |
| Volumétrie | Non imposée → hypothèse à défendre : ~50 dépôts simultanés, ~5 000 fichiers/jour | Aucun broker nécessaire ; file en base suffisante |
| Stockage | **Objet compatible S3**, pensé multi-nœuds | Aucun état local, aucun fichier temporaire partagé |
| Analyse | **Asynchrone** | `202` puis suivi par l'API |
| Authentification | **Keycloak, toujours exigé** (décision du porteur, 2026-09-25, puis 2026-09-29 : plus de mode sans authentification, ADR-0014) ; **sans rôle** : un espace de fichiers privé par utilisateur (`owner_id = sub`), fichier d'autrui → `404` (contrat 1.3) | Serveur de ressources JWT (`iss`, `aud`) et session du navigateur ; fichiers cloisonnés par `sub` ; aucun propriétaire de repli |
| Outils | Spring Boot + React + base suffisent | **Aucun broker**, aucune bibliothèque de résilience |
| Interface | Tableau paginé **dans le cœur** (décision du porteur) | `GET /files` en pagination par numéro de page (`Pageable`/`PagedModel`), tri en liste blanche ; `/summary` ; **plus de `/limits`** ; téléchargement par `GET /files/{id}/content` avec l'identité de l'appelant, sans lien signé (ADR-0013) — voir `contracts/README.md` §2.5, §2.6, §2.11 |

---

## 3. Architecture retenue

**Un seul module Maven** (décision du porteur du projet, 27/09 : l'application
est petite). Les couches sont des **paquets**, dépendances dirigées vers
l'intérieur :

```
domain          automate, règles, objets valeur — AUCUN framework
   ▲
application     cas d'usage + définition des PORTS
   ▲
infrastructure  ADAPTATEURS : stockage objet, antivirus, persistance, web
   ▲
config          composition : quelle implémentation derrière quel port (main() reste à la racine)
```

**La frontière est tenue par ArchUnit**, pas par le classpath : `LayeringRulesTest`
fait échouer la construction si une dépendance remonte, ou si `domain` ou
`application` importent Spring, Jakarta, JDBC ou le SDK AWS — le domaine n'a
**aucune** exception, pas même JPA : le mapping vit dans
`persistence/file/entity` (ADR-0015). Affaiblir une de ces
règles, c'est supprimer une garantie — elles ne se touchent pas sans raison écrite.

### Architecture hexagonale — vérifiée à chaque build

```
web, scheduling, metrics  ──▶  port/in  ──▶  service  ──▶  port/out  ◀──  persistence, storage,
(adaptateurs PILOTANTS)       (cas d'usage)  (implémente)   (besoins)       antivirus, security
                                                                           (adaptateurs PILOTÉS)
```

- Un adaptateur **pilotant** (contrôleur, planificateur, métriques) n'appelle
  que des **ports d'entrée** (`…UseCase`) — jamais un service, jamais un port
  de sortie.
- Un adaptateur **piloté** implémente un **port de sortie** et n'appelle jamais
  un cas d'usage.
- Les adaptateurs ne dépendent **jamais les uns des autres**.
- Seule la composition — le paquet `config` — connaît les services
  (`UseCaseConfiguration`, qui publie chacun sous son port d'entrée) et
  construit les adaptateurs qui demandent un choix (`StorageConfiguration`,
  `AntivirusConfiguration`). Tout `@Bean` qui fournit un port y est déclaré.

`HexagonalArchitectureTest` fait échouer la construction sinon. Ajouter une
fonctionnalité = un port d'entrée, un service qui l'implémente, et l'adaptateur
qui l'appelle.

### Organisation du code : par concept, puis par nature — partout

```
domain/<concept>/<nature>                   domain/file/{model, valueobject, exception}, domain/owner/valueobject
application/<concept>/<nature>              application/file/{port/in, port/out, service, model, exception},
                                            application/idempotency/port/out
infrastructure/<adaptateur>/<concept>/<nature>
                                            web/file/{controller, dto, mapper, header, exception}
                                            persistence/file/{adapter, entity, repository, mapper, projection},
                                            persistence/idempotency/adapter
                                            storage/file/adapter, antivirus/file/adapter, …
                                            <adaptateur>/common/{config, support, …}
```

Le concept dit **de quoi** parle le code (`file` ; `common` pour le partagé) ;
la nature dit **ce qu'il est**. Le téléchargement est rangé sous `file` : il
sert un fichier. Un nouveau sujet reçoit son dossier, avec les mêmes natures.
`CodeLayoutRulesTest` tient cette disposition (profondeur minimale, contrôleurs
dans `controller/`, réponses dans `dto/`, adaptateurs dans `adapter/`,
configuration dans `common/config/`, exceptions dans `exception/`…).

### Ports principaux

```java
// Stockage : un port par rôle, chacun adossé à l'identité de stockage du rôle
interface QuarantineWriter { void write(ObjectKey key, InputStream content, long sizeBytes); void discard(ObjectKey key); }
interface ServableReader   { ContentStream open(ObjectKey key, ByteRange range); }
interface WorkerStorage    { InputStream openQuarantined(ObjectKey key);
                             void writeServable(ObjectKey key, InputStream content, long sizeBytes);
                             /* suppressions, liste pour le balayage */ }

// Antivirus : API HTTP ; WireMock et un double scripté pour les tests
interface AntivirusScanner {
    ScanOutcome scan(InputStream content, long sizeBytes);   // une panne est une EXCEPTION, jamais un résultat
    boolean isAvailable();                                     // portillon de santé, AVANT le claim
}
```

`ScanOutcome` n'est pas un verdict : l'adaptateur ne sait pas **quels octets**
il a analysés. Le worker hache le flux qu'il envoie et lie lui-même la
conclusion à cette empreinte (`ScanVerdict`).

Autres ports de sortie : `FileCatalog` (JPA), `FileWorkQueue` (SQL : claim,
verdict, promotion, reaper), `IdempotencyStore`,
`DownloadAudit`, `OperationalReadings`, `TransactionRunner`.

Ports d'entrée : `UploadFileUseCase`, `QueryFilesUseCase`, `DownloadFileUseCase`,
`ScanFilesUseCase`, `MaintainFilesUseCase`, `MonitorFilesUseCase`.

### Séparation des capacités (et non des processus)

**Un seul processus** (décision du porteur du projet, 27/09) : il n'y a pas
trois applications à démarrer. La séparation vit dans le code, et surtout dans
les **identifiants du stockage objet** : trois clients distincts, injectés à
trois composants distincts. Celui qui sert les fichiers reçoit des
identifiants sans **aucun** droit de lecture sur la quarantaine ; celui qui
accepte les dépôts n'a que l'écriture. Une erreur de code ne peut donc pas
faire lire la quarantaine au chemin de téléchargement : le stockage refuse.

---

## 4. Automate d'états

| État interne | Servable | Travail automatique | Statut public |
|---|---|---|---|
| `AWAITING_SCAN` | non | oui | `PENDING` |
| `SCANNING` | non | en cours (bail détenu) | `SCANNING` |
| `RETRY_WAIT` | non | oui, à échéance | `PENDING` |
| `PROMOTING` | non | oui | `SCANNING` |
| `AVAILABLE` | **oui** | non | `AVAILABLE` |
| `INFECTED` | non | non | `INFECTED` |
| `UNSCANNABLE` | non | non | `UNSCANNABLE` |
| `FAILED_FINAL` | non | non | `FAILED` |

```
dépôt finalisé
      ▼
AWAITING_SCAN ──claim(token, bail)──▶ SCANNING
      ▲                                  ├── menace ──────▶ INFECTED
      │                                  ├── limite ──────▶ UNSCANNABLE
      │                                  ├── erreur finale▶ FAILED_FINAL
      │                                  ├── panne ───────▶ RETRY_WAIT
      └── échéance ◀── RETRY_WAIT        └── sain ────────▶ PROMOTING
                                                              │ copie + vérif + CAS
                                                              ▼
                                                          AVAILABLE
```

**Points à ne pas rater** (défauts corrigés lors de la confrontation) :

1. `CLEAN` est un **verdict d'antivirus**, `AVAILABLE` un **état métier**. Les
   confondre rend inexprimable « verdict sain obtenu, copie non terminée ».
2. `RETRY_WAIT` (transitoire) et `FAILED_FINAL` (terminal) sont **deux états
   distincts** : sans cette séparation, le claim reprend indéfiniment des
   travaux terminaux.
3. Une panne technique n'est **pas** un verdict : elle mène à `RETRY_WAIT`,
   puis à `FAILED_FINAL`, jamais à `INFECTED`. Il n'existe pas d'état
   `SCAN_FAILED`.

---

## 5. Les quatre protocoles critiques

Ce sont eux qui portent les garanties. Ils s'écrivent à la main
(Spring `JdbcClient`) : aucune forme Spring Data n'existe pour eux. Le reste de
l'accès aux données passe par JPA.

### 5.1 Ingestion — ordre strict

```
1. écrire l'objet dans la quarantaine (flux, SHA-256 et compteur d'octets au passage)
2. commiter { métadonnées + travail } en UNE transaction
3. répondre 202
```

Une panne entre 1 et 2 laisse un **objet orphelin** — invisible, non
référencé, en quarantaine, nettoyé par balayage. L'ordre inverse laisserait une
**référence brisée visible**. Principe : en panne partielle, préférer
l'orphelin invisible.

### 5.2 Claim atomique

```sql
WITH candidate AS (
  SELECT id FROM stored_file
   WHERE status IN ('AWAITING_SCAN','RETRY_WAIT')
     AND next_attempt_at <= clock_timestamp()
     AND attempts < :maxAttempts
   ORDER BY next_attempt_at, uploaded_at, id
   FOR UPDATE SKIP LOCKED LIMIT 1)
UPDATE stored_file f
   SET status='SCANNING', lease_token=:claimToken, lease_holder=:workerId,
       -- bail proportionnel à la taille du fichier pris : 30 s + 1,2 s par Mio
       lease_expires_at=clock_timestamp() + make_interval(
           secs => :leaseMinSeconds + f.size_bytes / 1048576.0 * :leasePerMibSeconds),
       attempts=f.attempts+1, version=f.version+1
  FROM candidate WHERE f.id = candidate.id
RETURNING f.*;
```

Une requête : sélection, exclusion mutuelle, prise de bail, comptage.
**C'est le cœur technique du système**, et il ne demande aucun composant
supplémentaire.

⚠️ `attempts < :maxAttempts` est **obligatoire** : sans lui, un travail
terminal est repris à l'infini.

### 5.3 Écriture du verdict — avec jeton de cloisonnement

```sql
UPDATE stored_file SET ...
 WHERE id = :fileId
   AND status = 'SCANNING'
   AND lease_token = :claimToken            -- ⚠ le jeton de CE claim
   AND lease_expires_at > clock_timestamp();
```

Le contrôle porte sur le **jeton du claim**, pas sur l'identifiant du worker :
un worker gelé dont le bail a expiré peut se re-réclamer le même fichier, et
son ancien thread écraserait alors un verdict légitime.

### 5.4 Promotion — reprenable

1. relire `quarantine/<id>` et écrire **directement** `servable/<id>` ;
2. recalculer taille et SHA-256 pendant la copie ;
3. refuser si l'un des deux diffère de l'attestation (objet servable supprimé) ;
4. `PROMOTING → AVAILABLE` par écriture conditionnelle sur le jeton ;
5. supprimer la source ensuite — un échec est rattrapé par le balayage.

Pas de zone temporaire : le point de validation est la **ligne**, pas l'objet.
Un objet servable dont la ligne n'est pas `AVAILABLE` n'est jamais servi.

**Points de panne testés** (`FilePromotionServiceTest`) : avant la copie, à
mi-copie, copie non conforme, bail perdu avant le `CAS`, source impossible à
supprimer après le `CAS`.

---

## 6. Base de données

Schéma livré : [`ARCHITECTURE.md`](ARCHITECTURE.md) §6.3 et les migrations
Flyway `V1` à `V9`. Il remplace celui de
[`../docs/23-modele-de-donnees.md`](../docs/23-modele-de-donnees.md), **avec
les corrections suivantes, non négociables** :

- **Contraintes `CHECK` totales.** En SQL, un `CHECK` ne rejette que `FALSE` :
  il accepte `UNKNOWN`. `status='AVAILABLE' AND scan_result IS NULL` passe une
  contrainte naïve. Utiliser `IS NOT DISTINCT FROM` et rendre chaque prédicat
  total. **À tester en injectant explicitement tous les `NULL` possibles.**
- Contrainte de bail **bidirectionnelle**, couvrant `SCANNING` **et**
  `PROMOTING`.
- `UNIQUE (object_key)` — une convention dans le code n'est pas une contrainte.
- Index **partiels** sur les seuls états actifs : l'index de file reste
  minuscule quel que soit l'historique.
- `status_changed_at` distinct de `uploaded_at`.
- `owner_id` présent dès le départ : le `sub` du propriétaire, toujours.

Migrations Flyway, compatibles ascendantes. ⚠️ `ALTER TYPE … ADD VALUE` ne peut
pas être utilisé dans la même transaction que la valeur ajoutée : ajouter un
état demande une migration dédiée — ce qui force à traiter le nouvel état
partout.

---

## 7. Antivirus — consommé par son API HTTP

Décision du porteur du projet (26/09) : l'énoncé dit « un antivirus disponible
via une **API** », on le prend au mot. Détail et justification :
[`ARCHITECTURE.md`](ARCHITECTURE.md) §5.2 et [`../infra/README.md`](../infra/README.md) §3.

- **API HTTP d'un conteneur dédié** : `POST /scanHandlerBody` (corps brut,
  relayé **en flux**), `GET /version` (moteur et signatures, tracés au verdict),
  `GET /` (portillon de santé).
- Moteur : **ClamAV** derrière `ajilaag/clamav-rest`, **image épinglée par
  digest** ; chemin de scan **lu dans le code source** avant adoption : il ne
  met pas le corps en tampon.
- **Image dérivée** ([`../infra/antivirus/Dockerfile`](../infra/antivirus/Dockerfile))
  pour forcer `AlertExceedsMax` : sans ce réglage, une limite interne atteinte
  remonte comme une **analyse propre**. Le build échoue s'il ne trouve plus la
  directive à corriger.
- Limites (`MAX_SCAN_SIZE` 1 Go, `MAX_FILE_SIZE` 2000 Mo, récursion, nombre
  de fichiers) pilotées depuis `docker-compose.yml`, donc versionnées.
  ⚠️ **`MAX_FILE_SIZE` strictement supérieur à `MAX_SCAN_SIZE`** — mesuré
  (B-008) : dans l'autre sens, une grosse entrée d'archive est tronquée **sans
  alerte** et l'archive déclarée saine. L'image refuse de démarrer sinon.
- Angle mort connu du moteur (B-008) : une entrée compressée dont l'en-tête
  local est au format zip64 n'est pas analysée (EICAR y passe). Documenté,
  caractérisé par un test, non corrigeable côté service sans parser les
  archives soi-même.
- Traduction : `200` → `CLEAN` ; `406` → `INFECTED`, **sauf** description
  `Heuristics.Limits.Exceeded…` → `UNSCANNABLE` ; `412` → `UNSCANNABLE` ;
  `413`, timeout, coupure, corps illisible → **panne technique**, jamais un
  verdict.
- API non chiffrée ni authentifiée : réseau interne, jamais publiée.
- Doubles et second adaptateur : `ScriptedScanner` (verdicts et pannes
  scriptés, tests unitaires du worker), WireMock (pannes de l'API HTTP), et
  `InstantCleanAntivirusScanner` (verdict sain immédiat, essais de capacité
  seulement : profil `capacity`). Le cas infecté se teste contre le **vrai**
  moteur, avec EICAR. Le client `clamd` en TCP reste un repli possible
  derrière le port.

---

## 8. Résilience — sans bibliothèque

Trois des quatre fonctions existent déjà par construction :

| Fonction | Mécanisme retenu |
|---|---|
| Timeout | Configuration du client (connexion **et** lecture) ; et une échéance sur le flux (`DeadlineInputStream`) : dépôt 60 s + 8 s/Mio (`408`), analyse et promotion dans leur bail |
| Réessai + backoff | Colonnes `attempts` et `next_attempt_at` — **persistées**, donc elles survivent au redémarrage |
| Limite de concurrence | Analyses : **nombre fixe de boucles séquentielles** (`ScanWorkerPool`, une analyse à la fois chacune) — c'est la borne, tenue par `ScanWorkerPoolTest`. Dépôts : **`Semaphore` explicite**. ⚠️ Avec les virtual threads, la taille d'un pool n'est **pas** une limite pour des tâches **soumises** (un thread par requête) ; un nombre fixe de boucles, si. Le sémaphore d'analyse, qui ne pouvait jamais bloquer, a été retiré le 30/09 |
| Coupe-circuit | Portillon de santé : le worker **ne prend pas de travail** quand l'antivirus est dégradé — il ne consomme donc pas de tentatives |

Ajouter une bibliothèque créerait **deux politiques de réessai concurrentes**,
une en mémoire et une persistée.

---

## 9. Règles non négociables

| # | Règle |
|---|---|
| B-1 | Aucun contenu de fichier en `byte[]`, `String` ou chargé en mémoire. Tout est flux |
| B-2 | Jamais de `MultipartFile` : dépôt en `application/octet-stream` relayé |
| B-3 | Clé de stockage = UUID généré. Jamais le nom fourni par l'utilisateur |
| B-4 | Taille vérifiée **pendant** la lecture, pas seulement d'après `Content-Length` |
| B-5 | Type de contenu **détecté** par le serveur, jamais celui déclaré |
| B-6 | Aucune transition vers un état servable hors de l'automate du domaine |
| B-7 | Aucun appel sortant sans timeout explicite |
| B-8 | Aucune contrainte SQL reposant sur un prédicat pouvant valoir `UNKNOWN` |
| B-9 | Jamais de découpage d'un fichier pour contourner une limite d'antivirus |
| B-10 | Aucun état local, aucun fichier temporaire partagé : le code ne doit rien supposer d'un nœud unique |
| B-11 | Aucune dépendance ajoutée sans justification écrite |
| B-12 | Le contrat [`../contracts/openapi.yaml`](../contracts/openapi.yaml) fait foi ; un test vérifie la conformité des réponses |

---

## 10. Tests attendus

| Famille | Contenu |
|---|---|
| Domaine (sans Spring) | Automate exhaustif ; **pour chaque état**, `isDownloadable()` — le test échoue si un état est ajouté sans être classé |
| Contraintes SQL | Insertion d'un `AVAILABLE` avec chaque champ de verdict à `NULL` : **toutes échouent** (PostgreSQL réel, Testcontainers) |
| Parcours nominal | Dépôt → analyse → promotion → téléchargement |
| **EICAR** | Fichier infecté → jamais disponible. Reconstruire la chaîne à l'exécution, ne pas l'écrire en clair dans le dépôt |
| Mémoire | Dépôt et téléchargement d'un fichier de 500 Mo, **heap borné mesuré** |
| Concurrence | Deux workers, un seul verdict accepté ; bail expiré ; worker zombie incapable d'écrire |
| Points de panne de la promotion | Les cinq interstices du §5.4 |
| Antivirus indisponible | Ingestion et téléchargement des fichiers déjà disponibles **continuent** |
| Modes de panne de l'antivirus | Silence, lenteur, coupure, corps illisible, `412`, `413`, `406 Heuristics.Limits.Exceeded` — **WireMock**, puisque l'antivirus parle HTTP |
| Sécurité | Traversée de chemin, `Content-Length` mensonger, lecture directe de la quarantaine avec les identifiants de `delivery` |
| Architecture | ArchUnit : `domain` sans Spring, aucune écriture de statut hors domaine |

---

## 10 bis. Charge, ressources et pannes

Les tests de correction ci-dessus ne disent rien de la tenue en charge. Le
plan dédié — **écrit avant le code, délibérément** — est
[`../docs/31-plan-de-tests-charge-et-resilience.md`](../docs/31-plan-de-tests-charge-et-resilience.md).

**Huit exigences de testabilité en découlent, à prévoir dès la conception** —
sans elles, les tests correspondants sont infaisables, pas « à faire plus
tard » :

| # | À prévoir |
|---|---|
| TST-1 | Antivirus bouchon à verdict immédiat — livré : profil `capacity` et `ANTIVIRUS_MODE=instant-clean` (`docs/31` disait « profil `loadtest` ») |
| TST-2 | **Antivirus contrôlable** : WireMock (silence, lenteur, coupure, charabia, `406 Heuristics.Limits.Exceeded`, `412`, `413`) — `docs/31` dit « faux `clamd` TCP », caduc depuis le passage à l'API HTTP |
| TST-3 | Métriques Micrometer nommées, dont `praxedo.queue.oldest_pending_age` (préfixe `ecluse.` de `docs/31` renommé) |
| TST-4 | Toutes les limites externalisées (taille, boucles d'analyse, seuil d'admission, pool, timeouts, bail) |
| TST-5 | Requêtes SQL d'inspection documentées (file, incohérences) |
| TST-6 | Arrêt propre déclenchable, worker tuable indépendamment |
| TST-7 | Journal structuré avec `fileId` |
| TST-8 | Jeu de fichiers générable de 1 Mo à 500 Mo |

## 11. Stack

| Brique | Choix |
|---|---|
| Java | 21 (virtual threads activés) |
| Framework | **Spring Boot 4.1.1**, MVC + virtual threads — pas WebFlux. Starter `spring-boot-starter-webmvc` (`-web` est déprécié en 4.1) |
| Build | Maven **wrapper** (Maven absent de la machine), **module unique** |
| Base | PostgreSQL + Flyway ; **JPA (Spring Data) pour les lectures, `JdbcClient` pour les trois statements qui portent les garanties** — voir `ARCHITECTURE.md` §6.1 |
| Stockage | AWS SDK v2 (S3) ; **SeaweedFS** en local (déjà en place dans `docker-compose.yml`) |
| Antivirus | **API HTTP** d'un conteneur ClamAV dédié (client `RestClient`) ; bouchon à verdict immédiat pour les essais de capacité |
| Détection de type | Renifleur interne sur les 512 premiers octets, compatible flux |
| Tests | JUnit 5, Testcontainers, ArchUnit, **WireMock** |
| API | contrat `contracts/openapi.yaml` écrit à la main et vérifié par un test ; erreurs RFC 9457 |

Versions relevées et justifiées le 2026-09-26 dans
[`ARCHITECTURE.md`](ARCHITECTURE.md) §2 — le point « à vérifier avant de figer »
est clos.

---

## 12. Definition of done

Tous les points sont tenus ; le détail et la preuve de chacun sont dans
[`ARCHITECTURE.md`](ARCHITECTURE.md) §15.

- [x] `docker compose --profile app up -d --build` puis `./mvnw verify`
- [x] Parcours de démonstration : fichier sain → disponible → téléchargé ;
      EICAR → bloqué, jamais servi (`ScanPipelineTest`, `scripts/demo.sh`)
- [x] Antivirus arrêté : l'ingestion et le téléchargement des fichiers déjà
      disponibles fonctionnent toujours (`scripts/demo.sh --resilience`)
- [x] Worker tué en plein traitement : le fichier est repris sans intervention
      (*reaper*, `FilePersistenceTest`)
- [x] Fichier de 500 Mo : heap borné, mesuré (`UploadMemoryTest`, `DownloadMemoryTest`)
- [x] Toutes les contraintes SQL testées avec des `NULL` (`StoredFileConstraintsTest`)
- [x] Conformité au contrat vérifiée par un test (`ContractConformanceTest`)
- [x] ArchUnit au vert (`./mvnw verify`) — pas de CI : exercice, décision du porteur du projet (01/10)
