# Remarques de relecture du back-end

Ce document rassemble les remarques formulées pendant la relecture guidée du
code. Elles restent séparées de la documentation descriptive : chaque point
doit être arbitré, puis reporté dans le code, les tests et le contrat concernés
s'il est retenu.

## Arbitrages du 30/09

Chaque remarque garde ci-dessous son texte d'origine, suivi de son
**arbitrage** et de ce qui a été fait. Journal de la session :
[`B-018`](../../docs/prompts/B-018-relecture-guidee-du-code.md).

| Remarque | Arbitrage | Où |
|---|---|---|
| R-001 | Appliquée côté API : `Retry-After` proportionnel à la taille (2 s + 0,05 s/Mio, au plus 30 s), aussi sur le `304` ; front inchangé | `PollingHeaders` |
| R-002 | Appliquée | `JpaFileCatalog` |
| R-003 | **Option 2** : agrégat pur, entité JPA distincte | ADR-0015 |
| R-004 | Nom conservé (vocabulaire *Command/Query*), Javadoc explicite | `FileQuery` |
| R-005 | Appliquée | `FileQuery.of` |
| R-006 | Appliquée, limitée au câblage des ports, vérifiée par ArchUnit | paquet `config` |
| R-007 | Échéance de transfert sur les flux (dépôt, analyse, promotion) plutôt qu'une borne d'appel S3 | `DeadlineInputStream`, contrat 1.8 |
| R-008 | Appliquée au port **et** à l'adaptateur ; nom conservé | concept `idempotency` |
| R-009 | Noms revus, vocabulaire « en attente » unifié, cache extrait et testé en concurrence | `PendingFileCountCache` |

## Scénario 1 — Déposer un fichier

### R-001 — Adapter le délai initial de suivi à la taille du fichier

**État : appliquée côté API (30/09), après un premier arbitrage révisé.**

Après un dépôt accepté, `FileUploadController` renvoie actuellement un
`Retry-After` fixe de deux secondes. Ce délai est une indication pour la
prochaine consultation du statut, pas une promesse de fin d'analyse. Il peut
néanmoins être inutilement court pour un fichier volumineux et provoquer des
requêtes de suivi dont la réponse sera presque certainement encore
non terminale.

Faire varier le délai initial en fonction de la taille connue du fichier, avec
un plancher et un plafond. La taille ne doit pas être présentée comme une
estimation exacte : la profondeur de la file, la concurrence des workers, la
disponibilité et le débit de l'antivirus, la promotion et les réessais influent
également sur le temps réel.

Le changement doit être réalisé de bout en bout : le front applique aujourd'hui
sa propre cadence de suivi, de deux à dix secondes, sans exploiter le
`Retry-After` de la réponse d'upload. Modifier uniquement le contrôleur ne
réduirait donc pas le nombre de requêtes.

À mettre à jour ensemble :

- le calcul du `Retry-After` dans le back-end ;
- le contrat OpenAPI et les tests de l'API ;
- la lecture de cet en-tête par le front et ses tests de polling.

**Premier arbitrage, révisé.** Le délai avait d'abord été gardé fixe, au motif
que l'interface suit la liste et non chaque fichier, et qu'« un `304` ne coûte
presque rien ». Le second argument était faux : même répondue `304`, une
interrogation vérifie le jeton et **relit la ligne** — l'`ETag` est sa
version — sur le pool de l'API (10 connexions). Et l'énoncé nomme les systèmes
tiers : le déclencheur retenu pour la piste était déjà rempli.

**Arbitrage retenu : côté API seulement.** Le front n'a pas à changer : il suit
la liste à son propre rythme. La remarque visait surtout les clients de l'API,
qui interrogent chaque fichier.

- Règle : **2 s, plus 0,05 s par Mio, au plus 30 s**, arrondi à la seconde la
  plus proche — 1 Mo → 2 s, 50 Mo → 4 s, 500 Mo → 26 s. Calée sur le débit
  mesuré (analyse ~20 Mo/s, puis copie de promotion) : un fichier de 500 Mo
  est consulté environ deux fois pendant son traitement, au lieu d'une
  vingtaine.
- Arrondi au plus proche plutôt qu'au-dessus : arrondir au-dessus aurait porté
  un fichier de 1 Mo (2,05 s) à 3 s.
