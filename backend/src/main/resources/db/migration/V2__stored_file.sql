-- V2 — la table qui porte l'invariant.
--
-- Une seule table pour l'état métier ET la file de travail : créer le fichier
-- et créer le travail sont ainsi la même écriture, dans la même transaction.
-- Il n'y a donc rien à réconcilier entre deux sources de vérité — c'est ce qui
-- rend un courtier de messages inutile à cette échelle.
--
-- ═══ POURQUOI « IS NOT DISTINCT FROM » PARTOUT ═══
--
-- En SQL, une contrainte CHECK rejette FALSE et ACCEPTE UNKNOWN. Écrite
-- naïvement, « scan_result = 'CLEAN' » vaut UNKNOWN quand scan_result est NULL,
-- et la ligne passe. Un fichier disponible sans verdict serait donc accepté par
-- une contrainte qui prétend l'interdire.
--
-- Chaque prédicat ci-dessous est TOTAL : il vaut TRUE ou FALSE, jamais UNKNOWN.
-- Le test StoredFileConstraintsTest injecte les NULL un par un pour le prouver.

CREATE TYPE file_status AS ENUM (
    'AWAITING_SCAN',   -- reçu, en attente d'analyse
    'SCANNING',        -- analyse en cours (bail détenu)
    'RETRY_WAIT',      -- panne technique, nouvelle tentative programmée
    'PROMOTING',       -- verdict sain, copie vérifiée en cours (bail détenu)
    'AVAILABLE',       -- SERVABLE : le seul état téléchargeable
    'INFECTED',        -- menace détectée, définitif
    'UNSCANNABLE',     -- hors capacité d'analyse, définitif
    'FAILED_FINAL'     -- tentatives épuisées, définitif
);

CREATE TYPE scan_result AS ENUM ('CLEAN', 'INFECTED', 'UNSCANNABLE');

CREATE TYPE storage_area AS ENUM ('QUARANTINE', 'SERVABLE');

CREATE TABLE stored_file (
    id                      uuid         PRIMARY KEY,
    owner_id                text         NOT NULL,   -- v1 : 'anonymous' ; v2 : sub du jeton

    -- Déclaré par l'appelant : métadonnée, jamais une clé ni un chemin (B-3)
    original_filename       text         NOT NULL,

    -- Établi par le serveur
    detected_content_type   text         NOT NULL DEFAULT 'application/octet-stream',
    size_bytes              bigint       NOT NULL,
    content_sha256          char(64)     NOT NULL,

    storage_area            storage_area NOT NULL DEFAULT 'QUARANTINE',
    object_key              text         NOT NULL,

    -- ═══ L'INVARIANT ═══
    status                  file_status  NOT NULL DEFAULT 'AWAITING_SCAN',
    status_reason           text,

    -- Verdict d'analyse
    scan_result             scan_result,
    scan_threat_name        text,
    scan_engine             text,
    scan_engine_version     text,
    scan_signature_version  text,
    scanned_sha256          char(64),                -- lie l'attestation AU CONTENU
    scanned_at              timestamptz,
    scan_duration_ms        integer,

    -- File de travail : il n'y en a pas d'autre
    attempts                integer      NOT NULL DEFAULT 0,
    next_attempt_at         timestamptz  NOT NULL DEFAULT clock_timestamp(),
    lease_token             uuid,                    -- par PRISE, pas par worker
    lease_holder            text,
    lease_expires_at        timestamptz,
    last_error              text,

    uploaded_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
    status_changed_at       timestamptz  NOT NULL DEFAULT clock_timestamp(),
    updated_at              timestamptz  NOT NULL DEFAULT clock_timestamp(),
    version                 bigint       NOT NULL DEFAULT 0,

    -- Une convention dans le code n'est pas une contrainte.
    CONSTRAINT uq_stored_file_object_key UNIQUE (object_key),

    CONSTRAINT size_is_positive  CHECK (size_bytes > 0),
    CONSTRAINT attempts_positive CHECK (attempts >= 0),
    CONSTRAINT sha256_is_hex     CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT scanned_sha256_is_hex CHECK (
        scanned_sha256 IS NULL OR scanned_sha256 ~ '^[0-9a-f]{64}$'),

    -- ═══ C1 — un fichier disponible porte une attestation complète, liée à SON contenu
    CONSTRAINT available_requires_attestation CHECK (
        status <> 'AVAILABLE' OR (
                scan_result            IS NOT DISTINCT FROM 'CLEAN'
            AND scanned_sha256         IS NOT DISTINCT FROM content_sha256
            AND storage_area           IS NOT DISTINCT FROM 'SERVABLE'
            AND scanned_at             IS NOT NULL
            AND scan_engine            IS NOT NULL
            AND scan_signature_version IS NOT NULL)),

    -- ═══ C2 — rien d'autre qu'un fichier disponible ne vit dans la zone servable
    CONSTRAINT servable_area_requires_available CHECK (
        storage_area <> 'SERVABLE' OR status IS NOT DISTINCT FROM 'AVAILABLE'),

    -- ═══ C3 — les états terminaux non servables portent le verdict qui les justifie
    CONSTRAINT infected_requires_verdict CHECK (
        status <> 'INFECTED' OR scan_result IS NOT DISTINCT FROM 'INFECTED'),
    CONSTRAINT unscannable_requires_verdict CHECK (
        status <> 'UNSCANNABLE' OR scan_result IS NOT DISTINCT FROM 'UNSCANNABLE'),

    -- ═══ C4 — un verdict est daté, et un verdict daté existe
    CONSTRAINT verdict_is_dated CHECK ((scan_result IS NULL) = (scanned_at IS NULL)),

    -- ═══ C5 — un bail existe SI ET SEULEMENT SI un travail est en cours
    CONSTRAINT lease_matches_status CHECK (
        (status IN ('SCANNING', 'PROMOTING')) = (lease_token IS NOT NULL)),
    CONSTRAINT lease_fields_together CHECK (
            (lease_token IS NULL) = (lease_holder IS NULL)
        AND (lease_token IS NULL) = (lease_expires_at IS NULL))
);

COMMENT ON TABLE stored_file IS
    'État métier et file de travail. L''invariant « aucun fichier non analysé n''est servi » '
    'est porté par les contraintes C1 à C5, écrites en prédicats totaux.';

COMMENT ON COLUMN stored_file.scanned_sha256 IS
    'Empreinte des octets réellement analysés. Sans elle, l''attestation dirait '
    '« un fichier a été analysé », et non « CE contenu a été analysé ».';

COMMENT ON COLUMN stored_file.lease_token IS
    'Identifie une PRISE, pas un worker : un worker gelé qui se re-réclame le '
    'même fichier ne doit pas pouvoir écraser le verdict du travail légitime.';
