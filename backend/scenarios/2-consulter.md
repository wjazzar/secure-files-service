# Scénario 2 — Consulter ses fichiers (`QueryFilesUseCase`)

**Le scénario**, en trois requêtes que l'interface enchaîne réellement :

```http
GET /api/v1/files?page=0&size=20&sort=uploadedAt,desc&status=PENDING&q=rapport
GET /api/v1/files/summary?q=rapport
GET /api/v1/files/{id}          (If-None-Match: W/"{id}-3")
```

Trois méthodes d'un même port d'entrée, toutes **cloisonnées au propriétaire**.

## La carte

```
[web] FileQueryController
  ├─ list ........ l.67 ─┐
  ├─ summary ..... l.88 ─┼─▶ [port in] QueryFilesUseCase
  └─ detail ...... l.105 ┘      └─ [service] FileQueryService
                                    ├─ list ... l.43 ─▶ [port out] FileCatalog.findPage ─────▶ JpaFileCatalog l.143
                                    ├─ count .. l.61 ─▶ [port out] FileCatalog.countByStatus ─▶ JpaFileCatalog l.157
                                    └─ find ... l.52 ─▶ [port out] FileCatalog.findOwnedBy ──▶ JpaFileCatalog l.136
                                                                 └─ StoredFileJpaRepository (JPQL, requêtes dérivées)
```

## 2a — La page filtrée

[`FileQueryController.list`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L66)

| Ligne | Ce qui se passe |
|---|---|
| [74](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L74) | Les paramètres sont validés **avant** d'entrer dans le cœur : `page ≥ 0` et 10 000 fichiers de profondeur au plus ([l. 143](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L143)), `size ≥ 1` ([l. 160](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L160)) et plafonnée à 100 par [`PageQuery.capped`](../src/main/java/com/praxedo/securefiles/application/file/model/PageQuery.java#L59), tri en **liste blanche** ([l. 167](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L167)), statuts publics ([l. 176](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L176)), recherche ≤ 100 caractères ([l. 196](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L196)). Tout écart → `400 INVALID_PARAMETER` |
| [77](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L77) | **Entrée dans le cœur** : `filesUseCase.list(owner(), …)`. Le propriétaire vient de `CurrentOwner` — jamais d'un paramètre de la requête |
| [service l. 45](../src/main/java/com/praxedo/securefiles/application/file/service/FileQueryService.java#L45) | [`FileQuery.of`](../src/main/java/com/praxedo/securefiles/application/file/model/FileQuery.java#L49) traduit les statuts **publics** en états **internes** ([l. 55](../src/main/java/com/praxedo/securefiles/application/file/model/FileQuery.java#L55)) : `PENDING` → `AWAITING_SCAN` + `RETRY_WAIT`. Le client ne voit jamais les états internes |
| [`JpaFileCatalog.findPage`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JpaFileCatalog.java#L143) | La recherche est échappée pour `LIKE` ([`containsPattern`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JpaFileCatalog.java#L190) : `%`, `_`, `\` deviennent littéraux) ; le tri passe par une table d'attributs autorisés, avec l'identifiant en départage stable ([`orderOf`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JpaFileCatalog.java#L169)) |
| [`StoredFileJpaRepository.search`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/repository/StoredFileJpaRepository.java#L52) | JPQL : `where f.ownerId = :owner and f.status in :statuses and immutable_unaccent(lower(f.originalFilename)) like immutable_unaccent(lower(:pattern))` — insensible à la casse **et aux accents**, servi par l'index trigramme posé sur la même expression (`V9`) — + requête de comptage pour la pagination |
| [79-81](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L79) | `200`, `Cache-Control: no-cache`, corps par [`FileResponseMapper.page`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/mapper/FileResponseMapper.java#L60) |

## 2b — Les compteurs

[`summary`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L88) →
[`FileQueryService.count`](../src/main/java/com/praxedo/securefiles/application/file/service/FileQueryService.java#L61)

| Où | Ce qui se passe |
|---|---|
| [service l. 63](../src/main/java/com/praxedo/securefiles/application/file/service/FileQueryService.java#L63) | [`JpaFileCatalog.countByStatus`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JpaFileCatalog.java#L157) → JPQL `group by f.status` ([repository l. 69](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/repository/StoredFileJpaRepository.java#L69)), **même recherche** que la liste : les filtres annoncent ce que le tableau montre |
| [service l. 65-69](../src/main/java/com/praxedo/securefiles/application/file/service/FileQueryService.java#L65) | Les 8 états internes sont repliés sur les 6 statuts publics (`merge`) |
| contrôleur | [`FileResponseMapper.counters`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/mapper/FileResponseMapper.java#L67) : les 6 statuts toujours présents, à 0 si besoin |

## 2c — Le détail, conçu pour être interrogé en boucle

[`detail`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L105)

| Ligne | Ce qui se passe |
|---|---|
| [`FileIdMapper.fromPath`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/mapper/FileIdMapper.java#L16) | Identifiant qui n'est pas un UUID → `404`, comme un fichier inconnu — même lecture que le téléchargement |
| [110](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L110) | [`FileQueryService.find`](../src/main/java/com/praxedo/securefiles/application/file/service/FileQueryService.java#L52) → [`JpaFileCatalog.findOwnedBy`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/adapter/JpaFileCatalog.java#L136) → [`findByIdAndOwnerId`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/repository/StoredFileJpaRepository.java#L50). **Le fichier de Bob, demandé par Alice, n'est pas trouvé** : `404 FILE_NOT_FOUND`, jamais `403` — l'API ne révèle pas qu'il existe |
| entité | La ligne est lue en [`StoredFileEntity`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/entity/StoredFileEntity.java), puis [`StoredFileEntityMapper.toDomain`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/mapper/StoredFileEntityMapper.java#L62) la convertit en passant par le **constructeur** de l'agrégat, qui [vérifie l'invariant](../src/main/java/com/praxedo/securefiles/domain/file/model/StoredFile.java#L120) : une ligne rendue incohérente par un `UPDATE` manuel échoue ici, bruyamment, au lieu de partir dans une réponse |
| [111](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L111) | `ETag` faible `W/"{id}-{version}"` ([l. 211](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L211)) : la colonne `version` augmente à chaque transition |
| [113-114](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L113) | `If-None-Match` identique → **`304`, sans corps**, avec le même `Retry-After` : pas de corps à produire ni à transférer — mais le jeton est vérifié et la ligne relue quand même, d'où un conseil de rythme à la mesure du fichier |
| [116-126](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/controller/FileQueryController.java#L116) | Sinon `200`. Tant que le fichier n'est pas dans un état terminal, `Retry-After` — sur le `200` comme sur le `304` — est **proportionnel à sa taille** : 2 s, plus 0,05 s par Mio, au plus 30 s ([`PollingHeaders`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/header/PollingHeaders.java), la même règle qu'au dépôt). Une interrogation n'est pas gratuite, même en `304` : jeton vérifié, ligne relue |

## Points d'arrêt conseillés

`FileQueryController.list` l. 77 · `FileQueryService.count` l. 65 ·
`JpaFileCatalog.findPage` l. 144 · `FileQueryController.detail` l. 113.

## Rejouer

`ReadApiTest` (les trois points d'entrée, `ETag`/`304`, paramètres refusés),
`FileCatalogQueryTest` (tri, recherche, cloisonnement, contre PostgreSQL réel).