- Le plancher est l'ancienne valeur fixe : sous charge, le conseil peut être
  trop court, jamais plus fréquent qu'avant. Le plafond borne le retard d'un
  verdict. La profondeur de la file n'y entre pas : un nœud ne connaît pas le
  nombre de workers du cluster.
- Appliquée au `202` du dépôt, au `200` du détail **et à son `304`** : un
  client qui revalide par `If-None-Match` ne reçoit presque que des `304`, et
  sans cela ne verrait le conseil qu'une fois par transition.
- Une seule règle, [`PollingHeaders`](../src/main/java/com/praxedo/securefiles/infrastructure/web/file/header/PollingHeaders.java),
  réglée par `praxedo.polling` dans `application.yml` ; contrat 1.8. Tests :
  `PollingHeadersTest` (valeurs, plancher, plafond, réglages incohérents) et
  `ReadApiTest` (2 s pour 1 Mo, 26 s pour 500 Mo, en-tête présent sur le
  `304`).

### R-002 — Expliciter le nom de la dépendance Spring Data

**État : appliquée (30/09).**

Dans `JpaFileCatalog`, le champ de type `StoredFileJpaRepository` est nommé
`files`. Ce nom décrit les objets manipulés, mais masque la nature de la
dépendance lors de la lecture rapide du code.

Le renommer en `storedFileRepository` — ou en `storedFileJpaRepository` si la
distinction JPA est utile dans ce contexte — rendrait immédiatement visible
qu'il s'agit du repository Spring Data délégué par l'adaptateur. Appliquer le
même nom au paramètre du constructeur. Ce changement est purement mécanique et
ne modifie aucun comportement.

**Arbitrage.** `storedFileRepository`, champ et paramètre.

### R-003 — Clarifier le double rôle de `StoredFile`

**État : corrigée — option 2 (30/09).**

`StoredFile` est placé dans le domaine et porte l'automate métier, mais il est
également annoté comme entité JPA et sert directement de modèle aux repositories
Spring Data. Sa lecture peut donc laisser hésiter entre agrégat métier, modèle
de persistance et objet de transport.

Ce n'est pas seulement une question de nom : le domaine a une dépendance de
compilation vers `jakarta.persistence` et Hibernate. Il adopte également leurs
contraintes et mécanismes (`@Entity`, constructeur sans argument, `@PostLoad`,
`@Version`). L'affirmation « domaine sans framework » et la règle ArchUnit
associée comportent donc une exception suffisamment importante pour rendre la
formulation actuelle inexacte.

Un simple renommage de la classe actuelle en `StoredFileEntity` serait
trompeur : les services applicatifs continueraient à l'utiliser comme objet du
domaine. Deux options cohérentes sont possibles :

1. assumer le compromis actuel et conserver le nom métier `StoredFile`, en
   documentant clairement que les annotations JPA constituent l'unique
   exception à l'indépendance du domaine ;
2. rendre la séparation stricte avec un `StoredFile` pur dans le domaine, un
   `StoredFileEntity` dans l'infrastructure de persistance et un mapper entre
   les deux.

La seconde option clarifie réellement les frontières mais ajoute une classe
miroir et du mapping. Pour le périmètre restreint du test technique, la première
reste défendable comme compromis explicite ; elle ne permet cependant pas de
présenter le domaine comme dépourvu de dépendances de framework.

**Arbitrage : option 2**, décision du porteur du projet
([ADR-0015](../../docs/adr/0015-domaine-sans-annotation-de-persistance.md)).

- [`StoredFile`](../src/main/java/com/praxedo/securefiles/domain/file/model/StoredFile.java) : classe `final`, champs
  `final` typés par les objets valeur, invariant vérifié par le constructeur —
  donc pour toute instance, relue comprise. Plus de constructeur pour
  Hibernate ni de `@PostLoad`.
- [`StoredFileEntity`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/entity/StoredFileEntity.java) :
  une colonne par champ, aucun comportement.
- [`StoredFileEntityMapper`](../src/main/java/com/praxedo/securefiles/infrastructure/persistence/file/mapper/StoredFileEntityMapper.java) :
  vers le domaine, passe par le constructeur de l'agrégat, comme
  `StoredFileRowMapper` pour la file de travail.
