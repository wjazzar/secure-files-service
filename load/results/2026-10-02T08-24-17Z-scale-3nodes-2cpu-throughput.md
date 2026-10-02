Essai commencé le 2026-10-02T08:25:02.000Z — échauffement 90 s, paliers de 60 s (+10 s de montée)
Nœuds mesurés : 3 × 2 CPU vu(s) par la JVM, tas maximal 645.9 Mo, ramasse-miettes G1 Young Generation ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

CPU, tas : le nœud le plus chargé.

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 150 fichiers/s | 146.66/s (82.7 Mo/s) | 143.95/s | 81.6 Mo/s | 216 (+89) | 2.9 s | 0.9 s | 86 ms | 58 % | 165.6 Mo | 111 | **non** |
| 200 fichiers/s | 109.24/s (62.4 Mo/s) | 105.13/s | 58.6 Mo/s | 555 (+449) | 10.8 s | 10.7 s | 2680 ms | 50 % | 158.9 Mo | 5430 | **non** |
| 250 fichiers/s | 69.62/s (41.9 Mo/s) | 69.05/s | 39.3 Mo/s | 618 (+159) | 12.6 s | 12.4 s | 3707 ms | 44 % | 172.6 Mo | 8695 | **non** |
| 300 fichiers/s | 59.29/s (37.3 Mo/s) | 59.27/s | 33.0 Mo/s | 554 (-118) | 15.1 s | 12.5 s | 2750 ms | 45 % | 155.4 Mo | 13991 | **non** |

Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne (à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; base : attente moyenne d'une connexion et demandes en attente au pire moment, pour le pool de l'API puis pour celui de la file de travail.

| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente BD api / file | En attente max api / file |
|---|---|---|---|---|---|---|
| 150 fichiers/s | 33 ms | 88 ms | 4.71 | 0.6 % | 22.4 / 0.0 ms | 40 / 0 |
| 200 fichiers/s | 44 ms | 92 ms | 4.58 | 0.6 % | 479.2 / 0.0 ms | 40 / 0 |
| 250 fichiers/s | 46 ms | 106 ms | 3.18 | 0.8 % | 722.6 / 0.0 ms | 41 / 0 |
| 300 fichiers/s | 46 ms | 101 ms | 2.74 | 0.5 % | 827.0 / 0.0 ms | 41 / 0 |

Invariant (praxedo_invariant_violations), maximum sur tout l'essai : 0