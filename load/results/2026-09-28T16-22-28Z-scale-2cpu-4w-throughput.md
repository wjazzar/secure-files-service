Essai commencé le 2026-09-28T16:23:14.000Z — échauffement 60 s, paliers de 60 s (+10 s de montée)
Nœuds mesurés : 1 × 2 CPU vu(s) par la JVM, tas maximal 1610.6 Mo, ramasse-miettes G1 Young Generation ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

CPU, tas : le nœud le plus chargé.

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 80 fichiers/s | 80.01/s (45.6 Mo/s) | 80.04/s | 45.6 Mo/s | 3 (-33) | 2.8 s | 2.6 s | 28 ms | 49 % | 154.8 Mo | 0 | oui |
| 100 fichiers/s | 96.28/s (54.1 Mo/s) | 87.89/s | 49.2 Mo/s | 495 (+472) | 6.0 s | 5.2 s | 75 ms | 48 % | 168.1 Mo | 101 | **non** |
| 120 fichiers/s | 86.01/s (48.2 Mo/s) | 85.99/s | 48.3 Mo/s | 500 (+3) | 7.2 s | 7.6 s | 156 ms | 47 % | 165.1 Mo | 2052 | **non** |
| 150 fichiers/s | 63.35/s (35.9 Mo/s) | 63.33/s | 36.0 Mo/s | 500 (+3) | 14.0 s | 14.1 s | 715 ms | 43 % | 172.6 Mo | 4846 | **non** |

Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne (à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; base : attente moyenne d'une connexion du pool, et demandes en attente au pire moment.

| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente connexion BD | Demandes BD en attente (max) |
|---|---|---|---|---|---|---|
| 80 fichiers/s | 14 ms | 35 ms | 1.09 | 0.4 % | 0.0 ms | 0 |
| 100 fichiers/s | 16 ms | 37 ms | 1.37 | 0.5 % | 7.7 ms | 0 |
| 120 fichiers/s | 13 ms | 34 ms | 1.12 | 0.5 % | 5.9 ms | 0 |
| 150 fichiers/s | 16 ms | 38 ms | 1.02 | 0.5 % | 37.6 ms | 0 |

Invariant (praxedo_invariant_violations), maximum sur tout l'essai : 0