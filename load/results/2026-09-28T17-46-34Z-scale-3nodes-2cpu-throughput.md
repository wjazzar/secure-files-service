Essai commencé le 2026-09-28T17:47:18.000Z — échauffement 90 s, paliers de 60 s (+10 s de montée)
Nœuds mesurés : 3 × 2 CPU vu(s) par la JVM, tas maximal 645.9 Mo, ramasse-miettes G1 Young Generation ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

CPU, tas : le nœud le plus chargé.

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 150 fichiers/s | 88.20/s (53.0 Mo/s) | 90.24/s | 51.2 Mo/s | 52 (-163) | 12.9 s | 3.4 s | 3077 ms | 66 % | 149.1 Mo | 3255 | **non** |
| 200 fichiers/s | 99.33/s (55.2 Mo/s) | 91.08/s | 50.7 Mo/s | — (—) | 8.2 s | 7.0 s | 3016 ms | 61 % | 159.2 Mo | 4159 | **non** |
| 250 fichiers/s | 62.06/s (67.0 Mo/s) | 70.55/s | 40.5 Mo/s | 0 (-586) | 15.3 s | 13.6 s | 1736 ms | 38 % | 153.5 Mo | 7657 | **non** |
| 300 fichiers/s | 0.00/s (168.0 Mo/s) | 0.00/s | 0.0 Mo/s | 0 (+0) | 0.0 s | — s | 14 ms | 28 % | 158.1 Mo | 0 | oui |

Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne (à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; base : attente moyenne d'une connexion et demandes en attente au pire moment, pour le pool de l'API puis pour celui de la file de travail.

| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente BD api / file | En attente max api / file |
|---|---|---|---|---|---|---|
| 150 fichiers/s | 38 ms | 88 ms | 3.45 | 0.6 % | 486.0 / 0.0 ms | 41 / 0 |
| 200 fichiers/s | 41 ms | 87 ms | 3.76 | 0.7 % | 515.0 / 0.0 ms | 41 / 0 |
| 250 fichiers/s | 43 ms | 89 ms | 3.05 | 0.6 % | 491.5 / 0.0 ms | 40 / 0 |
| 300 fichiers/s | — ms | — ms | 0.00 | 0.3 % | 0.0 / 0.3 ms | 0 / 0 |

Invariant (praxedo_invariant_violations), maximum sur tout l'essai : 0