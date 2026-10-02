Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).

### Palier 150 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 3.93 | 1267 | 81.2 | 12 |
| praxedo-objectstore | 3.34 | 1424 | 330.4 | 12 |
| praxedo-backend | 1.21 | 502 | 169.9 | 12 |
| praxedo-backend-2 | 1.21 | 458 | 153.8 | 12 |
| praxedo-backend-3 | 1.20 | 462 | 169.6 | 12 |
| praxedo-postgres | 0.74 | 307 | 0.4 | 12 |
| k6-run-df9b0c8dabf6 | 0.27 | 655 | 83.9 | 12 |
| praxedo-grafana | 0.06 | 121 | 0.0 | 12 |
| praxedo-prometheus | 0.05 | 49 | 0.1 | 12 |
| praxedo-keycloak | 0.02 | 833 | 0.0 | 12 |

### Palier 200 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 2.97 | 1283 | 61.9 | 12 |
| praxedo-objectstore | 2.77 | 1583 | 235.9 | 12 |
| praxedo-backend | 1.00 | 507 | 134.3 | 12 |
| praxedo-backend-3 | 0.97 | 467 | 128.8 | 12 |
| praxedo-backend-2 | 0.97 | 459 | 127.0 | 12 |
| praxedo-postgres | 0.84 | 328 | 0.3 | 12 |
| k6-run-df9b0c8dabf6 | 0.32 | 1116 | 80.2 | 12 |
| praxedo-grafana | 0.10 | 119 | 0.0 | 12 |
| praxedo-prometheus | 0.07 | 52 | 0.1 | 12 |
| praxedo-keycloak | 0.02 | 833 | 0.0 | 12 |

### Palier 250 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 2.11 | 1307 | 41.9 | 12 |
| praxedo-objectstore | 1.82 | 1558 | 168.8 | 12 |
| praxedo-backend-3 | 0.76 | 467 | 94.4 | 12 |
| praxedo-backend-2 | 0.75 | 456 | 92.6 | 12 |
| praxedo-backend | 0.68 | 503 | 99.8 | 12 |
| praxedo-postgres | 0.62 | 345 | 0.2 | 12 |
| k6-run-df9b0c8dabf6 | 0.38 | 1122 | 74.8 | 12 |
| praxedo-prometheus | 0.17 | 60 | 0.1 | 12 |
| praxedo-grafana | 0.15 | 121 | 0.0 | 12 |
| praxedo-keycloak | 0.05 | 838 | 0.0 | 12 |

### Palier 300 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.24 | 1319 | 32.8 | 12 |
| praxedo-objectstore | 1.06 | 1556 | 138.0 | 12 |
| praxedo-backend | 0.51 | 506 | 81.7 | 12 |
| praxedo-backend-2 | 0.46 | 458 | 85.3 | 12 |
| praxedo-backend-3 | 0.41 | 467 | 83.5 | 12 |
| k6-run-df9b0c8dabf6 | 0.38 | 1304 | 76.7 | 12 |
| praxedo-postgres | 0.34 | 355 | 0.2 | 12 |
| praxedo-grafana | 0.02 | 120 | 0.0 | 12 |
| praxedo-prometheus | 0.01 | 46 | 0.1 | 12 |
| praxedo-keycloak | 0.00 | 834 | 0.0 | 12 |

