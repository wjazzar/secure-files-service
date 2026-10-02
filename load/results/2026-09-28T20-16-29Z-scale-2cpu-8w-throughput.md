Essai commencé le 2026-09-28T20:17:22.000Z — échauffement 60 s, paliers de 60 s (+10 s de montée)
Nœuds mesurés : 1 × 2 CPU vu(s) par la JVM, tas maximal 645.9 Mo, ramasse-miettes G1 Young Generation ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

CPU, tas : le nœud le plus chargé.

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 fichiers/s | 99.97/s (55.7 Mo/s) | 102.22/s | 57.0 Mo/s | 29 (+12) | 1.7 s | 1.3 s | 33 ms | 59 % | 214.5 Mo | 0 | oui |
| 130 fichiers/s | 130.03/s (72.8 Mo/s) | 128.55/s | 71.8 Mo/s | 90 (-18) | 1.5 s | 1.0 s | 38 ms | 68 % | 224.0 Mo | 0 | oui |
| 160 fichiers/s | 160.00/s (90.0 Mo/s) | 161.04/s | 90.7 Mo/s | 70 (+28) | 0.9 s | 0.8 s | 47 ms | 85 % | 222.1 Mo | 0 | oui |
| 200 fichiers/s | 57.02/s (32.1 Mo/s) | 58.06/s | 31.8 Mo/s | 491 (+141) | 13.6 s | 13.9 s | 46 ms | 55 % | 199.2 Mo | 7126 | **non** |

Où part le temps — antivirus : durée par appel, et analyses en cours en moyenne (à comparer au nombre total de workers) ; GC : part du temps en pause, nœud le plus touché ; base : attente moyenne d'une connexion et demandes en attente au pire moment, pour le pool de l'API puis pour celui de la file de travail.

| Palier visé | Antivirus moyen | Antivirus p95 | Analyses en cours (moy.) | Pause GC | Attente BD api / file | En attente max api / file |
|---|---|---|---|---|---|---|
| 100 fichiers/s | 16 ms | 39 ms | 1.65 | 0.4 % | 0.4 / 0.0 ms | 0 / 0 |
| 130 fichiers/s | 15 ms | 38 ms | 1.87 | 0.5 % | 1.4 / 0.0 ms | 0 / 0 |
| 160 fichiers/s | 16 ms | 39 ms | 2.57 | 0.7 % | 1.2 / 0.0 ms | 0 / 0 |
| 200 fichiers/s | 23 ms | 62 ms | 1.33 | 0.4 % | 282.3 / 0.0 ms | 15 / 0 |

Invariant (praxedo_invariant_violations), maximum sur tout l'essai : 0