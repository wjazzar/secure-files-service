#!/usr/bin/env bash
# Vérifie que chaque brique répond, ET qu'elle fait ce qu'elle promet :
#
#   PostgreSQL   les deux bases existent, le schéma du service est migré
#   Stockage     la livraison ne peut PAS lire la quarantaine — et lit la zone servable
#   Antivirus    un contenu sain passe, EICAR est détecté
#   Keycloak     un jeton est délivré au système tiers de démonstration (client
#                confidentiel, client credentials), avec l'audience attendue
#   Service      prêt, refuse qui n'a pas de jeton, accepte le jeton du système tiers
#   Interface    servie, et branchée sur le service
#
#   ./scripts/check.sh           contre un environnement démarré par start.sh
#
# Rien de durable n'est écrit : l'objet de test du stockage est effacé, et EICAR
# n'est jamais écrit sur le disque (l'antivirus du poste le mettrait en
# quarantaine) : la chaîne est assemblée en mémoire et envoyée par un tube.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

BACKEND="http://localhost:${BACKEND_PORT:-8080}"
MANAGEMENT="http://localhost:${BACKEND_MANAGEMENT_PORT:-8091}"
KEYCLOAK="http://localhost:${KEYCLOAK_PORT:-8081}"
ANTIVIRUS="http://localhost:${ANTIVIRUS_PORT:-9000}"
FRONTEND="http://127.0.0.1:5173"
PG_USER="${POSTGRES_USER:-praxedo}"

failures=0
section() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m[OK]\033[0m %s\n' "$*"; }
ko()   { printf '  \033[31m[KO]\033[0m %s\n' "$*"; failures=$((failures + 1)); }
info() { printf '  [--] %s\n' "$*"; }
field() { sed -n "s/.*\"$1\": *\"\\{0,1\\}\\([^\",}]*\\).*/\\1/p" | head -1; }
health() {
    docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null \
        || echo absent
}
http_code() { curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$@" 2>/dev/null || true; }
# Client S3 officiel, avec l'identité demandée, sur le réseau des conteneurs.
# MSYS_NO_PATHCONV : sous Git Bash, les chemins du conteneur ne doivent pas
# être traduits en chemins Windows.
s3() { # $1 = identité, puis les arguments de « aws s3api »
    local identity=$1; shift
    MSYS_NO_PATHCONV=1 docker run --rm --network praxedo_default \
        -e AWS_ACCESS_KEY_ID="$identity" -e AWS_SECRET_ACCESS_KEY="$identity-secret" -e AWS_DEFAULT_REGION=us-east-1 \
        amazon/aws-cli:2.32.9@sha256:734684f3fc98bbac0e7796c34f44c375c01f05febac52a10649a55eef1e96c24 --endpoint-url http://objectstore:8333 s3api "$@" >/dev/null 2>&1
}

section "Conteneurs"
for service in postgres objectstore keycloak antivirus backend; do
    state=$(health "praxedo-$service")
    if [ "$state" = healthy ]; then ok "$service"; else ko "$service : $state"; fi
done
init=$(docker inspect -f '{{.State.ExitCode}}' praxedo-objectstore-init 2>/dev/null || echo absent)
if [ "$init" = 0 ]; then ok "zones de stockage créées"; else ko "création des zones de stockage : $init"; fi

section "PostgreSQL"
databases=$(docker exec praxedo-postgres psql -U "$PG_USER" -d postgres -tAc \
    "select string_agg(datname, ' ' order by datname) from pg_database where datname in ('praxedo', 'keycloak')" 2>/dev/null)
if [ "$databases" = "keycloak praxedo" ]; then ok "bases praxedo (le service) et keycloak"; else ko "bases trouvées : « $databases »"; fi
migrations=$(docker exec praxedo-postgres psql -U "$PG_USER" -d praxedo -tAc \
    "select count(*) || ' migrations, dernière V' || max(version::int) from flyway_schema_history where success" 2>/dev/null)
if [ -n "$migrations" ]; then ok "schéma migré par Flyway : $migrations"; else ko "schéma non migré (le service a-t-il démarré ?)"; fi
roles=$(docker exec praxedo-postgres psql -U "$PG_USER" -d postgres -tAc \
    "select count(*) from pg_roles where rolname in ('praxedo_owner', 'praxedo_app', 'keycloak') and not rolsuper" 2>/dev/null)
if [ "$roles" = 3 ]; then ok "un rôle par usage, aucun superutilisateur : praxedo_owner (migrations), praxedo_app (service), keycloak"
else ko "rôles praxedo_owner, praxedo_app, keycloak : « $roles » sur 3 (volume antérieur ? docker compose down -v)"; fi
sessions=$(docker exec praxedo-postgres psql -U "$PG_USER" -d postgres -tAc \
    "select string_agg(distinct datname || ':' || usename, ' ') from pg_stat_activity where datname in ('praxedo', 'keycloak') and pid <> pg_backend_pid()" 2>/dev/null)
case " $sessions " in
    *" praxedo:$PG_USER "*|*" keycloak:$PG_USER "*) ko "le superutilisateur sert une application : « $sessions »" ;;
    *praxedo:praxedo_app*) ok "le service se connecte en praxedo_app ($sessions)" ;;
    *) ko "aucune session praxedo_app : « $sessions »" ;;
