-- V9 — recherche insensible aux accents (GET /api/v1/files?q=).
--
-- « releve » doit trouver « Relevé.pdf ». unaccent retire les accents ; pg_trgm
-- (V1) sert toujours la recherche « contient ».
--
-- unaccent() n'est que STABLE (son dictionnaire peut changer) : un index ne
-- peut pas s'appuyer dessus. La fonction ci-dessous le fige en désignant son
-- dictionnaire explicitement, ce qui la rend IMMUTABLE — la recette usuelle.
-- Le changement de dictionnaire, très improbable, imposerait de reconstruire
-- l'index.
--
-- Extension « de confiance » depuis PostgreSQL 13 : le propriétaire de la base
-- la crée sans être superutilisateur.

CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE FUNCTION immutable_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

-- L'index trigramme de V3 suit la nouvelle expression de recherche, que
-- StoredFileJpaRepository écrit à l'identique : toute autre écriture le
-- contournerait et balaierait la table.
DROP INDEX idx_file_name_trgm;
CREATE INDEX idx_file_name_trgm
    ON stored_file USING gin (immutable_unaccent(lower(original_filename)) gin_trgm_ops);
