# Scénario 3 — Analyser et promouvoir (`ScanFilesUseCase`)

**Le scénario** : le `rapport.pdf` déposé au [scénario 1](1-deposer.md) attend
en `AWAITING_SCAN`. Une boucle du worker le prend, l'envoie à ClamAV, reçoit
« sain », le recopie dans la zone servable en vérifiant chaque octet, et le rend
`AVAILABLE`.

Personne n'appelle ce port par HTTP : c'est le **cycle de vie de
l'application** qui démarre les boucles.

## La carte

```
démarrage Spring (SmartLifecycle)
└─ [scheduling] ScanWorkerPool.start ........................................ l.66
   └─ 4 threads virtuels « scan-worker-N », chacun : loop() .................. l.75
      └─ [port in] ScanFilesUseCase.processNext
         └─ [service] FileScanService.processNext ........................... l.105
            ├─ [port out] AntivirusScanner.isAvailable ─▶ Metered ─▶ HttpAntivirusScanner l.97
            ├─ [port out] FileWorkQueue.claimNextDue ─▶ JdbcFileWorkQueue ... l.109  (SQL : le claim)
            └─ handle() ........................................................ l.133
               ├─ analyse() — dans le bail ..................................... l.186
               │  ├─ [port out] WorkerStorage.openQuarantined ─▶ S3WorkerStorage l.36
               │  └─ [port out] AntivirusScanner.scan ─▶ Metered ─▶ HttpAntivirusScanner l.77
               ├─ StoredFile.scanned (domaine) ................................. l.194
               ├─ [port out] FileWorkQueue.writeVerdict ─▶ JdbcFileWorkQueue ... l.152  (SQL conditionnel)
               └─ [service] FilePromotionService.promote ...................... l.74
                  ├─ WorkerStorage.openQuarantined + writeServable ─▶ S3WorkerStorage l.36 / l.45
                  ├─ StoredFile.promoted (domaine) ............................. l.221
                  ├─ [port out] FileWorkQueue.markAvailable ─▶ JdbcFileWorkQueue l.214 (SQL : CAS)
                  └─ WorkerStorage.deleteQuarantined ─▶ S3WorkerStorage ........ l.50
```

## Pas à pas

### Étape 1 — Les boucles démarrent avec l'application

[`ScanWorkerPool`](../src/main/java/com/praxedo/securefiles/infrastructure/scheduling/file/scheduler/ScanWorkerPool.java)
(actif si `praxedo.worker.enabled`, l. 40)