esac

section "Stockage objet — l'isolation qui porte la garantie"
key="check-$(date +%s)-$$.txt"
if s3 praxedo-ingest put-object --bucket quarantine --key "$key" --body /etc/hostname; then
    ok "le dépôt écrit dans la quarantaine"
else
    ko "le dépôt n'écrit pas dans la quarantaine"
fi
if s3 praxedo-delivery get-object --bucket quarantine --key "$key" /tmp/out; then
    ko "la livraison LIT la quarantaine : l'isolation n'est pas effective"
else
    ok "la livraison ne peut pas lire la quarantaine (refusé par le stockage)"
fi
if s3 praxedo-worker put-object --bucket servable --key "$key" --body /etc/hostname \
    && s3 praxedo-delivery get-object --bucket servable --key "$key" /tmp/out; then
    ok "contre-épreuve : la livraison lit la zone servable"
else
    ko "la livraison ne lit pas la zone servable : elle ne pourrait rien servir"
fi
s3 praxedo-worker delete-object --bucket quarantine --key "$key"
s3 praxedo-worker delete-object --bucket servable --key "$key"

section "Antivirus"
version=$(curl -sS --max-time 5 "$ANTIVIRUS/version" 2>/dev/null)
if [ -n "$version" ]; then
    info "ClamAV $(echo "$version" | field Clamav), signatures $(echo "$version" | field Signature)"
fi
code=$(printf 'bonjour' | http_code --data-binary @- "$ANTIVIRUS/scanHandlerBody")
if [ "$code" = 200 ]; then ok "contenu sain → 200"; else ko "contenu sain → « $code » (200 attendu)"; fi
answer=$(printf '%s%s%s%s' 'X5O!P%@AP[4\PZX54(P^)7CC)7}' '$EICAR-STANDARD-' 'ANTIVIRUS-TEST-FILE!' '$H+H*' \
    | curl -sS --max-time 10 -w ' %{http_code}' --data-binary @- "$ANTIVIRUS/scanHandlerBody" 2>/dev/null)
case "$answer" in
    *Eicar*406) ok "EICAR détecté → 406 ($(echo "$answer" | grep -o 'Eicar[A-Za-z.-]*' | head -1))" ;;
    *) ko "EICAR non détecté : « $answer » (les signatures sont-elles chargées ?)" ;;
esac