- `StoredFileJpaRepository` étend `Repository` et non `JpaRepository` : plus
  aucun `save` ni `delete` hérité ; il est en lecture seule par construction.
- ArchUnit : le domaine n'a plus d'exception (`jakarta..` et `org.hibernate..`
  interdits) ; toute `@Entity` vit dans `persistence/*/entity`.
- Test : [`StoredFileEntityMapperTest`](../src/test/java/com/praxedo/securefiles/infrastructure/persistence/file/mapper/StoredFileEntityMapperTest.java)
  — aller-retour dans chaque état, ligne incohérente refusée à la lecture.

### R-004 — Renommer `FileQuery` pour exprimer des critères

**État : arbitrée — nom conservé (30/09).**

`FileQuery` n'exécute pas une requête et ne construit pas de SQL. C'est un objet
immuable qui porte le périmètre d'une lecture : propriétaire obligatoire,
états internes retenus et recherche éventuelle dans le nom. Sa fabrique traduit
les statuts publics et normalise la recherche avant l'appel au port
`FileCatalog`.

Le nom `FileQueryConditionBuilder` ne correspondrait pas non plus à son rôle :
la classe n'utilise pas le patron Builder et n'assemble pas progressivement des
conditions techniques. Préférer `FileSearchCriteria` ou `FileFilter`. Le premier
nom décrit le plus précisément son rôle et reste distinct de `PageQuery`, qui
porte le numéro, la taille et le tri de la page.

**Arbitrage.** Le nom suit le vocabulaire *Command/Query* de la couche
application : `UploadCommand` demande un changement, `FileQuery` une lecture,
`QueryFilesUseCase` y répond, `PageQuery` en donne la page. Renommer
`FileQuery` seul briserait cette symétrie ; et l'objet sert aussi aux
compteurs, et son propriétaire est un **cloisonnement** plutôt qu'un critère de
recherche. La Javadoc dit désormais en première ligne qu'il n'exécute rien et
pourquoi il porte ce nom.

### R-005 — Expliciter le paramètre `wanted` de `FileQuery.of`

**État : appliquée (30/09).**

Le paramètre `wanted` est un ensemble de `PublicStatus` demandés par le client.
La fabrique le traduit en états internes `FileStatus` avant la persistance : par
exemple, le filtre public `PENDING` correspond à `AWAITING_SCAN` et
`RETRY_WAIT`. Un ensemble vide signifie l'absence de filtre et devient donc
l'ensemble de tous les états internes.

Renommer `wanted` en `requestedPublicStatuses` rendrait le type, la provenance
et l'intention visibles sans devoir lire l'implémentation. Le nom plus court
`publicStatuses` serait également acceptable.

**Arbitrage.** `requestedPublicStatuses` ; en face, la variable locale devient
`internalStates` : la traduction se lit dans les deux sens.

### R-006 — Rendre le point de composition découvrable

**État : appliquée (30/09).**

Les adaptateurs non annotés sont construits explicitement par des méthodes
`@Bean`, ce qui permet notamment de contrôler les trois identités S3. Cependant,
le câblage est dispersé dans des configurations rangées au fond de chaque
adaptateur (`infrastructure/storage/common/config`, persistance, antivirus,
sécurité…), tandis que `UseCaseConfiguration` se trouve à la racine. Pour
comprendre l'injection d'un port comme `QuarantineWriter`, il faut donc chercher
où un bean de ce type est déclaré.

Regrouper les classes de composition dans un paquet racine identifiable, par
exemple `com.praxedo.securefiles.config`, avec plusieurs fichiers ciblés
(`UseCaseConfiguration`, `StorageConfiguration`,
`PersistenceConfiguration`, etc.). Éviter un fichier unique géant : l'objectif
est un index de câblage facile à parcourir, pas de mélanger tous les détails de
configuration. Les propriétés et le code propres à chaque adaptateur peuvent
rester dans leurs paquets actuels.

Ce regroupement rendrait la composition hexagonale visible sans perdre le
contrôle explicite des clients et des droits.

