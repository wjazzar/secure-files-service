# Plan d'exécution du back-end

> **Plan écrit le 2026-09-26, exécuté : tous les lots sont livrés** (§2).
> Analyse : Claude (Opus 5). Il remplaçait le premier plan de développement,
> qui fixait les lots B0 à B7 avant que l'infrastructure ne soit écrite et que
> le contrat ne passe en 1.3. Relu contre le code le 02/10 : chaque tâche dit ce
> qui a été fait, et ce qui a été fait autrement que prévu.
>
> L'architecture livrée est dans [`ARCHITECTURE.md`](ARCHITECTURE.md) ; les règles
> non négociables dans [`AGENTS.md`](AGENTS.md).

---

## 0. Le point de départ, au 26/09

Ce qui était **déjà fait** et changeait le plan initial :

| Acquis | Conséquence sur le plan |
|---|---|
| `docker-compose.yml` complet et documenté (PostgreSQL, SeaweedFS, antivirus en API HTTP, Keycloak), configurations versionnées | Le lot « socle d'infrastructure » du premier plan est **sans objet**. Il reste à **vérifier** que l'environnement fait ce qu'il promet |
| Antivirus exposé par une **API HTTP**, image dérivée forçant `AlertExceedsMax`, limites à 512 Mo (décision du 26/09) | Le spike antivirus ne choisit plus le moyen d'accès : il **mesure** et **vérifie** ce qui est en place |
| Nommage unifié sur **praxedo** (décision du 26/09) | Plus d'arbitrage à prendre au lot B1 : le `groupId` est `com.praxedo`, le paquet `com.praxedo.securefiles` |
| Contrat `openapi.yaml` 1.3.0 figé, front v1 développé dessus | Aucune conception d'API à faire : on implémente un contrat existant |
| Choix du stockage objet tranché (SeaweedFS), identités séparées définies | Le spike stockage se réduit à deux inconnues techniques précises (§B0) |
| Versions vérifiées le 26/09 (`ARCHITECTURE.md` §2) | Le lot B1 n'a plus de recherche à faire |

Ce qui **manquait** alors, et bloquait :

| Manque au 26/09 | Effet | Levé par |
|---|---|---|
| **Git non initialisé** | Aucun travail n'était réversible ; deux sessions écrivaient dans le même dossier | Lot B0.1 |
| **Maven absent de la machine** | Il fallait faire venir le wrapper avant la première compilation | Lot B1 (`mvnw`) |
| Aucun code back | Tout restait à écrire | Lots B1 à B9 |

---

## 1. Principes d'ordonnancement

1. **Le cœur d'abord, les bonus jamais avant.** Un parcours complet et testé vaut
   mieux que deux moitiés. Liste, recherche et compteurs sont déjà au contrat et
   au front : ils se font, mais **après** le téléchargement (lot B7).
2. **Chaque lot se termine par une preuve exécutable**, pas par « ça marche chez
   moi ». La colonne *Preuve* de chaque lot est une commande ou un test.
3. **Ce qui porte une garantie se teste en même temps qu'on l'écrit.** Le claim,
   les contraintes `CHECK` et la promotion ne se testent pas « à la fin » : leur
   test est la seule chose qui distingue une garantie d'une intention.
4. **Une entrée de journal par lot** (`docs/prompts/B-00N-*.md`), avec la rubrique
   « ce que j'ai rejeté ». C'est un livrable (`EX-13`), pas un supplément.
5. **Un ADR par décision du §0 de `ARCHITECTURE.md`**, écrit au moment du lot
   concerné, pas reconstitué à la fin.

---

## 2. Vue d'ensemble des lots