section "Keycloak"
token=$(curl -sS --max-time 10 "$KEYCLOAK/realms/praxedo/protocol/openid-connect/token" \
    -u "praxedo-integration:${PRAXEDO_INTEGRATION_SECRET:-praxedo-local-integration-secret-do-not-reuse}" \
    -d grant_type=client_credentials 2>/dev/null \
    | field access_token)
if [ -n "$token" ]; then
    ok "jeton délivré au système tiers praxedo-integration (client credentials)"
    payload=$(printf '%s' "$token" | cut -d. -f2 | tr '_-' '/+')
    case $(( ${#payload} % 4 )) in 2) payload="$payload==" ;; 3) payload="$payload=" ;; esac
    claims=$(printf '%s' "$payload" | { base64 -d 2>/dev/null || base64 -D; } 2>/dev/null)
    case "$claims" in
        *praxedo-files-api*) ok "audience praxedo-files-api présente (le service la vérifie)" ;;
        *) ko "audience praxedo-files-api absente du jeton : le service le refuserait" ;;
    esac
else
    ko "aucun jeton délivré"
fi

section "Exposition"
exposed=$(docker ps --filter name=praxedo- --format '{{.Names}} {{.Ports}}' | grep -E '0\.0\.0\.0:|\[::\]:|:::' || true)
if [ -z "$exposed" ]; then ok "aucun port publié hors de 127.0.0.1"; else ko "ports ouverts sur le réseau : $exposed"; fi

section "Service"
readiness=$(curl -sS --max-time 5 "$MANAGEMENT/actuator/health/readiness" 2>/dev/null)
case "$readiness" in *'"UP"'*) ok "prêt (sonde sur le port de management)" ;; *) ko "pas prêt : « $readiness »" ;; esac
code=$(http_code "$BACKEND/actuator/prometheus")
case "$code" in
    401|404) ok "le port de l'API ne sert pas les métriques → $code" ;;
    *) ko "métriques sur le port de l'API → « $code » (401 ou 404 attendu)" ;;
esac
code=$(http_code "$BACKEND/api/v1/files")
case "$code" in
    401) ok "sans jeton → 401 : l'authentification Keycloak est exigée"
         if [ -n "$token" ] && [ "$(http_code -H "Authorization: Bearer $token" "$BACKEND/api/v1/files")" = 200 ]; then
             ok "jeton du système tiers accepté"
         else
             ko "jeton du système tiers refusé"
         fi
         if [ "$(http_code "$BACKEND/api/v1/auth/login")" = 302 ]; then
             ok "connexion du navigateur : /api/v1/auth/login redirige vers Keycloak"
         else
             ko "connexion du navigateur : /api/v1/auth/login ne redirige pas"
         fi ;;
    *) ko "liste des fichiers sans jeton → « $code », 401 attendu" ;;
esac

section "Interface"
if [ "$(http_code "$FRONTEND/")" = 200 ]; then
    ok "servie sur $FRONTEND"
    # Le serveur de développement injecte sa configuration dans chaque module :
    # c'est là qu'on voit si le navigateur parle au service ou à des bouchons.
    code=$(http_code "$FRONTEND/api/v1/files")
    if curl -sS --max-time 10 "$FRONTEND/src/config/env.ts" 2>/dev/null | grep -q '"VITE_API_MOCKING": *"true"'; then
        ko "elle tourne sur des bouchons (npm run dev:mock) : elle n'utilise pas le service — arrêtez-la, relancez start"
    else
        case "$code" in
            200|401) ok "branchée sur le service (le relais /api répond $code)" ;;
            *) ko "le relais /api ne joint pas le service : « $code »" ;;
        esac
    fi
else
    info "non lancée — ./scripts/start.sh la lance"
fi

echo
if [ "$failures" -eq 0 ]; then
    printf '\033[32mTout est en ordre.\033[0m\n'
else
    printf '\033[31m%s vérification(s) en échec.\033[0m Journaux : docker compose logs <service>\n' "$failures"
    exit 1
fi
