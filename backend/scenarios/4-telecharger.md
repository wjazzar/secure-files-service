# Scénario 4 — Télécharger (`DownloadFileUseCase`)

**Le scénario** : `rapport.pdf` est `AVAILABLE` ([scénario 3](3-analyser-et-promouvoir.md)).
Alice clique « Télécharger » : l'interface relit le fichier, puis laisse le
navigateur suivre `links.content` — son cookie de session part tout seul. Sa
connexion coupe à mi-chemin, le navigateur reprend avec une plage d'octets.
Un système tiers lit le même contenu avec son jeton.

```http
GET /api/v1/files/{id}                                   → 200 {"downloadable": true, "links": {"content": "/api/v1/files/{id}/content"}}
GET /api/v1/files/{id}/content                           → 200 + ETag, 2 000 000 octets   (Cookie: session)
GET /api/v1/files/{id}/content   Range: bytes=1000000-   → 206, la seconde moitié
GET /api/v1/files/{id}/content                           → 200                            (Authorization: Bearer …)
```

**Une seule preuve : l'identité de l'appelant** (ADR-0013). Pas de lien
signé, pas de second secret : avec la session par cookie (ADR-0012), un lien
HMAC doublait l'authentification.

## La carte

```
[sécurité] SecurityConfiguration — identité établie (cookie ou Bearer), sinon 401
[web] FileDownloadController.downloadContent ... l.78
  └─▶ [port in] DownloadFileUseCase.open
        └─ [service] FileDownloadService.open ... l.58
             ├─ servableFile() .................. l.76 ─▶ [port out] FileCatalog.findOwnedBy ─▶ JpaFileCatalog
             ├─ [port out] ServableReader.open ─▶ S3ServableReader l.37
             └─ [port out] DownloadAudit.served ─▶ JdbcDownloadAudit l.25
```

## 4a — Qui demande ?

