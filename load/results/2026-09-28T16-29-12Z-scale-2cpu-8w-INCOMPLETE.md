# ⚠️ Campagne incomplète — `scale-2cpu-8w` (2026-09-28T16-29-12Z)

La série a été interrompue par erreur pendant le palier 200 fichiers/s.

- Les paliers 100, 130 et 160 sont complets. Le palier 200 n'a pas été mesuré.
- `*-throughput.md` et `*-dependencies.md` ont été régénérés après coup, depuis
  Prometheus et le relevé `docker stats`, pile encore démarrée. La fenêtre de
  mesure part du premier échantillon `docker stats`, pris juste avant k6.
- Pas de `*-metadata.json`. Les journaux du back-end sont dans `*-backend.log`
  (aucune erreur, deux avertissements de configuration locale).
- La file s'est vidée (profondeur 0 relevée après coup).

À refaire pour obtenir le palier 200 ; les trois premiers paliers restent
exploitables.
