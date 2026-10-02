# Antivirus exposé par une API HTTP — image dérivée, et pourquoi
#
# L'énoncé impose de « déléguer l'analyse à un antivirus disponible via une
# API ». On part donc de l'image ajilaag/clamav-rest, qui fait tourner ClamAV
# et l'expose en REST (POST /scanHandlerBody, GET /version, GET /metrics).
#
# Deux choses manquent à cette image, et elles sont critiques :
#
#   1. AlertExceedsMax n'est PAS réglé par son point d'entrée ;
#   2. rien n'empêche MaxFileSize <= MaxScanSize, qui tronque en silence les
#      grosses entrées d'archive (voir praxedo-entrypoint.sh).
#
# Sans ce réglage, une limite interne atteinte (archive trop profonde, volume
# décompressé trop gros, trop de fichiers) remonte comme une ANALYSE PROPRE.
# Le service déclarerait sain un fichier analysé partiellement. C'est le genre
# de défaut de configuration qui rend une garantie de sécurité fausse sans
# qu'aucun test du chemin nominal ne s'en aperçoive.
#
# Les limites de taille, elles, sont pilotées par variables d'environnement
# depuis docker-compose.yml : elles restent versionnées dans le dépôt.
#
# Image épinglée par digest : une mise à jour est un changement explicite.

FROM ajilaag/clamav-rest:0.6.6@sha256:f5aea983b9c73ccb5d085fc89c77ab8da607582e65cc59d3d0f7c13eff836359

USER root

# Le point d'entrée amont recopie /etc/clamav/* vers /clamav/etc/ à CHAQUE
# démarrage, puis lance clamd sur /clamav/etc/clamd.conf : corriger la source
# suffit, et la correction survit aux redémarrages.
#
# Le build ÉCHOUE si la directive a disparu du fichier amont ou si la
# substitution n'a pas pris : une évolution de l'image casse la construction au
# lieu de désactiver la garantie en silence.
RUN set -eu; \
    conf=/etc/clamav/clamd.conf; \
    grep -q 'AlertExceedsMax' "$conf"; \
    sed -i 's/^#AlertExceedsMax .*$/AlertExceedsMax yes/' "$conf"; \
    grep -qx 'AlertExceedsMax yes' "$conf"

# Seconde moitié de la garantie, découverte en la MESURANT (B-008) : même avec
# AlertExceedsMax, une entrée d'archive plus grosse que MaxFileSize est tronquée
# sans alerte si MaxFileSize <= MaxScanSize. La garde refuse de démarrer dans
# cette configuration, avant que clamd ne puisse répondre quoi que ce soit.
# (Retour chariot retiré : une extraction Windows ne doit pas casser le shebang.)
COPY praxedo-entrypoint.sh /usr/local/bin/praxedo-entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/praxedo-entrypoint.sh \
    && chmod 0755 /usr/local/bin/praxedo-entrypoint.sh

USER clamav

ENTRYPOINT ["praxedo-entrypoint.sh"]
