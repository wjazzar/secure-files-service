Essai commencé le 2026-09-28T13:33:53.000Z — paliers de 60 s (+10 s de montée)
Nœud mesuré : 1 CPU vu(s) par la JVM, tas maximal 778.5 Mo ; machine Docker : 24 CPU, 15.6 Gio, Docker Desktop

| Palier visé | Déposés | Traités | Débit traité | Retard fin (Δ) | Plus ancien max | Lag p95 | Dépôt p95 | CPU moyen | Tas max | 429 | Tient ? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 30 fichiers/s | 30.00/s (16.3 Mo/s) | 31.67/s | 17.3 Mo/s | 59 (-54) | 5.0 s | 5.1 s | 72 ms | 68 % | 85.1 Mo | 0 | oui |
| 50 fichiers/s | 50.02/s (28.0 Mo/s) | 49.78/s | 27.9 Mo/s | 18 (+8) | 1.0 s | 0.9 s | 37 ms | 69 % | 77.5 Mo | 0 | oui |
| 70 fichiers/s | 70.00/s (39.9 Mo/s) | 61.24/s | 34.7 Mo/s | 498 (+464) | 7.1 s | 1.9 s | 32 ms | 71 % | 88.8 Mo | 0 | **non** |
| 90 fichiers/s | 0.00/s (0.0 Mo/s) | 0.00/s | 0.0 Mo/s | 500 (+0) | 77.1 s | — s | 2 ms | 16 % | 87.4 Mo | 5400 | **non** |
| 120 fichiers/s | 0.00/s (0.0 Mo/s) | 0.00/s | 0.0 Mo/s | 500 (+0) | 147.1 s | — s | 2 ms | 19 % | 92.3 Mo | 7201 | **non** |