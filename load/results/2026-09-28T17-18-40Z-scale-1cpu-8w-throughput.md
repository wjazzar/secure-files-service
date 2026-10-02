Essai commencé le 2026-09-28T17:19:32.000Z — échauffement 60 s, paliers de 60 s (+10 s de montée)
Nœuds mesurés : 1 × 1 CPU vu(s) par la JVM, tas maximal 624.4 Mo, ramasse-miettes Copy + MarkSweepCompact ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

CPU, tas : le nœud le plus chargé.

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 70 fichiers/s | 69.99/s (38.6 Mo/s) | 70.61/s | 38.9 Mo/s | 50 (+29) | 1.2 s | 1.4 s | 78 ms | 84 % | 95.2 Mo | 0 | oui |
| 85 fichiers/s | 84.33/s (48.6 Mo/s) | 83.95/s | 48.4 Mo/s | 34 (+5) | 2.0 s | 1.7 s | 65 ms | 81 % | 101.3 Mo | 5 | **non** |
| 100 fichiers/s | 100.01/s (55.8 Mo/s) | 97.55/s | 54.6 Mo/s | 257 (+228) | 2.5 s | 2.6 s | 62 ms | 89 % | 107.4 Mo | 0 | oui |
| 120 fichiers/s | 86.77/s (51.5 Mo/s) | 87.82/s | 48.7 Mo/s | 435 (-35) | 6.3 s | 6.9 s | 71 ms | 90 % | 124.2 Mo | 1739 | **non** |

Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne (à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; base : attente moyenne d'une connexion et demandes en attente au pire moment, pour le pool de l'API puis pour celui de la file de travail.

| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente BD api / file | En attente max api / file |
|---|---|---|---|---|---|---|
| 70 fichiers/s | 27 ms | 83 ms | 1.89 | 1.6 % | 0.0 / 0.0 ms | 0 / 0 |
| 85 fichiers/s | 24 ms | 67 ms | 2.00 | 1.5 % | 4.5 / 0.0 ms | 0 / 0 |
| 100 fichiers/s | 25 ms | 65 ms | 2.40 | 1.6 % | 0.0 / 0.0 ms | 0 / 0 |
| 120 fichiers/s | 27 ms | 77 ms | 2.37 | 2.4 % | 0.0 / 0.0 ms | 0 / 0 |

Invariant (praxedo_invariant_violations), maximum sur tout l'essai : 0