**Arbitrage.** Paquet [`config`](../src/main/java/com/praxedo/securefiles/config/package-info.java) : `UseCaseConfiguration`,
`StorageConfiguration`, `AntivirusConfiguration`, `ServiceProperties`. Seul le
**câblage des ports** y entre ; la configuration technique d'un adaptateur,
qui ne câble aucun port (pools, chaînes de sécurité, connecteur, propriétés,
fabriques de clients — `S3Clients`, et `AntivirusClients` extrait pour
l'occasion), reste dans son `common/config`. Les adaptateurs de persistance,
sans rien à choisir, restent déclarés par `@Repository` ; la `package-info` le
dit. `main()` reste à la racine, sans quoi le scan ne couvrirait plus rien.

Deux règles ArchUnit changent : la couche `composition` couvre `config` — un
paquet hors de toute couche échapperait sans bruit à `layeredArchitecture()` —
et une règle nouvelle exige que toute méthode `@Bean` qui fournit un port soit
déclarée dans `config`. L'index ne peut donc pas se disperser à nouveau.

### R-007 — Distinguer les timeouts de transport d'une borne d'appel S3

**État : traitée — échéance sur les flux (30/09).**

Les clients S3 configurent explicitement un délai de connexion de deux secondes
et un `socketTimeout` de cinq minutes. Ce dernier borne l'attente de transfert
de données sur une connexion établie ; ce n'est pas une durée maximale garantie
pour l'ensemble d'un dépôt, d'une copie ou d'un téléchargement de 500 Mo.

Le SDK ne reçoit actuellement ni `apiCallAttemptTimeout` ni `apiCallTimeout`.
Vérifier par les tests de charge et de panne si une borne globale est nécessaire
pour empêcher un appel qui progresse très lentement de rester actif indéfiniment.
Si elle est ajoutée, la dimensionner sur les opérations maximales mesurées et la
coordonner avec les baux du worker ; un délai fixe trop court casserait les flux
de 500 Mo parfaitement sains.

**Arbitrage.** Pas de borne d'appel S3 : la Javadoc du SDK (2.55.6) précise que
`apiCallTimeout` ne couvre pas la lecture d'un `ResponseInputStream` une fois
la réponse rendue — elle n'aurait borné ni la lecture de la quarantaine ni
celle d'un téléchargement. La borne est posée **sur le flux**, par
[`DeadlineInputStream`](../src/main/java/com/praxedo/securefiles/application/common/io/DeadlineInputStream.java), qui
consulte l'horloge avant chaque lecture, et dimensionnée comme les baux (un
plancher plus un temps par Mio) :

| Transfert | Échéance | Au-delà |
|---|---|---|
| Corps d'un dépôt | 60 s + 8 s par Mio annoncé (~1 Mbit/s au plus lent) | `408 UPLOAD_TOO_SLOW`, objet supprimé, place rendue |
| Lecture pour l'analyse | le bail d'analyse | échec technique, `RETRY_WAIT` aussitôt |
| Copie de promotion | le bail de promotion | échec technique, `RETRY_WAIT` aussitôt |

Côté worker, la correction n'était pas en jeu — un verdict tardif est refusé
par le jeton du claim — mais la place d'analyse restait prise pour rien. Côté
dépôt, le risque réaliste était le **client** au compte-gouttes plutôt que S3 :
c'était le constat S-06 de l'audit de sécurité, classé le 29/09 en limitation
acceptée et désormais corrigé. Contrat 1.8 (`408`), front (code, libellé,
réessai proposé). Tests déterministes, sans conteneur, par une horloge qui
avance à chaque lecture : `DeadlineInputStreamTest`, et les scénarios « au
compte-gouttes » de `UploadFileServiceTest` et `FileScanServiceTest`.

### R-008 — Rendre la persistance de l'idempotence plus facile à trouver

**État : appliquée (30/09).**

`IdempotencyStore` est un port de persistance PostgreSQL, implémenté par
`JdbcIdempotencyStore`. Son classement sous
`infrastructure/persistence/file/adapter` est techniquement cohérent avec le
cas d'usage d'upload, mais le concept d'idempotence possède sa propre table, son
propre cycle de vie et des opérations spécifiques de réservation. Il est donc
difficile à localiser en parcourant l'arborescence, et le mot `Store` peut être
confondu avec le stockage objet.

