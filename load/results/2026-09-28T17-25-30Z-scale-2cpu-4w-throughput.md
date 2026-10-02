Essai commencé le 2026-09-28T17:26:14.000Z — échauffement 60 s, paliers de 60 s (+10 s de montée)
Nœuds mesurés : 1 × 2 CPU vu(s) par la JVM, tas maximal 645.9 Mo, ramasse-miettes G1 Young Generation ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

CPU, tas : le nœud le plus chargé.

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 80 fichiers/s | 79.96/s (46.1 Mo/s) | 80.53/s | 46.4 Mo/s | 3 (-56) | 1.0 s | 1.3 s | 31 ms | 57 % | 110.8 Mo | 0 | oui |
| 100 fichiers/s | 100.01/s (55.7 Mo/s) | 99.99/s | 55.7 Mo/s | 38 (-49) | 1.4 s | 1.2 s | 28 ms | 56 % | 134.5 Mo | 0 | oui |
| 120 fichiers/s | 81.26/s (46.5 Mo/s) | 75.51/s | 42.6 Mo/s | 486 (+381) | 16.2 s | 16.5 s | 32 ms | 51 % | 126.0 Mo | 2176 | **non** |
| 150 fichiers/s | 57.22/s (66.7 Mo/s) | 66.13/s | 37.1 Mo/s | 0 (-503) | 7.1 s | 7.0 s | 27 ms | 59 % | 129.5 Mo | 1991 | **non** |

Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne (à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; base : attente moyenne d'une connexion et demandes en attente au pire moment, pour le pool de l'API puis pour celui de la file de travail.

| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente BD api / file | En attente max api / file |
|---|---|---|---|---|---|---|
| 80 fichiers/s | 15 ms | 38 ms | 1.18 | 0.8 % | 0.0 / 0.0 ms | 0 / 0 |
| 100 fichiers/s | 13 ms | 36 ms | 1.27 | 0.8 % | 0.0 / 0.0 ms | 0 / 0 |
| 120 fichiers/s | 15 ms | 37 ms | 1.12 | 0.7 % | 76.4 / 0.0 ms | 31 / 0 |
| 150 fichiers/s | 13 ms | 37 ms | 0.89 | 0.8 % | 27.8 / 0.0 ms | 40 / 0 |

Invariant (praxedo_invariant_violations), maximum sur tout l'essai : 0