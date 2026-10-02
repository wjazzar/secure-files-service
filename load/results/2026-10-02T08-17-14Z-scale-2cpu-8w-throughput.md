Essai commencé le 2026-10-02T08:18:06.000Z — échauffement 60 s, paliers de 60 s (+10 s de montée)
Nœuds mesurés : 1 × 2 CPU vu(s) par la JVM, tas maximal 645.9 Mo, ramasse-miettes G1 Young Generation ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

CPU, tas : le nœud le plus chargé.

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 fichiers/s | 99.77/s (56.5 Mo/s) | 102.02/s | 57.6 Mo/s | 6 (-33) | 1.5 s | 1.2 s | 42 ms | 62 % | 180.7 Mo | 0 | oui |
| 130 fichiers/s | 129.98/s (73.0 Mo/s) | 131.08/s | 73.5 Mo/s | 88 (+77) | 1.2 s | 0.9 s | 36 ms | 73 % | 168.8 Mo | 0 | oui |
| 160 fichiers/s | 69.95/s (40.3 Mo/s) | 67.73/s | 38.4 Mo/s | 481 (+166) | 18.8 s | 19.7 s | 80 ms | 51 % | 190.6 Mo | 5323 | **non** |
| 200 fichiers/s | 53.09/s (30.7 Mo/s) | 53.02/s | 31.0 Mo/s | 501 (-6) | 14.3 s | 14.9 s | 51 ms | 32 % | 168.7 Mo | 8789 | **non** |

Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne (à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; base : attente moyenne d'une connexion et demandes en attente au pire moment, pour le pool de l'API puis pour celui de la file de travail.

| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente BD api / file | En attente max api / file |
|---|---|---|---|---|---|---|
| 100 fichiers/s | 18 ms | 42 ms | 1.79 | 0.6 % | 1.7 / 0.0 ms | 0 / 0 |
| 130 fichiers/s | 16 ms | 40 ms | 2.08 | 0.7 % | 0.5 / 0.0 ms | 0 / 0 |
| 160 fichiers/s | 18 ms | 43 ms | 1.20 | 0.4 % | 87.0 / 0.0 ms | 0 / 0 |
| 200 fichiers/s | 18 ms | 43 ms | 0.96 | 0.3 % | 184.4 / 0.0 ms | 38 / 0 |

Invariant (praxedo_invariant_violations), maximum sur tout l'essai : 0