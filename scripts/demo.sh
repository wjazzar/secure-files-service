#!/usr/bin/env bash
# Démonstration reproductible du service — contre un service déjà démarré :
#
#   docker compose --profile app up -d --build
#   ./scripts/demo.sh                 fichier sain, puis EICAR
#   ./scripts/demo.sh --resilience    + antivirus arrêté : rien n'est perdu
#   ./scripts/demo.sh --big           + un fichier de 500 Mo, aller et retour
#
# Prérequis : bash, curl, et sha256sum ou shasum (Linux, macOS, Git Bash).
# Aucun fichier n'est laissé derrière ; EICAR n'est jamais écrit sur le disque
# (l'antivirus du poste le mettrait en quarantaine) : il est assemblé à la
# volée et envoyé par un tube.
#
# Authentification (toujours active) : le script se présente comme le système
# tiers de démonstration — jeton Bearer du client praxedo-integration, obtenu
# auprès de Keycloak et renouvelé avant son échéance. Les fichiers déposés
# appartiennent donc à ce compte de service, pas à un utilisateur du realm.
set -euo pipefail

API="${API:-http://localhost:8080}"
MANAGEMENT="${MANAGEMENT:-http://localhost:8091}"
KEYCLOAK="${KEYCLOAK:-http://localhost:8081}"
INTEGRATION_SECRET="${PRAXEDO_INTEGRATION_SECRET:-praxedo-local-integration-secret-do-not-reuse}"
RESILIENCE=false
BIG=false
for argument in "$@"; do
    case "$argument" in
        --resilience) RESILIENCE=true ;;
        --big) BIG=true ;;
        *) echo "usage: $0 [--resilience] [--big]" >&2; exit 2 ;;
    esac
done

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
field() { sed -n "s/.*\"$1\":\"\\{0,1\\}\\([^\",}]*\\).*/\\1/p" | head -1; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi; }

# Un jeton du compte de service, renouvelé au bout de 4 min (il en vit 5).
AUTH=()
TOKEN_AT=0
refresh_token() {
    [ $(( $(date +%s) - TOKEN_AT )) -lt 240 ] && return 0
    local token
    token=$(curl -sS -u "praxedo-integration:$INTEGRATION_SECRET" \
        "$KEYCLOAK/realms/praxedo/protocol/openid-connect/token" -d grant_type=client_credentials | field access_token)
    [ -n "$token" ] || { echo "  ✘ Keycloak n'a pas délivré de jeton ($KEYCLOAK)" >&2; exit 1; }
    AUTH=(-H "Authorization: Bearer $token")
    TOKEN_AT=$(date +%s)
}
api() { refresh_token; curl -sS ${AUTH[@]+"${AUTH[@]}"} "$@"; }

upload() { # $1 = nom ; corps sur l'entrée standard, ou $2 = fichier
    if [ $# -ge 2 ]; then
        api -X POST "$API/api/v1/files" -H "X-File-Name: $1" -H "Content-Type: application/octet-stream" \
            --upload-file "$2"
    else
        api -X POST "$API/api/v1/files" -H "X-File-Name: $1" -H "Content-Type: application/octet-stream" \
            --data-binary @-
    fi
}

wait_terminal() { # $1 = id ; affiche le statut à chaque changement
    local last="" status
    for _ in $(seq 1 300); do
        status=$(api "$API/api/v1/files/$1" | field status)
        [ "$status" != "$last" ] && echo "  statut : $status" && last=$status
        case "$status" in AVAILABLE|INFECTED|UNSCANNABLE|FAILED) return 0 ;; esac
        sleep 1
    done
    echo "  toujours $last après 5 minutes" >&2
    return 1
}

# Un seul chemin de sortie : l'identité de l'appelant suffit (jeton ici, cookie
# de session pour le navigateur). Aucun lien signé à demander.
download() { api "$API/api/v1/files/$1/content"; }

bold "0. Le service répond-il ?"
curl -sS --fail "$MANAGEMENT/actuator/health/readiness" && echo
refresh_token
echo "  authentification Keycloak : jeton Bearer du système tiers praxedo-integration"

bold "1. Un fichier sain : déposé, analysé, promu, téléchargé à l'identique"
workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT
head -c 2000000 /dev/urandom > "$workdir/rapport.bin"
expected=$(sha256 < "$workdir/rapport.bin")
answer=$(upload "rapport.bin" "$workdir/rapport.bin")
id=$(echo "$answer" | field id)
echo "  202 Accepted — id $id, statut $(echo "$answer" | field status)"
wait_terminal "$id"
echo "  lien de contenu : $(api "$API/api/v1/files/$id" | sed -n 's/.*"content":"\([^"]*\)".*/\1/p')"
actual=$(download "$id" | sha256)
echo "  SHA-256 envoyé : $expected"
echo "  SHA-256 reçu   : $actual"
[ "$expected" = "$actual" ] && echo "  ✔ identiques" || { echo "  ✘ différents" >&2; exit 1; }

bold "2. EICAR — la signature de test que tout antivirus reconnaît"
answer=$(printf '%s%s%s%s' 'X5O!P%@AP[4\PZX54(P^)7CC)7}' '$EICAR-STANDARD-' 'ANTIVIRUS-TEST-FILE!' '$H+H*' \
    | upload "facture.pdf")
id=$(echo "$answer" | field id)
echo "  202 Accepted — id $id (le nom « facture.pdf » ne trompe personne : le type est détecté)"
wait_terminal "$id"
echo "  menace : $(api "$API/api/v1/files/$id" | field threatName)"
refusal=$(download "$id")
echo "  téléchargement → $(echo "$refusal" | field status) $(echo "$refusal" | field code) — jamais servi"

if $RESILIENCE; then
    bold "3. L'antivirus s'arrête : le service continue, aucun essai n'est consommé"
    docker compose stop antivirus >/dev/null
    echo "  antivirus arrêté"
    answer=$(head -c 100000 /dev/urandom | upload "pendant-la-panne.bin")
    id=$(echo "$answer" | field id)
    echo "  dépôt accepté malgré la panne — id $id"
    sleep 8
    detail=$(api "$API/api/v1/files/$id")
    echo "  après 8 s : statut $(echo "$detail" | field status), essais $(echo "$detail" | field scanAttempts)"
    docker compose start antivirus >/dev/null
    echo "  antivirus relancé (compter jusqu'à une minute de chargement des signatures)"
    wait_terminal "$id"
fi

if $BIG; then
    bold "4. 500 Mo, aller et retour — le service tient en mémoire constante"
    truncate -s 500M "$workdir/gros.bin" 2>/dev/null || dd if=/dev/zero of="$workdir/gros.bin" bs=1M count=500 status=none
    expected=$(sha256 < "$workdir/gros.bin")
    start=$(date +%s)
    id=$(upload "gros.bin" "$workdir/gros.bin" | field id)
    echo "  déposé en $(( $(date +%s) - start )) s — id $id"
    wait_terminal "$id"
    actual=$(download "$id" | sha256)
    [ "$expected" = "$actual" ] && echo "  ✔ 500 Mo relus à l'identique" || { echo "  ✘ différents" >&2; exit 1; }
fi

bold "Ce que la supervision en a vu"
curl -sS "$MANAGEMENT/actuator/prometheus" | grep -E '^praxedo_(scan_verdict_total|queue_depth|invariant_violations|upload_bytes_total|download_bytes_total)' || true
