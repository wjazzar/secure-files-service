Essai commencé le 2026-09-28T20:24:12.000Z — échauffement 90 s, paliers de 60 s (+10 s de montée)
Nœuds mesurés : 3 × 2 CPU vu(s) par la JVM, tas maximal 645.9 Mo, ramasse-miettes G1 Young Generation ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

CPU, tas : le nœud le plus chargé.

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 150 fichiers/s | 146.50/s (82.5 Mo/s) | 144.99/s | 81.6 Mo/s | 215 (+185) | 2.9 s | 2.0 s | 206 ms | 53 % | 196.6 Mo | 84 | **non** |
| 200 fichiers/s | 179.90/s (102.7 Mo/s) | 175.70/s | 100.6 Mo/s | 495 (+262) | 3.8 s | 3.3 s | 661 ms | 58 % | 188.3 Mo | 1179 | **non** |
| 250 fichiers/s | 82.80/s (48.0 Mo/s) | 82.26/s | 45.8 Mo/s | 585 (+3) | 12.6 s | 12.5 s | 1635 ms | 39 % | 198.4 Mo | 9839 | **non** |
| 300 fichiers/s | 53.69/s (33.5 Mo/s) | 54.66/s | 31.6 Mo/s | 501 (—) | 16.4 s | 13.7 s | 2006 ms | 41 % | 186.1 Mo | 13671 | **non** |

Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne (à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; base : attente moyenne d'une connexion et demandes en attente au pire moment, pour le pool de l'API puis pour celui de la file de travail.

| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente BD api / file | En attente max api / file |
|---|---|---|---|---|---|---|
| 150 fichiers/s | 34 ms | 88 ms | 4.94 | 0.5 % | 20.1 / 0.0 ms | 18 / 0 |
| 200 fichiers/s | 44 ms | 95 ms | 7.80 | 0.7 % | 69.4 / 0.0 ms | 31 / 0 |
| 250 fichiers/s | 46 ms | 99 ms | 3.74 | 0.4 % | 509.9 / 0.0 ms | 31 / 0 |
| 300 fichiers/s | 53 ms | 132 ms | 2.89 | 0.3 % | 824.8 / 0.0 ms | 40 / 0 |

Invariant (praxedo_invariant_violations), maximum sur tout l'essai : 0