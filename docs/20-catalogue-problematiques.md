# 20 — Catalogue des problématiques

> **Analyse : Claude (Opus 5)** — 2026-09-20 — confrontation : [`CONFRONTATION.md`](CONFRONTATION.md)
>
> **Analyse écrite avant le code, gardée telle qu'elle a été rendue.** Les
> « Réponse » sont les recommandations du 20/09 ; certaines ont été révisées
> par le cadrage du 21/09, par la confrontation ou par les mesures. Chaque
> problématique se termine donc par un paragraphe **« Livré »**, relu contre
> le code le 02/10, qui dit ce que le système fait réellement. En cas d'écart,
> c'est ce paragraphe — et [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md)
> — qui fait foi.

Format imposé : **Problème → Question → Options → Réponse → Ce qui ferait
basculer la réponse**. La dernière rubrique est la plus importante : une
recommandation sans seuil de bascule est un dogme, pas une décision
d'architecture.

Les problématiques sont classées **par concern** (séparation des
responsabilités), pas par ordre d'implémentation.

## Index

| Concern | Problématiques |
|---|---|
| **A. Ingestion** (API d'entrée) | [P-01](#p-01) · [P-02](#p-02) · [P-03](#p-03) · [P-04](#p-04) · [P-05](#p-05) · [P-06](#p-06) · [P-07](#p-07) |
| **B. Persistance** (contenu + état) | [P-08](#p-08) · [P-09](#p-09) · [P-10](#p-10) · [P-11](#p-11) · [P-12](#p-12) |
| **C. Déclenchement** (file de travail) | [P-13](#p-13) · [P-14](#p-14) · [P-15](#p-15) · [P-16](#p-16) · [P-17](#p-17) |
| **D. Analyse** (antivirus) | [P-18](#p-18) · [P-19](#p-19) · [P-20](#p-20) · [P-21](#p-21) · [P-22](#p-22) · [P-23](#p-23) |
| **E. Service** (téléchargement) | [P-24](#p-24) · [P-25](#p-25) · [P-26](#p-26) · [P-27](#p-27) · [P-28](#p-28) |
| **F. Transverse** | [P-29](#p-29) · [P-30](#p-30) · [P-31](#p-31) · [P-32](#p-32) · [P-33](#p-33) |

---

# A. Concern « Ingestion »

**Responsabilité** : accepter des octets, les persister durablement, en
enregistrer l'existence, et rendre la main. **Rien d'autre.** En particulier :
l'ingestion ne scanne pas, ne décide pas, ne promeut pas.

<a id="p-01"></a>
## P-01 — Où passent les octets pendant l'upload ?

**Problème** — Un fichier de 2 Go traverse l'application. Selon l'implémentation,
il peut se retrouver intégralement en mémoire (OOM), intégralement sur disque
(I/O doublées), ou ne jamais être matérialisé (flux).

**Question** — Quel chemin les octets empruntent-ils entre la socket HTTP et le
stockage final ?

| Option | Mécanisme | Mémoire | I/O disque | Verdict |
|---|---|---|---|---|
| `@RequestParam MultipartFile` | Spring bufferise en mémoire puis sur disque au-delà du seuil | seuil | **×2** (écriture temp + relecture) | ❌ |
| `byte[]` / `String` en paramètre | Tout en mémoire | **taille du fichier** | 0 | ❌ OOM garanti |
| `HttpServletRequest.getInputStream()` relayé | Flux direct socket → stockage | **constante (8 Ko)** | ×1 | ✅ |
| Commons FileUpload *streaming* (itérateur de parts) | Idem, pour du multipart | constante | ×1 | ✅ |

**Réponse** — **Deux points d'entrée, tous deux en flux** :
- `application/octet-stream` + métadonnées en en-têtes → destiné aux systèmes
  tiers et aux gros fichiers. `request.getInputStream()` relayé directement.
- `multipart/form-data` → confort navigateur, traité par l'API *streaming*
  de Commons FileUpload, **jamais** par `MultipartFile`.

Dans les deux cas le flux traverse un `DigestInputStream` (SHA-256) et un
compteur d'octets, sans coût mémoire supplémentaire.

**Bascule** — Aucune. C'est la traduction directe de `EX-07` ; `MultipartFile`
resterait acceptable uniquement si l'on plafonnait les fichiers à quelques Mo,
ce que l'énoncé interdit.

**Livré** — **Un seul point d'entrée** : `application/octet-stream`, corps brut lu en flux, nom du fichier dans l'en-tête `X-File-Name`. Le point d'entrée `multipart/form-data` n'a pas été fait : l'analyse multipart est désactivée (`spring.servlet.multipart.enabled=false`), Commons FileUpload n'est pas une dépendance, et l'interface envoie elle aussi le corps brut. Le flux traverse `InspectingInputStream` (SHA-256 et compteur d'octets), avec un tampon de 64 Kio. La taille maximale est de 500 Mo.

---

<a id="p-02"></a>
## P-02 — Comment se protéger d'une taille annoncée mensongère ?

**Problème** — `Content-Length` est déclaré par le client. Un client hostile
annonce 1 Ko et envoie 100 Go. Un client en `Transfer-Encoding: chunked`
n'annonce rien du tout.

**Question** — Comment appliquer un plafond de taille quand on ne peut pas
faire confiance à ce qui est annoncé ?

| Option | Efficacité |
|---|---|
| Vérifier `Content-Length` avant de lire | ❌ contournable, et absent en chunked |
| Limite du conteneur (`max-http-request-size`) | 🟠 utile, mais coupe brutalement, sans réponse propre |
| **Compter les octets pendant la lecture, avorter au dépassement** | ✅ seule méthode fiable |

**Réponse** — Un `InputStream` décorateur qui compte et lève une exception
dédiée au franchissement du plafond → réponse `413` propre, objet partiel
supprimé du stockage. Le `Content-Length` déclaré sert uniquement de
**pré-filtre** (rejet immédiat s'il est déjà trop grand) et de **contrôle de
cohérence** a posteriori (déclaré ≠ réel → rejet, journalisé comme suspect).

**Bascule** — Aucune.

**Livré** — `Content-Length` est **obligatoire** : sans lui (envoi par morceaux), `411 LENGTH_REQUIRED`. Annoncé au-delà de 500 Mo : `413 FILE_TOO_LARGE` avant de lire un octet. Les octets sont comptés pendant la lecture : un corps plus court ou plus long qu'annoncé donne `400 CONTENT_LENGTH_MISMATCH`, et l'objet est supprimé.

---

<a id="p-03"></a>
## P-03 — Que se passe-t-il si le client coupe en plein upload ?

**Problème** — Coupure réseau à 80 % d'un fichier de 3 Go. Des octets ont été
écrits dans le stockage. Aucune métadonnée n'existe encore.

**Question** — Comment éviter d'accumuler des fragments, et surtout d'exposer
un fichier tronqué ?

| Option | Conséquence |
|---|---|
| Écrire les métadonnées d'abord, le contenu ensuite | ❌ une ligne visible pointe vers un contenu inexistant ou partiel |
| **Écrire le contenu d'abord, commiter les métadonnées ensuite** | ✅ en cas de coupure : un objet orphelin, invisible et inoffensif |
| Écriture temporaire puis renommage atomique | ✅ complément : le fichier n'apparaît jamais partiel à son emplacement final |

**Réponse** — Ordre strict : **contenu → commit métadonnées → réponse**, avec
écriture sous nom temporaire puis renommage atomique (`Files.move` avec
`ATOMIC_MOVE`, ou upload multipart S3 dont l'objet n'apparaît qu'à la
complétion). Un balayage périodique supprime les orphelins de plus de N heures.

Principe généralisable : **en panne partielle, préférer l'orphelin invisible à
la référence brisée visible.**

**Bascule** — Aucune. C'est un invariant de conception, pas une préférence.

**Livré** — L'ordre contenu → ligne → réponse est tenu. Il n'y a **pas** de nom temporaire ni de renommage : l'objet est écrit par un `PutObject` sous sa clé définitive (un UUID) dans la quarantaine, et n'y apparaît qu'une fois complet. Le balayage (toutes les 10 min) supprime les orphelins de plus de deux heures.

---

<a id="p-04"></a>
## P-04 — Comment rendre l'upload rejouable sans doublon ?

**Problème** — Un système tiers poste 500 Mo, le réseau coupe **après**
l'écriture mais **avant** la réponse. Il retente. Sans protection : deux
fichiers, deux scans, deux entrées.

**Question** — Comment reconnaître un rejeu ?

| Option | Limite |
|---|---|
| Dédupliquer par nom de fichier | ❌ deux fichiers différents peuvent porter le même nom |
| Dédupliquer par empreinte du contenu | 🟠 un même contenu peut légitimement être déposé deux fois |
| **En-tête `Idempotency-Key` fourni par le client** | ✅ standard de fait, explicite, sans ambiguïté |

**Réponse** — `Idempotency-Key` + table `(tenant, key) → snapshot de réponse`,
TTL 24 h, contrainte d'unicité en base. Rejeu → **on renvoie la réponse
d'origine à l'identique**. Même clé avec un contenu différent → `422`.
Détail complet : [`04-idempotence.md`](04-idempotence.md) §1.

**Bascule** — Si l'API n'était destinée qu'à un navigateur, on pourrait s'en
passer. `EX-02` mentionne explicitement les « systèmes tiers » : on ne s'en
passe pas.

**Livré** — `Idempotency-Key`, table `(owner_id, clé)`, 24 h, unicité en base. Deux écarts : le rejeu rend **le fichier du premier dépôt dans son état courant**, pas un instantané de la réponse d'origine ; et l'empreinte de la requête porte sur le **nom et la taille annoncée**, pas sur le contenu — `422` pour un autre nom ou une autre taille. Détail : [`04-idempotence.md`](04-idempotence.md) §1.

---

<a id="p-05"></a>
## P-05 — Comment garantir que tout fichier reçu sera bien analysé ?

**Problème** — Le commit des métadonnées et la publication du travail de scan
sont deux opérations. Un crash entre les deux laisse un fichier en quarantaine
que **personne ne scannera jamais**. Il ne sera pas servi (l'invariant tient),
mais il ne sera jamais disponible non plus — panne silencieuse.

**Question** — Comment rendre atomiques « le fichier existe » et « le travail
est à faire » ?

| Option | Garantie |
|---|---|
| Publier dans un broker après le commit | ❌ fenêtre de perte |
| Transaction distribuée (XA) | ❌ complexité disproportionnée |
| **Outbox : le travail est une ligne écrite dans la même transaction** | ✅ atomicité native du SGBD |
| **Reaper : balayage des travaux anciens non traités** | ✅ filet de sécurité, indépendant du transport |

**Réponse** — **Les deux**, ils ne se remplacent pas. L'outbox garantit qu'aucun
travail n'est perdu au moment de la création ; le reaper garantit qu'aucun
travail n'est perdu **ensuite** (worker mort, message perdu, bug).

Le reaper est ce qui transforme « ça marche » en « c'est exploitable » : le
système se répare seul, quel que soit le mode de panne.

**Bascule** — Aucune sur l'outbox. Le reaper pourrait être une commande
manuelle en dernier recours, mais son coût est d'une requête planifiée.

**Livré** — **Pas d'outbox** : il n'y a pas de travail à publier. La ligne du fichier **est** le travail (`AWAITING_SCAN`), écrite dans une seule transaction ; « le fichier existe » et « le travail est à faire » sont le même fait. Le *reaper* est livré (toutes les 30 s).

---

<a id="p-06"></a>
## P-06 — Que répond l'API au terme de l'upload ?

**Problème** — La ressource existe mais n'est pas utilisable. `201 Created`
laisserait croire qu'elle est prête.

**Question** — Quel contrat de réponse ?

| Option | Sémantique |
|---|---|
| `200 OK` | ❌ ne dit rien de l'état |
| `201 Created` | 🟠 exact pour la métadonnée, trompeur pour l'usage |
| **`202 Accepted`** | ✅ « reçu, traitement en cours » — exactement la situation |

**Réponse** — `202 Accepted` + `Location` + corps portant `status`,
`downloadable: false` et les liens de suivi. Le code de statut **porte
l'invariant dès le contrat d'API** : l'appelant apprend dès la première réponse
qu'une étape reste à franchir.

**Bascule** — Si un mode synchrone optionnel était ajouté
([P-22](#p-22) / `D-06`), il répondrait `201` avec le verdict inclus.

**Livré** — `202 Accepted` + `Location` + `Retry-After`, corps portant `status: PENDING`, `downloadable: false` et les liens. Aucun mode synchrone (`D-06`).

---

<a id="p-07"></a>
## P-07 — Comment empêcher l'ingestion de noyer le reste du système ?

**Problème** — L'ingestion est rapide, l'analyse est lente. Si l'on accepte sans
limite, la file grandit indéfiniment, le stockage se remplit de fichiers jamais
analysés, et le délai de mise à disposition devient infini.

**Question** — Où placer la contre-pression, et sous quelle forme ?

| Option | Effet |
|---|---|
| Ne rien faire | ❌ dégradation illimitée et invisible |
| Limiter le débit d'entrée (req/s) | 🟠 protège l'API, pas la file |
| **Refuser au-delà d'une profondeur de file** | ✅ protège la promesse de délai |
| **Quotas par tenant** | ✅ empêche un client d'affamer les autres |

**Réponse** — Trois garde-fous cumulés : limite de débit par principal,
**`429` + `Retry-After` quand la profondeur de file ou l'âge du plus vieux
travail dépasse un seuil**, et quotas par tenant (volume total, nombre de
fichiers en attente).

Le second est le plus important et le plus rarement implémenté : il traduit un
objectif de service (« un fichier est disponible en moins de N minutes ») en
règle d'admission.

**Bascule** — Aucune, mais les seuils sont à calibrer une fois la volumétrie
connue (cadrage du 21/09, [`28`](28-precisions-de-cadrage.md) §2).

**Livré** — Deux bornes, appliquées avant la lecture du corps : `429 TOO_MANY_PENDING_FILES` à partir de 500 fichiers en attente (le **nombre**, pas l'âge du plus vieux), et `429 TOO_MANY_CONCURRENT_UPLOADS` au-delà de 50 dépôts simultanés par nœud. **Ni limite de débit par appelant, ni quota par utilisateur** : limitations acceptées, au README §10.

---

# B. Concern « Persistance »

**Responsabilité** : conserver les octets, et conserver l'**état de vérité**
du système (quel fichier, dans quel état, avec quel verdict). Ces deux rôles
sont **distincts** et peuvent être portés par des technologies différentes.

<a id="p-08"></a>
## P-08 — Où stocke-t-on les octets ?

**Problème** — Le contenu peut faire de quelques Ko à plusieurs Go, doit être
lisible en flux par le worker et par le service, et doit pouvoir être isolé.

**Question** — Base de données, système de fichiers, ou stockage objet ?

| Option | Composant ajouté | Streaming | Isolation | Multi-nœuds | Sauvegarde |
|---|---|---|---|---|---|
| **Base — `bytea`** | aucun | ❌ chargé en mémoire, **limite 1 Go** | 🟠 | ✅ | ❌ alourdit tout |
| **Base — Large Object** | aucun | ✅ (API `LargeObject`, jusqu'à 4 To) | 🟠 | ✅ | ❌ alourdit tout, `vacuumlo` requis |
| **Système de fichiers** | **aucun** | ✅ natif JDK | ✅ par montages séparés | ❌ nécessite NFS/partage | ✅ simple |
| **Stockage objet (MinIO/S3)** | 1 | ✅ | ✅ par IAM | ✅ | ✅ |

> ⚠️ **Révisé le 2026-09-21 par le cadrage** : le stockage doit être pensé
> comme partagé, de type objet (S3), plutôt que sur disque local.
> **Le stockage objet est donc retenu dès le départ**, derrière le port
> `FileContentStore`. L'isolation passe alors par deux emplacements et deux
> jeux d'identifiants, et non plus par des montages Docker.
> Voir [`28-precisions-de-cadrage.md`](28-precisions-de-cadrage.md) §3.

**Réponse initiale (caduque)** — Système de fichiers au palier 1, derrière le
port `FileContentStore`, avec un adapter S3 au palier 2.

Justification : le système de fichiers couvre le streaming, permet
l'isolation par montages Docker distincts (voir [P-11](#p-11)), n'ajoute aucun
composant, et le port rend le passage à S3 réversible en une ligne de
configuration. Stocker les octets **en base est écarté** : cela alourdit chaque
sauvegarde et chaque réplication du volume total des fichiers, pour un bénéfice
transactionnel dont on n'a pas besoin (voir [P-10](#p-10)).

**Bascule** — Passage à S3 dès que : plusieurs nœuds d'application doivent
partager le stockage sans NFS, **ou** le volume dépasse ce qu'un volume unique
peut porter, **ou** l'on veut décharger la bande passante de téléchargement par
URL présignée ([P-25](#p-25)).

**Livré** — Stockage objet compatible S3 (SeaweedFS en local). Pas de port unique `FileContentStore` : **un port par rôle** (`QuarantineWriter`, `WorkerStorage`, `ServableReader`), chacun adossé à sa propre identité. Deux zones, **trois** identités (`ingest`, `worker`, `delivery`) — deux ne suffisaient pas ([`26`](26-reponse-a-la-contre-analyse.md)).

---

<a id="p-09"></a>
## P-09 — Où et comment enregistre-t-on l'état de scan ?

**Problème** — C'est **le cœur du système** : savoir, de façon fiable et
concurrente, si un fichier a été analysé et avec quel résultat. Point
insuffisamment traité dans ma première analyse.

**Question** — Quel moteur, et quel schéma ?

| Option | Transactions | Contraintes d'unicité | `SKIP LOCKED` | Verdict |
|---|---|---|---|---|
| **PostgreSQL** | ✅ | ✅ | ✅ | ✅ |
| MySQL 8 | ✅ | ✅ | ✅ | ✅ équivalent, préférence à l'usage |
| H2 (Java, embarqué) | ✅ | ✅ | ❌ | 🟠 tests seulement |
| MongoDB | 🟠 | 🟠 | ❌ | ❌ on perdrait l'atomicité du claim |
| En mémoire | ❌ | ❌ | ❌ | ❌ |

**Réponse** — **PostgreSQL**. Ce n'est pas un choix de confort : trois
mécanismes du système reposent directement sur des garanties du SGBD —
l'outbox transactionnel ([P-05](#p-05)), le claim atomique d'un travail
([P-15](#p-15)), et l'unicité de la clé d'idempotence ([P-04](#p-04)). Les
reconstruire applicativement au-dessus d'un moteur non transactionnel
introduirait des courses impossibles à tester.

**Le schéma complet (DDL, index, requêtes critiques) fait l'objet d'un document
dédié : [`23-modele-de-donnees.md`](23-modele-de-donnees.md).**

**Bascule** — MySQL si c'est le standard de l'entreprise (question à poser).
Aucune bascule vers un moteur non transactionnel.

**Livré** — PostgreSQL. Des trois mécanismes cités, deux reposent sur la base (le claim atomique, l'unicité de la clé d'idempotence) ; l'outbox n'existe pas ([P-05](#p-05)). Le schéma livré est dans [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §6.3 ; celui de [`23`](23-modele-de-donnees.md) est remplacé.

---

<a id="p-10"></a>
## P-10 — Comment garder cohérents les octets et leur état ?

**Problème** — Deux systèmes de stockage distincts (fichiers + base) ne peuvent
pas être écrits dans une transaction unique.

**Question** — Faut-il une transaction distribuée ?

| Option | Coût | Verdict |
|---|---|---|
| Transaction XA | élevé, fragile | ❌ |
| Tout mettre en base pour n'avoir qu'un système | sauvegardes lourdes | ❌ cf. [P-08](#p-08) |
| **Ordonner les écritures pour que l'incohérence soit bénigne** | nul | ✅ |

**Réponse** — Pas de transaction distribuée. On **ordonne** :
contenu d'abord, métadonnées ensuite. Les deux incohérences possibles sont
alors :

| Incohérence | Gravité | Traitement |
|---|---|---|
| Octets sans métadonnée (orphelin) | **bénigne** — invisible, non référencé, en quarantaine | balayage périodique |
| Métadonnée sans octets | **grave** — entrée visible, téléchargement incompréhensible | **rendue impossible par l'ordre** |

C'est un exemple de cohérence à terme choisie délibérément : on n'élimine pas
l'incohérence, on la **dirige vers sa forme inoffensive**.

**Bascule** — Aucune.

**Livré** — Tel quel : contenu d'abord, ligne ensuite, orphelins balayés.

---

<a id="p-11"></a>
## P-11 — Comment empêcher *physiquement* la lecture d'un fichier non analysé ?

**Problème** — Une garde applicative (`if (status != CLEAN) refuser`) tombe avec
le bug qui la contourne. L'énoncé dit « **aucun** » : il faut une garantie qui
survive à un bug.

**Question** — Peut-on obtenir une isolation qui ne dépende pas du code ?

| Option | Niveau de défense | Composant requis |
|---|---|---|
| Garde applicative seule | 1 | aucun |
| Garde + contrôle d'autorisation séparé | 2 | aucun |
| **+ le processus qui sert n'a pas accès physique à la quarantaine** | **3** | aucun (montages Docker) |
| + politique IAM sur bucket séparé | 3 | MinIO/S3 |

**Réponse** — **Trois niveaux, sans composant supplémentaire.** Le conteneur
qui sert les fichiers ne monte pas le volume de quarantaine :

```yaml
api:                                   # rôle : servir
  volumes:
    - servable:/data/servable:ro       # lecture seule, et rien d'autre
worker:                                # rôle : analyser et promouvoir
  volumes:
    - quarantine:/data/quarantine
    - servable:/data/servable
```

Démonstration, en une commande :
`docker exec api ls /data/quarantine` → *No such file or directory*.

Même avec un `status` corrompu en base, même avec la garde applicative
supprimée, le processus qui sert **ne peut pas atteindre les octets**.

**Bascule** — Avec S3 (palier 2), la même propriété s'obtient par deux jeux de
credentials et une politique IAM par préfixe. Propriété identique, composant en
plus. À noter : la version précédente de mon analyse considérait MinIO comme
**nécessaire** à cet argument — c'était une erreur.

**Livré** — La propriété est tenue par le **stockage objet**, pas par des montages Docker : un seul processus porte les trois rôles ([ADR-0002](adr/0002-un-processus-trois-identites.md)), et l'identité `delivery`, seule utilisée par le chemin de téléchargement, n'a aucun droit sur la zone de quarantaine. `ObjectStorageTest` le vérifie : la lecture est refusée par le stockage.

---

<a id="p-12"></a>
## P-12 — Comment interroger la file sans écrouler la base ?

**Problème** — Le worker demande en boucle « quels travaux sont à faire ? ». Une
requête mal indexée sur une table de plusieurs millions de lignes, exécutée
toutes les secondes par M workers, devient le goulot d'étranglement.

**Question** — Quelle indexation, quelle fréquence ?

| Option | Coût |
|---|---|
| `SELECT * WHERE status='AWAITING_SCAN'` sans index | ❌ balayage complet |
| Index sur `status` seul | 🟠 peu sélectif (la plupart des lignes finissent `CLEAN`) |
| **Index partiel sur les seuls états actifs** | ✅ index minuscule, constant dans le temps |
| Polling adaptatif (backoff quand la file est vide) | ✅ complément |

**Réponse** — **Index partiel** : l'index ne contient que les lignes en attente,
soit typiquement quelques dizaines de lignes, quel que soit le volume
historique.

```sql
CREATE INDEX idx_scan_queue ON stored_file (next_attempt_at)
  WHERE status IN ('AWAITING_SCAN', 'SCANNING');
```

Plus un polling adaptatif : 200 ms quand la file est active, jusqu'à 5 s quand
elle est vide. DDL complet et autres index en
[`23-modele-de-donnees.md`](23-modele-de-donnees.md).

**Bascule** — Si le polling devenait mesurablement coûteux (à surveiller via
`pg_stat_statements`), on passerait à `LISTEN`/`NOTIFY` (PostgreSQL, sans
composant supplémentaire) avant d'envisager un broker.

**Livré** — Index partiels : `idx_file_queue` sur `(next_attempt_at, uploaded_at, id)` pour les états `AWAITING_SCAN` et `RETRY_WAIT`, `idx_file_lease` sur `lease_expires_at` pour `SCANNING` et `PROMOTING`. Le polling n'est pas adaptatif : une boucle qui vient de traiter un fichier reprend aussitôt ; file vide, elle attend 2 s (`praxedo.worker.poll-interval`).

---

# C. Concern « Déclenchement »

**Responsabilité** : transporter un travail de l'ingestion vers un analyste,
exactement une fois en effet, sans perte ni double exécution.

<a id="p-13"></a>
## P-13 — Faut-il un transport distinct de la base ?

**Problème** — La question du broker est souvent traitée comme allant de soi.
Elle mérite d'être posée à l'envers : **qu'est-ce que la base ne sait pas
faire ?**

**Question** — Quelles fonctions attend-on du transport ?

| Fonction attendue | La base seule la fournit-elle ? |
|---|---|
| Ne pas perdre un travail | ✅ (persistance + transaction) |
| Distribuer entre N workers | ✅ (`FOR UPDATE SKIP LOCKED`) |
| Réessayer avec délai | ✅ (colonnes `attempts`, `next_attempt_at`) |
| Mettre de côté après N échecs | ✅ (un état, une requête) |
| Observer la file | ✅ **mieux** qu'un broker : c'est du SQL |
| Notifier sans attendre le polling | 🟠 `LISTEN`/`NOTIFY` |
| Découpler plusieurs consommateurs métier | ❌ |
| Rejouer un historique d'événements | ❌ |
| Absorber des dizaines de milliers de msg/s | ❌ |

**Réponse** — À l'échelle de cet exercice, **les trois fonctions que la base ne
couvre pas ne sont pas requises**. Le broker serait un composant ajouté pour
des besoins absents.

**Bascule** — Un broker devient justifié dès qu'**un second consommateur
métier** apparaît (indexation, notification, facturation), ou au-delà de
~1 000 travaux/s, ou si le rejeu d'historique devient une exigence.

**Livré** — Aucun broker ([ADR-0001](adr/0001-la-base-de-donnees-fait-file.md)), confirmé par le cadrage du 21/09.

---

<a id="p-14"></a>
## P-14 — Si l'on prend un broker, lequel ?

**Problème** — Kafka est l'outil maîtrisé par le porteur du projet. Il faut
déterminer honnêtement s'il convient à cet usage.

**Question** — Kafka, RabbitMQ, Redis, Artemis, ou la base ?

Le comparatif détaillé est en
[`22-comparatif-produits.md`](22-comparatif-produits.md) §1. Résumé du point
décisif :

> Le besoin ici est du **job dispatch à durée très variable** (un scan peut
> durer 200 ms ou 4 minutes selon la taille). Ce n'est pas un flux
> d'événements.

Les trois frictions de Kafka sur ce cas précis, toutes vérifiables :

| Friction | Mécanisme | Mitigation possible | Coût de la mitigation |
|---|---|---|---|
| **`max.poll.interval.ms`** (5 min par défaut) : un traitement plus long fait considérer le consommateur comme mort → rééquilibrage → retraitement | conception de Kafka | relever la valeur, `max.poll.records=1`, ou `pause()`/`resume()` de la partition | configuration fine, non évidente, et une valeur haute retarde la détection des vrais consommateurs morts |
| **Blocage en tête de partition** : un fichier de 5 Go bloque tous les fichiers derrière lui sur la même partition | offsets séquentiels | beaucoup de partitions, ou partitionnement par taille | parallélisme plafonné par le nombre de partitions |
| **Pas d'acquittement par message** ni de remise différée native | offsets séquentiels | `@RetryableTopic` (topics de retry non bloquants) | 3 topics supplémentaires pour ce que `next_attempt_at` fait en une colonne |

**Réponse** — **Aucun broker au palier 1.** Pour le palier 3, si un broker est
introduit, RabbitMQ conviendrait techniquement mieux (acquittement par message,
`prefetch=1`, DLX natif) mais **Kafka reste le bon choix pour ce projet**, pour
une raison qui n'est pas technique : c'est l'outil maîtrisé, et un outil mal
maîtrisé se paie au premier incident.

**Recommandation stratégique** : ne pas implémenter Kafka, et **écrire cette
analyse** dans le README.

> Expliquer *pourquoi les garanties de Kafka ne correspondent pas au job
> dispatch à durée variable* démontre une maîtrise bien supérieure à celle que
> démontrerait le fait de le câbler. Le piège de `max.poll.interval.ms` est
> souvent ignoré : le connaître et l'écrire fait la différence.

**Bascule** — Si le palier 3 est atteint avec du temps disponible :
implémenter un **adapter Kafka derrière le même port**, activable par profil.
Deux adapters pour un port, avec le seuil de bascule documenté, est la
meilleure démonstration possible.

**Livré** — Aucun broker, et aucun adaptateur Kafka : le palier 3 de [`25`](25-paliers-de-perimetre.md) n'a pas été engagé. La file est le port `FileWorkQueue`, adossé à la base.

---

<a id="p-15"></a>
## P-15 — Comment éviter que deux workers traitent le même fichier ?

**Problème** — M workers interrogent la même file. Sans exclusion, le même
fichier est scanné plusieurs fois (coût) et deux verdicts concurrents peuvent
s'écraser (corruption d'état).

**Question** — Quel mécanisme d'exclusion, sans verrou distribué ?

| Option | Problème |
|---|---|
| `synchronized` / verrou JVM | ❌ ne franchit pas la frontière du processus |
| Verrou distribué (Redis, ZooKeeper) | ❌ composant ajouté, cas limites nombreux |
| `SELECT … FOR UPDATE` | 🟠 les workers se bloquent mutuellement |
| **`SELECT … FOR UPDATE SKIP LOCKED`** | ✅ chaque worker prend une ligne différente, sans attente |
| **`UPDATE … WHERE status = 'AWAITING_SCAN'`** | ✅ atomique ; 0 ligne affectée = déjà pris |

**Réponse** — Les deux combinés : `SKIP LOCKED` pour la sélection, transition
conditionnelle pour la prise, et `lease_expires_at` pour la reprise après
panne.

```sql
UPDATE stored_file SET status = 'SCANNING',
       lease_holder = :workerId,
       lease_expires_at = now() + interval '10 minutes',
       attempts = attempts + 1
 WHERE id = (SELECT id FROM stored_file
              WHERE status = 'AWAITING_SCAN' AND next_attempt_at <= now()
              ORDER BY next_attempt_at
              FOR UPDATE SKIP LOCKED LIMIT 1)
RETURNING *;
```

Une seule requête fait : sélection, exclusion mutuelle, prise de bail et
comptage de tentatives. **C'est le cœur technique du système**, et il tient en
dix lignes sans aucun composant supplémentaire.

**Bascule** — Aucune. Ce motif reste valide même si un broker est ajouté plus
tard : il protège contre la redélivraison, que tout broker pratique.

**Livré** — Une seule instruction : sélection `FOR UPDATE SKIP LOCKED` dans une CTE, puis `UPDATE … RETURNING`. Trois différences avec la requête ci-dessus : le claim reprend aussi les fichiers en `RETRY_WAIT` et s'arrête à `attempts < max` (sinon un travail terminal est repris à l'infini) ; il pose un **jeton de bail** unique (`lease_token`) ; et le bail est **proportionnel à la taille** (30 s + 1,2 s par Mio), calculé sur l'horloge de la base.

---

<a id="p-16"></a>
## P-16 — Que se passe-t-il si un worker meurt en plein scan ?

**Problème** — Le worker a pris le travail (`SCANNING`), puis son conteneur est
tué. Le fichier reste indéfiniment en `SCANNING` : jamais servi, jamais repris.

**Question** — Comment détecter et reprendre ?

| Option | Limite |
|---|---|
| Heartbeat du worker | composant de surveillance à écrire |
| Détection par le broker | dépend d'un broker, et mal adapté aux traitements longs |
| **Bail à expiration + balayage** | ✅ aucune coordination, aucun composant |

**Réponse** — Chaque prise pose un `lease_expires_at`. Un balayage périodique
repasse en `AWAITING_SCAN` toute ligne `SCANNING` dont le bail a expiré. La
durée du bail doit dépasser le p99 de la durée de scan, sinon un scan lent mais
sain se fait voler son travail.

Complément indispensable : l'écriture du verdict est conditionnée à
`lease_holder = :workerId`, pour qu'un **worker zombie** (bail expiré, travail
déjà repris) ne puisse pas écraser le verdict du worker légitime.

**Bascule** — Aucune. C'est ce mécanisme qui rend le système auto-réparant.

**Livré** — Le *reaper* passe toutes les 30 s : un bail expiré, en `SCANNING` comme en `PROMOTING`, renvoie le fichier en `RETRY_WAIT` avec un délai (ou en `FAILED_FINAL` si les tentatives sont épuisées), et non en `AWAITING_SCAN`. L'écriture du verdict est conditionnée au **jeton de la prise** (`lease_token`), pas à `lease_holder` : un worker gelé peut reprendre lui-même son fichier, et son ancien fil d'exécution écraserait alors le verdict — défaut corrigé lors de la confrontation.

---

<a id="p-17"></a>
## P-17 — Combien de scans en parallèle ?

**Problème** — L'antivirus est la ressource rare. Trop de workers le saturent ;
trop peu allongent la file. La limite est **côté antivirus**, pas côté worker.

**Question** — Où placer la limite de parallélisme ?

| Option | Effet |
|---|---|
| Nombre de réplicas de worker | 🟠 grossier, lent à ajuster |
| **Taille du pool de threads du worker** | ✅ limite naturelle et gratuite |
| Sémaphore autour de l'appel AV | ✅ si plusieurs pools appellent l'AV |
| Bibliothèque de type *bulkhead* | ❌ redondant avec les deux précédents |

**Réponse** — La **taille du pool de threads du worker est le bulkhead**. Un
pool de taille N ⇒ au plus N scans concurrents par instance, donc N × réplicas
appels concurrents à l'antivirus. Aucune bibliothèque nécessaire.

Dimensionnement : ClamAV traite typiquement un scan par thread ; commencer à
`N = nombre de threads clamd / nombre de réplicas`, puis ajuster à la mesure.

**Bascule** — Si plusieurs chemins de code appelaient l'antivirus, un
`Semaphore` (JDK) partagé deviendrait nécessaire. Ce n'est pas le cas ici.

**Livré** — ⚠️ « La taille du pool est le *bulkhead* » est **faux** avec les threads virtuels pour des tâches soumises : un pool n'y borne plus rien (défaut corrigé lors de la confrontation). La borne livrée est un **nombre fixe de boucles d'analyse** (4 par nœud, `ScanWorkerPool`), chacune menant une analyse à la fois. Les dépôts, eux, sont bornés par un `Semaphore` explicite (50 par nœud).

---

# D. Concern « Analyse »

**Responsabilité** : obtenir un verdict pour un contenu donné, ou échouer
proprement. Ce concern ne décide pas de la promotion — il produit un verdict.

<a id="p-18"></a>
## P-18 — Quel antivirus, et comment l'appeler ?

**Problème** — L'énoncé impose « un antivirus disponible via une API », sans
préciser lequel.

**Question** — Quel moteur, quel protocole ?

| Option | Confidentialité | Reproductible sur tout poste | Déterministe | Coût |
|---|---|---|---|---|
| **ClamAV + wrapper REST (conteneur)** | ✅ local | ✅ | ✅ (EICAR) | faible |
| ClamAV via `clamd` TCP `INSTREAM` | ✅ | ✅ | ✅ | ~80 lignes de client, « API » discutable |
| **Stub HTTP maison** (détecte EICAR) | ✅ | ✅ | ✅ | très faible, mais ne scanne pas vraiment |
| VirusTotal | ❌ **les fichiers deviennent consultables par des tiers** | ❌ clé requise | ❌ quotas | — |
| Service commercial | 🟠 | ❌ compte requis | 🟠 | — |

**Réponse** — **ClamAV via un wrapper REST** comme adapter réel, **plus un stub
déterministe** comme second adapter (tests, CI, démonstration sans réseau).
Deux adapters pour un port : c'est la matérialisation du mot « déléguer ».

> Ton point est juste : **l'outil n'est pas le sujet.** Un antivirus imparfait
> derrière une abstraction propre vaut mieux qu'un moteur sophistiqué couplé au
> code. Ce qui est évalué, c'est la délégation, pas le taux de détection.

**Détail opérationnel important** : l'image ClamAV télécharge sa base de
signatures au premier démarrage (~300 Mo, plusieurs minutes). Sans
`healthcheck` et sans volume persistant pour la base, le premier
`docker compose up` donnera l'impression que rien ne marche.
À traiter explicitement.

**Bascule** — Si ClamAV s'avérait pénible à exploiter sous Docker Desktop
Windows, le stub devient l'adapter par défaut et ClamAV un profil optionnel —
avec la limite clairement annoncée dans le README. C'est une dégradation
acceptable ; masquer le problème ne le serait pas.

**Livré** — ClamAV 1.4.6 derrière `ajilaag/clamav-rest`, consommé par son API HTTP ([ADR-0004](adr/0004-antivirus-par-api-http.md)), dans une image dérivée qui force `AlertExceedsMax` ; `healthcheck` et volume pour les signatures. Le second adaptateur n'est **pas** un bouchon détectant EICAR : c'est `InstantCleanAntivirusScanner` (verdict sain immédiat), réservé aux essais de capacité et inactif sans le profil `capacity`. Les tests utilisent le vrai moteur, WireMock et un moteur scripté.

---

<a id="p-19"></a>
## P-19 — Que faire d'un fichier trop gros pour l'antivirus ?

**Problème** — L'énoncé demande des « tailles très variables » **et** un scan.
Ces deux exigences sont en **tension directe** : ClamAV plafonne
(par défaut, en version 1.4 : `StreamMaxLength` et `MaxFileSize` à 100 Mo,
`MaxScanSize` à 400 Mo — la première version de ce document annonçait 25 Mo
pour `StreamMaxLength`, valeur d'anciennes versions).
Un fichier de 5 Go n'est pas analysable tel quel.

**Question** — Refuser, découper, ou accepter sans servir ?

| Option | Analyse |
|---|---|
| Relever les plafonds à l'infini | ❌ déplace le problème vers un OOM de l'antivirus |
| **Découper en morceaux et scanner chacun** | ❌ **faux** : une signature peut chevaucher deux morceaux, et la détection d'archive est détruite. Pire que rien, car on se croit couvert |
| Refuser à l'ingestion (`413`) | 🟠 honnête et simple, mais contredit `EX-07` |
| **Accepter, marquer `UNSCANNABLE`, ne jamais servir** | ✅ respecte `EX-07` **et** `EX-03` |

> ⚠️ **Révisé le 2026-09-21 par le cadrage** : taille maximale
> 200-500 Mo. On configure l'antivirus pour analyser **tout ce qu'on accepte**
> (plafond commun à 500 Mo) ; au-delà, refus `413` à l'ingestion.
> `UNSCANNABLE` ne sert donc plus pour la taille du fichier déposé, mais
> reste indispensable pour les impossibilités découvertes pendant l'analyse
> (archive chiffrée, récursion, taille décompressée).
> Voir [`28-precisions-de-cadrage.md`](28-precisions-de-cadrage.md) §1.

**Réponse initiale (partiellement caduque)** — Plafonds relevés à une valeur
assumée et documentée, puis état **`UNSCANNABLE`** au-delà. Le fichier est conservé, jamais servi, et
l'utilisateur voit pourquoi. Le seuil est lu depuis
`AntivirusScanner.capabilities()`, pas codé en dur, et vérifié **avant**
d'engager un scan voué à l'échec.

> C'est probablement le meilleur contenu du README : il prouve qu'on a lu la
> documentation de la dépendance au lieu de supposer qu'elle est sans limite.

**Bascule** — Si la volumétrie réelle (question Q2) montrait que tous les
fichiers tiennent sous la limite, le refus à l'ingestion deviendrait acceptable
et plus simple. À confirmer avant de figer.

⚠️ **À vérifier empiriquement** avant de figer : valeurs par défaut réelles de
l'image retenue et comportement exact au dépassement.

**Livré** — Conforme à la révision du 21/09 : 500 Mo pour l'admission comme pour l'analyse, `413` au-delà. Le port n'a **pas** de `capabilities()` (méthode retirée : la limite est une donnée de configuration). Les limites ont été **mesurées** : `MaxFileSize` doit rester strictement supérieur à `MaxScanSize`, sinon une grosse entrée d'archive est tronquée sans alerte ([ADR-0005](adr/0005-ordre-des-limites-de-l-antivirus.md)). Écart avec la note ci-dessus : une **archive chiffrée** n'est pas signalée par le moteur tel qu'il est configuré ([`06`](06-securite-et-angles-morts.md) §2).

---

<a id="p-20"></a>
## P-20 — Comment distinguer une panne d'un verdict ?

**Problème** — L'antivirus ne répond pas. Est-ce que le fichier est dangereux ?
Non : on **ne sait pas**. Confondre les deux est l'erreur la plus courante.

**Question** — Combien d'états de sortie, et lesquels ?

| Résultat de l'appel | Signification | Rejouable | État |
|---|---|---|---|
| verdict sain | information | — | `CLEAN` |
| verdict menace | information | non | `INFECTED` |
| taille/archive hors capacité | information | non | `UNSCANNABLE` |
| timeout, `503`, réseau | **absence** d'information | **oui** | `SCAN_FAILED` |
| `400`/`415` (bug d'intégration) | absence d'information | **non** (retenter ne corrige pas un bug) | `SCAN_FAILED` + alerte |

**Réponse** — **Quatre états de sortie distincts.** Les trois non-`CLEAN` sont
tous non servables (fail-closed), mais leur traitement diffère radicalement :
`INFECTED` est terminal, `UNSCANNABLE` est terminal mais rescannable
manuellement, `SCAN_FAILED` est réessayé automatiquement.

Un système qui n'a que `CLEAN`/`INFECTED` transforme chaque panne de
l'antivirus en fausse accusation d'infection.

**Bascule** — Aucune. C'est de la correction, pas du goût.

**Livré** — Les issues sont : verdict sain → `PROMOTING` puis `AVAILABLE` ; menace → `INFECTED` ; limite du moteur → `UNSCANNABLE` ; panne → `RETRY_WAIT`, puis `FAILED_FINAL` quand les tentatives sont épuisées. Il n'y a pas d'état `SCAN_FAILED`, pas de réanalyse manuelle d'un `UNSCANNABLE`, et pas de catégorie « bogue d'intégration, non rejouable » : toute réponse qui n'est pas un verdict est une panne, bornée par le nombre de tentatives.

---

<a id="p-21"></a>
## P-21 — Comment gérer les réessais, sans bibliothèque de résilience ?

**Problème** — Une bibliothèque de résilience a été proposée dans ma première
analyse. Le porteur du projet en a demandé la justification : après examen,
elle est **redondante** avec ce que la file en base fournit déjà.

**Question** — Qu'apporte réellement une telle bibliothèque ici ?

| Fonction | Fournie par Resilience4j | Déjà disponible sans elle |
|---|---|---|
| **Timeout** | `@TimeLimiter` | ✅ timeouts du client HTTP (JDK / `RestClient`) — **1 ligne de configuration** |
| **Retry + backoff** | `@Retry` | ✅ colonnes `attempts` + `next_attempt_at` — **déjà nécessaires pour le reaper** |
| **Bulkhead** | `@Bulkhead` | ✅ taille du pool de threads du worker (cf. [P-17](#p-17)) |
| **Circuit breaker** | `@CircuitBreaker` | 🟠 seule fonction réellement absente |

**Réponse** — **Aucune bibliothèque de résilience.** Trois des quatre fonctions
sont déjà présentes par construction ; les ajouter en double créerait deux
politiques de réessai concurrentes (celle de la bibliothèque, en mémoire, et
celle de la file, persistée) — une source de bugs classique.

Pour la quatrième, un **portillon de santé** de ~25 lignes remplace le circuit
breaker et fait mieux dans ce contexte :

> Avant de prendre un travail, le worker consulte l'état de santé de
> l'antivirus (mis à jour par les résultats des derniers appels). S'il est
> dégradé, le worker **ne prend pas de travail** et attend. Résultat : la file
> ne se vide pas en échecs, les tentatives ne sont pas consommées inutilement,
> et le système reprend seul dès le rétablissement.

Un circuit breaker classique fait échouer vite ; ici on ne veut pas échouer
vite, on veut **ne pas consommer de tentatives**. Le portillon est
sémantiquement plus juste.

**Bascule** — Si un jour plusieurs dépendances externes distinctes devaient
être protégées, une bibliothèque redeviendrait rentable. Avec une seule
dépendance et une file persistée, elle ne l'est pas.

**Livré** — Aucune bibliothèque de résilience. Le portillon de santé interroge l'antivirus (`GET /`, délai de 2 s) **avant chaque prise de travail** ; il n'est pas déduit des derniers appels. La limite de concurrence n'est pas la taille d'un pool ([P-17](#p-17)).

---

<a id="p-22"></a>
## P-22 — Que se passe-t-il si l'antivirus est totalement indisponible ?

**Problème** — Panne longue (mise à jour, incident). Le service doit-il
s'arrêter ?

**Question** — Quel mode dégradé ?

| Option | Conséquence |
|---|---|
| Refuser les uploads | ❌ indisponibilité inutile : on sait recevoir et stocker |
| Servir sans scanner | ❌ **viole `EX-03`** — jamais |
| Scanner plus tard, tout bloquer entre-temps | ✅ |
| **Continuer à ingérer + continuer à servir les fichiers déjà `CLEAN`** | ✅ dégradation correcte |

**Réponse** — **Fail-closed sur la sécurité, fail-open sur la disponibilité.**
L'ingestion continue (les fichiers s'accumulent en quarantaine), le service des
fichiers déjà validés continue sans être affecté, seule la mise à disposition
des nouveaux fichiers est retardée.

C'est la conséquence directe du découplage des trois disponibilités
([`05-ha-et-resilience.md`](05-ha-et-resilience.md) §1), et **c'est
démontrable en direct** : arrêter le conteneur antivirus et montrer que
l'upload et le téléchargement fonctionnent toujours.

**Bascule** — Si la file dépasse un seuil pendant la panne, [P-07](#p-07)
prend le relais et l'ingestion commence à refuser (`429`). La dégradation reste
maîtrisée.

**Livré** — Tel quel, avec `AVAILABLE` là où ce texte dit `CLEAN`. `scripts/demo.sh --resilience` le rejoue : antivirus arrêté, dépôt et téléchargement continuent, aucune tentative n'est consommée.

---

<a id="p-23"></a>
## P-23 — Un verdict est-il valable indéfiniment ?

**Problème** — Un fichier `CLEAN` en janvier peut être `INFECTED` en mars, si sa
signature est publiée entre-temps. Sans politique, la garantie de l'énoncé se
dégrade silencieusement avec le temps.

**Question** — Faut-il rescanner, et quand ?

| Niveau | Contenu | Coût |
|---|---|---|
| **1 — exposer `scannedAt` + version de signatures, endpoint `/rescan`** | ✅ le problème est identifié et adressable | très faible |
| 2 — rescan planifié au-delà de N jours | | moyen |
| 3 — rescan déclenché par mise à jour des signatures | | élevé |

**Réponse** — **Niveau 1**, niveaux 2 et 3 en pistes d'amélioration. Le simple
fait d'exposer la version de base de signatures dans les métadonnées montre
qu'on a vu la dimension temporelle du problème.

⚠️ Conséquence directe sur [P-04](#p-04) / déduplication : réutiliser un verdict
`CLEAN` par empreinte **sans vérifier la version de signatures est une
faille**, pas une optimisation.

**Bascule** — Dès que la rétention dépasse quelques jours en production, le
niveau 2 devient nécessaire.

**Livré** — Le niveau 1 **sans** le point d'entrée : le détail d'un fichier expose `scannedAt`, `engineVersion` et `signatureVersion`, mais il n'existe aucun `/rescan`. La réanalyse est une limite connue (README §10). Aucune réutilisation de verdict par empreinte (`D-08`, reportée).

---

# E. Concern « Service »

**Responsabilité** : délivrer les octets d'un fichier validé, à un appelant
autorisé, et **à personne d'autre dans aucun autre cas**.

<a id="p-24"></a>
## P-24 — Comment garantir qu'on ne sert jamais un fichier non validé ?

**Problème** — C'est l'exigence centrale (`EX-03`). Un `if` ne suffit pas.

**Question** — Comment obtenir une garantie qui survive à un bug ?

**Réponse** — Trois lignes de défense **de natures différentes** (une erreur
dans l'une ne se propage pas aux autres) :

| Niveau | Mécanisme | Résiste à |
|---|---|---|
| 1. Domaine | l'automate : « servable » n'existe que depuis `CLEAN` | une erreur de logique métier |
| 2. Application | le use case refuse tout état ≠ `CLEAN` et vérifie l'appartenance | une erreur de contrôleur, l'énumération d'identifiants |
| 3. Infrastructure | le processus qui sert n'a **pas accès** à la quarantaine ([P-11](#p-11)) | **un bug dans les niveaux 1 et 2** |

Plus une **preuve** : un test paramétré qui, pour chaque valeur de
l'énumération d'états, vérifie le refus — écrit de sorte que l'ajout d'un
nouvel état non classé **fasse échouer le test**. Le default-deny est ainsi
*testé*, pas seulement *codé*.

**Bascule** — Aucune.

**Livré** — Le seul état servable est `AVAILABLE` (et non `CLEAN`, qui est un verdict). Trois couches indépendantes : l'automate, les contraintes `CHECK` de la base, et l'identité de stockage `delivery`, sans droit sur la quarantaine ; s'y ajoute la revérification de l'état par le cas d'usage au moment de servir. `FileStatusTest` et `DownloadApiTest` portent la preuve.

---

<a id="p-25"></a>
## P-25 — Qui porte la bande passante de téléchargement ?

**Problème** — Servir 500 fichiers de 1 Go par heure à travers l'application
consomme sa bande passante et ses threads.

**Question** — Relayer, ou rediriger ?

| Option | Bande passante | Contrôle | Prérequis |
|---|---|---|---|
| **Relais par l'application** | portée par l'app | ✅ total, audit exact | aucun |
| Redirection vers URL présignée | déportée | 🟠 l'URL est un porteur d'autorisation ; comptage à l'émission | **stockage objet** |

**Réponse** — **Relais par l'application au palier 1** (le système de fichiers
ne permet pas de présigner), avec prise en charge des requêtes `Range` pour la
reprise. **Redirection présignée au palier 2**, quand S3 entre en jeu.

Le relais reste parfaitement viable : avec les virtual threads, une instance
tient un grand nombre de transferts concurrents, et le facteur limitant devient
le réseau, pas l'application.

**Bascule** — Passage au présigné dès que la bande passante de sortie de l'API
devient le facteur limitant mesuré. C'est aussi le déclencheur du passage à S3
([P-08](#p-08)) : les deux décisions sont liées.

**Livré** — Relais par le service, avec une plage d'octets (`Range`) pour la reprise — y compris avec le stockage objet : **pas d'URL présignée** ([ADR-0013](adr/0013-telechargement-par-l-identite-de-l-appelant.md)). Elle ne revérifierait pas l'état du fichier ; elle reste une piste (README §10). Taille maximale : 500 Mo.

---

<a id="p-26"></a>
## P-26 — Que répond-on pour chaque état ?

**Problème** — Sept états, sept réponses HTTP à choisir. Un choix incohérent
rend l'API inexploitable par un système tiers.

**Réponse** — Table de décision exhaustive, détaillée en
[`contracts/README.md`](../contracts/README.md) §2.7. Principes :

| Principe | Application |
|---|---|
| Distinguer transitoire et définitif | `409 + Retry-After` (en cours) vs `403` (définitif) |
| Ne pas mentir sur la cause | `503` pour `SCAN_FAILED` (notre panne), pas `403` |
| Ne pas divulguer par la réponse | `404` pour un fichier d'un autre tenant, jamais `403` |
| Rendre les clients immunisés aux nouveaux états | exposer un booléen `downloadable` calculé côté serveur |

**Bascule** — Le choix `403` vs `404` sur `INFECTED` reste ouvert (`D-09`).

**Livré** — Huit états internes, six statuts publics. Un état non servable répond toujours **`409`** : `FILE_NOT_READY` + `Retry-After` (transitoire), `FILE_INFECTED`, `FILE_UNSCANNABLE`, `FILE_SCAN_FAILED` (définitifs) — ni `403`, ni `503` pour l'état d'un fichier. `503` est réservé à une dépendance en panne ; `404` au fichier inconnu ou d'un autre utilisateur. `downloadable` et `terminal` sont calculés par le serveur. `D-09` est acté.

---

<a id="p-27"></a>
## P-27 — Un fichier sain peut-il quand même être une attaque ?

**Problème** — Oui. Un fichier HTML ou SVG contenant du JavaScript est
parfaitement `CLEAN` pour un antivirus : ce n'est pas un virus. Servi depuis le
domaine de l'application, il s'exécute dans le contexte de sécurité de
celle-ci.

**Question** — Que faut-il en plus de l'antivirus ?

**Réponse** — L'antivirus est **une** mesure, pas **la** mesure :

| Mesure | Contre |
|---|---|
| `Content-Disposition: attachment` | exécution dans le navigateur |
| `X-Content-Type-Options: nosniff` | ré-interprétation du type par le navigateur |
| type **détecté** côté serveur, jamais le type déclaré | tromperie sur le type |
| clé de stockage = UUID, jamais le nom fourni | traversée de chemin |
| nom d'origine assaini (contrôle, `\r\n`, bidirectionnel, longueur) | injection d'en-tête, tromperie visuelle |
| domaine de service distinct (piste) | XSS de même origine |

À écrire explicitement dans le README : **un fichier `CLEAN` n'est pas un
fichier inoffensif.** Le dire relève de la lucidité, pas d'un aveu de
faiblesse.

**Bascule** — Aucune.

**Livré** — `attachment`, `nosniff`, clé UUID, nom assaini. Le contenu est **toujours** servi en `application/octet-stream` : le type détecté n'est qu'une métadonnée, il ne sert jamais à servir. Pas de domaine de service distinct ; l'en-tête `Content-Security-Policy: sandbox` n'est pas envoyé (audit S-18, en attente).

---

<a id="p-28"></a>
## P-28 — Qui a le droit de télécharger quoi ?

**Problème** — Une API où tout identifiant connu est téléchargeable par
quiconque échoue à l'esprit de l'exercice, même avec un antivirus parfait.

**Question** — Quel niveau d'authentification et de cloisonnement ?

| Option | Effort | Apport |
|---|---|---|
| Aucune | nul | ❌ négatif sur un exercice de sécurité |
| **JWT validé par clé de test, sans fournisseur d'identité** | faible | ✅ prise en compte démontrée |
| OIDC complet + Keycloak | élevé | 🟠 consomme le budget sur un sujet périphérique |

**Réponse** — JWT porteur d'un `tenantId` et d'un `subject`, validé par une clé
symétrique de test, **plus un contrôle d'appartenance sur chaque accès**. La
notion de tenant est présente **dès le schéma** : l'ajouter après coup imposerait
une migration et la revue de chaque requête.

**Bascule** — Si un fournisseur d'identité existant est imposé, on s'y
intègre.

**Livré** — OpenID Connect avec **Keycloak**, toujours exigé ([ADR-0014](adr/0014-authentification-toujours-exigee.md)), et non un JWT validé par une clé de test. **Pas de `tenantId`** : le propriétaire est le `sub` de l'appelant (`D-11`), et chaque accès filtre sur lui.

---

# F. Concern « Transverse »

<a id="p-29"></a>
## P-29 — Quel modèle de concurrence pour l'application web ?

**Problème** — Spring MVC + virtual threads, ou WebFlux ? Les deux répondent au
même problème : tenir beaucoup de connexions sur une charge I/O-bound.

**Question** — Lequel, et surtout : peut-on prendre les deux ?

| Critère | MVC + virtual threads | WebFlux |
|---|---|---|
| Concurrence sur I/O | ✅ élevée | ✅ élevée |
| Accès base | JDBC standard | **R2DBC requis** — sinon on bloque la boucle d'événements |
| Client antivirus | client HTTP bloquant standard | doit être réactif de bout en bout |
| Streaming de fichier | `InputStream` → `OutputStream` | `Flux<DataBuffer>`, contre-pression native |
| Débogage | pile d'appels lisible | piles réactives, difficiles |
| Coût d'apprentissage | nul | élevé |

**Réponse** — **Spring MVC + virtual threads.** Deux arguments :

1. **Ce ne sont pas des compléments.** Combiner les deux n'additionne pas leurs
   bénéfices : un thread virtuel bloqué dans une chaîne réactive n'apporte
   rien, et une chaîne réactive contenant un appel bloquant annule le bénéfice
   du réactif. **Loom rend WebFlux largement inutile pour ce cas d'usage** —
   c'est précisément ce qu'un architecte cherchera à vérifier.
2. **Le gain réactif serait marginal ici.** La charge longue (le scan) est déjà
   sortie du thread HTTP par l'asynchronisme. Il ne reste au chemin web que le
   relais d'octets, où le réactif n'apporte pas de gain décisif — mais impose
   R2DBC, un client AV réactif, et un débogage nettement plus difficile.

**Bascule** — Le réactif deviendrait pertinent avec des dizaines de milliers de
connexions simultanées très longues. Mais dans ce cas, le bon levier serait
d'abord l'upload direct au stockage par URL présignée, qui sort complètement
les octets de l'application — un gain d'un autre ordre de grandeur.

**Livré** — Spring MVC + threads virtuels ([ADR-0010](adr/0010-mvc-et-threads-virtuels.md)).

---

<a id="p-30"></a>
## P-30 — Un déployable ou deux ?

**Problème** — L'API et le worker ont des profils de charge orthogonaux, mais
deux artefacts doublent la complexité opérationnelle.

**Réponse** — **Un artefact, deux rôles activés par profil Spring**
(`--spring.profiles.active=api|worker`), déployés en **deux conteneurs**
distincts issus de la même image.

Cela donne simultanément : un seul build, un scaling indépendant des deux
rôles, l'isolation physique de la quarantaine par montages différenciés
([P-11](#p-11)), et un mode tout-en-un pour le développement local.

**Bascule** — Deux artefacts distincts si les cycles de livraison devaient
diverger. La séparation est triviale une fois l'hexagone en place.

**Livré** — Un artefact et **un seul processus** : pas de profils `api` / `worker`, pas de second conteneur ([ADR-0002](adr/0002-un-processus-trois-identites.md)). L'isolation de la quarantaine tient aux identités du stockage. Deux interrupteurs (`WORKER_ENABLED`, `SCHEDULING_ENABLED`) permettraient de démarrer un nœud sans ses boucles d'analyse.

---

<a id="p-31"></a>
## P-31 — Que faut-il observer ?

**Problème** — Un système « hautement disponible » dont on ne voit pas la
dégradation ne l'est pas.

**Réponse** — Les métriques **métier** priment sur les métriques techniques.
La plus importante :

> **`scan_oldest_pending_age_seconds`** — l'âge du plus vieux fichier en
> attente. C'est la seule métrique qui dise directement *« depuis combien de
> temps un utilisateur attend son fichier »*. Elle est le déclencheur de
> scaling et l'indicateur d'alerte.

Liste complète : [`05-ha-et-resilience.md`](05-ha-et-resilience.md) §6.

**Objectif concret** : pouvoir répondre en moins d'une minute à « pourquoi ce
fichier précis n'est-il pas encore téléchargeable ? », sans lire de code.

**Bascule** — Les traces distribuées ne se justifient qu'à partir de trois
composants applicatifs ; avec deux rôles, un `fileId` corrélé dans les logs
suffit.

**Livré** — La métrique s'appelle `praxedo.queue.oldest_pending_age` (`praxedo_queue_oldest_pending_age_seconds` dans Prometheus). Corrélation par `X-Request-Id` et par `fileId` dans les journaux ; pas de traces distribuées. Liste : [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §12.

---

<a id="p-32"></a>
## P-32 — Comment tester ce qui n'est censé jamais arriver ?

**Problème** — L'invariant porte sur ce qui **ne doit pas** se produire. On ne
peut pas tester un cas nominal pour le prouver.

**Réponse** — Trois familles de tests, détaillées en
[`09-strategie-de-test.md`](09-strategie-de-test.md) :

| Famille | Exemple |
|---|---|
| **Exhaustivité sur l'énumération** | pour chaque état, le téléchargement est refusé — et l'ajout d'un état non classé fait échouer le test |
| **Tentatives d'attaque** | traversée de chemin, `Content-Length` mensonger, accès inter-tenant, accès direct à la quarantaine |
| **Injection de panne** | antivirus arrêté, timeout, réponse malformée, worker tué en plein scan |

Le fichier **EICAR** (68 octets, inoffensif, détecté par tous les moteurs) rend
le chemin « infecté » testable de façon déterministe en CI, sans manipuler de
malware. Détail pratique : le reconstruire à l'exécution par concaténation,
pour ne pas faire mettre le dépôt en quarantaine par l'antivirus de
qui le clone.

**Bascule** — Aucune.

**Livré** — Les trois familles existent ([`09-strategie-de-test.md`](09-strategie-de-test.md)). Il n'y a pas d'intégration continue : les tests tournent sur le poste (`./mvnw verify`).

---

<a id="p-33"></a>
## P-33 — Comment lance-t-on tout cela ?

**Problème** — Souvent négligé, et pourtant décisif : si `docker compose up`
échoue sur la machine de qui découvre le projet, la qualité du code ne sera
pas vue.

**Réponse** — Contraintes à respecter :

| Contrainte | Raison |
|---|---|
| **Une seule commande** pour tout démarrer | toute étape supplémentaire est une occasion d'échec |
| Aucun compte, aucune clé d'API à créer | conséquence directe du choix ClamAV local ([P-18](#p-18)) |
| `healthcheck` sur l'antivirus + volume pour ses signatures | sinon le premier démarrage semble bloqué pendant plusieurs minutes |
| Un jeu de données de démonstration (fichier sain + EICAR) | le parcours se démontre en trente secondes |
| Procédure testée sur une machine vierge | « ça marche chez moi » ne compte pas |
| Scripts compatibles Windows (développement sous PowerShell) | éviter les scripts bash-only non doublés |

**Bascule** — Aucune. C'est le premier contact avec le travail.

**Livré** — `scripts/start` (`.sh` et `.ps1`) démarre tout, `scripts/check` vérifie, `scripts/stop` arrête. Aucun compte ni clé à créer : la configuration de développement est versionnée et le realm Keycloak est importé avec ses comptes de démonstration. `scripts/demo.sh` (bash) rejoue le parcours : fichier sain, puis EICAR assemblé à la volée.
