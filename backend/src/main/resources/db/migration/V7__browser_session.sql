-- V7 — sessions du navigateur (authentification v2, ADR-0012).
--
-- Le schéma est celui que Spring Session JDBC publie pour PostgreSQL
-- (org/springframework/session/jdbc/schema-postgresql.sql, version 4.1.1),
-- recopié tel quel : Flyway garde la main sur le schéma, et Spring Session ne
-- crée rien lui-même (spring.session.jdbc.initialize-schema: never).
--
-- La session HTTP vit ici et non dans la mémoire d'un nœud (règle B-10) :
-- quand un nœud renouvelle les jetons d'une session auprès de Keycloak, il les
-- réécrit dans cette table, et le nœud suivant les relit. Rien à propager.
--
-- Ce qui y est rangé, sérialisé par Spring Session : l'utilisateur connecté
-- (jeton d'identité compris, pour la déconnexion OpenID Connect), et les jetons
-- d'accès et de rafraîchissement — SANS l'enregistrement du client, donc sans
-- son secret (voir SessionAuthorizedClients). Un jeton de rafraîchissement lu
-- ici ne sert à rien sans ce secret, qui n'est pas en base.
--
-- Aucune contrainte CHECK : aucun prédicat ne peut valoir UNKNOWN (règle B-8).

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
-- Purge des sessions expirées, par Spring Session (une fois par minute).
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