Avant le contrôleur, Spring Security établit l'identité
([`SecurityConfiguration`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/config/SecurityConfiguration.java#L77)) :
un en-tête `Authorization` → chaîne sans état du jeton `Bearer` (l. 77-88) ;
sinon → chaîne du navigateur, session relue dans PostgreSQL par Spring
Session (l. 94-111). Le téléchargement n'a **aucune exception** : il exige
l'une des deux, comme tout le reste de `/api` (l. 83, l. 107). Un `GET` ne
demande pas de jeton CSRF : il ne modifie rien. Sans identité → `401
UNAUTHENTICATED`, avant la moindre lecture.

## 4b — Le contenu, servi

[`FileDownloadController.downloadContent`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileDownloadController.java#L78) →
[`FileDownloadService.open`](../src/main/java/com/praxedo/securefiles/application/file/service/FileDownloadService.java#L58)

| Où | Ce qui se passe |
|---|---|
| [`FileIdMapper.fromPath`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/mapper/FileIdMapper.java#L16) | Identifiant qui n'est pas un UUID → `404 FILE_NOT_FOUND`, comme un fichier inconnu |
| [contrôleur 82](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileDownloadController.java#L82) | [`DownloadHeaders.rangeOf`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/header/DownloadHeaders.java#L44) : une seule plage (`bytes=a-b` ou `bytes=a-`) est honorée ; plusieurs plages, plage suffixe ou en-tête malformé → **fichier entier** (RFC 9110 l'autorise ; plusieurs plages exigeraient un corps assemblé en mémoire) |
| [service 59](../src/main/java/com/praxedo/securefiles/application/file/service/FileDownloadService.java#L59) → [`servableFile`](../src/main/java/com/praxedo/securefiles/application/file/service/FileDownloadService.java#L76) | ⭐ La ligne est lue **pour ce propriétaire** (l. 77) : absente ou à quelqu'un d'autre → `UnknownFile` → `404`. Puis **la seule question que le chemin de téléchargement a le droit de poser** : `isDownloadable()` (l. 78), qui ne répond oui que pour `AVAILABLE` ([`FileStatus`](../src/main/java/com/praxedo/securefiles/domain/file/model/FileStatus.java#L81)). Sinon `NotServable(statut public)` → `409`. **Relu à chaque requête** : rien de ce qui a été répondu avant ne vaut permission |
| [service 62](../src/main/java/com/praxedo/securefiles/application/file/service/FileDownloadService.java#L62) | [`S3ServableReader.open`](../src/main/java/com/praxedo/securefiles/infrastructure/storage/file/adapter/S3ServableReader.java#L37) avec l'identité **`delivery`**, qui n'a **aucun droit** sur la quarantaine : `HeadObject` pour la taille (l. 39) ; plage au-delà de la fin → `RangeNotSatisfiableException` (l. 43) → `416` avec `Content-Range: bytes */<taille>` ; puis `GetObject` avec la plage (l. 52) |
| [service 63-65](../src/main/java/com/praxedo/securefiles/application/file/service/FileDownloadService.java#L63) | `AVAILABLE` sans objet servable : ne devrait jamais arriver (la promotion écrit l'objet avant la ligne) → journal `INVARIANT ANOMALY`, `503` — une anomalie criée, pas maquillée en `404` |
| [service 67-72](../src/main/java/com/praxedo/securefiles/application/file/service/FileDownloadService.java#L67) | [`JdbcDownloadAudit.served`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JdbcDownloadAudit.java#L25) (`DOWNLOAD_SERVED`, acteur, plage) **avant le premier octet**. S'il échoue, le flux S3 est fermé et rien n'est servi : pas de téléchargement sans trace |
| [contrôleur 88-107](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileDownloadController.java#L88) | La réponse est écrite **à la main** sur le flux du servlet : `200`/`206` (l. 90), `application/octet-stream` (l. 91), longueur (l. 92), `Content-Disposition: attachment` avec repli ASCII et nom UTF-8 exact (l. 93, [`DownloadHeaders.attachment`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/header/DownloadHeaders.java#L28)), `nosniff` (l. 94), `Cache-Control: private, no-store` (l. 96), `ETag` = SHA-256 (l. 98) — le contenu d'un fichier servi ne change jamais —, `Content-Range` si partiel (l. 99-102). Puis `transferTo` (l. 104) : S3 → client, en flux |
| [contrôleur 81-84](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileDownloadController.java#L81) | Le `try-with-resources` referme le flux S3 ([`FileDownload.close`](../src/main/java/com/praxedo/securefiles/application/file/model/FileDownload.java)) ; octets comptés dans `praxedo.download.bytes` |

**La reprise** : même URL, `Range: bytes=1000000-` → même chemin, `206`,
`Content-Range: bytes 1000000-1999999/2000000`. L'identité et l'état sont
revérifiés à la reprise aussi.

## Côté interface

[`useDownloadFile`](../../frontend/src/features/files/hooks/use-download-file.ts)
relit `GET /files/{id}` au clic, puis confie `links.content` au gestionnaire
de téléchargement du navigateur — jamais `fetch` + `Blob`, qui chargerait
500 Mo dans l'onglet. La relecture sert l'utilisateur, pas la sécurité : un
fichier devenu non servable ou une session expirée s'**expliquent** par un
message au lieu d'un téléchargement en échec.

## Les branches d'erreur

[`ApiExceptionHandler.downloadRefused`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/error/ApiExceptionHandler.java#L173)
et [`notServable`](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/error/ApiExceptionHandler.java#L191) :

| Situation | Réponse |
|---|---|
| Sans identité | `401 UNAUTHENTICATED` |
| Fichier inconnu ou d'autrui | `404 FILE_NOT_FOUND` |
| `HEAD` sur le contenu | `405` : rien n'est ouvert ni audité |
| `PENDING` / `SCANNING` | `409 FILE_NOT_READY` + `Retry-After` |
| `INFECTED` / `UNSCANNABLE` / `FAILED` | `409 FILE_INFECTED` / `FILE_UNSCANNABLE` / `FILE_SCAN_FAILED`, sans `Retry-After` : c'est définitif |
| Plage au-delà de la fin | `416 RANGE_NOT_SATISFIABLE` ([l. 218](../src/main/java/com/praxedo/securefiles/infrastructure/web/common/error/ApiExceptionHandler.java#L218)) |
| `AVAILABLE` sans objet, stockage en panne | `503 SERVICE_UNAVAILABLE` |

## Ce qui a changé, au bout du scénario

Rien dans l'état du fichier — **servir ne change pas un fichier**. Seul le
journal grandit : un `DOWNLOAD_SERVED` par requête de contenu, avec son acteur
et sa plage (`whole`, puis `bytes=1000000-`, puis `whole` pour le système
tiers). Un refus n'y laisse rien : rien n'a été servi.

## Points d'arrêt conseillés

`FileDownloadService.servableFile` l. 78 · `S3ServableReader.open` l. 43 ·
`FileDownloadController.stream` l. 90.

## Rejouer

`DownloadApiTest` (contenu, en-têtes, `409` par état, fichier d'autrui,
plages, `416`, anomalie `503`, audit), `BrowserSessionTest` (le cookie seul
suffit), `OidcSecurityTest` (sans identité : `401`), `DownloadHeadersTest`,
`DownloadMemoryTest` (500 Mo à travers un tas de 256 Mo).
