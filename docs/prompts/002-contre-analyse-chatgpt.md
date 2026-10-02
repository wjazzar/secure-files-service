# 002 — Contre-analyse indépendante ChatGPT et verdict

- **Date** : 2026-09-21
- **Outil** : ChatGPT (Codex)
- **Objectif** : produire une analyse indépendante de l'énoncé, confronter les
  recommandations Claude à des mécanismes vérifiables, puis consigner un
  verdict sans usurper les décisions du porteur du projet.
- **Phase du projet** : cadrage / confrontation

---

## Prompt

> Voici l'énoncé et l'analyse de Claude. Produis ta propre analyse,
> indépendante :
>
> 1. ton avis sur l'énoncé et ses ambiguïtés ;
> 2. une confrontation point par point avec l'analyse de Claude, appuyée sur
>    des mécanismes vérifiables et des sources ;
> 3. un verdict argumenté, consigné dans le dépôt, sans trancher à ma place
>    les décisions qui me reviennent.

Contexte appliqué : `AGENTS.md`, l'énoncé `docs/00-enonce.md`, l'intégralité des
documents `docs/01` à `docs/25`, le protocole `docs/CONFRONTATION.md` et les
deux précédentes entrées du journal.

---

## Démarche

1. Relire l'énoncé sans reprendre les conclusions Claude comme prémisses.
2. Séparer exigences imposées, propriétés dérivées et fonctions optionnelles.
3. Auditer indépendamment architecture, domaine/DDL/concurrence et
   API/périmètre.
4. Chercher des contre-exemples concrets aux garanties revendiquées.
5. Vérifier les faits susceptibles d'avoir changé dans les sources officielles.
6. Produire une recommandation distincte dans `docs/chatgpt/`, sans modifier
   les documents attribués à Claude ni signer d'ADR.

---

## Ce que j'ai retenu de l'analyse Claude

| Proposition | Motif |
|---|---|
| Scan asynchrone et durable | découple la requête HTTP du traitement long et permet la reprise |
| Streaming intégral | traduction nécessaire des tailles variables |
| PostgreSQL comme file | atomicité entre état et travail ; `SKIP LOCKED` adapté |
| Aucun broker au premier palier | aucune exigence ne justifie sa sémantique ou son coût |
| MVC + virtual threads | cohérent avec JDBC et les flux bloquants |
| Distinction infection / limite / panne | évite les faux verdicts et les retries erronés |
| ClamAV local + EICAR | reproductible, confidentiel et testable |
| Découpe verticale | un parcours correct vaut mieux que plusieurs fonctions incomplètes |

---

## Ce que j'ai rejeté ou corrigé

| Proposition initiale | Correction |
|---|---|
| Deux rôles API/worker avec API sans quarantaine | impossible : l'API doit écrire l'upload. Trois rôles `ingest/delivery/worker` |
| `CLEAN` posé avant promotion | état `PROMOTING`, destination vérifiée, puis `AVAILABLE` |
| Déplacement atomique quarantaine → servable | copie vers temporaire dans la cible, hash, renommage atomique local, CAS et nettoyage |
| `CHECK` SQL présenté comme absolu | le prédicat est contournable par `NULL`; utiliser des prédicats totaux et tester les `NULL` |
| `SCAN_FAILED` retryable et terminal | séparer `RETRY_WAIT` et `FAILED_FINAL` |
| worker ID comme clôture de lease | token UUID propre à chaque claim + expiration + heartbeat/CAS |
| taille du pool comme bulkhead avec virtual threads | sémaphore explicite ou exécuteur plateforme borné |
| acceptation des fichiers connus trop gros en `UNSCANNABLE` | `413` ; conserver `UNSCANNABLE` pour les limites découvertes par le scan |
| wrapper REST ClamAV implicite | préférer l'API officielle `clamd INSTREAM` sauf wrapper audité |
| déduplication de verdict au palier 1 | retirer ; conserver le hash pour l'intégrité |
| deux formats d'upload | un flux binaire suffit, y compris depuis React avec XHR |
| `202` nécessaire après upload | `201` est plus exact si la ressource durable existe déjà |
| `403` infecté et `503` scan échoué | `409` + code métier pour le propriétaire ; réserver `403` à l'authz et `503` à une indisponibilité actuelle |
| Kafka au palier 3 pour démontrer un adapter | retirer tant qu'aucun besoin ne le déclenche |
| palier 1 en deux jours | estimation non étayée et irréaliste pour la definition of done annoncée |

---

