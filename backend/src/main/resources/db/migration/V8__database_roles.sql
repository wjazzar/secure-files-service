-- V8 — droits du rôle d'exécution (audit S-03).
--
-- Trois rôles, créés par infra/postgres/initdb/01-roles-and-databases.sh :
-- praxedo_owner possède le schéma et migre (Flyway), praxedo_app exécute le
-- service, keycloak a sa propre base.
--
-- praxedo_app lit et écrit des LIGNES, rien d'autre : sans être propriétaire
-- des tables, il ne peut ni retirer une contrainte CHECK, ni désactiver un
-- trigger, ni créer ou modifier une table. Les garanties portées par le schéma
-- (prédicats totaux, journal en ajout seul) ne tiennent donc plus à la
-- discipline du code applicatif : une faille dans le service ne peut pas les
-- retirer.

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO praxedo_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO praxedo_app;

-- Les tables des migrations futures, créées par le propriétaire, reçoivent
-- les mêmes droits sans qu'on ait à y penser.
ALTER DEFAULT PRIVILEGES FOR ROLE praxedo_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO praxedo_app;
ALTER DEFAULT PRIVILEGES FOR ROLE praxedo_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO praxedo_app;

-- Le journal d'audit : ajouter et lire, jamais réécrire. Les triggers de V6
-- refusent déjà UPDATE, DELETE et TRUNCATE — à tout rôle, propriétaire compris ;
-- le rôle d'exécution n'en a de plus pas le droit.
REVOKE UPDATE, DELETE, TRUNCATE ON file_audit_event FROM praxedo_app;

-- L'historique de Flyway appartient au propriétaire, qui seul migre.
REVOKE ALL ON flyway_schema_history FROM praxedo_app;
