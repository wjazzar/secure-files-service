Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).

### Palier 150 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 2.37 | 1262 | 52.1 | 12 |
| praxedo-objectstore | 2.22 | 1388 | 201.6 | 12 |
| praxedo-backend | 0.99 | 449 | 101.5 | 12 |
| praxedo-backend-3 | 0.98 | 442 | 115.8 | 12 |
| praxedo-backend-2 | 0.97 | 492 | 108.5 | 12 |
| praxedo-postgres | 0.44 | 261 | 0.2 | 12 |
| k6-run-27794b85e693 | 0.22 | 1092 | 62.7 | 12 |
| praxedo-prometheus | 0.09 | 56 | 0.1 | 12 |
| praxedo-grafana | 0.09 | 133 | 0.0 | 12 |
| praxedo-keycloak | 0.01 | 826 | 0.0 | 12 |

### Palier 200 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-objectstore | 2.86 | 1462 | 217.8 | 12 |
| praxedo-antivirus | 2.47 | 1241 | 53.3 | 12 |
| praxedo-backend | 1.11 | 448 | 115.8 | 12 |
| praxedo-backend-2 | 0.93 | 482 | 120.5 | 12 |
| praxedo-backend-3 | 0.87 | 466 | 114.1 | 12 |
| praxedo-postgres | 0.67 | 286 | 0.3 | 12 |
| k6-run-27794b85e693 | 0.30 | 1124 | 76.6 | 12 |
| praxedo-prometheus | 0.09 | 61 | 0.1 | 12 |
| praxedo-grafana | 0.08 | 136 | 0.0 | 12 |
| praxedo-keycloak | 0.01 | 824 | 0.0 | 12 |

### Palier 250 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.89 | 1262 | 43.7 | 12 |
| praxedo-objectstore | 1.89 | 1496 | 194.2 | 12 |
| praxedo-backend | 0.77 | 452 | 110.7 | 12 |
| praxedo-backend-3 | 0.71 | 469 | 118.0 | 12 |
| praxedo-backend-2 | 0.63 | 481 | 108.9 | 12 |
| praxedo-postgres | 0.39 | 353 | 0.2 | 12 |
| k6-run-27794b85e693 | 0.37 | 1606 | 96.7 | 12 |
| praxedo-keycloak | 0.12 | 837 | 0.0 | 12 |
| praxedo-prometheus | 0.10 | 63 | 0.1 | 12 |
| praxedo-grafana | 0.09 | 138 | 0.0 | 12 |

### Palier 300 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-objectstore | 1.02 | 1497 | 172.4 | 12 |
| praxedo-backend | 0.58 | 452 | 114.3 | 12 |
| praxedo-backend-3 | 0.45 | 478 | 116.2 | 12 |
| praxedo-backend-2 | 0.41 | 480 | 114.3 | 12 |
| k6-run-27794b85e693 | 0.35 | 1765 | 169.4 | 12 |
| praxedo-grafana | 0.08 | 137 | 0.0 | 12 |
| praxedo-prometheus | 0.08 | 71 | 0.1 | 12 |
| praxedo-postgres | 0.02 | 355 | 0.0 | 12 |
| praxedo-antivirus | 0.01 | 1247 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 834 | 0.0 | 12 |

