-- Journal d'audit — ARCHITECTURE.md §6.2 et §13.4.
--
-- Deux sources, un seul journal :
--   * les TRANSITIONS D'ÉTAT sont écrites par un trigger, dans la transaction
--     même qui change l'état : aucun chemin applicatif ne peut changer un
--     statut sans laisser de trace, pas même un bug ;
--   * les TÉLÉCHARGEMENTS (lien émis, contenu servi) sont écrits par
--     l'application, puisqu'ils ne changent aucun état.
--
-- Pas de clé étrangère vers stored_file, à dessein : l'audit doit survivre à
-- une purge du fichier qu'il décrit.

CREATE TABLE file_audit_event (
    id          bigserial   PRIMARY KEY,
    file_id     uuid        NOT NULL,
    owner_id    text        NOT NULL,
    event_type  text        NOT NULL,
    from_status file_status,
    to_status   file_status,
    actor       text        NOT NULL,
    details     jsonb,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_audit_file ON file_audit_event (file_id, occurred_at);

-- ─────────────────────────────────────────────────────────────────────────
-- Les transitions, nommées. L'acteur est celui qui tenait le bail : le worker
-- qui a conclu, ou le reaper quand le bail a expiré sans conclusion.
-- ─────────────────────────────────────────────────────────────────────────
CREATE FUNCTION audit_file_transition() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    kind  text;
    actor text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        kind  := 'UPLOADED';
        actor := NEW.owner_id;
    ELSIF NEW.status = 'SCANNING' THEN
        kind  := 'CLAIMED';
        actor := NEW.lease_holder;
    ELSIF OLD.status IN ('SCANNING', 'PROMOTING')
          AND OLD.lease_expires_at <= clock_timestamp()
          AND NEW.status IN ('RETRY_WAIT', 'FAILED_FINAL') THEN
        kind  := 'LEASE_EXPIRED';
        actor := 'reaper';
    ELSIF NEW.status IN ('PROMOTING', 'INFECTED', 'UNSCANNABLE') THEN
        kind  := 'VERDICT';
        actor := OLD.lease_holder;
    ELSIF NEW.status = 'AVAILABLE' THEN
        kind  := 'PROMOTED';
        actor := OLD.lease_holder;
    ELSIF NEW.status IN ('RETRY_WAIT', 'FAILED_FINAL') THEN
        kind  := 'TECHNICAL_FAILURE';
        actor := OLD.lease_holder;
    ELSIF NEW.status = 'AWAITING_SCAN' THEN
        kind  := 'RELEASED';
        actor := OLD.lease_holder;
    ELSE
        kind  := 'STATUS_CHANGED';
        actor := NULL;
    END IF;

    INSERT INTO file_audit_event (file_id, owner_id, event_type, from_status, to_status, actor, details)
    VALUES (
        NEW.id,
        NEW.owner_id,
        kind,
        CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.status END,
        NEW.status,
        coalesce(actor, 'system'),
        jsonb_strip_nulls(jsonb_build_object(
            'reason',           NEW.status_reason,
            'attempts',         NEW.attempts,
            'scanResult',       NEW.scan_result,
            'threat',           NEW.scan_threat_name,
            'engineVersion',    NEW.scan_engine_version,
            'signatureVersion', NEW.scan_signature_version,
            'error',            NEW.last_error))
    );
    RETURN NULL;
END;
$$;

CREATE TRIGGER stored_file_audit_insert
    AFTER INSERT ON stored_file
    FOR EACH ROW EXECUTE FUNCTION audit_file_transition();

CREATE TRIGGER stored_file_audit_transition
    AFTER UPDATE OF status ON stored_file
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION audit_file_transition();

-- ─────────────────────────────────────────────────────────────────────────
-- En ajout seul — une propriété de la base, pas une convention du code.
-- Le rôle applicatif possédant la table (un seul rôle en local), un REVOKE ne
-- suffirait pas : ce sont des triggers qui refusent. La séparation d'un rôle
-- de migration et d'un rôle applicatif sans droit DDL est listée en piste.
-- ─────────────────────────────────────────────────────────────────────────
CREATE FUNCTION refuse_audit_rewrite() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'file_audit_event is append-only: % refused', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER file_audit_event_no_rewrite
    BEFORE UPDATE OR DELETE ON file_audit_event
    FOR EACH ROW EXECUTE FUNCTION refuse_audit_rewrite();

CREATE TRIGGER file_audit_event_no_truncate
    BEFORE TRUNCATE ON file_audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_audit_rewrite();
