Essai commencé le 2026-09-28T16:04:58.000Z — échauffement 60 s, paliers de 180 s (+10 s de montée)
Nœuds mesurés : 1 × 1 CPU vu(s) par la JVM, tas maximal 778.5 Mo, ramasse-miettes Copy + MarkSweepCompact ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

CPU, tas : le nœud le plus chargé.

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 50 fichiers/s | 50.01/s (28.7 Mo/s) | 49.93/s | 28.7 Mo/s | 26 (+20) | 1.7 s | 1.8 s | 44 ms | 72 % | 95.5 Mo | 0 | oui |
| 80 fichiers/s | 80.01/s (45.6 Mo/s) | 80.27/s | 45.8 Mo/s | 29 (-10) | 1.4 s | 1.3 s | 36 ms | 90 % | 100.9 Mo | 0 | oui |

Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne (à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; base : attente moyenne d'une connexion du pool, et demandes en attente au pire moment.

| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente connexion BD | Demandes BD en attente (max) |
|---|---|---|---|---|---|---|
| 50 fichiers/s | 17 ms | 52 ms | 0.83 | 0.7 % | 0.0 ms | 0 |
| 80 fichiers/s | 14 ms | 36 ms | 1.12 | 1.0 % | 0.0 ms | 0 |

Invariant (praxedo_invariant_violations), maximum sur tout l'essai : 0