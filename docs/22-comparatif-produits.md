# 22 — Comparatif des produits, par catégorie

> **Analyse : Claude (Opus 5)** — 2026-09-20 — confrontation : [`CONFRONTATION.md`](CONFRONTATION.md)
>
> **Comparatif écrit avant le code, gardé tel qu'il a été rendu.** Les
> « Verdict » et « Retenu » sont ceux du 20/09. Chaque catégorie se termine par
> un paragraphe **« Livré »**, relu contre le code le 02/10 ; les versions
> livrées et leurs raisons sont au [`README`](../README.md) §8.1.

Pour chaque catégorie : les candidats réels, leurs propriétés **vérifiables**
(pas leurs arguments marketing), le verdict pour ce projet, et le seuil de
bascule.

Colonne « langage » incluse uniquement pour répondre à la question de la
contrainte « écosystème Java » — voir
[`24-contrainte-java-et-dependances.md`](24-contrainte-java-et-dependances.md)
pour pourquoi ce critère n'en est pas un.

---

## 1. Transport des travaux de scan

**Besoin réel** : distribuer des travaux **à durée très variable** (200 ms à
4 min) entre N workers, sans perte, sans double exécution, avec réessai et
reprise après mort d'un worker. **Ce n'est pas un flux d'événements.**

| Produit | Langage | Ack par message | Traitement long | Réessai différé | DLQ | Observabilité | Composant |
|---|---|---|---|---|---|---|---|
| **PostgreSQL (`SKIP LOCKED`)** | C | ✅ par ligne | ✅ sans limite | ✅ colonne | ✅ un état | ✅ **SQL** | **0** |
| RabbitMQ | Erlang | ✅ natif | ✅ `prefetch=1` | 🟠 via DLX + TTL | ✅ natif | 🟠 UI dédiée | 1 |
| **Kafka** | Java/Scala | ❌ offsets séquentiels | ⚠️ `max.poll.interval.ms` | 🟠 topics de retry | 🟠 topic DLT | 🟠 outillage | 1 |
| Redis Streams | C | ✅ `XACK` + PEL | ✅ | 🟠 manuel | 🟠 manuel | 🟠 | 1 |
| ActiveMQ Artemis | **Java** | ✅ | ✅ | ✅ | ✅ | 🟠 | 1 |
| Spring `@Async` (mémoire) | Java | — | ✅ | ❌ | ❌ | ❌ | 0 |

### Le point décisif sur Kafka

Kafka est excellent pour ce pour quoi il est conçu : un journal d'événements
partitionné, ordonné, rejouable, à très haut débit. Trois de ses propriétés
structurelles frottent avec du *job dispatch* à durée variable :

**a) `max.poll.interval.ms`** (défaut 5 minutes)
Si le traitement d'un enregistrement dépasse cet intervalle entre deux appels
à `poll()`, le consommateur est considéré comme mort. Le groupe se rééquilibre,
la partition est réassignée, **et le travail est retraité par un autre
consommateur pendant que le premier le traite encore**.
*Mitigations* : relever la valeur (mais on retarde d'autant la détection des
vrais consommateurs morts), `max.poll.records=1`, ou `pause()`/`resume()` de la
partition pendant le traitement. Toutes viables, aucune évidente.
→ Notre claim atomique ([P-15](20-catalogue-problematiques.md#p-15)) neutralise
le double traitement, mais on aura payé un rééquilibrage pour rien.

**b) Blocage en tête de partition**
Les offsets sont séquentiels. Un fichier de 5 Go qui prend 4 minutes **bloque
tous les fichiers derrière lui sur sa partition**. Un fichier de 10 Ko déposé
après lui attend 4 minutes. C'est le comportement inverse de celui attendu
d'une file de travaux.
*Mitigation* : beaucoup de partitions, ou un partitionnement par classe de
taille. Le parallélisme reste plafonné par le nombre de partitions.

**c) Pas de remise différée native**
`next_attempt_at` en base est une colonne. En Kafka, il faut des topics de
retry (`@RetryableTopic` de Spring Kafka) — trois topics supplémentaires pour
reproduire une colonne.

