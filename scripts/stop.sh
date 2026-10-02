#!/usr/bin/env bash
# Arrête l'environnement démarré par start.sh.
#
#   ./scripts/stop.sh            arrête les conteneurs ; les données sont conservées
#   ./scripts/stop.sh --purge    arrête et EFFACE les données : base, fichiers déposés,
#                                signatures antivirus (re-téléchargées au prochain démarrage)
#
# L'interface, elle, s'arrête par Ctrl+C dans le terminal de start.sh.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

case "${1:-}" in
    "") docker compose --profile app down
        echo "Arrêté. Les données sont conservées ; ./scripts/start.sh reprend là où l'on s'était arrêté." ;;
    --purge) docker compose --profile app down --volumes
        echo "Arrêté, données effacées. Le prochain démarrage repart de zéro." ;;
    *) echo "usage: $0 [--purge]" >&2; exit 2 ;;
esac
