#!/bin/sh
# Rôles et bases, créés au premier démarrage du conteneur (volume vide).
#
# Un rôle par usage, aucun superutilisateur hors administration (audit S-03) :
#
#   praxedo_owner  propriétaire du schéma : seul Flyway s'en sert, pour migrer
#   praxedo_app    le service à l'exécution : lire et écrire des lignes, rien
#                  d'autre. Il ne peut ni retirer une contrainte CHECK ni
#                  désactiver le trigger d'audit : l'invariant ne dépend plus
#                  de la discipline du code (droits accordés par V8)
#   keycloak       propriétaire de sa seule base, sans accès à celle du service
#
# POSTGRES_USER (superutilisateur) ne sert qu'à l'administration : ce script,
# la console psql, les scripts de vérification. Ni le service ni Keycloak ne
# l'utilisent.
#
# Les mots de passe viennent de l'environnement du conteneur
# (docker-compose.yml ; valeurs de DÉVELOPPEMENT LOCAL).
#
# ⚠️ Ne s'exécute que sur un volume vide : après une mise à jour du dépôt qui
# modifie ce fichier, « docker compose down -v » une fois.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v db="$POSTGRES_DB" \
    -v owner_password="${DB_OWNER_PASSWORD:?DB_OWNER_PASSWORD is required}" \
    -v app_password="${DB_APP_PASSWORD:?DB_APP_PASSWORD is required}" \
    -v keycloak_db="${KEYCLOAK_DB:-keycloak}" \
    -v keycloak_password="${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD is required}" <<'SQL'
-- Le service : le schéma à un rôle, l'exécution à un autre.
CREATE ROLE praxedo_owner LOGIN PASSWORD :'owner_password';
CREATE ROLE praxedo_app LOGIN PASSWORD :'app_password';

-- Depuis PostgreSQL 15, le schéma public appartient au propriétaire de la
-- base : praxedo_owner y crée les tables, praxedo_app n'y crée rien.
ALTER DATABASE :"db" OWNER TO praxedo_owner;
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO praxedo_app;

-- Keycloak : sa base, à lui seul.
CREATE ROLE keycloak LOGIN PASSWORD :'keycloak_password';
CREATE DATABASE :"keycloak_db" OWNER keycloak;
REVOKE ALL ON DATABASE :"keycloak_db" FROM PUBLIC;
SQL