| Ligne | Ce qui se passe |
|---|---|
| [52-64](../src/main/java/com/praxedo/securefiles/infrastructure/scheduling/file/scheduler/ScanWorkerPool.java#L52) | Constructeur : `praxedo.worker.concurrency` inférieur à 1 est refusé au démarrage — ne pas analyser sur un nœud se dit par `praxedo.worker.enabled=false` (l. 56-59) |
| [66-73](../src/main/java/com/praxedo/securefiles/infrastructure/scheduling/file/scheduler/ScanWorkerPool.java#L66) | `start()` : Spring l'appelle une fois le contexte prêt. **4 threads virtuels** (`praxedo.worker.concurrency`) : **c'est la borne des analyses simultanées**. Une boucle mène une analyse à la fois et rien d'autre n'appelle le service : 4 boucles, 4 analyses au plus. Les threads virtuels n'y changent rien — ils retirent la limite d'un pool sur des tâches *soumises*, pas celle d'un nombre fixe de boucles |
| [75-88](../src/main/java/com/praxedo/securefiles/infrastructure/scheduling/file/scheduler/ScanWorkerPool.java#L75) | Chaque boucle appelle `workerUseCase.processNext()` (l. 79) et **attend son retour** avant de prendre le fichier suivant. `false` = rien à faire → pause de 2 s (`poll-interval`, l. 90-96). Une exception inattendue est journalisée, la boucle continue (l. 80-82) |

### Étape 2 — Le portillon, puis le claim

[`FileScanService.processNext`](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L105)

| Ligne | Ce qui se passe |
|---|---|
| [107](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L107) | **Portillon de santé AVANT le claim** : [`MeteredAntivirusScanner.isAvailable`](../src/main/java/com/praxedo/securefiles/infrastructure/antivirus/file/adapter/MeteredAntivirusScanner.java#L66) (met à jour la jauge `praxedo.antivirus.available`) → [`HttpAntivirusScanner.isAvailable`](../src/main/java/com/praxedo/securefiles/infrastructure/antivirus/file/adapter/HttpAntivirusScanner.java#L97) : `GET /`, 2 s maximum. Antivirus en panne → `false` : **aucun fichier pris, aucune tentative consommée** |
| [110](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L110) | Un **jeton de bail** neuf, propre à ce claim |
| [112-113](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L112) | [`JdbcFileWorkQueue.claimNextDue`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JdbcFileWorkQueue.java#L108) — **une seule instruction** (l. 110-135) : choisit le fichier dû le plus ancien (états dérivés de l'automate par `FileStatusSql`, `next_attempt_at ≤ now`, `attempts < 5`), l'**exclut des autres workers** (`FOR UPDATE SKIP LOCKED`), pose le bail (`SCANNING`, jeton, détenteur, échéance de 30 s + 1,2 s par Mio sur l'horloge **de la base**), compte la tentative, `RETURNING` la ligne. Trigger → `CLAIMED` |
| [120-122](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L120) | File vide → `false` |
| [123-129](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L123) | Le claim est noté en cours (pour l'arrêt propre, étape 7) ; le MDC porte `fileId` : chaque ligne de journal de ce traitement le mentionne |

### Étape 3 — L'analyse : les octets lus, hachés et envoyés en même temps

[`FileScanService.analyse`](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L186)

| Ligne | Ce qui se passe |
|---|---|
| [187-189](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L187) | [`S3WorkerStorage.openQuarantined`](../src/main/java/com/praxedo/securefiles/infrastructure/storage/file/adapter/S3WorkerStorage.java#L36) : `GetObject` sur `quarantine`, identité **`worker`** — un flux, pas un tableau d'octets |
| [190](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L190) | [`DeadlineInputStream`](../src/main/java/com/praxedo/securefiles/application/common/io/DeadlineInputStream.java) : la lecture s'arrête **à la fin du bail que la base a accordé** (30 s + 1,2 s par Mio) : sa durée est lue dans la ligne réclamée (`lease_expires_at` − `status_changed_at`, deux instants de l'horloge de la base), puis mesurée sur celle du nœud — jamais recalculée, et les deux horloges ne sont jamais comparées. Au-delà, le verdict serait refusé de toute façon — son écriture est conditionnée au jeton — : un stockage qui envoie au compte-gouttes ou un moteur qui lit lentement ne gardent donc pas la place d'analyse pour rien. Le fichier repart aussitôt en file, sans attendre le *reaper* ([l. 195-197](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L195)) |
| [191](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L191) | `InspectingInputStream` : le worker **mesure lui-même** ce qu'il envoie |
| [194](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L194) | `scanner.scan(...)` — appelé directement, **sans sémaphore** : le nombre de boucles (étape 1) borne déjà les analyses simultanées. Un sémaphore à autant de permis que de boucles ne pourrait jamais bloquer ; pris après le claim, il consommerait en outre le bail s'il devait attendre |
| [Metered l. 51](../src/main/java/com/praxedo/securefiles/infrastructure/antivirus/file/adapter/MeteredAntivirusScanner.java#L51) | Le décorateur compte l'analyse en cours, la chronomètre, compte le verdict ou la panne |
| [Http l. 80](../src/main/java/com/praxedo/securefiles/infrastructure/antivirus/file/adapter/HttpAntivirusScanner.java#L80) | Version du moteur et des signatures lue **avant** l'analyse (cache d'une minute, [l. 144](../src/main/java/com/praxedo/securefiles/infrastructure/antivirus/file/adapter/HttpAntivirusScanner.java#L144)) |
| [Http l. 84-88](../src/main/java/com/praxedo/securefiles/infrastructure/antivirus/file/adapter/HttpAntivirusScanner.java#L84) | `POST /scanHandlerBody`, `Content-Length` connu (l. 86), corps écrit **en flux** : `content.transferTo(out)` (l. 87) |
| [Http l. 106-122](../src/main/java/com/praxedo/securefiles/infrastructure/antivirus/file/adapter/HttpAntivirusScanner.java#L106) | Traduction : `200` → `CLEAN` ; `406` → `INFECTED`, **sauf** `Heuristics.Limits.Exceeded…` → `UNSCANNABLE` ; `412` → `UNSCANNABLE` ; tout autre code → `ScannerUnavailableException`. Une coupure ou un délai dépassé aussi (l. 91). **Une panne n'est jamais un verdict** |
| [198-200](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L198) | Le moteur a répondu sans avoir tout lu → panne, pas verdict |
| [201-202](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L201) | Le `ScanVerdict` est construit **par le worker**, avec l'empreinte **des octets réellement envoyés** (`measured.digest()`) : l'adaptateur antivirus ne peut pas attester un contenu qu'il n'a pas mesuré |

### Étape 4 — Le verdict, écrit sous condition du bail

[`FileScanService.handle`](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L133)

| Ligne | Ce qui se passe |
|---|---|
| [143-148](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L143) | Empreinte analysée ≠ empreinte du dépôt → le verdict **n'est jamais écrit**, c'est une panne (stockage altéré) |
| [150](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L150) | [`StoredFile.scanned`](../src/main/java/com/praxedo/securefiles/domain/file/model/StoredFile.java#L194) — l'automate : seulement depuis `SCANNING` (l. 196), empreinte revérifiée (l. 199). `CLEAN` → **`PROMOTING`** en gardant le bail (l. 205) — **pas `AVAILABLE`** ; `INFECTED` → `INFECTED` ; `UNSCANNABLE` → `UNSCANNABLE`. Renvoie une **nouvelle** instance : rien ne s'écrit par effet de bord |
| [152](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L152) | [`JdbcFileWorkQueue.writeVerdict`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JdbcFileWorkQueue.java#L151) : `UPDATE … WHERE id = :id AND status = 'SCANNING' AND lease_token = :claim AND lease_expires_at > now` (l. 179-182). Le bail est renouvelé pour la promotion, en proportion de la taille : 30 s + 1,8 s par Mio (l. 172-175). Trigger → `VERDICT` (menace, versions moteur et signatures dans `details`) |
| [152-154](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L152) · [`written`](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L226) | `written` renvoie un simple oui/non et dit lui-même pourquoi rien n'est écrit : 0 ligne modifiée = **bail perdu** (le worker a gelé, le *reaper* a repris le fichier) → le résultat est jeté et journalisé ; pour un verdict, compteur `praxedo.scan.verdict.rejected` (l. 230-233) ; base injoignable → trois essais, délai doublé, puis le bail prend le relais (l. 235-242) |
| [157-165](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L157) | `PROMOTING` → promotion. `INFECTED` ou `UNSCANNABLE` : **fin du parcours**, l'objet reste en quarantaine, jamais servi |

### Étape 5 — La promotion : recopier, revérifier, basculer

[`FilePromotionService.promote`](../src/main/java/com/praxedo/securefiles/application/file/service/FilePromotionService.java#L74)

| Ligne | Ce qui se passe |
|---|---|
| [82-85](../src/main/java/com/praxedo/securefiles/application/file/service/FilePromotionService.java#L82) | Relecture de la quarantaine — bornée par le **bail de promotion** (30 s + 1,8 s par Mio) : au-delà, le *compare-and-set* serait refusé, la copie s'arrête et le fichier repart en file ([l. 86-90](../src/main/java/com/praxedo/securefiles/application/file/service/FilePromotionService.java#L86)) —, hachée au passage, écrite **directement sous sa clé finale** dans `servable` ([`S3WorkerStorage.writeServable`](../src/main/java/com/praxedo/securefiles/infrastructure/storage/file/adapter/S3WorkerStorage.java#L45)). Pas de `CopyObject` : une copie côté serveur ne rendrait pas d'empreinte |
| [93-97](../src/main/java/com/praxedo/securefiles/application/file/service/FilePromotionService.java#L93) | **Dernier contrôle** : taille et SHA-256 de la copie = ceux attestés. Sinon, la copie est supprimée et le fichier retourne en file |
| [99](../src/main/java/com/praxedo/securefiles/application/file/service/FilePromotionService.java#L99) | [`StoredFile.promoted`](../src/main/java/com/praxedo/securefiles/domain/file/model/StoredFile.java#L221) : refuse sans attestation `CLEAN` de **cette** empreinte ([`ScanVerdict.attestsCleanFor`](../src/main/java/com/praxedo/securefiles/domain/file/model/ScanVerdict.java#L70)) → `AVAILABLE`, zone `SERVABLE` |
| [100](../src/main/java/com/praxedo/securefiles/application/file/service/FilePromotionService.java#L100) | [`JdbcFileWorkQueue.markAvailable`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JdbcFileWorkQueue.java#L213) : **compare-and-set** sur le jeton (l. 226-229). Les contraintes [`available_requires_attestation`](../src/main/resources/db/migration/V2__stored_file.sql#L85) et [`servable_area_requires_available`](../src/main/resources/db/migration/V2__stored_file.sql#L95) refuseraient toute ligne `AVAILABLE` sans attestation propre. Trigger → `PROMOTED` |
| [100-105](../src/main/java/com/praxedo/securefiles/application/file/service/FilePromotionService.java#L100) | Bail perdu pendant la copie → on laisse faire le nouveau propriétaire : même contenu, même clé |
| [107-112](../src/main/java/com/praxedo/securefiles/application/file/service/FilePromotionService.java#L107) | La source en quarantaine est supprimée ; un échec ici est sans gravité, le balayage la retrouvera ([scénario 5](5-entretenir.md)) |

### Étape 6 — Les pannes : jamais un verdict, toujours une nouvelle tentative

| Où | Ce qui se passe |
|---|---|
| [`handle` l. 135-141](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L135) | Antivirus muet, stockage en panne, objet absent, E/S, interruption, bail dépassé → [`recordFailure`](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L208) |
| [`StoredFile.technicalFailure`](../src/main/java/com/praxedo/securefiles/domain/file/model/StoredFile.java#L241) | `RETRY_WAIT`, ou `FAILED_FINAL` si les 5 tentatives sont épuisées |
| [`JdbcFileWorkQueue.writeTechnicalFailure`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JdbcFileWorkQueue.java#L241) | Conditionné au jeton ; `next_attempt_at` = maintenant + délai croissant avec *jitter* ([`WorkerSettings.retryDelay`](../src/main/java/com/praxedo/securefiles/application/file/model/WorkerSettings.java#L39)). Trigger → `TECHNICAL_FAILURE`. Persisté : survit au redémarrage |

### Étape 7 — L'arrêt propre

[`ScanWorkerPool.stop`](../src/main/java/com/praxedo/securefiles/infrastructure/scheduling/file/scheduler/ScanWorkerPool.java#L98) :
plus de nouvelle prise, 20 s pour finir (l. 101-105), interruption des
retardataires, puis `releaseInFlight()` (l. 107) →
[`FileScanService.releaseInFlight`](../src/main/java/com/praxedo/securefiles/application/file/service/FileScanService.java#L266) →
[`StoredFile.releasedOnShutdown`](../src/main/java/com/praxedo/securefiles/domain/file/model/StoredFile.java#L259)
(**la tentative est rendue** : rien n'a été conclu) →
[`JdbcFileWorkQueue.releaseOnShutdown`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JdbcFileWorkQueue.java#L281).
Trigger → `RELEASED`. Un arrêt brutal, lui, est rattrapé par le *reaper*
([scénario 5](5-entretenir.md)).

## Ce qui a changé, au bout du scénario

| Où | Quoi |
|---|---|
| Seau `servable` | Objet `<uuid>`, identique à l'octet près (SHA-256 vérifié) |
| Seau `quarantine` | Objet supprimé |
| `stored_file` | `AVAILABLE`, `SERVABLE`, verdict `CLEAN` lié à `content_sha256`, 1 tentative, plus de bail |
| `file_audit_event` | `CLAIMED` → `VERDICT` → `PROMOTED`, acteur : l'identifiant du worker (processus et hôte) |

## Points d'arrêt conseillés

`FileScanService.processNext` l. 113 · `FileScanService.analyse` l. 201 ·
`HttpAntivirusScanner.translate` l. 107 · `FileScanService.handle` l. 152 ·
`FilePromotionService.promote` l. 93 et l. 100.

## Rejouer

`ScanPipelineTest` (vrai ClamAV : sain → promu, la copie servable identique à
l'octet près ; EICAR → bloqué, jamais copié dans la zone servable), `FileScanServiceTest` et `FilePromotionServiceTest` (chaque branche et
chaque point d'interruption, sans Spring), `ScanWorkerPoolTest` (jamais plus
d'analyses simultanées que de boucles, contre une file qui ne se vide jamais), `HttpAntivirusScannerTest` (chaque
réponse du moteur simulée par WireMock), `AntivirusEngineLimitsTest` (les
limites du vrai moteur), `FilePersistenceTest` (claim concurrent, worker zombie).