### Verdict

| Palier | Choix |
|---|---|
| Palier 1 et 2 | **PostgreSQL `SKIP LOCKED`** — aucun composant, sémantique exactement adaptée |
| Palier 3 (si temps) | **Kafka**, en adapter derrière le même port, avec les mitigations ci-dessus explicitées |

**Kafka plutôt que RabbitMQ au palier 3, bien que RabbitMQ convienne mieux
techniquement** : c'est l'outil maîtrisé par le porteur du projet, et un
outil mal maîtrisé se paie au premier incident. Ce critère l'emporte ici sur l'adéquation théorique — et
**c'est un arbitrage à assumer explicitement**, pas à masquer.

> **Recommandation stratégique** : la maîtrise de Kafka se démontre mieux en
> **expliquant pourquoi ses garanties ne conviennent pas à ce cas d'usage**
> qu'en le câblant. Son piège, `max.poll.interval.ms`, est souvent ignoré :
> l'écrire fait la différence.

**Livré** — PostgreSQL, `FOR UPDATE SKIP LOCKED`
([ADR-0001](adr/0001-la-base-de-donnees-fait-file.md)). Aucun broker, aucun
adaptateur Kafka : le palier 3 n'a pas été engagé. La taille maximale d'un
fichier est de 500 Mo, non de plusieurs Go.

---

## 2. Stockage du contenu

**Besoin réel** : écrire et lire en flux des objets de quelques Ko à plusieurs
Go, pouvoir isoler physiquement deux zones, ne jamais charger en mémoire.

| Option | Langage | Streaming | Limite de taille | Isolation | Multi-nœuds | Poids sur les sauvegardes | Composant |
|---|---|---|---|---|---|---|---|
| **Système de fichiers** | — | ✅ natif JDK | système de fichiers | ✅ montages séparés | ❌ sans NFS | ✅ séparé de la base | **0** |
| PostgreSQL `bytea` | C | ❌ en mémoire | **1 Go** | 🟠 | ✅ | ❌ **la base porte tout** | 0 |
| PostgreSQL Large Object | C | ✅ API `LargeObject` | 4 To | 🟠 | ✅ | ❌ idem, + `vacuumlo` | 0 |
| **MinIO** | Go | ✅ | illimité | ✅ IAM | ✅ | ✅ | 1 |
| AWS S3 | — | ✅ | 50 To/objet (5 To avant décembre 2025) | ✅ IAM | ✅ | ✅ | service |

### Pourquoi le stockage en base est écarté

