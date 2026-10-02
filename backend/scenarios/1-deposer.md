# Scénario 1 — Déposer un fichier (`UploadFileUseCase`)

**Le scénario** : Alice envoie `rapport.pdf`, 2 000 000 octets, avec une clé
d'idempotence (pour pouvoir réessayer sans créer de doublon).

```http
POST /api/v1/files
Authorization: Bearer <jeton d'Alice>
X-File-Name: rapport.pdf
Content-Length: 2000000
Idempotency-Key: depot-2026-0001
Content-Type: application/octet-stream

<les 2 000 000 octets, bruts — pas de multipart>
```

**Le résultat** : `202 Accepted`, un objet dans la zone `quarantine`, une ligne
`AWAITING_SCAN` en base, un événement `UPLOADED` dans le journal d'audit — et le
fichier devient éligible pour le worker ([scénario 3](3-analyser-et-promouvoir.md)).

## La carte

```
POST /api/v1/files
└─ [web]        FileUploadController.upload ................................ l.73
   └─ [port in] UploadFileUseCase.upload
      └─ [service] UploadFileService.upload ................................ l.86
         ├─ contrôles sans lire le corps ...................................... l.87-96
         │  └─ UploadAdmission.admit : place sur le nœud, puis fichiers en attente
         │     └─ PendingFileCountCache ─▶ [port out] FileCatalog.countPendingFiles ─▶ JpaFileCatalog l.203
         ├─ réservation de la clé ............................................. l.119
         │  └─ [port out] IdempotencyStore.reserve ─▶ JdbcIdempotencyStore ...... l.38
         └─ store() ........................................................... l.132
            ├─ DeadlineInputStream (échéance du transfert, selon la taille annoncée)
            ├─ InspectingInputStream (taille + SHA-256 au passage)
            ├─ ContentSniffer.detect ........................................... l.41
            ├─ [port out] QuarantineWriter.write ─▶ S3QuarantineWriter ......... l.26
            │                                     └─ S3Objects.put ........... l.30
            └─ [port out] TransactionRunner.inTransaction ─▶ SpringTransactionRunner
               ├─ [port out] FileCatalog.insert ─▶ JpaFileCatalog.insert ........ l.101
               │     └─ trigger SQL stored_file_audit_insert (V6) ─▶ UPLOADED
               └─ [port out] IdempotencyStore.complete ─▶ JdbcIdempotencyStore .. l.84
```

## Pas à pas

### Étape 1 — Le contrôleur : refuser tout ce qui peut l'être sur les en-têtes

