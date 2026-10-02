# 26 — Réponse à la contre-analyse ChatGPT

> **Analyse : Claude (Opus 5)** — 2026-09-21 — confrontation : [`CONFRONTATION.md`](CONFRONTATION.md)
>
> Évaluation des documents `docs/chatgpt/20`, `21` et `22`. Ce document ne
> tranche aucune décision : il classe les corrections par **coût et criticité**
> pour permettre l'arbitrage humain.
>
> **Réponse du 21/09, gardée telle qu'elle a été rendue.** Elle parle encore de
> volumes Docker, de trois profils Spring et de l'état `SCAN_FAILED` : le §9,
> relu contre le code le 02/10, dit **ce que chaque correction est devenue**.

---

## 1. Verdict sur la contre-analyse

**Elle est bonne, et elle a trouvé de vrais défauts dans mon travail.**

Quatre bugs et deux lacunes de conception, tous vérifiables par un mécanisme
précis — pas des divergences de goût. Le protocole de confrontation a produit
exactement ce qu'on en attendait : une correction factuelle, pas un match
d'opinions.

| | Nombre |
|---|---|
| Défauts réels trouvés dans mon analyse | **6** (4 bugs + 2 lacunes) |
| Corrections que j'accepte intégralement | 14 |
| Corrections que j'accepte avec nuance | 4 |
| Points que je conteste ou marque comme non vérifiés | 6 |

Le point le plus important est qu'aucune de ces corrections **n'ajoute de
composant d'infrastructure**. La sobriété tient ; c'est la mécanique qui était
fausse.

---

## 2. Défauts réels — concession intégrale

### 2.1 L'isolation à deux rôles est auto-contradictoire ⛔

