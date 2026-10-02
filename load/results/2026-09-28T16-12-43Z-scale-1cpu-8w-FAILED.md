# ⚠️ Campagne échouée — `scale-1cpu-8w` (2026-09-28T16-12-43Z) — À REFAIRE

Ne pas utiliser ces résultats dans une médiane ni dans le rapport de capacité.

## Ce qui s'est passé

- Palier 120 fichiers/s : effondrement. 40 dépôts acceptés/s, 40 fichiers
  traités/s, dépôt p95 4,3 s, 3 480 `429`. Le CPU n'est qu'à 64 %.
- Pool de connexions à la base saturé : jusqu'à 167 demandes en attente,
  152 ms d'attente moyenne (pool de 10, délai d'attente de 5 s).
- Après la charge, 3 fichiers sont restés en `SCANNING`. La file ne s'est pas
  vidée en 180 s (`queueDrainedAfterThroughput: false`), et le lanceur a
  supprimé le projet. **Les journaux du back-end sont perdus.**

## Instantané conservé (pris à 16:21:54 UTC, avant la suppression)

| Fichier | Statut | Tentatives | Taille | Déposé | Pris par le worker | Bail jusqu'à |
|---|---|---:|---:|---|---|---|
| 44d32aae… | SCANNING | 1 | 2 Mio | 16:18:09 | 16:18:22 | 16:28:22 |
| 1d693dab… | SCANNING | 1 | 2 Mio | 16:18:09 | 16:18:22 | 16:28:22 |
| ce9664e3… | SCANNING | 1 | 512 Kio | 16:18:09 | 16:18:22 | 16:28:22 |

Métriques au même instant : `scan_inflight` 0, antivirus disponible,
`scan_failures` 0, `work_technical_failures` 0, invariant 0.

## Piste, non vérifiée (journaux perdus)

Ces fichiers ont été pris au début du palier 120, pendant la saturation du
pool. Aucune analyse n'était en cours et aucun échec n'a été enregistré. Un
worker a donc probablement reçu une erreur de base (délai d'obtention d'une
connexion dépassé) après l'analyse, erreur que le code ne rattrape pas. Le
fichier attend alors l'expiration de son bail (10 min) pour être repris.

## Pour la reprise

Relancer `node load/run-capacity.mjs scale-1cpu-8w`, en conservant les
journaux du back-end si la file ne se vide pas.
