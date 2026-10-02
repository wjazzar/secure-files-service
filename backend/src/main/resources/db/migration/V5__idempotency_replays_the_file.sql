-- V5 — l'idempotence rejoue le fichier, pas un instantané de réponse.
--
-- V4 prévoyait de mémoriser le corps de la réponse (response_status,
-- response_body) pour le rejouer tel quel. À l'implémentation, c'est la
-- mauvaise chose à rejouer : un client qui relance son dépôt après une coupure
-- doit recevoir l'état ACTUEL de son fichier, pas une photo « en attente »
-- prise avant que l'analyse ne se termine. On mémorise donc le fichier produit,
-- et la réponse est reconstruite à partir de lui.
--
-- Bénéfice annexe : plus aucun JSON n'est sérialisé par la couche application
-- pour être stocké en base.

ALTER TABLE idempotency_record DROP CONSTRAINT completed_has_response;
ALTER TABLE idempotency_record DROP COLUMN response_status;
ALTER TABLE idempotency_record DROP COLUMN response_body;

-- Prédicat total, comme partout : state est NOT NULL, et IS NOT NULL ne vaut
-- jamais UNKNOWN.
ALTER TABLE idempotency_record ADD CONSTRAINT completed_names_its_file CHECK (
    state <> 'COMPLETED' OR file_id IS NOT NULL);
