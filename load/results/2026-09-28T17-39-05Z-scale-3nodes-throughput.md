Essai commencé le 2026-09-28T17:40:00.000Z — échauffement 90 s, paliers de 60 s (+10 s de montée)
Nœuds mesurés : 2 × 1 CPU vu(s) par la JVM, tas maximal 624.4 Mo, ramasse-miettes Copy + MarkSweepCompact ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

CPU, tas : le nœud le plus chargé.

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 120 fichiers/s | 75.09/s (43.7 Mo/s) | 80.00/s | 44.2 Mo/s | 287 (-76) | 11.4 s | 9.9 s | 4012 ms | 71 % | 112.9 Mo | 2499 | **non** |
| 160 fichiers/s | 95.31/s (54.5 Mo/s) | 92.77/s | 52.1 Mo/s | 328 (+116) | 12.5 s | 10.7 s | 3490 ms | 76 % | 128.0 Mo | 3480 | **non** |
| 200 fichiers/s | 88.17/s (49.8 Mo/s) | 86.56/s | 47.4 Mo/s | 626 (+194) | 14.3 s | 11.4 s | 2529 ms | 53 % | 128.1 Mo | 7210 | **non** |
| 250 fichiers/s | 23.95/s (123.6 Mo/s) | 33.49/s | 19.4 Mo/s | 0 (-581) | 8.5 s | 7.4 s | 27 ms | 54 % | 132.1 Mo | 1774 | **non** |

Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne (à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; base : attente moyenne d'une connexion et demandes en attente au pire moment, pour le pool de l'API puis pour celui de la file de travail.

| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente BD api / file | En attente max api / file |
|---|---|---|---|---|---|---|
| 120 fichiers/s | 31 ms | 87 ms | 2.49 | 0.9 % | 461.0 / 0.0 ms | 41 / 0 |
| 160 fichiers/s | 23 ms | 61 ms | 2.09 | 0.9 % | 363.2 / 0.0 ms | 40 / 0 |
| 200 fichiers/s | 20 ms | 48 ms | 1.75 | 0.8 % | 476.1 / 0.0 ms | 40 / 0 |
| 250 fichiers/s | 23 ms | 60 ms | 0.76 | 0.8 % | 43.9 / 0.0 ms | 1 / 0 |

Invariant (praxedo_invariant_violations), maximum sur tout l'essai : 0