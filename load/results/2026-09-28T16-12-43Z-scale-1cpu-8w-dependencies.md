Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).

### Palier 70 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.59 | 2092 | 38.4 | 12 |
| praxedo-objectstore | 1.16 | 1213 | 153.8 | 12 |
| praxedo-backend | 0.97 | 432 | 232.6 | 12 |
| praxedo-postgres | 0.31 | 115 | 0.2 | 12 |
| k6-run-f7b8f4b521b9 | 0.09 | 155 | 38.6 | 12 |
| praxedo-grafana | 0.01 | 124 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 42 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 824 | 0.0 | 12 |

### Palier 85 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.61 | 1198 | 52.1 | 12 |
| praxedo-objectstore | 1.50 | 1322 | 206.9 | 12 |
| praxedo-backend | 0.92 | 433 | 301.5 | 12 |
| praxedo-postgres | 0.45 | 130 | 0.3 | 12 |
| k6-run-f7b8f4b521b9 | 0.11 | 156 | 49.3 | 12 |
| praxedo-grafana | 0.02 | 126 | 0.0 | 12 |
| praxedo-keycloak | 0.01 | 825 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 44 | 0.1 | 12 |

### Palier 100 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.66 | 1231 | 51.2 | 12 |
| praxedo-objectstore | 1.59 | 1413 | 210.5 | 12 |
| praxedo-backend | 1.01 | 438 | 321.2 | 12 |
| praxedo-postgres | 0.37 | 140 | 0.3 | 12 |
| k6-run-f7b8f4b521b9 | 0.13 | 156 | 58.2 | 12 |
| praxedo-grafana | 0.08 | 128 | 0.0 | 12 |
| praxedo-prometheus | 0.07 | 44 | 0.0 | 12 |
| praxedo-keycloak | 0.03 | 825 | 0.0 | 12 |

### Palier 120 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.62 | 1247 | 23.6 | 12 |
| praxedo-objectstore | 0.71 | 1412 | 88.7 | 12 |
| praxedo-backend | 0.70 | 479 | 177.3 | 12 |
| praxedo-postgres | 0.23 | 150 | 0.2 | 12 |
| k6-run-f7b8f4b521b9 | 0.17 | 1126 | 58.1 | 12 |
| praxedo-grafana | 0.13 | 129 | 0.0 | 12 |
| praxedo-prometheus | 0.10 | 47 | 0.1 | 12 |
| praxedo-keycloak | 0.05 | 832 | 0.0 | 12 |

