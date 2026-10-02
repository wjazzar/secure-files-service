# 23 — Modèle de données

> **Analyse : Claude (Opus 5)** — 2026-09-20 — confrontation : [`CONFRONTATION.md`](CONFRONTATION.md)
>
> Document répondant à une demande du porteur du projet : le modèle de
> données — où les fichiers sont enregistrés, et comment leur état d'analyse
> est tracé.

> ⚠️ **Remplacé par [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §6**
> et les migrations Flyway, qui décrivent le schéma livré (constat du 26/09,
> clos le 01/10). Ce document est antérieur à la confrontation : sept états,
> `CLEAN` valant disponibilité (`D-19`), colonne `tenant_id` (`D-11`), bail
> contrôlé par `lease_holder`, contraintes contournables par `NULL`. Gardé
> pour l'historique du raisonnement.

C'est le cœur du système. **La base ne stocke pas les fichiers : elle stocke la
vérité sur les fichiers** — et c'est elle qui porte l'invariant `EX-03`.

---

## 1. Ce que la base porte, et ce qu'elle ne porte pas

| Donnée | Où | Pourquoi |
|---|---|---|
| Les **octets** | Système de fichiers (palier 1) / S3 (palier 2) | Volume, streaming, poids sur les sauvegardes — cf. [P-08](20-catalogue-problematiques.md#p-08) |
| L'**état de scan** | **Base** | Transactions, unicité, claim atomique |
| La **file de travail** | **Base** (la même table) | Pas de seconde source de vérité à réconcilier |
| Le **verdict** et sa traçabilité | **Base** | Auditabilité |
| L'**idempotence** | **Base** (contrainte d'unicité) | Seul endroit où la course est réellement empêchée |
| L'**audit** | **Base** (table dédiée, append-only) | Preuve |

### Décision structurante : la table d'état **est** la file

Il n'y a **pas** de table `outbox` séparée, ni de table `scan_job` distincte.
La table `stored_file` porte à la fois l'état métier et les colonnes de
travail (`attempts`, `next_attempt_at`, `lease_*`).

**Pourquoi** — c'est ce qui rend l'outbox gratuit : créer le fichier et créer
le travail sont **la même écriture**, dans la même transaction, par
construction. Il n'y a rien à synchroniser, donc rien à désynchroniser.

**Contrepartie assumée** — la table mélange deux préoccupations (état métier
et mécanique de traitement). C'est un compromis délibéré : à cette échelle,
l'atomicité gratuite vaut mieux que la pureté du modèle. Si le volume imposait
de séparer les deux, la table de travail deviendrait distincte — avec alors un
vrai outbox à gérer.

---

## 2. Schéma

```
┌──────────────────────────┐
│  stored_file             │  état + file de travail
│  ──────────────────────  │
│  PK id (uuid)            │
│  tenant_id               │──┐
│  status  ◀── l'invariant │  │
│  storage_zone            │  │
│  object_key              │  │
│  content_sha256          │  │
│  … verdict inline …      │  │
│  … colonnes de file …    │  │
└──────────┬───────────────┘  │
           │ 1..n             │
           ▼                  │
┌──────────────────────────┐  │   ┌──────────────────────────┐
│  file_audit_event        │  └──▶│  idempotency_record      │
│  ──────────────────────  │      │  ──────────────────────  │
│  append-only             │      │  PK (tenant_id, key)     │
│  qui / quoi / quand      │      │  snapshot de réponse     │
└──────────────────────────┘      └──────────────────────────┘
```

Trois tables. Pas davantage au palier 1.

---

## 3. DDL

### 3.1 Énumérations

```sql
-- Types PostgreSQL natifs plutôt que contraintes CHECK : l'ajout d'une valeur
-- est explicite (ALTER TYPE), ce qui force à traiter le nouvel état partout.
CREATE TYPE file_status AS ENUM (
    'AWAITING_SCAN',   -- reçu, en attente d'analyse          -- non servable
    'SCANNING',        -- analyse en cours (bail détenu)      -- non servable
    'CLEAN',           -- verdict sain                        -- SERVABLE
    'INFECTED',        -- menace détectée                     -- non servable
    'SCAN_FAILED',     -- panne technique, réessayable        -- non servable
    'UNSCANNABLE',     -- hors capacité de l'antivirus        -- non servable
    'DELETED'          -- supprimé logiquement                -- non servable
);

CREATE TYPE storage_zone AS ENUM ('QUARANTINE', 'SERVABLE');
```

### 3.2 Table principale

```sql
CREATE TABLE stored_file (
    -- Identité
    id                      uuid         PRIMARY KEY,
    tenant_id               text         NOT NULL,

    -- Métadonnées déclarées par l'appelant (données non fiables)
    original_filename       text         NOT NULL,
    declared_content_type   text,

    -- Métadonnées établies par le serveur (fiables)
    detected_content_type   text,
    size_bytes              bigint       NOT NULL CHECK (size_bytes >= 0),
    content_sha256          char(64)     NOT NULL,

    -- Emplacement physique
    storage_zone            storage_zone NOT NULL DEFAULT 'QUARANTINE',
    object_key              text         NOT NULL,   -- UUID, JAMAIS le nom fourni

    -- ═══ L'INVARIANT ═══
    status                  file_status  NOT NULL DEFAULT 'AWAITING_SCAN',

    -- Verdict (nul tant qu'aucune analyse n'a abouti)
    scan_result             text,                    -- CLEAN | INFECTED | UNSCANNABLE | FAILED
    scan_threat_name        text,
    scan_engine             text,
    scan_signature_db_ver   text,                    -- ⚠ indispensable au rescan et à la dédup
    scanned_at              timestamptz,
    scan_duration_ms        integer,

    -- ═══ COLONNES DE FILE (l'outbox est ici) ═══
    attempts                integer      NOT NULL DEFAULT 0,
    next_attempt_at         timestamptz  NOT NULL DEFAULT now(),
    lease_holder            text,
    lease_expires_at        timestamptz,
    last_error              text,

    -- Traçabilité
    uploaded_by             text         NOT NULL,
    uploaded_at             timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    version                 bigint       NOT NULL DEFAULT 0,   -- verrou optimiste JPA

    -- ═══ CONTRAINTES PORTANT L'INVARIANT ═══

    -- R-02 : impossible d'être CLEAN sans verdict complet.
    -- Cette contrainte est la traduction SQL de l'exigence EX-03 : même une
    -- écriture directe en base ne peut pas produire un fichier servable
    -- sans verdict. C'est la garantie de dernier recours.
    CONSTRAINT clean_requires_verdict CHECK (
        status <> 'CLEAN'
        OR (scan_result = 'CLEAN' AND scanned_at IS NOT NULL
            AND scan_signature_db_ver IS NOT NULL)
    ),

    -- R-04 : un objet n'est dans la zone servable que s'il est CLEAN
    CONSTRAINT servable_zone_requires_clean CHECK (
        storage_zone <> 'SERVABLE' OR status = 'CLEAN'
    ),

    -- Un bail implique un détenteur, et réciproquement
    CONSTRAINT lease_consistency CHECK (
        (lease_holder IS NULL) = (lease_expires_at IS NULL)
    ),

    -- Seul l'état SCANNING détient un bail
    CONSTRAINT lease_only_when_scanning CHECK (
        lease_holder IS NULL OR status = 'SCANNING'
    )
);
```

> Les quatre `CHECK` sont le point important de ce schéma. Ils font de
> l'invariant une propriété **du moteur de base de données**, pas du code
> applicatif : aucun bug, aucune requête manuelle, aucune migration mal écrite
> ne peut produire un fichier servable sans verdict. C'est la quatrième ligne
> de défense, en complément des trois de
> [P-24](20-catalogue-problematiques.md#p-24).

### 3.3 Index

```sql
-- ① File de travail : index PARTIEL sur les seuls états actifs.
--    Quelques dizaines de lignes, quel que soit l'historique.
--    C'est ce qui rend le polling gratuit. Cf. P-12.
CREATE INDEX idx_file_queue
    ON stored_file (next_attempt_at)
    WHERE status IN ('AWAITING_SCAN', 'SCAN_FAILED');

-- ② Reaper : baux expirés. Index partiel également.
CREATE INDEX idx_file_expired_lease
    ON stored_file (lease_expires_at)
    WHERE status = 'SCANNING';

-- ③ Listing utilisateur (pagination par curseur)
CREATE INDEX idx_file_tenant_listing
    ON stored_file (tenant_id, uploaded_at DESC, id);

-- ④ Déduplication de verdict (D-08)
CREATE INDEX idx_file_content_hash
    ON stored_file (content_sha256)
    WHERE status = 'CLEAN';

-- ⑤ Balayage des orphelins / rétention
CREATE INDEX idx_file_retention
    ON stored_file (uploaded_at)
    WHERE status IN ('INFECTED', 'DELETED');
```

**Pourquoi les index partiels comptent ici** : sans eux, l'index de file
grandirait indéfiniment avec l'historique (des millions de lignes `CLEAN`),
alors que la requête n'en cible que quelques dizaines. C'est un différenciateur
concret de PostgreSQL face à MySQL
([`22-comparatif-produits.md`](22-comparatif-produits.md) §3).

### 3.4 Idempotence

```sql
CREATE TABLE idempotency_record (
    tenant_id           text        NOT NULL,
    idempotency_key     text        NOT NULL,
    request_fingerprint char(64)    NOT NULL,   -- hash(taille+nom+type+sha256)
    state               text        NOT NULL,   -- IN_PROGRESS | COMPLETED
    response_status     integer,
    response_body       jsonb,
    file_id             uuid        REFERENCES stored_file(id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    expires_at          timestamptz NOT NULL,   -- TTL 24 h
    PRIMARY KEY (tenant_id, idempotency_key)
);

CREATE INDEX idx_idempotency_expiry ON idempotency_record (expires_at);
```

La clé primaire composite **est** le mécanisme d'idempotence : c'est le
`INSERT … ON CONFLICT DO NOTHING` qui tranche la course, pas un `SELECT`
préalable suivi d'un `INSERT` (qui serait vulnérable).

### 3.5 Audit

```sql
CREATE TABLE file_audit_event (
    id          bigserial   PRIMARY KEY,
    file_id     uuid        NOT NULL,
    tenant_id   text        NOT NULL,
    event_type  text        NOT NULL,   -- UPLOADED, SCAN_STARTED, VERDICT,
                                        -- PROMOTED, DOWNLOAD_AUTHORIZED, DELETED
    from_status file_status,
    to_status   file_status,
    actor       text        NOT NULL,   -- sujet JWT ou identifiant de worker
    details     jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_file ON file_audit_event (file_id, occurred_at);
```

Table **append-only** : aucun `UPDATE`, aucun `DELETE` (à faire respecter par
les droits du rôle applicatif, pas seulement par convention). Elle répond à la
question « **que s'est-il passé pour ce fichier ?** » — la question posée en
cas d'incident.

---

## 4. Les trois requêtes critiques

Ce sont les seules qui portent une garantie de correction. Elles méritent
d'être écrites à la main et testées en concurrence.

### 4.1 Prendre un travail (claim atomique)

```sql
UPDATE stored_file
   SET status           = 'SCANNING',
       lease_holder     = :workerId,
       lease_expires_at = now() + :leaseDuration,
       attempts         = attempts + 1,
       updated_at       = now(),
       version          = version + 1
 WHERE id = (
       SELECT id FROM stored_file
        WHERE status IN ('AWAITING_SCAN', 'SCAN_FAILED')
          AND next_attempt_at <= now()
        ORDER BY next_attempt_at
        FOR UPDATE SKIP LOCKED
        LIMIT 1
 )
RETURNING *;
```

Une requête réalise : sélection du plus urgent, exclusion mutuelle entre
workers (`SKIP LOCKED` — chacun prend une ligne **différente**, sans attente),
prise de bail, et comptage de tentatives. **C'est le cœur technique du
système.** Zéro ligne retournée = rien à faire, le worker attend.

### 4.2 Écrire le verdict (protection contre le worker zombie)

```sql
UPDATE stored_file
   SET status                = :newStatus,
       scan_result           = :result,
       scan_threat_name      = :threatName,
       scan_engine           = :engine,
       scan_signature_db_ver = :sigVersion,
       scanned_at            = now(),
       scan_duration_ms      = :durationMs,
       lease_holder          = NULL,
       lease_expires_at      = NULL,
       updated_at            = now(),
       version               = version + 1
 WHERE id           = :fileId
   AND status       = 'SCANNING'
   AND lease_holder = :workerId;      -- ⚠ le bail m'appartient TOUJOURS
```

La dernière condition traite le **worker zombie** : un worker dont le bail a
expiré (travail déjà repris par un autre) ne doit pas pouvoir écraser le
verdict du worker légitime. Sans elle, un worker gelé puis réveillé peut
réécrire un état obsolète — bug rare, non déterministe, très difficile à
diagnostiquer.

### 4.3 Reprendre les travaux abandonnés (reaper)

```sql
UPDATE stored_file
   SET status          = CASE WHEN attempts >= :maxAttempts
                              THEN 'SCAN_FAILED'::file_status
                              ELSE 'AWAITING_SCAN'::file_status END,
       lease_holder    = NULL,
       lease_expires_at= NULL,
       next_attempt_at = now() + (interval '1 second' * power(2, attempts)),
       last_error      = 'lease expired',
       updated_at      = now()
 WHERE status = 'SCANNING'
   AND lease_expires_at < now();
```

Le backoff exponentiel est calculé **en SQL**, dans la colonne
`next_attempt_at`. C'est cela qui rend une bibliothèque de résilience
redondante ([P-21](20-catalogue-problematiques.md#p-21)) : la politique de
réessai est **persistée**, donc elle survit au redémarrage du worker — ce
qu'une politique en mémoire ne fait pas.

> À ajouter en production : un jitter (`+ random() * interval '5 seconds'`)
> pour éviter que tous les travaux repris ne repartent en même temps.

---

## 5. Requêtes d'observation

La file étant une table, l'exploitation se fait en SQL — avantage concret sur
un broker, où il faudrait un outillage dédié.

```sql
-- Vue d'ensemble instantanée
SELECT status, count(*), pg_size_pretty(sum(size_bytes)) AS volume
  FROM stored_file GROUP BY status ORDER BY 2 DESC;

-- LA métrique d'alerte : depuis combien de temps le plus vieux fichier attend
SELECT extract(epoch FROM now() - min(uploaded_at)) AS oldest_pending_seconds
  FROM stored_file WHERE status IN ('AWAITING_SCAN', 'SCANNING');

-- Travaux en échec définitif (équivalent d'une DLQ, en une requête)
SELECT id, original_filename, attempts, last_error, updated_at
  FROM stored_file WHERE status = 'SCAN_FAILED' AND attempts >= 3;

-- Détection d'incohérence : un servable sans verdict (doit TOUJOURS être vide)
SELECT count(*) FROM stored_file
 WHERE storage_zone = 'SERVABLE' AND status <> 'CLEAN';
```

La dernière requête est une **assertion d'invariant exécutable**. À exposer
comme indicateur de santé : si elle renvoie autre chose que zéro, il y a une
faille de sécurité active. C'est le genre de contrôle qu'un architecte
appréciera — la vérification continue de l'invariant, pas seulement sa
promesse.

---

## 6. Migrations

Flyway, migrations numérotées et **compatibles ascendantes** (permettant un
déploiement progressif sans interruption) :

| Version | Contenu |
|---|---|
| `V1__enums_and_stored_file.sql` | Types, table principale, contraintes |
| `V2__indexes.sql` | Index (dont partiels) |
| `V3__idempotency.sql` | Table d'idempotence |
| `V4__audit.sql` | Table d'audit |

⚠️ **Piège PostgreSQL à connaître** : `ALTER TYPE … ADD VALUE` ne peut pas
s'exécuter dans un bloc transactionnel avant PostgreSQL 12, et la nouvelle
valeur n'est pas utilisable dans la même transaction. Ajouter un état au
système demande donc une migration dédiée — ce qui est une bonne chose : cela
force à traiter explicitement le nouvel état partout, conformément au principe
de default-deny.

---

## 7. Questions de modélisation restées ouvertes

| Question | Recommandation | Référence |
|---|---|---|
| Faut-il une table `scan_job` séparée ? | Non au palier 1 (l'atomicité gratuite prime) | §1 |
| Le verdict doit-il être historisé, ou seul le dernier compte ? | Dernier verdict en colonnes + historique complet dans l'audit | §3.5 |
| Partitionner `stored_file` par date ? | Non — utile au-delà de plusieurs dizaines de millions de lignes | — |
| `tenant_id` : colonne, schéma, ou base par tenant ? | **Colonne** + filtre systématique ; les autres options sont disproportionnées | `D-11` |
| Purger l'audit ? | Non — c'est la preuve. Archivage à froid si le volume l'impose | §3.5 |
| Chiffrer `original_filename` ? | Non par défaut ; à revoir si les noms sont sensibles | — |
