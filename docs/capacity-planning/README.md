# Capacity planning

> **Unités.** Débits et mémoires relevés par `load/report.mjs` : **Mo = 10⁶
> octets**. Tailles du corpus (128 Kio, 512 Kio, 2 Mio) et relevés Docker :
> **Mio = 2²⁰ octets**.

## Conclusion

- **Un nœud de 2 CPU et 1 Gio, 8 workers, ClamAV réel, tient 130 à 160
  fichiers/s** (~73 à 90 Mo/s) : lag p95 d'une seconde au plus, dépôt p95
  sous 50 ms. Un nœud de 1 CPU tient 80 fichiers/s ; 100 y sont au genou (la
  file grossit), et le débit retombe vers 85 à 88 au-delà.
- **L'hypothèse du projet, 5 000 fichiers/jour, vaut 0,058 fichier/s** : un
  seul nœud a une marge de plus de 2 000 fois, soit ~13 millions de fichiers
  par jour. Ce sont les rafales, la taille et la nature des documents qui
  dimensionnent, pas le volume quotidien.
- **Ce qui limite au-delà est mesuré, pas supposé : la cadence des validations
  de PostgreSQL**, liée ici à un disque que la base partage avec le stockage
  objet. Un fichier coûte ~2 fsync du journal ; le disque de la machine de
  mesure en fait 497/s au repos et 60/s quand le stockage écrit en même temps.
- **Au-delà de ce plafond, le service se dégrade au lieu de plafonner** : le
  mécanisme est compris, la parade identifiée (délester quand la base
  ralentit), non encore réalisée.
- **Invariant « aucun fichier non analysé servi » : 0 dans toutes les
  campagnes**, maximum relu sur toute la durée de chacune.

Deux réserves conditionnent ces chiffres : le **corpus** (octets aléatoires,
peu coûteux pour ClamAV ; des archives ou des PDF déplaceraient le plafond de
l'antivirus) et le **banc** (une seule machine Docker Desktop pour tous les
composants : au-dessus de ~160 fichiers/s, on mesure son disque, pas le
service).

## Ce que tient un nœud

Meilleurs paliers **valides** de chaque gabarit, ClamAV réel, mélange 128 Kio
(50 %) / 512 Kio (35 %) / 2 Mio (15 %), soit ~0,56 Mo par fichier.

| Nœud | Tenu | Lag p95 | Dépôt p95 | Au-delà | Série |
|---|---|---|---|---|---|
| 1 CPU / 1 Gio, 4 workers | 80 (palier de 180 s) | 1,3 s | 36 ms | plafond ~85 | 1 et 2 |
| 1 CPU / 1 Gio, 8 workers | 100, au genou (file +228) | 2,6 s | 62 ms | plafond ~88 | 3 |
| 2 CPU / 1 Gio, 4 workers | 100 | 1,2 s | 28 ms | 75 à 120 | 3 |
| 2 CPU / 1 Gio, 8 workers | **160** | **0,8 s** | **47 ms** | 58 à 200 | 4 |

- **1 CPU** : borné par le processeur. JFR montre la JVM bridée par son quota
  52 % du temps ; doubler les workers n'apporte que 5 %.
- **2 CPU / 4 workers** : borné par les workers (un cœur sur deux utilisé).
- **2 CPU / 8 workers** : borné par la base (voir plus bas).
- **Rafale** : 50 dépôts simultanés de 2 Mio sont acceptés 50/50 à chaque
  passage ; p95 de 2,5 s sur un nœud de 1 CPU, 1,2 à 1,4 s sur un nœud de
  2 CPU — le temps de faire passer 105 Mo.
