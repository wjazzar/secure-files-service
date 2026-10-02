-- V4 — idempotence du dépôt.
--
-- La clé primaire composite EST le mécanisme : c'est le « INSERT … ON CONFLICT
-- DO NOTHING » qui tranche la course entre deux dépôts concurrents portant la
-- même clé. Un SELECT suivi d'un INSERT serait précisément le bug à éviter.

CREATE TABLE idempotency_record (
    owner_id            text        NOT NULL,
    idempotency_key     text        NOT NULL,
    request_fingerprint char(64)    NOT NULL,   -- hash(nom + taille)
    state               text        NOT NULL,
    file_id             uuid        REFERENCES stored_file(id) ON DELETE CASCADE,
    response_status     integer,
    response_body       jsonb,
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at          timestamptz NOT NULL,

    PRIMARY KEY (owner_id, idempotency_key),

    CONSTRAINT state_is_known CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),

    -- Prédicat total, même raison qu'en V2 : une réponse mémorisée incomplète
    -- serait rejouée telle quelle au client.
    CONSTRAINT completed_has_response CHECK (
        state <> 'COMPLETED' OR (
                response_status IS NOT NULL
            AND response_body   IS NOT NULL
            AND file_id         IS NOT NULL))
);

-- Purge des clés expirées, et reprise des IN_PROGRESS abandonnées par une panne.
CREATE INDEX idx_idempotency_expiry ON idempotency_record (expires_at);