[`FileUploadController.upload`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileUploadController.java#L73)

| Ligne | Ce qui se passe |
|---|---|
| [79-84](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileUploadController.java#L79) | Pas de `Content-Length` (envoi *chunked*) → `411 LENGTH_REQUIRED`. Sans longueur annoncée, ni la limite de taille ni le contrôle de longueur ne pourraient s'appliquer **avant** de lire |
| [86-87](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileUploadController.java#L86) | Construit l'`UploadCommand` : le propriétaire ([`CurrentOwner.resolve`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/identity/CurrentOwner.java#L33)), le nom, la longueur, la clé |
| [107-117](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileUploadController.java#L107) | `fileName()` : décode l'UTF-8 encodé en pourcentage, puis `FileName.sanitised` (dernier segment de chemin, caractères de contrôle retirés). `../../etc/passwd` devient `passwd`. Mal formé → `400 INVALID_FILE_NAME` |
| [120-130](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileUploadController.java#L120) | `idempotencyKey()` : ASCII imprimable, 8 à 128 caractères, sinon `400 INVALID_PARAMETER` |
| [88](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileUploadController.java#L88) | Le flux du servlet est enveloppé dans un `CountingInputStream` — **aucun `MultipartFile`, aucun `@RequestBody`** : rien dans Spring ne peut mettre le corps en tampon |
| [91](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileUploadController.java#L91) | **Entrée dans le cœur** : `uploadsUseCase.upload(command, body)`. Le contrôleur ne connaît que l'interface [`UploadFileUseCase`](../src/main/java/com/praxedo/securefiles/application/file/port/in/UploadFileUseCase.java) |
| [93](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileUploadController.java#L93) | `finally` : les octets reçus alimentent `praxedo.upload.bytes`, accepté ou non |

### Étape 2 — Le service : les refus qui ne coûtent rien

[`UploadFileService.upload`](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L86)

| Ligne | Ce qui se passe |
|---|---|
| [89-91](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L89) | Longueur 0 → `EmptyFile` → `400 EMPTY_FILE` |
| [92-94](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L92) | Plus de 500 Mo → `TooLarge` → `413 FILE_TOO_LARGE`. **Aucun octet du corps n'a été lu** |
| [95-96](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L95) | Contre-pression, par [`UploadAdmission.admit`](../src/main/java/com/praxedo/securefiles/application/file/service/UploadAdmission.java#L56) : d'abord une place sur ce nœud (`Semaphore`, sinon `429 TOO_MANY_CONCURRENT_UPLOADS`), puis le nombre de fichiers **en attente** — non terminaux — lu dans [`PendingFileCountCache`](../src/main/java/com/praxedo/securefiles/application/file/service/PendingFileCountCache.java#L51) : compté au plus toutes les 250 ms, par une seule requête ; les autres reprennent la mesure précédente sans attendre. ≥ 500 → `TooManyPending` → `429` + `Retry-After: 30`. Côté adaptateur : [`JpaFileCatalog.countPendingFiles`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JpaFileCatalog.java#L203) → `SELECT count(*) … WHERE status IN (<états non terminaux>)` via [`countByStatusIn`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/repository/StoredFileJpaRepository.java#L81) |

### Étape 3 — L'idempotence : réserver la clé avant de lire le corps

[`UploadFileService.reserve`](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L119) →
[`JdbcIdempotencyStore.reserve`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/idempotency/adapter/JdbcIdempotencyStore.java#L38)

L'empreinte de la requête est calculée par
[`fingerprint`](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L204) :
SHA-256 de `nom + '\n' + longueur` — ce qu'on sait d'une requête **avant** de
lire son corps.

| Ligne (adaptateur) | SQL / décision | Réponse |
|---|---|---|
| [41-51](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/idempotency/adapter/JdbcIdempotencyStore.java#L41) | `INSERT … 'IN_PROGRESS' … ON CONFLICT DO NOTHING` — une ligne insérée | `Granted` : on continue (**notre scénario**) |
| [61-71](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/idempotency/adapter/JdbcIdempotencyStore.java#L61) | Clé existante mais expirée → `DELETE` conditionnel, nouveau tour | — |
| [72-74](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/idempotency/adapter/JdbcIdempotencyStore.java#L72) | Même clé, **autre** empreinte | `Reused` → `422 IDEMPOTENCY_KEY_REUSED` |
| [75-77](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/idempotency/adapter/JdbcIdempotencyStore.java#L75) | Même clé, pas encore de fichier : l'autre requête streame encore | `InProgress` → `409` + `Retry-After: 2` |
| [78](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/idempotency/adapter/JdbcIdempotencyStore.java#L78) | Même clé, fichier déjà créé | `Replay` → le service relit le fichier ([l. 125](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L125)) et renvoie **le même** `202`, sans relire le corps |

Si `store()` échoue ensuite, la clé est rendue
([l. 112-115](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L112) →
[`release`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/idempotency/adapter/JdbcIdempotencyStore.java#L100)) :
le réessai du client doit pouvoir réussir.

### Étape 4 — Les octets, en flux, vers la quarantaine

[`UploadFileService.store`](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L132)

| Ligne | Ce qui se passe |
|---|---|
| [133-134](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L133) | Identifiant tiré (`FileId.random()`) ; **la clé de stockage est cet UUID**, jamais le nom d'Alice |
| [136-137](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L136) | [`DeadlineInputStream`](../src/main/java/com/praxedo/securefiles/application/common/io/DeadlineInputStream.java) : le corps doit être arrivé avant une **échéance proportionnelle à sa taille annoncée** (`praxedo.upload.transfer-deadline` : 60 s + 8 s par Mio, ~1 Mbit/s au plus lent). Le délai de lecture du connecteur ne mesure que le silence entre deux paquets : sans cette échéance, un client qui envoie au compte-gouttes garderait sa place sur le nœud indéfiniment |
| [138](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L138) | [`InspectingInputStream`](../src/main/java/com/praxedo/securefiles/application/common/io/InspectingInputStream.java#L31) : chaque octet lu met à jour le compteur et le SHA-256 ; au-delà de la longueur annoncée, [`advance`](../src/main/java/com/praxedo/securefiles/application/common/io/InspectingInputStream.java#L98) lève une `IOException` |
| [139](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L139) | Tampon de 64 Kio : **la seule mémoire utilisée**, quelle que soit la taille du fichier |
| [147](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L147) | [`ContentSniffer.detect`](../src/main/java/com/praxedo/securefiles/application/file/service/ContentSniffer.java#L41) : `mark` / lecture de 512 octets / `reset`. Le type est **détecté**, celui du client est ignoré — et de toute façon le fichier sera toujours servi en `application/octet-stream` |
| [148](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L148) | **Sortie du cœur** : `quarantine.write(…)` → [`S3QuarantineWriter.write`](../src/main/java/com/praxedo/securefiles/infrastructure/storage/file/adapter/S3QuarantineWriter.java#L26) → [`S3Objects.put`](../src/main/java/com/praxedo/securefiles/infrastructure/storage/common/support/S3Objects.java#L30) : `PutObject` sur le seau `quarantine`, avec la longueur connue, avec l'identité **`ingest`** (écriture seule sur la quarantaine) |
| [S3Objects l. 40](../src/main/java/com/praxedo/securefiles/infrastructure/storage/common/support/S3Objects.java#L40) | Le corps est confié au SDK par [`SingleUseContent`](../src/main/java/com/praxedo/securefiles/infrastructure/storage/common/support/SingleUseContent.java#L27) : si le SDK voulait le relire (réessai interne), il échoue au lieu de mettre 500 Mo en mémoire |
| [S3Objects l. 42](../src/main/java/com/praxedo/securefiles/infrastructure/storage/common/support/S3Objects.java#L42) | Erreur SDK → [`S3Failures.translate`](../src/main/java/com/praxedo/securefiles/infrastructure/storage/common/support/S3Failures.java#L27) → `StorageUnavailableException` → `503` |
| [149-163](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L149) | Échéance dépassée → l'objet est supprimé, `408 UPLOAD_TOO_SLOW`. Corps plus court ou plus long qu'annoncé ([`bodyWasTheProblem`](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L186)) → l'objet est supprimé, `400 CONTENT_LENGTH_MISMATCH`. Même réponse que la coupure ait lieu à 400 octets ou à 400 Mo |

### Étape 5 — La ligne et la clé, dans une seule transaction

| Ligne | Ce qui se passe |
|---|---|
| [166-167](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L166) | [`StoredFile.received`](../src/main/java/com/praxedo/securefiles/domain/file/model/StoredFile.java#L105) : `AWAITING_SCAN`, zone `QUARANTINE`, 0 tentative, **empreinte = celle calculée pendant l'écriture** |
| [169](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L169) | [`SpringTransactionRunner.inTransaction`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/common/adapter/SpringTransactionRunner.java#L30) : un `TransactionTemplate` — le cœur ne connaît pas `@Transactional` |
| [170](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L170) | [`JpaFileCatalog.insert`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JpaFileCatalog.java#L101) : `persist` + `flush` → `INSERT INTO stored_file`. `next_attempt_at` prend sa valeur par défaut (`clock_timestamp()`) : le fichier est **aussitôt éligible** pour le worker |
| base | Les contraintes `CHECK` de [`V2__stored_file.sql`](../src/main/resources/db/migration/V2__stored_file.sql) s'appliquent ; le trigger [`stored_file_audit_insert`](../src/main/resources/db/migration/V6__audit.sql#L86) écrit l'événement `UPLOADED` (acteur : Alice) **dans la même transaction** |
| [171](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L171) | [`JdbcIdempotencyStore.complete`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/idempotency/adapter/JdbcIdempotencyStore.java#L84) : la clé passe `COMPLETED` et pointe le fichier |
| [174-179](../src/main/java/com/praxedo/securefiles/application/file/service/UploadFileService.java#L174) | Échec de la transaction → l'objet est supprimé par courtoisie ; s'il reste, c'est un orphelin **invisible** que le balayage ramassera ([scénario 5](5-entretenir.md)). L'ordre objet → ligne est voulu : en panne partielle, un orphelin plutôt qu'une référence cassée |

### Étape 6 — La réponse

[`FileUploadController`, l. 96-99](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileUploadController.java#L96) :
`202 Accepted`, `Location: /api/v1/files/{id}`, `Retry-After: 2` (conseil de
rythme pour le suivi, proportionnel à la taille — 2 s pour les 2 Mo d'Alice,
26 s pour 500 Mo — et calculé comme celui du détail :
[`PollingHeaders`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/header/PollingHeaders.java)), corps produit par
[`FileResponseMapper.detail`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/mapper/FileResponseMapper.java#L42) :
statut public `PENDING`, `downloadable: false`.

## Les branches d'erreur, et où elles deviennent une réponse

| Exception | Levée | Traduite par | Réponse |
|---|---|---|---|
| `UploadHeaderException.LengthRequired` / `InvalidFileName` | contrôleur | [`uploadHeader`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/error/ApiExceptionHandler.java#L157) | `411` / `400` |
| `UploadRefusedException.*` (8 cas) | service | [`uploadRefused`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/error/ApiExceptionHandler.java#L111) — un `switch` sans `default` sur une classe scellée | `400`, `408`, `413`, `429`, `409`, `422` |
| `StorageUnavailableException` | adaptateur S3 | [`storageUnavailable`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/error/ApiExceptionHandler.java#L227) | `503` + `Retry-After` |
| `DataAccessException` (base injoignable) | adaptateurs JPA/JDBC | [`dependencyUnavailable`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/error/ApiExceptionHandler.java#L248) | `503` + `Retry-After` |

## Ce qui a changé, au bout du scénario

| Où | Quoi |
|---|---|
| Stockage, seau `quarantine` | Objet `<uuid>`, 2 000 000 octets |
| `stored_file` | 1 ligne : `AWAITING_SCAN`, `QUARANTINE`, `content_sha256` = empreinte des octets reçus |
| `idempotency_record` | `(<sub d'Alice>, depot-2026-0001)` → `COMPLETED`, `file_id` = l'UUID |
| `file_audit_event` | `UPLOADED  null → AWAITING_SCAN  by <sub d'Alice>` |

## Points d'arrêt conseillés

`FileUploadController.upload` l. 91 · `UploadFileService.upload` l. 97 ·
`UploadFileService.store` l. 148 et l. 169 · `JdbcIdempotencyStore.reserve`
l. 52.

## Rejouer

`UploadApiTest` (accepté, en-têtes refusés, corps mensonger, idempotence),
`UploadFileServiceTest` (tout le service sans Spring, dont le corps au
compte-gouttes, coupé à son échéance), `UploadAdmissionTest` et
`UploadAdmissionBoundsTest` (`429`), `PendingFileCountCacheTest` (personne
n'attend le rafraîchissement du compte), `UploadMemoryTest` (500 Mo à travers
un tas de 256 Mo).