Envisager un paquet explicite tel que
`infrastructure/persistence/idempotency/adapter`. Le nom du port peut rester
`IdempotencyStore`, qui est correct, ou devenir `IdempotencyRegistry` si l'on
veut mieux exprimer les opérations de réservation et éviter l'ambiguïté avec
S3. Ne pas le renommer en simple `Repository` : son comportement atomique va
au-delà d'un CRUD classique.

**Arbitrage.** Le port **et** l'adaptateur changent de concept, pour que la
convention « par concept, puis par nature » reste vraie des deux côtés :
`application/idempotency/port/out/IdempotencyStore` et
`infrastructure/persistence/idempotency/adapter/JdbcIdempotencyStore`. Le nom
`IdempotencyStore` est conservé : aucun port de stockage objet ne s'appelle
« Store » (`QuarantineWriter`, `ServableReader`, `WorkerStorage`).

### R-009 — Revoir les noms trop implicites dans `UploadAdmission`

**État : appliquée (30/09).**

Le problème de nommage ne se limite pas à `Semaphore uploads`. Plusieurs noms
ne décrivent leur rôle qu'une fois toute la classe comprise : `limits`,
`refreshing`, `pending`, `Reading`, `at` et `Slot`. Cette concision rend un
mécanisme de contre-pression déjà peu familier inutilement difficile à lire.

Préférer des noms qui portent le concept complet :

| Actuel | Proposition |
|---|---|
| `limits` | `uploadLimits` |
| `uploads` | `concurrentUploadPermits` |
| `refreshing` | `pendingCountRefreshLock` |
| `pending` | `cachedPendingWork` |
| `Reading` | `PendingWorkSnapshot` |
| `count` | `activeWorkCount` |
| `at` | `measuredAt` |
| `Slot` | `UploadPermit` |

Le type reste utile, mais il ne devrait pas être nécessaire de repartir du type
et de tous les appels pour deviner ce que protège un verrou ou ce que mesure une
valeur. Appliquer ce niveau d'explicitation aux autres classes lors de la
relecture, sans allonger mécaniquement les noms déjà non ambigus.

Au-delà des noms, l'algorithme de rafraîchissement du compte est difficile à
reconstituer : copie locale du cache, test d'âge, `tryLock` non bloquant,
utilisation volontaire d'une valeur périmée, puis double vérification après la
prise du verrou. La logique est cohérente, mais elle concentre trop de notions
de concurrence implicites dans une méthode courte.

Extraire ce comportement dans un composant nommé, par exemple
`PendingWorkCountCache`, ou au minimum décomposer la méthode avec des noms
métier et un commentaire expliquant la stratégie : une seule requête rafraîchit
le compte ; les autres ne bloquent jamais et acceptent temporairement l'ancienne
mesure. Ajouter un test concurrent qui raconte explicitement ce scénario.

**Arbitrage.** Le problème de fond était un concept sous trois noms :
`countActiveWork()` dans le catalogue, `maxPendingFiles` et
`TOO_MANY_PENDING_FILES` dans les limites et le contrat — alors que le statut
public `PENDING` ne couvre que les fichiers en attente d'analyse. Le code
d'erreur étant public, « pending » est gardé et **défini une fois** : non
terminal, analyses et promotions en cours comprises
(`FileCatalog.countPendingFiles`).

- [`PendingFileCountCache`](../src/main/java/com/praxedo/securefiles/application/file/service/PendingFileCountCache.java)
  porte l'algorithme, avec sa stratégie en tête de classe : une seule requête
  rafraîchit ; les autres ne bloquent jamais et reprennent la mesure
  précédente.
- Noms appliqués : `concurrentUploadPermits`, `UploadPermit`, `refreshLock`,
  `Snapshot(count, measuredAt)`. Non appliqués : `uploadLimits` (le type
  `UploadLimits` dans une classe `UploadAdmission` suffit) et
  `activeWorkCount` (redondant dans un `Snapshot`, et il aurait prolongé le
  second vocabulaire).
- [`PendingFileCountCacheTest`](../src/test/java/com/praxedo/securefiles/application/file/service/PendingFileCountCacheTest.java) :
  pendant qu'une requête attend la base, vingt autres répondent en moins de
  deux secondes avec la mesure précédente, et le compte n'est fait qu'une
  fois.
