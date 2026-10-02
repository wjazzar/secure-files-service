Essai commencé le 2026-09-28T16:29:54.000Z — échauffement 60 s, paliers de 60 s (+10 s de montée)
Nœuds mesurés : 1 × 2 CPU vu(s) par la JVM, tas maximal 1610.6 Mo, ramasse-miettes G1 Young Generation ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

CPU, tas : le nœud le plus chargé.

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 fichiers/s | 99.99/s (57.0 Mo/s) | 100.10/s | 57.0 Mo/s | 4 (-24) | 1.0 s | 1.2 s | 32 ms | 63 % | 218.2 Mo | 0 | oui |
| 130 fichiers/s | 107.63/s (60.0 Mo/s) | 100.26/s | 55.9 Mo/s | 425 (+398) | 6.4 s | 6.4 s | 780 ms | 64 % | 254.4 Mo | 1034 | **non** |
| 160 fichiers/s | 50.37/s (28.2 Mo/s) | 59.44/s | 33.5 Mo/s | 0 (-442) | 11.8 s | 12.1 s | 3471 ms | 41 % | 254.8 Mo | 2671 | **non** |

Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne (à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; base : attente moyenne d'une connexion du pool, et demandes en attente au pire moment.

| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente connexion BD | Demandes BD en attente (max) |
|---|---|---|---|---|---|---|
| 100 fichiers/s | 15 ms | 38 ms | 1.55 | 0.4 % | 0.1 ms | 0 |
| 130 fichiers/s | 27 ms | 84 ms | 2.69 | 0.5 % | 32.2 ms | 211 |
| 160 fichiers/s | 20 ms | 44 ms | 1.20 | 0.3 % | 154.0 ms | 0 |

Invariant (praxedo_invariant_violations), maximum sur tout l'essai : 0
