# 04 — Idempotence

> **Analyse écrite avant le code** (série 00), **relue contre le code livré le
> 02/10**. Chaque section garde le problème posé et dit ce qui est **livré**,
> ce qui a été **écarté** et pourquoi. Le protocole livré fait foi dans
> [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §9.

« Le service est idempotent » ne veut rien dire. L'idempotence n'est pas une
propriété globale : c'est **cinq problèmes distincts**, avec cinq mécanismes
distincts.

---

## Vue d'ensemble

| # | Dimension | Question à laquelle elle répond | Ce qui est livré |
|---|---|---|---|
| 1 | **Rejeu du dépôt** | « Le client a relancé après un timeout : ai-je créé deux fichiers ? » | `Idempotency-Key`, unicité en base ; le rejeu rend le fichier du premier dépôt |
| 2 | **Même contenu déposé deux fois** | « Le même fichier est envoyé 1 000 fois : dois-je l'analyser 1 000 fois ? » | **Rien en v1** : deux fichiers, deux analyses. Réutilisation de verdict en piste (`D-08`) |
| 3 | **Prise concurrente d'un travail** | « Deux workers prennent-ils le même fichier ? » | Claim atomique `FOR UPDATE SKIP LOCKED` + jeton de bail |
| 4 | **Analyse rejouée** | « Rejouer une analyse a-t-il un effet de bord ? » | Lecture pure d'un objet immuable ; écriture du verdict conditionnée au jeton |
| 5 | **Promotion rejouée** | « La copie vers la zone servable a été interrompue : peut-on la refaire ? » | Écriture sous la clé finale, vérifiée, puis bascule conditionnelle |

L'analyse initiale comptait la **suppression** comme cinquième dimension.
Aucune suppression de fichier n'est livrée (décision du 01/10) ; la promotion,
elle, est bien rejouée, et a pris cette place.

---

## 1. Rejeu du dépôt

### Le problème
Un client (surtout un **système tiers**, `EX-02`) dépose un fichier de 500 Mo.
Le réseau coupe après l'écriture complète mais avant la réponse. Le client
relance. Sans protection : deux fichiers, deux analyses, et un état incohérent
côté appelant.

### Le mécanisme livré
En-tête `Idempotency-Key`, facultatif, propre à l'appelant, gardé 24 h.

```
Table idempotency_record
├── owner_id, idempotency_key    (clé primaire composite)
├── request_fingerprint          (SHA-256 du nom et de la taille annoncée)
├── state : IN_PROGRESS | COMPLETED
├── file_id                      (le fichier créé — obligatoire si COMPLETED)
└── created_at, expires_at       (24 h)
```

Algorithme :

1. `INSERT … ON CONFLICT DO NOTHING` sur `(owner_id, idempotency_key)`, en
   état `IN_PROGRESS`.
2. **0 ligne insérée** → un enregistrement existe :
   - expiré → supprimé (suppression conditionnelle), on recommence ;
   - **empreinte différente** → `422 IDEMPOTENCY_KEY_REUSED` ;
   - même empreinte, `IN_PROGRESS` → `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` +
     `Retry-After` ;
   - même empreinte, `COMPLETED` → `202` avec **le fichier du premier dépôt,
     dans son état courant**.
3. **1 ligne insérée** → recevoir le fichier, puis, dans **une** transaction,
   écrire la ligne du fichier et passer l'enregistrement en `COMPLETED`.
4. Échec en cours de route → l'enregistrement est supprimé, la clé redevient
   utilisable.
5. Panne du nœud → l'enregistrement `IN_PROGRESS` est purgé au bout de deux
   heures par le balayage.

C'est l'`INSERT … ON CONFLICT` qui tranche la course, jamais un `SELECT` suivi
d'un `INSERT`.

### Deux écarts avec la proposition initiale

- **Pas d'instantané de réponse.** La première version (migration `V4`)
  rangeait le code et le corps de la réponse d'origine ; `V5` les a retirés.
  Un client qui relance après une coupure reçoit l'état **actuel** de son
  fichier, pas une photo « en attente » prise avant la fin de l'analyse.
- **L'empreinte ne contient pas le contenu.** Elle porte sur ce qui est connu
  **avant** de lire le corps : le nom et la taille annoncée, rien d'autre.
  Y mettre le SHA-256 du contenu obligerait à lire les 500 Mo du rejeu, alors
  que le rejeu répond justement sans les lire. **Limite assumée** : un rejeu
  de même nom et de même taille mais de contenu différent n'est pas
  distingué — il ne crée pas de second fichier, le client reçoit le fichier du
  premier dépôt avec son SHA-256, qu'il peut comparer au sien.

### Points d'attention
- L'expiration est nécessaire : une table d'idempotence sans purge croît
  indéfiniment.
- Sans `Idempotency-Key` : le comportement est « créer ». On **n'invente pas**
  une clé implicite à partir du nom — deux fichiers différents peuvent
  légitimement porter le même nom.

---

## 2. Même contenu déposé deux fois — `D-08`

### Le problème
Le SHA-256 est calculé pendant la réception, au fil du flux (`InspectingInputStream`). Si le même
contenu a déjà été analysé et déclaré sain, faut-il l'analyser de nouveau ?

### Le piège
**Oui, dans le cas général.** Un verdict sain n'est valable que pour une
version donnée de la base de signatures. Réutiliser aveuglément un verdict de
J-30 revient à servir un fichier dont la signature a été publiée depuis.

Une réutilisation correcte demanderait :

```
réutiliser le verdict existant SI ET SEULEMENT SI :
    verdict.result == CLEAN
ET  verdict.signatureVersion == version courante du moteur
ET  verdict.scannedAt > now() - âge maximal   (ex. 24 h)
SINON : nouvelle analyse
```

### Ce qui est livré
**Aucune déduplication.** Deux dépôts identiques font deux fichiers et deux
analyses (`D-08`, reporté). Mal faite, la réutilisation de verdict est une
faille ; bien faite, elle n'apporte rien à la volumétrie de l'exercice. Elle
est en piste d'amélioration au README §10, avec ses conditions.

La déduplication **de stockage** (un seul objet partagé entre plusieurs
fichiers) n'a jamais été envisagée : comptage de références, et canal
auxiliaire entre utilisateurs (tester l'existence d'un contenu par le temps de
réponse).

---

## 3. Prise concurrente d'un travail

### Le problème
Plusieurs workers, sur plusieurs nœuds, vident la même file. Deux d'entre eux
ne doivent pas analyser le même fichier ; un worker peut mourir après
l'analyse mais avant d'écrire le verdict.

L'analyse initiale raisonnait sur un broker livrant « au moins une fois ».
**Il n'y a pas de broker** ([ADR-0001](adr/0001-la-base-de-donnees-fait-file.md)) :
la ligne du fichier **est** le travail, et la base fait file.

### Le mécanisme livré : claim atomique

```sql
WITH candidate AS (
  SELECT id FROM stored_file
   WHERE status IN ('AWAITING_SCAN','RETRY_WAIT')
     AND next_attempt_at <= clock_timestamp()
     AND attempts < :maxAttempts
   ORDER BY next_attempt_at, uploaded_at, id
   FOR UPDATE SKIP LOCKED LIMIT 1)
UPDATE stored_file f
   SET status='SCANNING', lease_token=:claimToken, lease_holder=:workerId,
       lease_expires_at=clock_timestamp() + make_interval(
           secs => :leaseMinSeconds + f.size_bytes / 1048576.0 * :leasePerMibSeconds),
       attempts=f.attempts+1, version=f.version+1
  FROM candidate WHERE f.id = candidate.id
RETURNING f.*;
```

Une instruction : sélection, exclusion mutuelle, prise de bail, comptage de la
tentative. C'est un verrou distribué **sans infrastructure de verrouillage** :
la garantie vient de la base. `SKIP LOCKED` fait qu'un worker ne patiente pas
derrière un autre, il prend le fichier suivant.

### Écriture du verdict, conditionnée au jeton

```sql
UPDATE stored_file SET status = :statut, …
 WHERE id = :fileId
   AND status = 'SCANNING'
   AND lease_token = :claimToken            -- ⚠ le jeton de CE claim
   AND lease_expires_at > clock_timestamp();
```

Le contrôle porte sur le **jeton de la prise**, tiré au hasard à chaque claim,
et non sur l'identifiant du worker — correction issue de la confrontation
([`26`](26-reponse-a-la-contre-analyse.md)). Un worker gelé dont le bail a
expiré peut reprendre lui-même le même fichier : avec un contrôle sur son
identifiant, son ancien fil d'exécution écraserait le verdict légitime.
0 ligne modifiée → l'écriture est refusée et comptée
(`praxedo.scan.verdict.rejected`).

### Pas de verrou optimiste JPA sur ces écritures
Les instructions de la file sont écrites à la main (`JdbcClient`) et
contournent le contexte de persistance : `@Version` lèverait une exception au
lieu de répondre « ai-je gagné ? », et vérifierait la mauvaise chose. La
colonne `version` est incrémentée par ces instructions et sert d'`ETag` au
suivi ([ADR-0003](adr/0003-jpa-pour-lire-sql-pour-garantir.md)).

---

## 4. Idempotence de l'analyse elle-même

Elle est obtenue **par construction** :

- L'objet stocké est **immuable** : écrit une fois, sous une clé UUID.
- L'analyse est une **lecture pure** : elle ne modifie ni l'objet ni son
  emplacement.
- Le seul effet de bord est l'écriture du verdict, protégée par le §3.

Conséquence : **rejouer une analyse est toujours sûr**. C'est ce qui rend les
réessais et le *reaper* sans risque.

---

## 5. Idempotence de la promotion

La promotion relit l'objet de la quarantaine et l'écrit **directement sous sa
clé finale** dans la zone servable, en recalculant taille et SHA-256
([ADR-0006](adr/0006-promotion-par-relecture-verifiee.md)) :

- interrompue, elle est simplement refaite : réécrire le même contenu vérifié
  sous la même clé est sans effet observable ;
- un objet servable dont la ligne n'est pas `AVAILABLE` n'est **jamais servi** :
  le point de validation est la ligne, pas l'objet ;
- la bascule `PROMOTING → AVAILABLE` est une écriture conditionnée au jeton ;
- la suppression de la source tolère son absence ; un échec est rattrapé par
  le balayage de la quarantaine.

---

## 6. Ce qui n'existe pas

| Envisagé | État |
|---|---|
| `DELETE /api/v1/files/{id}`, suppression logique, état `DELETED` | **Non livré** : pas de suppression de fichier (décision du 01/10) |
| Outbox et événement à publier | **Sans objet** : pas de broker, l'état et le travail sont la même ligne |
| Déduplication de verdict | **Reportée** (`D-08`) |

---

## 7. Récapitulatif — invariants de concurrence testés

| Scénario | Résultat attendu | Test |
|---|---|---|
| Dépôts concurrents, même clé, même nom et même taille | Une seule réservation accordée, un seul fichier créé ; pendant le premier dépôt, les autres reçoivent `409` ; ensuite, le rejeu rend le même fichier | `JdbcIdempotencyStoreTest`, `UploadFileServiceTest`, `UploadApiTest` |
| Même clé, autre nom ou autre taille | `422 IDEMPOTENCY_KEY_REUSED` | `UploadFileServiceTest`, `UploadApiTest` |
| Huit workers en concurrence sur huit fichiers | Huit fichiers différents : jamais deux workers sur le même | `FilePersistenceTest` |
| Worker disparu pendant l'analyse | Bail expiré → fichier remis en file par le *reaper*, tentative comptée | `FilePersistenceTest` |
| Worker zombie écrivant après l'expiration de son bail | Écriture refusée (0 ligne) | `FilePersistenceTest` |
| Même contenu déposé deux fois, sans clé | Deux fichiers, deux analyses | — (comportement par défaut) |
| Promotion interrompue à chacun de ses cinq points | Reprise sans fichier servi à tort | `FilePromotionServiceTest` |
