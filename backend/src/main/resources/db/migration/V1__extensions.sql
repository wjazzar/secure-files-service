-- V1 — extensions PostgreSQL requises par le schéma.
--
-- pg_trgm sert l'index trigramme de la recherche « contient » insensible à la
-- casse sur le nom de fichier (GET /api/v1/files?q=). Sans lui, la recherche
-- balaie la table.
--
-- Migration séparée du schéma : une extension est une opération d'installation,
-- pas une évolution de modèle, et elle peut demander des droits distincts.

CREATE EXTENSION IF NOT EXISTS pg_trgm;
