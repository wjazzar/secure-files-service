# 20 — Contre-analyse indépendante de l'énoncé et de l'architecture

> **Analyse : ChatGPT (Codex)** — 2026-09-21 — confrontation :
> [`../CONFRONTATION.md`](../CONFRONTATION.md)
>
> **Nature** : avis indépendant. Ce document ne modifie ni les analyses Claude,
> ni le registre des décisions, ni les ADR. Les décisions finales restent
> humaines.

---

## 1. Verdict exécutif

L'analyse Claude a correctement identifié le cœur du problème : ce service
n'est pas un CRUD de fichiers, mais un système qui doit préserver une relation
entre des **octets immuables**, une **attestation antivirus** et une
**autorisation de sortie**.

Je valide les orientations suivantes :

- scan asynchrone ;
- streaming de bout en bout ;
- contenu hors base et métadonnées transactionnelles dans PostgreSQL ;
- file de travaux persistée dans PostgreSQL avec `FOR UPDATE SKIP LOCKED` ;
- aucun broker au premier périmètre ;
- Spring MVC avec virtual threads plutôt que WebFlux ;
- ClamAV auto-hébergé et test EICAR ;
- découpe verticale et explicitation des seuils d'évolution.

Je **ne valide pas l'architecture A2 dans sa forme actuelle**. Deux défauts
suffisent à empêcher le démarrage du code :

1. Le même conteneur API est censé écrire les uploads dans la quarantaine tout
   en n'ayant aucun montage de cette quarantaine. Cette garantie physique est
   impossible avec les deux rôles décrits.
2. La promotion entre deux volumes n'est ni atomique ni récupérable. Un crash
   peut laisser un fichier `CLEAN` mais définitivement absent de la zone
   servable.

Le verdict est donc :

> **Conserver A2, mais la remplacer par une A2 corrigée (« A2-R ») avant toute
> implémentation.** A2-R utilise trois rôles issus de la même image
> (`ingest`, `delivery`, `worker`), un protocole de promotion idempotent, un
> automate corrigé et des contraintes SQL totales.

---

## 2. Lecture indépendante de l'énoncé

### 2.1 Exigences réellement imposées

| Exigence | Conséquence minimale |
|---|---|
| Recevoir et conserver | persistance durable du contenu et état consultable |
| Utilisateurs et systèmes tiers | contrat d'API stable ; rejouabilité à traiter |
| Aucun fichier servi sans scan préalable | contrôle fail-closed lié aux octets exacts |
| Télécharger les fichiers validés | chemin de lecture distinct du chemin non validé |
| Interface programmatique | API versionnée et erreurs machine-lisibles |
| Nombreux utilisateurs simultanés | ne pas garder le scan dans la requête HTTP ; admission bornée |
| Tailles très variables | mémoire constante et limite explicite, pas taille infinie |
| Antivirus disponible via une API | port applicatif + véritable adapter réseau |
| Java / Spring Boot / React | stack du code livré, pas langage de l'infrastructure |
| Dépôt public et application fonctionnelle | démarrage reproductible sans compte externe |
| README et prompts | raisonnement et usage critique de l'IA traçables |

### 2.2 Propriétés correctement dérivées

L'énoncé ne prononce pas les mots suivants, mais ils découlent raisonnablement
des exigences :

- **asynchronisme durable** : le traitement ne doit pas dépendre de la durée
  de vie d'une requête ou d'un processus ;
- **immutabilité** : un verdict n'est valide que pour les octets analysés ;
- **default deny** : tout nouvel état reste non téléchargeable ;
- **reprise après panne** : un fichier accepté ne doit pas rester oublié ;
- **backpressure** : l'ingestion ne peut pas croître sans borne pendant une
  panne antivirus ;
- **streaming** : une taille variable ne doit pas devenir une consommation
  mémoire variable.

### 2.3 Éléments utiles mais non exigés

Ces fonctions ne doivent pas entrer dans le premier périmètre sans raison
supplémentaire :

- Kafka ou RabbitMQ ;
- stockage objet local ;
- déduplication de verdict ou de stockage ;
- SSE, webhook, rescan planifié ;
- suppression et rétention automatisées ;
- support simultané de deux formats d'upload ;
- second adapter de stockage ou de transport uniquement « pour démontrer »
  l'hexagone.

L'authentification et le multi-tenant ne sont pas explicitement demandés, mais
ils ne peuvent pas être simulés par un en-tête fourni librement par le client.
Soit un principal fiable est intégré, soit le premier périmètre est annoncé
honnêtement comme mono-tenant.

---

## 3. Ce que l'analyse Claude fait particulièrement bien

### 3.1 La formulation de l'invariant

