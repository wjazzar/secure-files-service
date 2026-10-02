Essai commencé le 2026-09-28T16:13:28.000Z — échauffement 60 s, paliers de 60 s (+10 s de montée)
Nœuds mesurés : 1 × 1 CPU vu(s) par la JVM, tas maximal 778.5 Mo, ramasse-miettes Copy + MarkSweepCompact ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

CPU, tas : le nœud le plus chargé.

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 70 fichiers/s | 66.15/s (36.8 Mo/s) | 68.12/s | 37.9 Mo/s | 375 (-87) | 8.3 s | 8.5 s | 104 ms | 92 % | 98.4 Mo | 232 | **non** |
| 85 fichiers/s | 85.06/s (48.2 Mo/s) | 89.59/s | 50.7 Mo/s | 23 (-300) | 3.6 s | 3.6 s | 80 ms | 87 % | 101.7 Mo | 0 | oui |
| 100 fichiers/s | 96.52/s (54.5 Mo/s) | 88.83/s | 50.5 Mo/s | 492 (+451) | 5.6 s | 5.6 s | 76 ms | 90 % | 107.3 Mo | 209 | **non** |
| 120 fichiers/s | 40.64/s (23.0 Mo/s) | 40.58/s | 22.8 Mo/s | 501 (+1) | 55.3 s | 27.9 s | 4275 ms | 64 % | 125.2 Mo | 3480 | **non** |

Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne (à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; base : attente moyenne d'une connexion du pool, et demandes en attente au pire moment.

| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente connexion BD | Demandes BD en attente (max) |
|---|---|---|---|---|---|---|
| 70 fichiers/s | 36 ms | 89 ms | 2.44 | 2.1 % | 0.2 ms | 0 |
| 85 fichiers/s | 24 ms | 66 ms | 2.16 | 1.7 % | 0.2 ms | 1 |
| 100 fichiers/s | 28 ms | 76 ms | 2.45 | 1.8 % | 0.1 ms | 1 |
| 120 fichiers/s | 38 ms | 178 ms | 1.55 | 1.7 % | 151.9 ms | 167 |

Invariant (praxedo_invariant_violations), maximum sur tout l'essai : 0