- **Démarrage à froid** : un nœud neuf de 1 CPU ne fournit que la moitié à
  deux tiers de sa capacité pendant ses premières minutes (le compilateur JIT
  prend l'unique cœur). Un nœud ajouté sous charge n'apporte pas sa capacité
  tout de suite.

**Plusieurs nœuds** : trois nœuds de 2 CPU ont atteint **176 fichiers/s** —
à peine plus qu'un seul. Ce n'est pas un verdict sur la montée en charge
horizontale : les trois nœuds partagent une base, et cette base un disque.
Ajouter des nœuds ajoute des connexions qui se disputent le journal, pas de
capacité de validation. La campagne de contrôle qui le trancherait (base sans
attente du disque) n'a pas été menée.

## Ce qui limite : la validation des transactions, sur ce disque

Démontré par la série 4, instrumentée côté base (`*-database.md`) :

| Mesure | 1 nœud | 3 nœuds |
|---|---:|---:|
| fsync moyen du journal (WAL) | 3,1 ms | 4,6 ms |
| fsync par fichier (validations groupées) | 2,35 | 1,62 |
| Instructions de plus d'1 s, toutes des `COMMIT` | 131 | 843 |
| Sessions en attente d'écriture du journal (`LWLock:WALWrite`), dernier palier | 11,5 en moyenne | 34 en moyenne |
| Attentes de verrou | 0 | 0 |

- Les connexions retenues plus de 2 s (détection de fuite Hikari) viennent des
  quatre écritures d'un fichier et de rien d'autre : dépôt, claim, verdict,
  passage à `AVAILABLE`. L'application ne garde pas ses transactions
  (`idle in transaction` < 0,1 en moyenne) : elle attend le disque.
- Les deux épisodes de blocage commencent quelques secondes après un
  checkpoint de PostgreSQL, dont les écritures s'ajoutent à celles du stockage
  objet (100 à 150 Mo/s sur le même disque virtuel).
- Mesure directe du disque (`pg_test_fsync`, méthode par défaut) :

| État du disque | fsync/s | Durée | Fichiers/s permis (~2 fsync/fichier) |
|---|---:|---:|---:|
| Au repos | 497 | 2,0 ms | ~250 |
| Pendant une campagne (fsync moyen 3 à 4,6 ms) | 220–330 | — | 110–165 — **observé : 160–176** |
| Écriture concurrente continue | 60 | 16,5 ms | ~30 — **observé à l'effondrement : 40–60** |

**Pourquoi le débit s'effondre au lieu de plafonner** : les validations
ralentissent, les connexions sont gardées plus longtemps, les pools se
remplissent, les dépôts s'empilent jusqu'à la borne de 50 par nœud, et la
file d'attente sur le journal s'allonge (jusqu'à 49 sessions à la fois). Plus
il y a de connexions, pire c'est.

**Ce que cela dit de l'architecture.** « La base fait file, aucun broker » :
le plafond de la file est la cadence de validation de la base. On sait
combien de validations coûte un fichier (~4 écritures, ~2 fsync après
regroupement) et ce qui déplace ce plafond — un disque dédié (le fsync d'un
SSD de serveur est typiquement dix à quarante fois plus court), le regroupement des
validations, et des validations asynchrones pour les étapes que la file sait
rejouer.

## Les dépendances

À 100 fichiers/s sur un nœud de 2 CPU (`docker stats`, série 2) :

| Composant | CPU | Réseau |
|---|---:|---:|
| ClamAV | **2,5 cœurs** (3,6 en pointe) | 57 Mo/s |
| SeaweedFS | 1,7 cœur | **236 Mo/s** |
| Back-end | 1,4 cœur | 347 Mo/s |
| PostgreSQL | 0,4 cœur | — |

- **L'application représente un quart du processeur consommé.** ClamAV est le
  premier consommateur, même sur des octets aléatoires : c'est lui qu'un
  corpus réel ferait exploser.
- **Le trafic du stockage vaut ~4 fois le volume déposé** (dépôt, relecture
  pour l'analyse, relecture et écriture pour la promotion), celui du back-end
  ~6 fois.
- **Le stockage retient la place d'un fichier supprimé** jusqu'à son
  compactage : chaque fichier occupe un temps près de deux fois sa taille
  (quarantaine et zone servable).

## Règle de dimensionnement

Pour ce mélange de tailles, avec ClamAV réel, et en gardant une marge sous
les plafonds mesurés :

| Composant | Règle | Source |
|---|---|---|
| Nœuds applicatifs (2 CPU / 1 Gio, 8 workers) | `plafond(pointe en fichiers/s / 130)` | séries 3 et 4 |
| Octets | `plafond(pointe en Mo/s / 73)` : on retient le plus grand des deux nombres de nœuds | 130 × 0,56 Mo ; le coût par octet n'est pas mesuré séparément du coût par fichier |
| Base | tenir ~2,5 fsync par fichier à la pointe, sur un disque qui ne sert qu'à elle | série 4 |
| ClamAV | ~2,5 cœurs par 100 fichiers/s, **sur octets aléatoires** : à remesurer sur un corpus réel | série 2 |
| Stockage objet | réseau ≥ 4 × le débit de dépôt ; capacité ≥ 2 × le volume en transit | séries 2 et 3 |
| Connexions PostgreSQL | 10 + workers + 2 par nœud (deux pools) : ~4 nœuds à 8 workers sous la limite par défaut de 100 | configuration |

## Ce qui a été corrigé en cours de route

Chaque correction est née d'une mesure, et chacune a son test.

| Constat | Correction | Référence |
|---|---|---|
| Une copie de promotion refusée par le stockage restait 15 min en `PROMOTING` : le SDK réessayait et redemandait un flux déjà consommé | Qui réessaie est décidé par identité : jamais le SDK pour les écritures en flux | `StorageRetriesTest`, B-015 |
| Au-delà de la saturation, le débit utile tombait de moitié : chaque refus comptait la file en base et lisait le corps du fichier | Compte de la file relu au plus tous les 250 ms ; 50 dépôts simultanés par nœud au plus ; `Expect: 100-continue` honoré ; 64 Ko lus d'un corps refusé | `UploadAdmissionBoundsTest`, `UploadAdmissionTest`, B-016 |
| Trois fichiers bloqués 10 min après une erreur de base non rattrapée | Résultat réécrit trois fois, puis bail proportionnel à la taille (30 s + 1,2 s/Mio) | `FileScanServiceTest`, `FilePersistenceTest`, B-016 |
| Les requêtes de l'API pouvaient affamer les workers | Deux pools de connexions : `api` et `queue` | `FilePersistenceTest`, B-016 |
| Tas à 75 % de 1 Gio : tas plein et hors tas dépassaient la limite | Tas à 60 % dans l'image | `backend/Dockerfile` |
| Fin de chaque campagne de la série 3 : stockage de test plein après ~25 000 fichiers | SeaweedFS de 20 à 100 volumes | `docker-compose.yml` |

## Limites et pistes d'amélioration

Chaque piste avec ce qui la déclencherait.

1. **Délester quand la base ralentit.** La borne actuelle regarde la file,
   pas la latence de la base : au-delà du plafond, le débit s'effondre.
   Refuser un dépôt quand l'attente d'une connexion dépasse un seuil ferait
   plafonner au lieu de s'effondrer. *Déclencheur : toute exploitation où la
   base peut ralentir — c'est-à-dire toute exploitation.*
2. **Moins d'attente sur le disque par fichier.** Sans rien perdre :
   regrouper les validations (`commit_delay`, `commit_siblings`) et espacer
   les checkpoints (`max_wal_size`, `checkpoint_timeout`). En assumant une
   perte possible au crash de la base : validations asynchrones pour le claim
   et le verdict, que la file rejoue par construction — mais **jamais** pour
   l'insertion du dépôt (la promesse du `202`) ni pour le passage à
   `AVAILABLE` (la suppression de la source le suit). *Déclencheur : un
   besoin au-delà de ~150 fichiers/s par base.*
3. **Montée en charge horizontale non démontrée sur ce banc.** Campagne de
   contrôle à faire : trois nœuds, base sans attente du disque
   (`synchronous_commit=off`, banc uniquement), comme la campagne « sans
   ClamAV » a isolé l'antivirus. *Déclencheur : avant d'annoncer un débit à
   plusieurs nœuds.*
4. **Corpus non représentatif.** Octets aléatoires ; rejouer avec des PDF,
   documents Office et archives anonymisés avant tout engagement, et
   remesurer ClamAV. *Déclencheur : avant une capacité de production.*
5. **Admission bornée en nombre de fichiers, pas en octets.** 500 fichiers de
   500 Mo sont admis sous la borne et représentent ~1 h 30 de retard.
   `praxedo_queue_depth_bytes` existe déjà. *Déclencheur : des dépôts
   volumineux en volume.*
6. **Journaux** : chaque échec du stockage journalise une pile d'appels
   complète — jusqu'à 320 Mo par nœud en incident. À passer en
   avertissement sans pile. *Déclencheur : première mise en exploitation.*
7. **Métriques et pool de l'API** : les jauges lues en base utilisent le pool
   de l'API ; quand il s'épuise, Prometheus perd le nœud. *Déclencheur :
   première mise en exploitation.*
8. **Non couvert** : concurrence de fichiers de 500 Mo (générateur en flux à
   écrire), endurance de deux heures, réseau contraint entre zones.

## Chronologie des campagnes

| Série (heures UTC) | Quoi | Enseignement |
|---|---|---|
| 1 — 28/09, 13 h 33–14 h 41 | 1 CPU / 1 Gio, ClamAV réel puis simulé, trois confirmations | 50 fichiers/s soutenables, plafond ~85 ; sans ClamAV ~115 ; défaut de promotion découvert |
| 2 — 16 h 03–16 h 34 | JFR, relevé des conteneurs, 2 CPU, 8 workers | Effondrement au-delà du plafond ; dépendances plus coûteuses que l'application |
| 3 — 17 h 18–17 h 53 | Après correctifs, deux pools, nœuds à 1 Gio, 3 nœuds | Nœud seul +15 à 30 % ; derniers paliers invalides : stockage de test plein |
| 4 — 20 h 16–20 h 30 | Base instrumentée, stockage agrandi | Plafond = validation des transactions sur le disque partagé ; 160 fichiers/s tenus |

### Passages non retenus

| Préfixe (`load/results/`) | Raison |
|---|---|
| `2026-09-28T13-33-29Z-*` | Exploration antérieure à l'isolation des campagnes : très probablement celle qui a exposé le défaut de promotion (fichiers bloqués en `PROMOTING`, file pleine) |
| `2026-09-28T13-44-36Z-*`, `13-45-30Z-*` | Lancements avortés, rafale seule |
| `2026-09-28T14-09-02Z-*`, `14-29-41Z-*` | Démarrage à froid, échauffement insuffisant |
| `2026-09-28T14-40-50Z-*` | Confirmation simulée interrompue volontairement |
| `2026-09-28T16-12-43Z-scale-1cpu-8w-*` | Échouée : trois fichiers bloqués (voir `*-FAILED.md`) |
| `2026-09-28T16-29-12Z-scale-2cpu-8w-*` | Interrompue au palier 200 (voir `*-INCOMPLETE.md`) |
| Série 3, dernier palier de chaque campagne | Stockage de test plein (20 volumes de 2 Go) : `500` du stockage dès ~25 000 fichiers |

## Protocole

- **Charge** : k6, débit d'arrivée contrôlé ; échauffement, puis paliers de
  60 s (180 s pour le profil) précédés de 10 s de montée ; rafale de 50
  dépôts simultanés de 2 Mio en tête de chaque campagne. Chaque campagne
  repart de volumes neufs.
- **« Tient »** : aucun `429`, file stable (croissance < 5 % des dépôts du
  palier), plus ancien travail sous deux minutes. Sur 60 s, ce critère sépare
  un palier sain d'un palier saturé, pas un palier soutenable d'un palier au
  genou : le genou se confirme par un palier long.
- **Preuves conservées** par campagne : rapport Prometheus
  (`*-throughput.md`), conteneurs (`*-dependencies.md`), base
  (`*-database.md`, série 4), JFR (`*-jfr.txt`), résumés k6, métadonnées ;
  journaux et enregistrements JFR bruts, volumineux, hors du dépôt.
- **Reproduire** : `load/README.md`. Série 4 :

```powershell
node load/run-capacity.mjs scale-2cpu-8w scale-3nodes-2cpu
```
