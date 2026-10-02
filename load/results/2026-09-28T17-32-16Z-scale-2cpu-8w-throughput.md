Essai commencé le 2026-09-28T17:32:58.000Z — échauffement 60 s, paliers de 60 s (+10 s de montée)
Nœuds mesurés : 1 × 2 CPU vu(s) par la JVM, tas maximal 645.9 Mo, ramasse-miettes G1 Young Generation ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

CPU, tas : le nœud le plus chargé.

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 fichiers/s | 99.84/s (57.9 Mo/s) | 99.75/s | 57.7 Mo/s | 13 (-82) | 1.0 s | 1.3 s | 32 ms | 58 % | 162.5 Mo | 0 | oui |
| 130 fichiers/s | 129.99/s (72.6 Mo/s) | 129.15/s | 72.1 Mo/s | 112 (-44) | 1.3 s | 0.8 s | 34 ms | 64 % | 160.4 Mo | 0 | oui |
| 160 fichiers/s | 61.27/s (35.6 Mo/s) | 58.09/s | 32.8 Mo/s | — (—) | 12.5 s | 12.1 s | 789 ms | 50 % | 154.4 Mo | 4238 | **non** |
| 200 fichiers/s | 0.00/s (112.6 Mo/s) | 4.91/s | 2.7 Mo/s | 0 (-353) | 8.2 s | 11.3 s | 13 ms | 46 % | 144.0 Mo | 0 | oui |

Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne (à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; base : attente moyenne d'une connexion et demandes en attente au pire moment, pour le pool de l'API puis pour celui de la file de travail.

| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente BD api / file | En attente max api / file |
|---|---|---|---|---|---|---|
| 100 fichiers/s | 16 ms | 39 ms | 1.60 | 0.6 % | 0.6 / 0.0 ms | 0 / 0 |
| 130 fichiers/s | 14 ms | 37 ms | 1.85 | 0.7 % | 0.2 / 0.0 ms | 0 / 0 |
| 160 fichiers/s | 16 ms | 40 ms | 0.94 | 0.5 % | 271.2 / 0.0 ms | 41 / 0 |
| 200 fichiers/s | 18 ms | 44 ms | 0.08 | 0.4 % | 0.0 / 0.0 ms | 0 / 0 |

Invariant (praxedo_invariant_violations), maximum sur tout l'essai : 0