**C'est la meilleure trouvaille de la contre-analyse, et c'est une faute de ma
part.** En [P-11](20-catalogue-problematiques.md#p-11) j'affirme que le
conteneur `api` ne monte pas la quarantaine ; en
[A2](21-architectures-candidates.md) ce même conteneur y écrit les uploads.
Les deux ne peuvent pas être vrais.

Docker propose `rw` et `ro`, pas « écriture sans lecture ». J'ai vérifié s'il
existait une échappatoire :

| Tentative de sauvetage | Pourquoi elle échoue |
|---|---|
| Volume `incoming` distinct, déplacé ensuite en quarantaine | `incoming` **est** la quarantaine : des octets non analysés restent atteignables par le processus qui sert |
| Permissions POSIX `-wx` sur le répertoire (pas de listage) | Le processus de livraison connaît les noms (ils sont en base) : l'ouverture par nom exact reste possible |
| Fichiers en mode `0220` (non lisibles par leur créateur) | Fragile, dépendant d'umask et d'ACL, et casse la relecture légitime |

Aucune ne tient. **La topologie à trois rôles (`ingest` / `delivery` /
`worker`) est la correction juste.** Un artefact, une image, trois profils
Spring, trois périmètres de privilèges.

> Coût réel : ~1 h de compose et de profils. Coût de ne pas le faire :
> l'argument de sécurité le plus fort du projet s'effondre à la première
> question sur l'isolation.

**Un coût que la contre-analyse n'a pas chiffré** : avec deux rôles exposés,
le front doit router `POST` vers `ingest` et `GET` vers `delivery`. Soit deux
ports publiés et deux URL de base côté React (zéro composant, un peu inélégant),
soit un reverse proxy — **qui serait un quatrième composant**. À trancher
explicitement ; je recommande les deux ports, documentés comme « en production,
un ingress route par chemin ».

### 2.2 La contrainte `CHECK` est contournable par `NULL` ⛔

Vérifié, et c'est exact. Avec `status='CLEAN'` et `scan_result IS NULL` :

```
status <> 'CLEAN'          →  FALSE
scan_result = 'CLEAN'      →  UNKNOWN          (et non FALSE)
FALSE OR (UNKNOWN AND …)   →  UNKNOWN
```

Un `CHECK` ne rejette que `FALSE`. La ligne **passe**. J'avais présenté cette
contrainte comme « la quatrième ligne de défense » et comme la preuve que
« même une écriture SQL manuelle ne peut pas produire un fichier servable sans
verdict ». C'était faux. La correction par `IS NOT DISTINCT FROM` est la bonne.

Leur remarque annexe est également juste : la phrase « aucune migration mal
écrite ne peut violer l'invariant » doit disparaître — un rôle propriétaire de
la table peut toujours supprimer une contrainte.

### 2.3 Boucle infinie sur les travaux terminaux ⛔

Mon claim sélectionne `status IN ('AWAITING_SCAN','SCAN_FAILED')` **sans
prédicat sur `attempts`**, alors que mon reaper pose `SCAN_FAILED` quand
`attempts >= maxAttempts`. Une ligne déclarée terminale est donc reprise
indéfiniment. Bug réel.

Deux corrections possibles :
- **minimale** : ajouter `AND attempts < :maxAttempts` au claim (15 min) ;
- **propre** : séparer `RETRY_WAIT` (transitoire) et `FAILED_FINAL` (terminal).

La seconde est meilleure : elle rend l'intention lisible dans l'état plutôt que
dans un prédicat, et elle rend la métrique « échecs définitifs » triviale.

### 2.4 Les virtual threads ne sont pas un bulkhead ⛔

Exact, et c'est gênant parce que j'utilisais cet argument comme l'un des
quatre piliers du rejet de Resilience4j. Avec
`spring.threads.virtual.enabled=true`, l'exécuteur devient un
*virtual-thread-per-task* non borné et les propriétés de taille de pool ne
s'appliquent plus.

**La conclusion survit — mais pas par l'argument que j'avais donné.** Un
`Semaphore` du JDK devant l'appel antivirus fait le travail en trois lignes,
ce qui ne justifie toujours pas une bibliothèque. Mais il faut l'écrire, et je
ne l'avais pas prévu.

### 2.5 Lacune : la promotion n'est ni atomique ni récupérable ⛔

`Files.move(…, ATOMIC_MOVE)` entre deux `FileStore` lève
`AtomicMoveNotSupportedException`. Deux volumes Docker sont deux `FileStore`.
Je parlais de « promotion » sans jamais spécifier le protocole — une lacune,
pas une erreur de détail : elle laisse un trou où un fichier peut être `CLEAN`
en base et absent de la zone servable.

Le protocole proposé (copie vers temporaire **dans la destination** → vérifier
taille + hash → renommage atomique **local au volume** → CAS en base →
suppression de la source, idempotente) est correct, et les cinq points de
crash à tester sont bien identifiés.

### 2.6 Lacune : `CLEAN` confond verdict et disponibilité ⛔

Juste, et c'est la correction la plus élégante de la contre-analyse.
`CLEAN` est un **résultat d'antivirus** ; `AVAILABLE` est un **état métier**.
Les confondre rend inexprimable l'état « verdict sain obtenu, copie non
terminée » — précisément l'état créé par §2.5.

Avec `PROMOTING` intercalé, le trou disparaît : rien n'est servable tant que la
destination n'est pas vérifiée.

**Conséquence que la contre-analyse ne relève pas** : cette séparation invalide
aussi ma contrainte `servable_zone_requires_clean` telle qu'écrite, et rend
enfin exprimables le rescan et la suppression — que j'avais modélisés en
contradiction avec mes propres contraintes (défaut n°8 de leur liste, exact).

---

## 3. Corrections acceptées avec nuance

### 3.1 `413` plutôt que `UNSCANNABLE` pour les tailles connues — **je concède, partiellement**

C'était le désaccord annoncé. Après examen, leur position est meilleure **pour
le cas connu avant scan** : conserver un contenu dont on sait qu'il ne pourra
jamais être servi est une pure charge (stockage, vecteur de déni de service par
saturation disque) sans issue fonctionnelle.

**La nuance que je maintiens** : `EX-01` dit « recevoir et **conserver** ».
Si la conservation est une finalité en soi — et non seulement un préalable au
téléchargement — alors `413` casse une fonction pour en protéger une autre.
De plus, la capacité du scanner peut changer (configuration relevée, moteur
remplacé) : un fichier `UNSCANNABLE` redevient alors analysable, un fichier
refusé est perdu.

**Position révisée** : politique d'admission **configurable**, **défaut `413`**,
et la question est à préciser au cadrage (« la conservation est-elle une
finalité indépendante du téléchargement ? »). `UNSCANNABLE` reste indispensable pour les
limites découvertes pendant l'analyse — sur ce point nous sommes d'accord.

> Leur découverte de `AlertExceedsMax` désactivé par défaut **renforce** ce
> second cas : sans ce réglage, une limite interne atteinte peut remonter comme
> un scan propre. C'est un défaut de configuration à portée de sécurité, et
> c'est un excellent point.

### 3.2 `201` plutôt que `202` — **match nul, faible enjeu**

Leur lecture de la RFC 9110 est défendable : la ressource *existe*. La mienne
aussi : le traitement demandé n'est pas achevé. Ils reconnaissent d'ailleurs
que `202` reste défendable.

Je maintiens une préférence marginale pour `202` (l'intention de l'appelant
— rendre le fichier disponible — n'est pas satisfaite), mais **ce n'est pas un
sujet** : ce qui compte est `Location` + `downloadable: false` + `Retry-After`
dans le corps. À trancher en trente secondes, pas en réunion.

### 3.3 WebFlux — **ma formulation était trop forte**

J'ai écrit que WebFlux « impose R2DBC » et suggéré une incompatibilité
technique avec les virtual threads. Ce n'est pas exact : on peut faire du JDBC
bloquant sur un scheduler dédié, et les deux modèles peuvent techniquement
coexister. **Le bon argument est celui qu'ils formulent** : aucun bénéfice
établi ici, coût de mise en œuvre et de diagnostic élevé. Conclusion identique,
justification meilleure.

### 3.4 A0→A4 n'est pas une échelle — **concession sur le fond**

Ils ont raison : je mélange quatre axes indépendants (synchronisme, file,
stockage, transit des octets). « Un ADR par axe » est la bonne structure de
décision.

Je défends un usage résiduel de la trajectoire : c'est un bon **récit
de la démarche** (« voici où je me situe et ce qui me ferait bouger »). Mais
comme structure de décision, les axes l'emportent.

### 3.5 `409` au lieu de `403`/`503` — **concède, avec une réserve**

Ils ont raison sur les deux points : `403` doit rester un refus
d'**autorisation** (et non un synonyme de verdict), et `503` décrit
l'indisponibilité du **service** — l'utiliser pour un fichier en `SCAN_FAILED`
est trompeur et peut déclencher des logiques de retry global côté client.

Réserve mineure : `409 Conflict` suggère un conflit *résoluble*, ce qui
convient mal à un état terminal comme `INFECTED`. `422` ou `410` seraient
discutables. Mais `409` + code machine stable (RFC 9457) est acceptable et
nettement meilleur que ma proposition.

---

## 4. Points que je conteste ou qui restent à vérifier

### 4.1 Trois faits non corroborés

Je ne peux pas confirmer ces affirmations, et deux d'entre elles sont
matérielles. **Elles doivent être vérifiées avant d'être répercutées**, au
même titre que mes propres valeurs ClamAV l'ont été.

| Affirmation | Mon état de connaissance | Matérialité |
|---|---|---|
| **AWS S3 : 50 To par objet** | Je n'ai pas trace de ce changement ; la limite connue est 5 To | Nulle — aucun impact sur une décision |
| **Dépôt communautaire MinIO archivé** | Je n'ai pas trace de l'archivage ; il y a eu des controverses de licence et de périmètre de l'édition communautaire | **Forte si exact** — cela change la recommandation de palier 2 |
| **H2 supporte `SKIP LOCKED`** | Incertain, je le croyais non supporté | Nulle — H2 est réservé aux tests, et les tests doivent de toute façon viser PostgreSQL via Testcontainers |

Sur H2, leur remarque de méthode est en revanche juste : mon « H2 est en Java
mais ne sait pas faire `SKIP LOCKED` » était un argument **décoratif**. Qu'il
soit exact ou non, il doit disparaître.

Sur ClamAV, j'accepte provisoirement leurs valeurs (mieux sourcées que les
miennes, qui étaient les anciens défauts) **mais l'exigence de vérification
empirique demeure** : l'image peut embarquer une configuration différente de
l'échantillon amont, et c'est le fait porteur de tout l'argument `D-15`.

### 4.2 Spring Boot 4.1.1 — accepter le principe, pas sauter la version à l'aveugle

Leur point de méthode est juste : la version doit être **décidée et épinglée**
au démarrage. Mais adopter une version majeure récente (Spring Framework 7)
comporte un risque d'écosystème rarement chiffré : Testcontainers, springdoc,
Flyway et les starters doivent tous suivre. **À vérifier avant de choisir**, et
la version précédente reste un choix défendable pour un exercice où le sujet
n'est pas la modernité du framework.

### 4.3 Incohérence interne dans leur propre modèle

Leur contrainte de lease (`chatgpt/21` §5.3) :

```sql
CHECK ( (status = 'SCANNING') = (lease_token IS NOT NULL AND …) )
```

Or leur matrice d'états (`chatgpt/21` §9) attribue un **« token de promotion »**
à l'état `PROMOTING`. Une ligne en `PROMOTING` porterait donc un lease non nul
avec `status <> 'SCANNING'` → `FALSE = TRUE` → **la contrainte rejette leur
propre état**.

Correction : `status IN ('SCANNING','PROMOTING') = (lease… IS NOT NULL)`.
Défaut mineur, mais qui illustre que les contraintes totales doivent être
testées exhaustivement — ce qu'ils recommandent eux-mêmes.

### 4.4 Inflation du périmètre — **mon désaccord principal**

C'est le point sur lequel je pousse le plus fort.

Ils critiquent — à juste titre — mon estimation de 2 jours comme irréaliste.
Mais **leur palier 1 corrigé est plus gros que le mien** : trois rôles,
protocole de promotion avec tests de points de crash, token de fencing, rôles
et vues PostgreSQL, matrice complète de contraintes, hash vérifié à trois
frontières, **et traçage distribué corrélé**.

Deux observations :

1. **Leur propre estimation de 4 à 6 jours est donc probablement optimiste**
   pour leur propre périmètre.
2. **Le traçage distribué en palier 1 contredit leur règle de sobriété**, alors
   qu'ils retirent Tika, le multipart et la déduplication. Un `fileId` corrélé
   dans des logs structurés suffit à répondre à « pourquoi ce fichier
   n'est-il pas disponible ». Le traçage relève du palier 2.

Or le porteur du projet a posé une contrainte explicite : un périmètre
restreint, parfaitement exécuté, plutôt qu'une couverture exhaustive. Un « NO-GO » assorti
d'une liste de dix-neuf décisions à valider avant la première ligne de code
risque la paralysie d'analyse — le symétrique du défaut qu'ils corrigent.

**Ce qui manque aux deux analyses : un tri par coût.** C'est l'objet de la
section suivante.

---

## 5. Triage des corrections — la synthèse utile

### A. À corriger avant d'écrire du code (bloquant, bon marché)

Ces sept points ferment de vrais trous pour un coût faible. Les ignorer rend
l'invariant indéfendable.

| # | Correction | Coût estimé |
|---|---|---|
| 1 | Trois rôles `ingest` / `delivery` / `worker` (+ décision sur le routage) | ~1 h |
| 2 | Contraintes `CHECK` totales (`IS NOT DISTINCT FROM`) + tests `NULL` | ~45 min |
| 3 | `AVAILABLE` distinct de `CLEAN`, + état `PROMOTING` | ~2 h (automate + DDL) |
| 4 | Protocole de promotion récupérable + tests de points de crash | ~4 h |
| 5 | `RETRY_WAIT` / `FAILED_FINAL` (ou a minima `attempts < max` au claim) | ~1 h |
| 6 | `Semaphore` explicite devant l'antivirus | ~15 min |
| 7 | Image ClamAV épinglée par digest, `clamd.conf` committé, `AlertExceedsMax yes` | ~1 h |

**Total : environ 1,5 jour.**

### B. À faire si le budget le permet (bon rapport valeur/coût)

| Correction | Coût | Valeur |
|---|---|---|
| Token de fencing par claim | ~30 min | Ferme un trou étroit mais réel (worker gelé qui se re-claime lui-même) |
| `UNIQUE (object_key)` + création exclusive (`CREATE_NEW`) | ~15 min | Une convention devient une contrainte |
| Hash vérifié aux trois frontières (upload / scan / promotion) | ~2 h | Lie cryptographiquement le verdict aux octets servis |
| `409` + codes machine à la place de `403`/`503` | ~30 min | Correction sémantique |
| Rôles PostgreSQL séparés + vue `downloadable_file` | ~4 h | **Très fort** : une 5ᵉ ligne de défense sans composant |

### C. À retirer du palier 1 (gain de temps net)

Tous proposés par la contre-analyse, tous acceptés :

multipart · Tika · déduplication (verdict et stockage) · `rescan` · `DELETE` ·
SSE/webhook · Kafka · second adapter purement démonstratif · **traçage
distribué** *(ajout de ma part)*

### D. À documenter sans implémenter

Content-Digest / RFC 9530 · table `scan_attempt` · stockage objet (en
réévaluant MinIO) · upload direct · rescan planifié

---

## 6. Effet net sur le calendrier

| | Jours |
|---|---|
| Mon palier 1 initial (estimation révisée, honnête) | ~3,0 |
| \+ corrections bloquantes (§5.A) | +1,5 |
| − fonctions retirées (§5.C) | −1,0 |
| **Palier 1 corrigé, réaliste** | **≈ 3,5 jours** |
| \+ section §5.B complète | ≈ 4,5 jours |

Mon estimation de 2 jours était fausse. Leur fourchette de 4 à 6 jours est
probablement juste pour *leur* périmètre, mais celui-ci est plus large que
nécessaire. **Environ 3,5 jours** pour un palier 1 corrigé et amputé des
fonctions non exigées me paraît la cible tenable.

---

## 7. Recommandation d'action

Je rejoins leur point 2 de l'ordre de livraison, et je le placerais **avant**
la suite des arbitrages :

> **Deux spikes courts, une demi-journée au total, avant toute autre décision.**

| Spike | Question tranchée | Décisions débloquées |
|---|---|---|
| **1 — ClamAV réel** : lancer l'image épinglée, lire la conf effective, envoyer un fichier au-dessus de la limite, observer la réponse exacte, mesurer le premier démarrage | Quelles sont les vraies limites ? Comment se manifeste un dépassement ? `INSTREAM` ou wrapper ? | `D-15`, `P-18`, `P-19` |
| **2 — Streaming brut** : `POST` de 1 Go en `octet-stream`, relayé vers un volume, heap mesuré, `ATOMIC_MOVE` inter-volumes testé | Le streaming tient-il ? Quel protocole de promotion ? | `D-04`, `D-18` |

Ces deux expériences trancheront factuellement plus de questions que n'importe
quel document supplémentaire — y compris celui-ci. **Nos deux analyses ont
maintenant produit environ 130 Ko de raisonnement ; le rendement marginal d'un
troisième tour d'écriture est faible.**

---

## 8. Ce que cette confrontation apporte au livrable

Au-delà des corrections, elle produit un **récit de démarche de première
qualité**, à consigner dans le README :

> « Ma première conception isolait la quarantaine par des montages Docker. Une
> revue indépendante a montré que c'était impossible en deux rôles : le
> processus d'upload doit écrire là où le processus de livraison ne doit pas
> lire. J'ai séparé en trois rôles issus de la même image. La même revue a
> montré qu'une contrainte `CHECK` que je présentais comme une garantie était
> contournable par `NULL`, PostgreSQL n'écartant que `FALSE`. Les deux
> corrections ont ajouté des garanties, pas des produits. »

Un candidat capable de raconter **une erreur trouvée, son mécanisme exact et sa
correction** est plus crédible qu'un candidat dont l'architecture est
présentée comme parfaite dès la première itération. C'est aussi la meilleure
réponse possible à la question « comment avez-vous utilisé l'IA ? » : deux
analyses indépendantes, un désaccord factuel, un arbitrage humain.

---

## 9. Ce qu'il en est advenu (relu le 02/10)

### Les six défauts (§2)

| Défaut | Ce qui est livré |
|---|---|
| 2.1 Isolation à deux rôles | Trois **identités de stockage** (`ingest`, `worker`, `delivery`) dans **un seul processus** — et non trois profils Spring ni trois conteneurs ([ADR-0002](adr/0002-un-processus-trois-identites.md)). La question du routage entre `ingest` et `delivery` ne se pose donc plus : une seule adresse |
| 2.2 `CHECK` contournable par `NULL` | Prédicats totaux (`IS NOT DISTINCT FROM`), testés en injectant chaque `NULL` (`StoredFileConstraintsTest`). Et le rôle du service ne peut plus retirer une contrainte (rôles PostgreSQL séparés) |
| 2.3 Boucle sur les travaux terminaux | Les deux corrections : `RETRY_WAIT` / `FAILED_FINAL`, **et** `attempts < max` dans le claim |
| 2.4 Threads virtuels et limite de concurrence | Le `Semaphore` devant l'antivirus a été écrit, puis **retiré le 30/09** : les analyses sont tirées par un nombre fixe de boucles (4 par nœud), qu'il ne pouvait jamais bloquer. Le `Semaphore` explicite est sur les **dépôts** (50 par nœud), où rien d'autre ne borne ([ADR-0010](adr/0010-mvc-et-threads-virtuels.md)) |
| 2.5 Promotion ni atomique ni récupérable | Protocole reprenable, **sans temporaire ni renommage** : avec un stockage objet, l'objet est relu depuis la quarantaine, écrit sous sa clé finale, vérifié (taille, SHA-256), puis la ligne bascule par écriture conditionnelle. Le point de validation est la ligne ([ADR-0006](adr/0006-promotion-par-relecture-verifiee.md)). Cinq points de panne testés |
| 2.6 `CLEAN` confondait verdict et disponibilité | `AVAILABLE` et `PROMOTING` livrés (`D-19`). Ni réanalyse ni suppression ne sont livrées |

### Les corrections nuancées (§3)

| Point | Ce qui est livré |
|---|---|
| 3.1 `413` ou `UNSCANNABLE` | `413` au-delà de 500 Mo — le cadrage du 21/09 a tranché. Le seuil est un paramètre ; la politique ne l'est pas |
| 3.2 `201` ou `202` | `202`, avec `Location`, `Retry-After` et `downloadable: false` |
| 3.3 WebFlux | MVC + threads virtuels |
| 3.4 Axes plutôt qu'échelle | Un ADR par décision ([`adr/`](adr/README.md)) |
| 3.5 `409` | `409` avec un code machine par état |

### Les faits à vérifier (§4.1)

| Affirmation | Vérification |
|---|---|
| Dépôt communautaire MinIO archivé | **Exact** (archivé le 25 avril 2026) : SeaweedFS a été retenu |
| Valeurs par défaut de ClamAV | **Exactes** pour la version 1.4 (`StreamMaxLength` 100 Mo, `AlertExceedsMax` désactivé) ; les limites de l'image ont ensuite été mesurées ([ADR-0005](adr/0005-ordre-des-limites-de-l-antivirus.md)) |
| AWS S3 : 50 To par objet | **Exact** (depuis décembre 2025). Sans incidence : 500 Mo au plus ici |
| H2 et `SKIP LOCKED` | Non vérifié, sans incidence : H2 n'est pas utilisé, les tests visent PostgreSQL |
| Spring Boot 4.1.1 (§4.2) | Adopté (versions au README §8.1) |

### Le triage (§5)

| Bloc | Ce qui est livré |
|---|---|
| A — à corriger avant le code | Les sept points, avec les écarts ci-dessus (1, 4, 6). Point 7 : image épinglée par empreinte et `AlertExceedsMax yes` forcé par une image dérivée ; les limites sont réglées par variables dans `docker-compose.yml`, il n'y a pas de `clamd.conf` versionné |
| B — si le budget le permet | Jeton par claim, `UNIQUE (object_key)`, empreinte vérifiée au dépôt, à l'analyse et à la promotion, `409` : livrés. Rôles PostgreSQL séparés : livrés (audit S-03). La vue `downloadable_file` : non |
| C — à retirer | Tout a été retiré, sauf un second adaptateur d'antivirus, utile aux essais de capacité |
| D — à documenter | Le stockage objet a été **livré** (cadrage du 21/09). Envoi direct au stockage et réanalyse planifiée sont des pistes (README §10). `Content-Digest` et la table `scan_attempt` n'ont pas été repris |

### Les deux spikes (§7)

Faits : le stockage objet en flux
([B-005](prompts/B-005-stockage-et-depot.md)) et les limites de l'antivirus
([B-008](prompts/B-008-limites-antivirus-mesurees.md)). Le second a mis au jour
l'ordre nécessaire des limites du moteur ([ADR-0005](adr/0005-ordre-des-limites-de-l-antivirus.md)).

### Le récit (§8)

Il est au README (§9, « Ce que les mesures ont changé », et §11). La phrase
« j'ai séparé en trois rôles issus de la même image » y serait fausse : ce sont
trois identités de stockage dans un seul processus.
