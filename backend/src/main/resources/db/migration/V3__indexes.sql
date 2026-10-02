-- V3 — index.
--
-- Les deux premiers sont PARTIELS, et c'est le point : la file de travail ne
-- contient que quelques dizaines de lignes à un instant donné, quel que soit
-- l'historique. Un index complet grandirait avec les millions de lignes
-- terminales, pour une requête qui n'en regarde jamais aucune.

-- ① File de travail : ce que lit le claim, à chaque tour de boucle du worker.
CREATE INDEX idx_file_queue
    ON stored_file (next_attempt_at, uploaded_at, id)
    WHERE status IN ('AWAITING_SCAN', 'RETRY_WAIT');

-- ② Reaper : les deux seuls états qui détiennent un bail.
CREATE INDEX idx_file_lease
    ON stored_file (lease_expires_at)
    WHERE status IN ('SCANNING', 'PROMOTING');

-- ③ Liste paginée (tri par défaut du contrat) et compteurs par statut.
CREATE INDEX idx_file_owner_listing ON stored_file (owner_id, uploaded_at DESC, id DESC);
CREATE INDEX idx_file_owner_status  ON stored_file (owner_id, status);

-- ④ Recherche « contient » insensible à la casse, sans balayage de table.
CREATE INDEX idx_file_name_trgm
    ON stored_file USING gin (lower(original_filename) gin_trgm_ops);

-- ⑤ Balayage des orphelins de la quarantaine (objet écrit, transaction perdue).
CREATE INDEX idx_file_uploaded_at ON stored_file (uploaded_at);
