Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).

### Palier 120 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 2.61 | 1258 | 45.0 | 12 |
| praxedo-objectstore | 1.70 | 1374 | 175.7 | 12 |
| praxedo-backend-2 | 0.66 | 428 | 91.7 | 12 |
| praxedo-backend | 0.64 | 434 | 93.7 | 12 |
| praxedo-backend-3 | 0.59 | 426 | 96.0 | 12 |
| praxedo-postgres | 0.37 | 307 | 0.2 | 12 |
| k6-run-d772c789cbf3 | 0.19 | 1627 | 55.6 | 12 |
| praxedo-prometheus | 0.03 | 55 | 0.1 | 12 |
| praxedo-grafana | 0.02 | 127 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 871 | 0.0 | 12 |

### Palier 160 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.81 | 1222 | 51.9 | 12 |
| praxedo-objectstore | 1.69 | 1338 | 213.6 | 12 |
| praxedo-backend-2 | 0.58 | 448 | 115.4 | 12 |
| praxedo-backend | 0.56 | 443 | 108.9 | 12 |
| praxedo-backend-3 | 0.55 | 431 | 115.8 | 12 |
| praxedo-postgres | 0.36 | 322 | 0.3 | 12 |
| k6-run-d772c789cbf3 | 0.20 | 1657 | 72.5 | 12 |
| praxedo-prometheus | 0.03 | 58 | 0.1 | 12 |
| praxedo-grafana | 0.02 | 127 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 872 | 0.0 | 12 |

### Palier 200 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-objectstore | 1.65 | 1386 | 174.3 | 12 |
| praxedo-antivirus | 1.50 | 1246 | 41.9 | 12 |
| praxedo-backend-3 | 0.52 | 448 | 94.4 | 12 |
| praxedo-backend-2 | 0.48 | 449 | 98.0 | 12 |
| praxedo-backend | 0.43 | 447 | 99.8 | 12 |
| praxedo-postgres | 0.35 | 337 | 0.2 | 12 |
| k6-run-d772c789cbf3 | 0.26 | 1668 | 78.4 | 12 |
| praxedo-keycloak | 0.03 | 872 | 0.0 | 12 |
| praxedo-grafana | 0.00 | 125 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 58 | 0.1 | 12 |

### Palier 250 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-objectstore | 1.51 | 1437 | 177.9 | 12 |
| praxedo-antivirus | 0.86 | 1256 | 16.4 | 12 |
| praxedo-backend-3 | 0.54 | 456 | 110.7 | 12 |
| praxedo-backend-2 | 0.50 | 453 | 112.5 | 12 |
| praxedo-backend | 0.47 | 455 | 107.1 | 12 |
| k6-run-d772c789cbf3 | 0.30 | 1633 | 134.8 | 12 |
| praxedo-postgres | 0.16 | 342 | 0.1 | 12 |
| praxedo-prometheus | 0.01 | 60 | 0.1 | 12 |
| praxedo-grafana | 0.00 | 126 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 871 | 0.0 | 12 |

