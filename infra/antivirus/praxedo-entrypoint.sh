#!/bin/sh
# Garde de démarrage de l'antivirus — refuse une configuration qui rendrait la
# garantie fausse, avant de passer la main au point d'entrée amont.
#
# Mesuré le 27/09 contre ClamAV 1.4.6 (docs/prompts/B-008) : quand MaxFileSize
# est inférieur ou égal à MaxScanSize, une entrée d'archive plus grosse que
# MaxFileSize est TRONQUÉE en silence — AlertExceedsMax ne lève rien sur ce
# chemin — et l'archive est déclarée saine alors que sa fin n'a jamais été lue.
# Avec MaxFileSize strictement supérieur à MaxScanSize, c'est la limite de
# volume analysé qui est atteinte la première, et celle-là, elle, alerte
# (Heuristics.Limits.Exceeded.MaxScanSize → le service classe « non analysable »).
#
# Une valeur illisible fait échouer le calcul, donc le démarrage : on échoue
# fermé, jamais ouvert.
set -eu

bytes() {
    value=$1
    case "$value" in
        *[Kk]) echo $(( ${value%?} * 1024 )) ;;
        *[Mm]) echo $(( ${value%?} * 1024 * 1024 )) ;;
        *[Gg]) echo $(( ${value%?} * 1024 * 1024 * 1024 )) ;;
        *)     echo $(( value )) ;;
    esac
}

file_size=$(bytes "${MAX_FILE_SIZE:?MAX_FILE_SIZE must be set}")
scan_size=$(bytes "${MAX_SCAN_SIZE:?MAX_SCAN_SIZE must be set}")

if [ "$file_size" -le "$scan_size" ]; then
    echo "FATAL: MAX_FILE_SIZE ($MAX_FILE_SIZE) must be strictly greater than MAX_SCAN_SIZE ($MAX_SCAN_SIZE)." >&2
    echo "       Otherwise ClamAV truncates a large archive entry without raising Heuristics.Limits.Exceeded," >&2
    echo "       and reports an archive it has only partly analysed as clean." >&2
    exit 64
fi

exec entrypoint.sh "$@"