| Lot | Objet | Budget | Dépend de | État |
|---|---|---|---|---|
| **B0** | Git, puis deux spikes mesurés | ½ j | — | ✅ **fait** — stockage (B-005) ; antivirus : ⭐ limites **mesurées**, un faux négatif trouvé et fermé (B-008) |
| **B1** | Socle Maven (module unique), CI, Flyway, sondes | ½ j | B0 | ✅ **fait** — la CI, écrite alors, a été retirée le 01/10 (exercice, décision du porteur du projet) |
| **B2** | Domaine pur : automate, verdict, attestation | ½ j | B1 | ✅ **fait** — 86 tests à la livraison du lot |
| **B3** | Persistance : schéma, contraintes totales, requêtes critiques | 1 j | B1, B2 | ✅ **fait** — 35 tests à la livraison du lot |
| **B4** | Ingestion en flux, idempotence, admission | 1 j | B3 | ✅ **fait** — 500 Mo à travers un tas de 256 Mo (B-005) |
| **B5** | Worker : claim, analyse, verdict, réessais, reaper | 1 j | B3, B4 | ✅ **fait** — vrai ClamAV : sain disponible, EICAR bloqué (B-006) |
| **B6** | Promotion vérifiée + service du contenu (plages d'octets) | 1 j | B5 | ✅ **fait** — **point d'intégration atteint** (B-007). Les liens signés livrés avec ce lot ont été retirés le 29/09 (ADR-0013) |
| **B7** | Liste, recherche, compteurs, détail — puis finition | ½ j | B6 | ✅ **fait** — lectures (B-004), métriques, journal d'audit (B-009) |
| **B8** | Keycloak (prévu optionnel) | ½ j | B7 | ✅ **fait** — vérifié contre le vrai realm (B-009) ; d'abord désactivable, l'authentification est **toujours exigée** depuis le 29/09 (ADR-0014), avec la session du navigateur (ADR-0012) |
| **B9** | README, démonstration, ADR, journal des prompts | ½ j | B7 | ✅ **fait** — dépôt publié par le porteur du projet |

**Budget cœur prévu : ~6 jours** (B0 à B7), ~7 avec la finition. Le lot B8
ne devait être engagé qu'une fois le reste terminé.

---

## 3. Le détail, lot par lot

### B0 — Git, puis deux spikes *(½ jour, avant tout code)*

**B0.1 — Initialiser le dépôt.** Première action, non négociable : deux sessions
écrivent dans le même dossier.

```bash
git init -b main
# .gitignore : target/, node_modules/, dist/, .idea/, .claude/settings.local.json, *.env
git add -A && git commit -m "chore: scoping, API contract, local infrastructure and frontend v1"
```

**B0.2 — Vérifier l'infrastructure existante.** Les cinq vérifications de
[`../infra/README.md`](../infra/README.md) §6, exécutées et **consignées** :
services sains, zones créées, `delivery` incapable de lire la quarantaine, EICAR
détecté, Keycloak délivrant un jeton. ✅ Passées ; `scripts/check.sh` et
`check.ps1` les rejouent depuis.

**B0.3 — Spike antivirus (API HTTP).** Quatre inconnues, quatre mesures :

| Question | Mesure attendue | État |
|---|---|---|
| `AlertExceedsMax` est-il présent dans la configuration effective ? | Lire la configuration du conteneur en marche | ✅ **fait le 26/09** : `AlertExceedsMax yes`, limites à 512M/1024M |
| Que renvoie l'API sur un fichier sain et sur EICAR ? | Codes et corps littéraux | ✅ **fait** : `200` `{OK   200}` / `406` `{FOUND Eicar-Test-Signature  406}` ; corps **non JSON** malgré l'en-tête |
| ⭐ Un dépassement de `MaxScanSize` remonte-t-il bien autre chose qu'un `200` ? | Archive dont le volume décompressé dépasse la limite | ✅ **fait le 27/09 (B-008)** : oui pour `MaxScanSize` — mais **non** pour une entrée plus grosse que `MaxFileSize` quand `MaxFileSize` ≤ `MaxScanSize` : tronquée en silence, archive déclarée saine. Corrigé (2000M > 1024M, garde au démarrage, test contre le vrai moteur) |
| Le corps est-il vraiment relayé **en flux** ? | Envoyer 500 Mo, observer la mémoire du conteneur **et** du client : elle doit rester plate | ✅ côté service : 500 Mo analysés de bout en bout (`demo.sh --big`), tas borné prouvé à 256 Mo ; mémoire du conteneur antivirus non instrumentée |
| Combien coûte une analyse de 500 Mo ? | Durée et mémoire, pour caler le bail, le timeout de lecture et le nombre de boucles d'analyse | ✅ mesuré (B-008) : ~30 s pour une archive qui se décompresse en 600 Mo, ~45 s pour 1,2 Go ; le fichier de 500 Mo de `demo.sh --big` passe sans approcher le bail. Bail et timeout (10 min) gardent une marge d'un ordre de grandeur |

**B0.4 — Spike stockage objet.** Deux inconnues **précises**, pas une exploration :

| Question | Pourquoi elle est bloquante | État |
|---|---|---|
| Le SDK AWS v2 calcule des sommes de contrôle par défaut sur `PutObject` : SeaweedFS les accepte-t-il ? | C'est l'incompatibilité classique entre le SDK et les stockages non-AWS. Si elle existe, il faut le savoir avant d'écrire l'adaptateur, pas pendant | ✅ **fait (B-005)** : non — avec les réglages par défaut, la somme de contrôle finissait **dans** l'objet stocké. Corrigé par `WHEN_REQUIRED` (`S3Clients`), revérifié à chaque build (`ObjectStorageTest`) |
| Un `PUT` de 500 Mo depuis un `InputStream` de taille connue passe-t-il **sans tampon** ? | C'est l'hypothèse sur laquelle repose toute la tenue en mémoire | ✅ **fait (B-005)** : oui, en un seul passage ; prouvé depuis par `UploadMemoryTest` (tas de 256 Mo) |

**Livrable** : les valeurs mesurées, dans
[`docs/prompts/B-005`](../docs/prompts/B-005-stockage-et-depot.md) (stockage) et
[`B-008`](../docs/prompts/B-008-limites-antivirus-mesurees.md) (antivirus). Le
code du spike est jeté.

**Preuve** : les notes existent, avec des chiffres dedans ; les cinq
vérifications de l'infrastructure sont passées.

---

### B1 — Socle *(½ jour)*

**Faire venir Maven.** Maven n'est pas installé : le wrapper doit venir d'ailleurs.
Le plus simple et le plus vérifiable est de générer une amorce chez Spring, qui
embarque `mvnw` :

```bash
curl -s https://start.spring.io/starter.zip \
  -d type=maven-project -d bootVersion=4.1.1 -d javaVersion=21 \
  -d groupId=com.praxedo -d artifactId=securefiles -d packageName=com.praxedo.securefiles \
  -d dependencies=webmvc,jdbc,flyway,validation,actuator \
  -o starter.zip
```

On en garde le wrapper et le `pom.xml` de référence. **Un seul module** :
décision du porteur du projet (27/09), l'application est petite. Les couches
sont des paquets, et c'est ArchUnit qui tient la frontière.

Nommage fixé par le porteur du projet : **praxedo** partout — `groupId`
`com.praxedo`, paquet `com.praxedo.securefiles`, métriques `praxedo.*`,
conteneurs et identités de stockage `praxedo-*` (déjà appliqué à
`docker-compose.yml` et `infra/**`).

**Tâches**

- [x] `pom.xml` unique : versions figées (`ARCHITECTURE.md` §2), JDK 21
      verrouillé par l'`enforcer`
- [x] Couches en paquets, dépendances dirigées vers l'intérieur, **vérifiées par
      ArchUnit** (c'est la seule barrière depuis l'abandon du multi-module)
- [x] Propriétés typées. Les profils `ingest`, `delivery`, `worker`, `all`,
      prévus ici, n'ont **pas** été faits : un seul processus porte les trois
      rôles (décision du 27/09, ADR-0002)
- [x] Flyway branché, datasource, HikariCP (deux pools depuis le 28/09)
- [x] Actuator : sondes de vivacité et de disponibilité — non différenciées
      par rôle, pour la même raison
- [x] Premier test ArchUnit : `domain` ne voit pas Spring
- [x] CI GitHub Actions (`./mvnw verify`) — écrite, puis **retirée** le 01/10 :
      pas de CI pour un exercice, décision du porteur du projet
- [x] Service applicatif ajouté au `docker-compose.yml` (profil `app`)

**Preuve** : `./mvnw verify` passe sur une machine sans Maven ; `docker compose
--profile app up` démarre tout ; `/actuator/health` répond, sur le port de
management.

---

### B2 — Domaine *(½ jour)*

- [x] `FileStatus` (8 états), `PublicStatus` (6), projection, `isDownloadable()`,
      `isTerminal()`
- [x] `StoredFile` : transitions légales uniquement ; toute transition illégale
      lève
- [x] `ScanVerdict`, `ScanResult` — l'attestation n'est pas un type à part :
      c'est le verdict, **lié au SHA-256** analysé (`scannedContent`)
- [x] `StatusReason` aligné sur l'énumération du contrat
- [x] Ports — sous leurs noms définitifs : `QuarantineWriter`,
      `ServableReader`, `WorkerStorage` (le `FileContentStore` prévu, découpé
      par rôle), `AntivirusScanner`, `FileCatalog` et `FileWorkQueue` (le
      `FileRepository` prévu, découpé entre lectures et file de travail)

**Preuve** : tests unitaires sans Spring, exécution en moins d'une seconde ; un
état ajouté sans être classé ne compile pas, et `FileStatusTest` vérifie chaque
valeur de l'énumération (`EnumSource`).

---

### B3 — Persistance *(1 jour)*

- [x] Migrations (`ARCHITECTURE.md` §6.3) — `V1` à `V4` à ce lot, `V1` à `V9`
      aujourd'hui
- [x] Insertion, lecture, liste paginée, compteurs : `JpaFileCatalog` (JPA
      pour les lectures, décision du 27/09), et non le `JdbcFileRepository`
      prévu
- [x] Les trois requêtes critiques : claim, verdict, reaper (`JdbcFileWorkQueue`)
- [x] `JdbcIdempotencyStore` ; l'audit des transitions est écrit par un
      **trigger** (`V6`), celui des téléchargements par `JdbcDownloadAudit`
      — et non par le `JdbcAuditLog` prévu
- [x] Requête d'assertion d'invariant exposée en indicateur et en métrique

**Preuve** — trois tests, sur PostgreSQL réel :

1. **`NULL` systématiques** : pour chaque champ de verdict, insérer un
   `AVAILABLE` avec ce champ à `NULL` → **toutes les insertions échouent**.
2. **Claim concurrent** : 8 threads, 8 lignes en attente → 8 prises distinctes,
   aucune ligne prise deux fois, aucune attente.
3. **Worker zombie** : un verdict écrit avec un jeton de bail périmé modifie
   **zéro** ligne.

---

### B4 — Ingestion *(1 jour)*

- [x] `POST /files` en `application/octet-stream`, corps lu en flux
- [x] SHA-256, comptage d'octets et détection de type **en un seul passage**
- [x] Contrôles : `411`, `400 EMPTY_FILE`, `413`, `400 INVALID_FILE_NAME`,
      `400 CONTENT_LENGTH_MISMATCH` — et, depuis le 30/09, `408 UPLOAD_TOO_SLOW`
- [x] Idempotence complète (`202` rejoué, `409`, `422`)
- [x] Admission : `429 TOO_MANY_PENDING_FILES` — et, depuis les campagnes de
      capacité, `429 TOO_MANY_CONCURRENT_UPLOADS` (50 dépôts par nœud)
- [x] Multipart désactivé, `max-swallow-size` borné (64 Ko)
- [x] Balayage des orphelins

**Preuve** : dépôt de 500 Mo avec **heap borné mesuré** ; deux dépôts concurrents
avec la même clé d'idempotence → un seul fichier, une seule réponse ; une panne
simulée entre l'écriture de l'objet et le commit laisse un orphelin, que le
balayage ramasse.

---

### B5 — Analyse *(1 jour)*

- [x] Adaptateur HTTP de l'antivirus : `POST /scanHandlerBody` en flux,
      `GET /version` en cache, `GET /` pour le portillon, timeouts explicites
- [x] Boucle du worker : claim, analyse en flux, verdict, libération du bail
- [x] Traduction des réponses : `200`/`406`/`412`/`413`, et surtout
      `406 Heuristics.Limits.Exceeded` → `UNSCANNABLE`, **pas** `INFECTED`
- [x] Réessais persistés, backoff avec *jitter*, `FAILED_FINAL` aux essais épuisés
- [x] Borne de concurrence (un nombre fixe de boucles séquentielles), portillon de santé
- [x] Reaper des baux expirés
- [x] Arrêt propre : libération des baux sans consommer de tentative

**Preuve** : EICAR → `INFECTED`, jamais servi (`ScanPipelineTest`) ; antivirus
arrêté → ingestion et téléchargements intacts, **aucune tentative consommée**
(`FileScanServiceTest`, `demo.sh --resilience`) ; bail expiré → le fichier
repart seul (`FilePersistenceTest`, `AuditTrailTest`) ; les modes de panne
simulés par WireMock (lenteur, coupure, moteur absent, `412`, `413`, erreur
serveur, `406 Heuristics.Limits.Exceeded`) produisent chacun le bon résultat
(`HttpAntivirusScannerTest`).

---

### B6 — Promotion et service *(1 jour)* — **jalon**

- [x] Protocole de promotion reprenable (`ARCHITECTURE.md` §7.4)
- [x] Vérification taille + SHA-256 avant bascule ; refus = incident tracé
- [x] `GET /files/{id}/content` avec l'identité de l'appelant : revérification,
      plages d'octets, en-têtes de sécurité (ADR-0013)
- [x] Table de décision complète des réponses (`ARCHITECTURE.md` §8.6)
- [x] Reaper des `PROMOTING` expirés. La purge des temporaires, prévue, est
      sans objet : la promotion écrit directement sous la clé finale

**Preuve** : les **cinq interstices** du §7.4 testés un par un ; lecture directe
de la quarantaine avec les identifiants `delivery` refusée ; un fichier qui
cesse d'être servable n'est pas servi.

> **Point d'intégration** : quand B6 est vert, le front bascule de MSW vers le
> vrai service. Voir §4.

---

### B7 — Liste, détail, compteurs, puis finition *(½ jour)*

- [x] `GET /files` : pagination, tri en liste blanche, filtre par statut public,
      recherche trigramme
- [x] `GET /files/summary`
- [x] `GET /files/{id}` avec `ETag` / `304` / `Retry-After`
- [x] Métriques (`ARCHITECTURE.md` §12), journalisation structurée
- [x] Test de conformité au contrat

**Preuve** : le tableau du front fonctionne sur le vrai service, filtres et
compteurs compris ; le test de conformité passe.

---

### B8 — Keycloak *(½ jour, prévu optionnel)*

Prévu pour n'être engagé **que** si B7 était terminé ; livré, puis rendu
obligatoire (ADR-0014).

- [x] Serveur de ressources JWT : signature (JWKS), émetteur, audience
- [x] `owner_id = sub` ; cloisonnement sur **toutes** les requêtes
- [x] `401` conforme, `404` pour le fichier d'autrui, **jamais** `403`
      d'autorisation
- [x] Contrat : retrait de `{}` de la liste `security` (révision 1.7)
- [x] Alignement du realm avec le contrat (voir `ARCHITECTURE.md` §17, dérive
      4) : nommage, suppression des rôles inutilisés
- [x] Le front bascule de l'adaptateur simulé vers Keycloak — par la session
      du navigateur, tenue par le service (ADR-0012)

**Preuve** : un utilisateur ne voit pas les fichiers d'un autre, même par
adresse directe ; une requête sans preuve d'identité répond `401`
(`OidcSecurityTest`, `BrowserSessionTest`).

---

### B9 — Livraison *(½ jour)*

- [x] **README** : ce qu'on a construit, les choix et leurs raisons, les
      hypothèses (volumétrie en tête) **avec ce qui les a motivées**, les limites
      connues **avec le déclencheur qui les lèverait**
- [x] ADR des décisions du §0 de l'architecture (`docs/adr/`, acceptés le 01/10)
- [x] Journal des prompts (`B-000` à `B-019`)
- [x] Scénario de démonstration reproductible (`scripts/demo.sh`) : fichier
      sain, EICAR, 500 Mo (`--big`), antivirus arrêté (`--resilience`). Le
      worker tué n'est pas dans le script : la reprise d'un bail expiré est
      prouvée par les tests (`FilePersistenceTest`)
- [x] Dépôt public poussé

---

## 4. Point d'intégration avec le front

Quand B6 est vert :

1. le front bascule de MSW vers le vrai service (proxy Vite vers `:8080`) ;
2. parcours vérifiés à la main : fichier sain, EICAR, fichier de 500 Mo ;
3. tout écart se corrige **dans le contrat d'abord**, puis des deux côtés.

Le branchement se fait sur Keycloak (B8) : l'authentification est toujours
exigée, il n'existe plus de mode anonyme, ni côté service ni côté interface
(29/09, ADR-0014).

---

## 5. Risques et parades

| Risque | Probabilité | Parade |
|---|---|---|
| Le SDK AWS et SeaweedFS ne s'entendent pas sur les sommes de contrôle | Moyenne | Mesuré **au spike B0.4**, avant d'écrire l'adaptateur. Repli : désactiver le calcul par défaut, documenté |
| Spring Boot 4 : friction d'écosystème (Jackson 3, starters renommés) | Moyenne | Versions vérifiées le 26/09 ; le socle B1 est justement là pour rencontrer la friction **avant** le code métier |
| Le conteneur antivirus rend la suite de tests lente (démarrage 1-2 min, 1-2 Go) | Élevée | WireMock pour les pannes ; le conteneur réel pour deux classes seulement (`ScanPipelineTest`, `AntivirusEngineLimitsTest`), démarré une fois. L'étiquette d'exclusion prévue n'a pas été posée |
| **Dépendance à un enrobage HTTP tiers** sur le chemin de sécurité | Moyenne | Image épinglée par digest, chemin de scan lu dans son code source, `AlertExceedsMax` forcé par une image dérivée dont le build échoue si la configuration bouge, et repli documenté sur le protocole natif derrière le même port |
| Le heap explose sur 500 Mo sans qu'on le voie | Moyenne | Le test mémoire est écrit **au lot B4**, pas à la fin ; il tourne avec un heap volontairement petit |
| Les bonus grignotent le cœur | Moyenne | B7 vient après B6, et B8 après B7. L'ordre est le garde-fou |
| Les deux sessions se marchent dessus | Faible | Règle de propriété des fichiers (`AGENTS.md` §4.1) ; Git initialisé en B0.1 |
| Le README est repoussé à la fin et bâclé | Élevée | Hypothèses et pistes notées **au fil de l'eau**, dans les ADR : B9 les assemble, il ne les invente pas |

---

## 6. Ce qui restait après les lots

**Tous les lots sont livrés.** Ce qui restait n'appartenait pas à la session
back-end :

1. ~~**Brancher le front** sur le vrai service (§4)~~ — fait (session front) ;
2. ~~**Signer les ADR**~~ — fait : acceptés par le porteur du projet le 01/10 ;
3. ~~**Publier le dépôt**~~ — fait par le porteur du projet ;
4. Signaler en amont l'angle mort zip64 de ClamAV (B-008) — reste à la main du
   porteur du projet, c'est une action publique.
