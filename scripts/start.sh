#!/usr/bin/env bash
# Démarre tout, en une commande : base de données, stockage objet, Keycloak,
# antivirus, service Spring Boot, puis l'interface React branchée sur le service.
#
#   ./scripts/start.sh               tout, avec l'authentification Keycloak —
#                                    toujours active : connexion par le navigateur
#                                    (cookie HttpOnly), jeton pour l'API
#   ./scripts/start.sh --no-front    sans l'interface (l'API seule)
#
# Relancer le script est sans risque : ce qui tourne déjà est conservé.
# Ctrl+C arrête l'interface ; ./scripts/stop.sh arrête le reste.
#
# Prérequis : Docker avec Compose v2, curl ; Node 22.12+ pour l'interface.
# Windows sans Git Bash : scripts/start.ps1 (voir README.txt).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

FRONT=true
for argument in "$@"; do
    case "$argument" in
        --no-front) FRONT=false ;;
        -h|--help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "usage: $0 [--no-front]" >&2; exit 2 ;;
    esac
done

BACKEND_PORT="${BACKEND_PORT:-8080}"
KEYCLOAK_PORT="${KEYCLOAK_PORT:-8081}"
ANTIVIRUS_PORT="${ANTIVIRUS_PORT:-9000}"
WAIT_SECONDS="${WAIT_SECONDS:-900}"

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
health() { # état d'un conteneur : healthy, starting, unhealthy, exited… ou absent
    docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null \
        || echo absent
}

bold "1/4  Prérequis"
command -v docker >/dev/null || fail "Docker est requis : https://docs.docker.com/get-docker/"
docker info >/dev/null 2>&1 || fail "Docker ne répond pas : démarrez Docker Desktop (ou le démon Docker), puis relancez."
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 est requis (commande « docker compose »)."
command -v curl >/dev/null || fail "curl est requis."
echo "  Docker $(docker version -f '{{.Server.Version}}'), $(docker compose version --short 2>/dev/null | sed 's/^/Compose /')"

# L'interface en mode « session » : la connexion passe par Keycloak, via le
# service (ADR-0012).
if $FRONT; then
    if ! command -v node >/dev/null || ! command -v npm >/dev/null; then
        FRONT=false
        FRONT_SKIPPED="Node.js est absent : installez Node 22.12+ (https://nodejs.org) pour l'interface"
    else
        node_version=$(node -p 'process.versions.node')
        if ! node -e 'const [a,b]=process.versions.node.split(".").map(Number); process.exit(a>22||(a===22&&b>=12)?0:1)'; then
            FRONT=false
            FRONT_SKIPPED="Node $node_version trouvé, 22.12+ requis pour l'interface"
        else
            echo "  Node $node_version"
        fi
    fi
fi
[ -n "${FRONT_SKIPPED:-}" ] && echo "  ⚠ Interface non lancée : $FRONT_SKIPPED"

bold "2/4  Construction et démarrage des conteneurs"
echo "  Premier lancement : quelques minutes (images, dépendances Maven, signatures antivirus)."
docker compose --profile app up -d --build \
    || fail "Le démarrage des conteneurs a échoué (voir ci-dessus). « port is already allocated » : README.txt §8."

bold "3/4  Attente des services"
containers="praxedo-postgres praxedo-objectstore praxedo-keycloak praxedo-antivirus praxedo-backend"
deadline=$(( $(date +%s) + WAIT_SECONDS ))
last=""
while :; do
    pending=""
    for container in $containers; do
        state=$(health "$container")
        case "$state" in
            healthy) ;;
            unhealthy|exited|dead|absent)
                fail "${container#praxedo-} est « $state ». Journaux : docker compose logs ${container#praxedo-}" ;;
            *) pending="$pending ${container#praxedo-}" ;;
        esac
    done
    [ -z "$pending" ] && break
    if [ "$pending" != "$last" ]; then
        echo "  en attente :$pending"
        case "$pending" in *antivirus*)
            echo "    (l'antivirus télécharge et charge ses signatures : 1 à 2 min au premier démarrage ;" \
                 "le service accepte déjà les dépôts, ils attendent dans la file)" ;;
        esac
        last=$pending
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
        docker compose --profile app ps
        [ "$(health praxedo-backend)" = healthy ] \
            || fail "Le service n'est pas prêt après $WAIT_SECONDS s. Journaux : docker compose logs backend"
        echo "  ⚠ Toujours en attente après $WAIT_SECONDS s :$pending — on continue, le service est prêt."
        break
    fi
    sleep 5
done
echo "  tout est prêt"

bold "4/4  Adresses"
cat <<EOF
  Service (API)    http://localhost:$BACKEND_PORT/api/v1/files
                   santé http://localhost:8091/actuator/health · métriques /actuator/prometheus (port de management, local)
  Keycloak         http://localhost:$KEYCLOAK_PORT        console : admin / admin
                   realm praxedo, comptes de démonstration : infra/keycloak/realm-praxedo.json
  Antivirus        http://localhost:$ANTIVIRUS_PORT/version
  Stockage S3      http://localhost:${S3_PORT:-8333}        console : http://localhost:${S3_UI_PORT:-19333}
  PostgreSQL       localhost:${POSTGRES_PORT:-5432}           bases praxedo et keycloak — praxedo / praxedo

  Vérifier chaque brique :  ./scripts/check.sh
  Tout arrêter :            ./scripts/stop.sh
EOF

echo "  Démonstration de l'API :  ./scripts/demo.sh   (--resilience, --big)"
cat <<EOF

  Authentification Keycloak, toujours active.
    Navigateur : « Se connecter » → page Keycloak (un compte de démonstration du realm) ;
                 le service garde les jetons, le navigateur n'a qu'un cookie HttpOnly.
    Système tiers (jeton Bearer, client confidentiel praxedo-integration) :
    token=\$(curl -s -u praxedo-integration:\${PRAXEDO_INTEGRATION_SECRET:-praxedo-local-integration-secret-do-not-reuse} \\
      http://localhost:$KEYCLOAK_PORT/realms/praxedo/protocol/openid-connect/token -d grant_type=client_credentials \\
      | sed 's/.*"access_token":"\\([^"]*\\)".*/\\1/')
    curl -H "Authorization: Bearer \$token" http://localhost:$BACKEND_PORT/api/v1/files
EOF

if ! $FRONT; then
    echo
    exit 0
fi

bold "Interface React — branchée sur le service"
if curl -s -o /dev/null --max-time 2 http://127.0.0.1:5173/; then
    fail "Le port 5173 est déjà pris : une interface tourne déjà ? Arrêtez-la (Ctrl+C dans son terminal), puis relancez.
./scripts/check.sh dit si elle est branchée sur le service ou sur des bouchons."
fi
cd frontend
if [ ! -d node_modules ] || [ package-lock.json -nt node_modules/.package-lock.json ]; then
    echo "  installation des dépendances (npm ci)…"
    npm ci --no-audit --no-fund
fi
echo "  Ouvrir http://127.0.0.1:5173 — Ctrl+C arrête l'interface (le reste continue de tourner)"
echo
# Le serveur de développement relaie /api vers le service : même origine,
# donc ni CORS ni liens de téléchargement à réécrire (frontend/vite.config.ts).
API_PROXY_TARGET="http://localhost:$BACKEND_PORT" exec npm run dev