## Défauts bloquants découverts

1. **Isolation contradictoire** : le même conteneur doit écrire une zone qu'il
   est censé ne pas monter.
2. **SQL et `NULL`** : PostgreSQL accepte un `CHECK` évalué à `UNKNOWN` ; une
   ligne téléchargeable sans `scan_result` peut passer.
3. **Promotion non récupérable** : un crash après verdict peut laisser un
   statut sain sans destination servable.
4. **Retry infini** : le claim reprend tous les `SCAN_FAILED`, même après le
   nombre maximal d'essais.
5. **Lease insuffisant** : `SCANNING` sans lease est autorisé et un identifiant
   worker réutilisé ne clôture pas un claim.
6. **Octets non liés au verdict** : clé physique non unique, aucun hash des
   octets effectivement scannés et promus.
7. **Idempotence contradictoire** : le fingerprint exige le hash avant lecture
   du corps ; `IN_PROGRESS` ne possède aucun mécanisme de reprise.
8. **Rescan/suppression incohérents** : la contrainte de zone interdit les
   transitions annoncées depuis l'état final.

---

## Vérifications effectuées

Sources primaires consultées le 2026-09-21 :

- PostgreSQL 16 — `SKIP LOCKED` et usage de type file :
  <https://www.postgresql.org/docs/16/sql-select.html#SQL-FOR-UPDATE-SHARE>
- ClamAV — protocole `INSTREAM` :
  <https://docs.clamav.net/manual/Usage/ClamdProtocol.html>
- ClamAV — configuration amont et limites actuelles :
  <https://github.com/Cisco-Talos/clamav/blob/main/etc/clamd.conf.sample>
- ClamAV — image Docker, signatures et mémoire :
  <https://docs.clamav.net/manual/Installing/Docker.html>
- Spring Boot — virtual threads, pool et keep-alive :
  <https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.virtual-threads>
- Java 21 — limites d'`ATOMIC_MOVE` entre stockages :
  <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html>
- Docker — modes de montage de volumes :
  <https://docs.docker.com/engine/storage/volumes/>
- HTTP — sémantique `201`/`202` :
  <https://datatracker.ietf.org/doc/html/rfc9110>
- HTTP — champ `Content-Digest` :
  <https://datatracker.ietf.org/doc/html/rfc9530>
- H2 — support actuel de `FOR UPDATE … SKIP LOCKED` :
  <https://h2database.com/html/commands.html#select>
- AWS S3 — limite actuelle d'un objet :
  <https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingObjects.html>
- Spring Boot — version stable et compatibilité Java :
  <https://docs.spring.io/spring-boot/system-requirements.html>
- MinIO — état actuel du projet communautaire :
  <https://github.com/minio/minio>

Constats factuels notables :

- `StreamMaxLength` est actuellement documenté à 100 MiB par défaut, pas
  25 MiB ; `AlertExceedsMax` reste désactivé par défaut ;
- les virtual threads ignorent les réglages habituels de taille de pool ;
- `ATOMIC_MOVE` n'est pas garanti entre deux `FileStore` ;
- H2 documente désormais `SKIP LOCKED` ;
- AWS S3 documente désormais 50 To par objet ;
- le dépôt communautaire MinIO est archivé ;
- Spring Boot 4.1.1 est la version stable courante et accepte Java 21.

---

## Verdict produit

Les recommandations sont consignées séparément dans :

- [`../chatgpt/20-contre-analyse-enonce-et-architecture.md`](../chatgpt/20-contre-analyse-enonce-et-architecture.md) ;
- [`../chatgpt/21-invariant-domaine-et-donnees.md`](../chatgpt/21-invariant-domaine-et-donnees.md) ;
- [`../chatgpt/22-verdict-et-arbitrages.md`](../chatgpt/22-verdict-et-arbitrages.md).

Verdict court : **A2-R ; PostgreSQL oui, broker non, MVC + virtual threads
oui, ClamAV oui, Kafka non. Aucun code applicatif avant arbitrage humain des
corrections bloquantes.**

---

## Décisions restées humaines

- tous les statuts `D-01…D-17` ;
- acceptation ou rejet de la topologie à trois rôles ;
- séparation `CLEAN` / `AVAILABLE` ;
- portée de l'authentification et du multi-tenant ;
- choix mono-hôte assumé ou stockage partagé ;
- limite maximale acceptée ;
- budget réel et fonctions retirées du premier palier.

Aucun ADR n'a été écrit et aucune décision n'a été marquée `ACTÉ`.