Formuler l'invariant en termes d'octets et de contenu est juste. Cela conduit
naturellement à l'immutabilité, au calcul d'une empreinte et au refus de toute
mutation en place.

### 3.2 PostgreSQL comme file de travaux

Le choix est adapté à un consommateur métier unique. PostgreSQL documente
explicitement `SKIP LOCKED` comme utilisable pour éviter la contention entre
plusieurs consommateurs d'une table assimilable à une file :
[documentation PostgreSQL 16](https://www.postgresql.org/docs/16/sql-select.html#SQL-FOR-UPDATE-SHARE).

La ligne métier et le travail étant créés dans la même transaction, il n'y a
pas de fenêtre « métadonnée créée mais événement perdu ». Ce n'est pas un
outbox séparé : **la ligne d'état est elle-même le travail**.

### 3.3 L'absence de broker

Kafka n'apporte rien au chemin critique actuel. Le besoin est de distribuer un
travail long et variable, pas de rejouer un journal d'événements. Ajouter
Kafka au troisième palier uniquement pour montrer un second adapter
contredirait la règle de sobriété. Je recommande de le retirer entièrement du
livrable prévisionnel.

### 3.4 MVC et virtual threads

Le pipeline est impératif et repose sur JDBC, des flux et un client antivirus
bloquant. MVC avec virtual threads est cohérent et plus facile à expliquer et
à diagnostiquer qu'une chaîne réactive partiellement bloquante.

### 3.5 La distinction des résultats

`INFECTED`, `UNSCANNABLE` et panne technique sont des informations différentes.
Les confondre produirait de faux verdicts et une politique de retry erronée.

### 3.6 La découpe verticale

Livrer un parcours complet, testé et documenté est préférable à une collection
de briques commencées. Ce principe de `docs/25` doit être conservé, même si le
contenu et les estimations des paliers doivent être revus.

---

## 4. Les décisions architecturales ne forment pas une échelle A0 → A4

La trajectoire proposée mélange quatre axes indépendants :

1. scan synchrone ou asynchrone ;
2. file PostgreSQL ou broker ;
3. filesystem ou stockage objet ;
4. octets relayés par l'application ou transférés directement vers le
   stockage.

On peut parfaitement utiliser PostgreSQL comme file avec un stockage objet,
ou effectuer un upload direct sans broker. Une « A3 partielle » est donc une
combinaison normale, pas une étape intermédiaire.

**Recommandation** : produire un ADR par axe plutôt qu'un ADR choisissant un
point sur une échelle artificielle.

---

## 5. Défauts bloquants d'A2

### 5.1 Le conteneur API ne peut pas uploader sans accès à la quarantaine

Le diagramme A2 fait transiter les octets du `POST` par le conteneur API vers
le volume de quarantaine. L'argument d'isolation affirme ensuite que le même
conteneur ne monte pas ce volume.

Docker propose des volumes en lecture-écriture ou en lecture seule, pas un
montage générique « écriture sans lecture » :
[documentation Docker](https://docs.docker.com/engine/storage/volumes/).

Si l'API monte la quarantaine en lecture-écriture, un bug de son chemin de
téléchargement peut la lire. Si elle ne la monte pas, l'upload ne fonctionne
pas.

**Correction recommandée** : trois rôles issus de la même image :

| Rôle | Endpoints | Montages |
|---|---|---|
| `ingest` | `POST /files` | `quarantine:rw`, aucun `servable` |
| `delivery` | statut, liste, téléchargement | `servable:ro`, aucune quarantaine |
| `worker` | aucun endpoint public | `quarantine:rw`, `servable:rw` |

Le front ou son serveur statique peut router le `POST` vers `ingest` et les
lectures vers `delivery`. Il n'y a toujours qu'un artefact Java et une image,
mais trois périmètres de privilèges réels.

La garantie honnête devient :

> Le processus qui autorise et sert un téléchargement ne possède aucun chemin
> vers la quarantaine.

Elle ne protège pas contre un compromis du worker, qui est nécessairement
privilégié.

### 5.2 La promotion ne peut pas être un déplacement atomique entre volumes

Deux volumes Docker correspondent normalement à deux `FileStore`. Java indique
qu'un `ATOMIC_MOVE` peut lever `AtomicMoveNotSupportedException` lorsque la
cible est sur un autre `FileStore` :
[API Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)).

La promotion doit être un protocole récupérable :

1. obtenir un verdict sain lié au hash effectivement scanné ;
2. copier vers un nom temporaire dans le volume servable ;
3. recalculer et comparer taille et SHA-256 ;
4. fermer et synchroniser le fichier ;
5. renommer atomiquement **dans le volume servable** ;
6. finaliser l'état en base par comparaison du token de lease ;
7. supprimer ensuite la copie de quarantaine, de façon idempotente ;
8. réconcilier les temporaires ou destinations orphelines après crash.

Le statut téléchargeable ne doit être posé qu'après l'existence vérifiée de la
destination finale.

### 5.3 Le filesystem n'est pas une solution haute disponibilité multi-hôte

Un volume Docker local est acceptable pour un démonstrateur mono-hôte. Il ne
permet pas de déplacer librement les réplicas entre plusieurs machines et ne
respecte donc pas, à lui seul, l'ambition « stateless et horizontalement
scalable ».

Deux positions sont défendables :

- assumer que le palier local démontre les invariants sur un hôte et décrire le
  stockage partagé comme évolution ;
- faire du stockage partagé une exigence du premier palier.

Il ne faut pas appeler le premier cas « hautement disponible ». Le compose est
un démonstrateur de comportement dégradé, pas un déploiement HA.

MinIO ne doit plus être l'évolution automatique : son dépôt communautaire est
désormais archivé et non maintenu. Son propre README renvoie vers d'autres
offres : [dépôt MinIO](https://github.com/minio/minio). Le choix d'un stockage
S3 local devra être réévalué au moment où ce besoin apparaîtra.

### 5.4 Les virtual threads ne constituent pas un bulkhead

Spring Boot précise que lorsque les virtual threads sont activés, les
propriétés de taille de pool ne produisent plus l'effet attendu et recommande
également `spring.main.keep-alive=true` pour les applications qui reposent sur
des threads daemon :
[documentation Spring Boot](https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.virtual-threads).

La phrase « la taille du pool worker est le bulkhead » est donc incompatible
avec l'activation globale des virtual threads.

**Correction** : utiliser un `Semaphore` explicite devant l'antivirus, ou un
exécuteur de threads plateforme volontairement borné dans le profil worker.
Les virtual threads améliorent l'attente I/O ; ils ne créent ni connexions
PostgreSQL, ni bande passante disque, ni capacité ClamAV.

WebFlux et virtual threads ne sont pas techniquement incompatibles, et WebFlux
n'impose pas absolument R2DBC. Le bon argument est plus simple : rendre tout le
pipeline réactif n'apporte pas de bénéfice établi ici et augmente fortement le
coût de mise en œuvre et de diagnostic.

### 5.5 La configuration ClamAV doit faire partie de la preuve

Plusieurs valeurs de l'analyse sont périmées. Le fichier amont actuel indique
notamment :

- `StreamMaxLength` : 100 MiB par défaut, pas 25 MiB ;
- `MaxScanSize` : 400 MiB ;
- `MaxFileSize` : 100 MiB ;
- limite technique annoncée : 2 GiB ;
- `AlertExceedsMax` désactivé par défaut.

Source : [`clamd.conf.sample`](https://github.com/Cisco-Talos/clamav/blob/main/etc/clamd.conf.sample).

Le dernier point est critique : une limite interne atteinte ne doit jamais être
interprétée comme un scan complet sain. La configuration retenue doit être
committée, l'image et son digest doivent être épinglés, et les réponses
`Heuristics.Limits.Exceeded.*` doivent devenir `UNSCANNABLE`.

L'adapter nominal peut appeler directement le protocole officiel `clamd`
`INSTREAM`, qui accepte le contenu en flux et applique `StreamMaxLength` :
[protocole ClamD](https://docs.clamav.net/manual/Usage/ClamdProtocol.html).
La socket TCP n'étant ni chiffrée ni authentifiée, elle doit rester sur un
réseau Docker interne et ne jamais être publiée.

Un wrapper REST non identifié ajoute une dépendance, peut bufferiser le corps
et peut perdre des informations de limite. Il n'est justifié que si son contrat
est inspecté et testé. Le stub EICAR reste un outil de test, jamais le profil
nominal de démonstration.

La documentation officielle recommande plusieurs gigaoctets de RAM pour
ClamAV en conteneur et explique les variantes d'images avec ou sans signatures
préchargées :
[ClamAV dans Docker](https://docs.clamav.net/manual/Installing/Docker.html).

### 5.6 La contre-pression ne peut pas attendre un second palier

Le scénario « antivirus indisponible mais uploads acceptés » fait croître le
disque sans borne. Le premier périmètre doit inclure au minimum :

- taille maximale par fichier ;
- volume et nombre maximum de travaux non terminaux ;
- seuil minimal d'espace disque ;
- refus temporaire avec `Retry-After` ;
- politique de purge des temporaires et orphelins.

« Tailles très variables » ne signifie ni taille illimitée, ni acceptation de
contenus que le système sait ne jamais pouvoir valider.

---

## 6. Désaccord majeur : le traitement des fichiers trop grands

Claude recommande d'accepter un fichier connu comme trop grand, de le conserver
en `UNSCANNABLE` et de ne jamais le servir.

Je recommande l'inverse pour le cas détectable avant scan :

> **Si la taille excède la capacité contractuelle du scanner, refuser
> l'ingestion avec `413`.**

Raisons :

- le service sait à l'avance qu'il ne pourra jamais remplir sa finalité ;
- conserver ce contenu consomme du stockage sans issue fonctionnelle ;
- cette voie facilite un déni de service disque ;
- l'énoncé ne promet aucune taille infinie ; il dit seulement « très
  variables » et ne fournit pas de borne.

L'état `UNSCANNABLE` reste indispensable lorsque l'impossibilité n'est connue
qu'après analyse : archive chiffrée, récursion, nombre de fichiers, taille
décompressée ou limite interne. Il n'est pas un substitut à une politique
d'admission.

---

## 7. Corrections factuelles complémentaires

| Affirmation existante | Situation vérifiée au 2026-09-21 | Conséquence |
|---|---|---|
| H2 ne supporte pas `SKIP LOCKED` | H2 le documente désormais dans `SELECT FOR UPDATE` ([source](https://h2database.com/html/commands.html#select)) | PostgreSQL reste retenu pour la fidélité production, mais cet argument doit disparaître |
| Un objet S3 est limité à 5 To | AWS documente désormais 50 To ([source](https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingObjects.html)) | Mettre à jour le comparatif ; sans impact sur le palier 1 |
| Spring Boot 3.5.x est le choix courant implicite | Spring Boot 4.1.1 est la version stable courante et supporte Java 21 ([source](https://docs.spring.io/spring-boot/system-requirements.html)) | La version doit être décidée et épinglée au démarrage, pas héritée d'un document ancien |
| `~1 000 travaux/s` déclenche un broker | seuil non mesuré | remplacer par DB mesurée saturée ou besoin de sémantique absent de PostgreSQL |
| un second consommateur impose un broker | pas nécessairement | comparer d'abord polling, outbox ou CDC selon le besoin réel |
| l'observabilité SQL est « meilleure » | préférence, pas propriété universelle | formuler les capacités et coûts, pas un classement absolu |

---

## 8. Architecture recommandée : A2-R

```text
                         ┌──────────────────────────┐
Client / React ──POST───▶│ ingest                   │
                         │ quarantaine rw seulement │
                         └──────────┬───────────────┘
                                    │ contenu finalisé
                                    ▼
                         ┌──────────────────────────┐
                         │ volume quarantine       │
                         └──────────┬───────────────┘
                                    │
                         ┌──────────▼───────────────┐
                         │ worker                   │
                         │ quarantine rw            │
                         │ servable rw              │
                         │ sémaphore AV borné       │
                         └──────┬──────────┬────────┘
                                │          │ INSTREAM
                                │          ▼
                                │       ClamAV
                                ▼
                         ┌──────────────────────────┐
                         │ volume servable         │
                         └──────────┬───────────────┘
                                    │ lecture seule
                         ┌──────────▼───────────────┐
Client / React ◀──GET────│ delivery                 │
                         │ servable ro seulement    │
                         └──────────────────────────┘

          ingest ─┐
        delivery ─┼──── PostgreSQL : état + file durable + audit
          worker ─┘
```

Propriétés :

- un artefact et une image Java ;
- trois rôles aux privilèges différents ;
- aucune quarantaine visible depuis le processus de livraison ;
- travail durable et observable sans broker ;
- effets du worker idempotents et récupérables ;
- limite de concurrence antivirus indépendante du nombre de virtual threads.

Limite assumée : le stockage fichier reste mono-hôte. A2-R est un excellent
démonstrateur local ; elle n'est pas un déploiement HA multi-zone.

---

## 9. Conclusion

Claude a choisi les bonnes briques et posé les bonnes questions. L'écart se
situe entre **l'intention de sécurité** et sa **réalisation mécanique**.

Le projet ne doit pas repartir vers Kafka, WebFlux ou une multiplication des
services. Il doit investir son budget dans :

1. l'isolation réelle des rôles ;
2. la promotion récupérable ;
3. le lien cryptographique entre octets scannés et octets servis ;
4. la correction de l'automate et des contraintes SQL ;
5. les tests d'injection de panne et de saturation.

**Verdict : A2-R, PostgreSQL oui, broker non, MVC + virtual threads oui,
ClamAV oui, Kafka non. Filesystem oui pour un démonstrateur mono-hôte clairement
annoncé, pas comme preuve de haute disponibilité multi-hôte.**