`bytea` est éliminé par sa limite de 1 Go **et** par le fait qu'il est chargé
en mémoire côté JDBC — incompatible avec [P-01](20-catalogue-problematiques.md#p-01).

Les Large Objects streament correctement et montent à 4 To, mais :
- chaque sauvegarde logique de la base embarque **le volume total des
  fichiers** — une base de 500 Go à sauvegarder pour 2 Go de métadonnées ;
- la réplication transporte les mêmes octets ;
- la suppression exige `vacuumlo`, oublié une fois sur deux ;
- ils échappent au modèle de sécurité par ligne.

Le bénéfice serait l'atomicité contenu/métadonnées. Or
[P-10](20-catalogue-problematiques.md#p-10) montre qu'on n'en a pas besoin :
l'ordre des écritures suffit à rendre l'incohérence bénigne.

### Verdict

**Système de fichiers au palier 1**, derrière le port `FileContentStore`,
**adapter S3/MinIO au palier 2**. L'argument d'isolation physique — le plus
fort du projet — **ne nécessite pas MinIO** : deux volumes Docker montés
différemment le produisent
([P-11](20-catalogue-problematiques.md#p-11)).

**Livré** — Verdict **renversé par le cadrage du 21/09** : un **stockage objet
compatible S3 dès le départ** ([`28`](28-precisions-de-cadrage.md) §3). Le
produit est **SeaweedFS**, et non MinIO : le dépôt communautaire de MinIO a été
archivé le 25 avril 2026, et SeaweedFS applique des droits par identité, dont
dépend l'isolation de la quarantaine. Pas de port unique `FileContentStore` :
un port par rôle, trois identités. Objets de 500 Mo au plus.

---

## 3. Base de métadonnées

**Besoin réel** : transactions, contraintes d'unicité, et `FOR UPDATE SKIP
LOCKED`. Ces trois éléments portent l'idempotence et le claim (et l'outbox,
envisagée alors, qui n'existe pas dans le système livré).

| Produit | Langage | Transactions | Unicité | `SKIP LOCKED` | Index partiels | Verdict |
|---|---|---|---|---|---|---|
| **PostgreSQL 16** | C | ✅ | ✅ | ✅ | ✅ | ✅ **retenu** |
| MySQL 8 | C++ | ✅ | ✅ | ✅ | ❌ (index filtrés absents) | ✅ acceptable |
| MariaDB | C | ✅ | ✅ | ✅ | ❌ | ✅ acceptable |
| H2 | **Java** | ✅ | ✅ | ❌ | ❌ | 🟠 tests uniquement |
| HSQLDB | **Java** | ✅ | ✅ | ❌ | ❌ | 🟠 tests uniquement |
| MongoDB | C++ | 🟠 multi-doc depuis 4.0 | 🟠 | ❌ | 🟠 | ❌ on perdrait le claim atomique |

**Retenu : PostgreSQL.** Les index partiels ([P-12](20-catalogue-problematiques.md#p-12))
sont un vrai différenciateur ici : l'index de file reste minuscule quelle que
soit la taille de l'historique.

> Note sur H2 : bien qu'il soit **écrit en Java** — donc seul candidat valide
> sous une lecture littérale de la contrainte — il ne supporte pas
> `SKIP LOCKED`. Illustration concrète que le critère « écrit en Java » ne
> sélectionne pas sur les bonnes propriétés.

**Bascule** : MySQL si c'est le standard de l'entreprise (question à poser).
Les index partiels seraient remplacés par des index composites, légèrement
moins efficaces.

**Livré** — PostgreSQL **17** (image `postgres:17.11-alpine`), avec les
extensions `pg_trgm` et `unaccent` pour la recherche par nom.

---

## 4. Antivirus

| Option | Langage | Local | Déterministe | Compte requis | Confidentialité | Limite de taille |
|---|---|---|---|---|---|---|
| **ClamAV + wrapper REST** | C | ✅ | ✅ EICAR | ❌ | ✅ | `StreamMaxLength` (100 Mo par défaut en 1.4) |
| ClamAV `clamd` TCP `INSTREAM` | C | ✅ | ✅ | ❌ | ✅ | idem |
| **Stub HTTP maison** | Java | ✅ | ✅ | ❌ | ✅ | aucune |
| VirusTotal | — | ❌ | ❌ quotas | ✅ | ❌ **fichiers exposés à des tiers** | 32 Mo (gratuit) |
| Cloudmersive / MetaDefender | — | ❌ | 🟠 | ✅ | 🟠 | variable |
| Windows Defender (`MpCmdRun`) | — | ✅ | 🟠 | ❌ | ✅ | — (non portable, pas une API) |

**Retenu** : **ClamAV via wrapper REST** (adapter réel) **+ stub déterministe**
(tests, CI, démonstration hors ligne). Deux adapters pour un port.

**VirusTotal est écarté pour une raison de sécurité, pas de confort** : les
fichiers soumis y deviennent consultables par des abonnés tiers. Pour des
« documents, rapports, exports » clients, c'est une fuite de données
caractérisée. **C'est un argument central** : il montre qu'on a
réfléchi à ce que « déléguer » implique réellement.

**Détails opérationnels à ne pas négliger** :
- La base de signatures se télécharge au premier démarrage (~300 Mo, plusieurs
  minutes). Sans `healthcheck` ni volume persistant, le premier
  `docker compose up` paraîtra bloqué.
- Les plafonds par défaut du moteur (`StreamMaxLength` et `MaxFileSize` à
  100 Mo, `MaxScanSize` à 400 Mo en version 1.4 ; la première version de ce
  document annonçait 25 Mo) : c'est l'origine de
  [P-19](20-catalogue-problematiques.md#p-19).

**Livré** — ClamAV 1.4.6 derrière `ajilaag/clamav-rest` 0.6.6, dans une image
dérivée ([ADR-0004](adr/0004-antivirus-par-api-http.md)). Les limites ont été
vérifiées en les **mesurant**
([ADR-0005](adr/0005-ordre-des-limites-de-l-antivirus.md)). Le « stub HTTP
maison » détectant EICAR n'a pas été écrit : les tests passent par le vrai
moteur, par WireMock et par un moteur scripté ; le second adaptateur livré
(`InstantCleanAntivirusScanner`, verdict sain immédiat) ne sert qu'aux essais
de capacité.

---

## 5. Résilience des appels sortants

| Option | Timeout | Retry | Backoff | Circuit breaker | Bulkhead | Dépendance |
|---|---|---|---|---|---|---|
| **Aucune bibliothèque** | ✅ client HTTP | ✅ **colonnes de la file** | ✅ colonne | 🟠 portillon maison (~25 l.) | ✅ pool de threads | **0** |
| Spring Retry | ❌ | ✅ | ✅ | ❌ | ❌ | 1 |
| Resilience4j | ✅ | ✅ | ✅ | ✅ | ✅ | 1 |
| Spring Cloud Circuit Breaker | via R4j | ✅ | ✅ | ✅ | ✅ | 2 |

**Retenu : aucune bibliothèque.** Trois des quatre fonctions existent déjà par
construction dans l'architecture A2 :

- **Timeout** : configuration du client HTTP, une ligne.
- **Retry + backoff** : les colonnes `attempts` et `next_attempt_at` sont
  **déjà nécessaires** pour le reaper. Ajouter un retry en mémoire par-dessus
  créerait **deux politiques concurrentes** — une en mémoire, une persistée —
  source de bugs classique et difficile à diagnostiquer.
- **Bulkhead** : la taille du pool de threads du worker *est* la limite de
  concurrence ([P-17](20-catalogue-problematiques.md#p-17)).
- **Circuit breaker** : seule fonction absente, remplacée par un **portillon de
  santé** plus adapté — le worker ne *prend pas* de travail quand l'antivirus
  est dégradé, au lieu d'échouer vite et de consommer des tentatives.
  Voir [P-21](20-catalogue-problematiques.md#p-21).

Ce choix ne tient pas à une préférence : **la bibliothèque serait
redondante**. Elle ajouterait une dépendance sans apporter de garantie que la
file ne fournit pas déjà.

**Bascule** : plusieurs dépendances externes distinctes à protéger.

**Livré** — Aucune bibliothèque. Une correction : la limite de concurrence
n'est **pas** la taille d'un pool de threads — avec les threads virtuels, un
pool ne borne plus des tâches soumises. Elle est tenue par un nombre fixe de
boucles d'analyse (4 par nœud) et, pour les dépôts, par un `Semaphore`
explicite (50 par nœud).

---

## 6. Couche web et modèle de concurrence

| Option | Concurrence I/O | Accès base | Client AV | Débogage | Apprentissage |
|---|---|---|---|---|---|
| **MVC + virtual threads (Java 21)** | ✅ élevée | JDBC standard | bloquant standard | ✅ pile lisible | nul |
| MVC + threads plateforme | 🟠 plafonné | JDBC | bloquant | ✅ | nul |
| WebFlux | ✅ élevée | **R2DBC requis** | doit être réactif | ❌ difficile | élevé |
| WebFlux + virtual threads | — | — | — | — | ❌ **contresens** |

**Retenu : MVC + virtual threads.**

Point à souligner : **virtual threads et
WebFlux sont deux réponses au même problème, pas des compléments.** Les
combiner n'additionne pas leurs bénéfices — un thread virtuel bloqué dans une
chaîne réactive n'apporte rien, et un appel bloquant dans une chaîne réactive
annule le bénéfice du réactif. Loom rend WebFlux largement inutile pour ce cas
d'usage.

Adopter WebFlux imposerait par ailleurs R2DBC (donc renoncer à JDBC, à Flyway
en mode standard, et à une partie de l'écosystème JPA) et un client antivirus
réactif. Le coût total dépasse largement la couche web.

Détail complet : [P-29](20-catalogue-problematiques.md#p-29).

**Livré** — MVC + threads virtuels
([ADR-0010](adr/0010-mvc-et-threads-virtuels.md)). JPA sert aux lectures,
`JdbcClient` aux instructions de la file.

---

## 7. Détection du type réel de contenu

| Option | Langage | Précision | Streaming | Poids |
|---|---|---|---|---|
| **Apache Tika Core** | **Java** | élevée | ✅ premiers octets | ~3 Mo |
| Détection par nombres magiques (maison) | Java | correcte sur les types courants | ✅ | ~30 lignes |
| `Files.probeContentType` (JDK) | Java | ❌ dépend de l'OS, souvent basée sur l'extension | — | 0 |
| Faire confiance au client | — | ❌ | — | 0 |

**Retenu** : Tika Core si le budget le permet, détection maison sinon. Les deux
sont acceptables ; **faire confiance au client ne l'est pas**
([P-27](20-catalogue-problematiques.md#p-27)).

`Files.probeContentType` est un piège : sur la plupart des systèmes, il se
contente de regarder l'extension — donc il fait exactement ce qu'on cherche à
éviter.

**Livré** — La **détection maison** : `ContentSniffer`, une quarantaine de
lignes, sur les 512 premiers octets. **Tika n'a pas été retenu** : le type
détecté n'est qu'une donnée descriptive, le contenu étant toujours servi en
`application/octet-stream`.

---

## 8. Front

| Option | Adéquation |
|---|---|
| **Vite + React 19 + TypeScript** | ✅ imposé par `EX-09`, démarrage rapide, build simple |
| Next.js | ❌ SSR sans intérêt ici, alourdit le compose |
| CRA | ❌ déprécié |
| TanStack Query | ✅ gère polling, cache et états de chargement — exactement le besoin |
| Composants de progression d'upload | `XMLHttpRequest` (événement `progress`) ; `fetch` ne donne pas la progression d'envoi |

Détail à connaître : **`fetch` n'expose pas la progression d'upload**. Pour une
barre de progression fidèle sur un fichier de 1 Go, il faut `XMLHttpRequest`
ou l'API `ReadableStream` en amont. C'est un détail d'implémentation qui
distingue une interface réellement utilisable d'une maquette.

**Livré** — Vite + React 19 + TypeScript, TanStack Query et TanStack Table,
axios (dont la progression d'envoi repose sur `XMLHttpRequest`), Zod,
shadcn/ui et Tailwind. Détail : [`frontend/ARCHITECTURE.md`](../frontend/ARCHITECTURE.md).
Fichiers de 500 Mo au plus.

---

## Récapitulatif des composants retenus

| Palier | Composants | Total |
|---|---|---|
| **1** | application (2 conteneurs, 1 image) + PostgreSQL + ClamAV + front | **3 + front** |
| **2** | + MinIO | 4 + front |
| **3** | + Kafka | 5 + front |

À comparer aux **7 composants** de ma première analyse. La réduction vient de
trois décisions : pas de broker ([P-13](20-catalogue-problematiques.md#p-13)),
pas de bibliothèque de résilience ([P-21](20-catalogue-problematiques.md#p-21)),
et stockage objet reporté au palier 2
([P-08](20-catalogue-problematiques.md#p-08)).

**Livré** — application (**un** conteneur) + PostgreSQL + stockage objet
(SeaweedFS) + ClamAV + Keycloak + interface ; Prometheus et Grafana pour la
supervision. Ni Kafka, ni MinIO. Le stockage objet et Keycloak sont entrés
par le cadrage du 21/09 et par décision du porteur du projet.
