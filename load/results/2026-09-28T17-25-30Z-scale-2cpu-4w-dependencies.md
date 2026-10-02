Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).

### Palier 80 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.67 | 2067 | 46.1 | 12 |
| praxedo-objectstore | 1.28 | 1186 | 182.8 | 12 |
| praxedo-backend | 1.12 | 426 | 280.2 | 12 |
| praxedo-postgres | 0.17 | 126 | 0.2 | 12 |
| k6-run-15ed983d91f6 | 0.09 | 145 | 46.9 | 12 |
| praxedo-grafana | 0.02 | 126 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 50 | 0.1 | 12 |
| praxedo-keycloak | 0.00 | 812 | 0.0 | 12 |

### Palier 100 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 2.06 | 1250 | 56.4 | 12 |
| praxedo-objectstore | 1.73 | 1250 | 226.9 | 12 |
| praxedo-backend | 1.29 | 444 | 339.4 | 12 |
| praxedo-postgres | 0.30 | 142 | 0.3 | 12 |
| k6-run-15ed983d91f6 | 0.12 | 147 | 56.6 | 12 |
| praxedo-grafana | 0.02 | 127 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 62 | 0.1 | 12 |
| praxedo-keycloak | 0.00 | 811 | 0.0 | 12 |

### Palier 120 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.82 | 1239 | 42.8 | 12 |
| praxedo-objectstore | 1.54 | 1341 | 177.9 | 12 |
| praxedo-backend | 1.14 | 445 | 275.9 | 12 |
| praxedo-postgres | 0.27 | 154 | 0.2 | 12 |
| k6-run-15ed983d91f6 | 0.15 | 380 | 53.7 | 12 |
| praxedo-grafana | 0.04 | 127 | 0.0 | 12 |
| praxedo-keycloak | 0.02 | 814 | 0.0 | 12 |
| praxedo-prometheus | 0.02 | 60 | 0.0 | 12 |

### Palier 150 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.31 | 1258 | 36.4 | 12 |
| praxedo-objectstore | 1.24 | 1374 | 177.9 | 12 |
| praxedo-backend | 1.13 | 451 | 294.2 | 12 |
| praxedo-postgres | 0.20 | 166 | 0.2 | 12 |
| k6-run-15ed983d91f6 | 0.19 | 361 | 78.3 | 12 |
| praxedo-grafana | 0.02 | 127 | 0.0 | 12 |
| praxedo-prometheus | 0.01 | 65 | 0.1 | 12 |
| praxedo-keycloak | 0.01 | 813 | 0.0 | 12 |

