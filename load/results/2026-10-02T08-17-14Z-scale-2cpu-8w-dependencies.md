Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).

### Palier 100 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 2.89 | 2216 | 56.8 | 12 |
| praxedo-objectstore | 2.04 | 1263 | 229.6 | 12 |
| praxedo-backend | 1.38 | 516 | 341.0 | 12 |
| praxedo-postgres | 0.46 | 181 | 0.3 | 12 |
| k6-run-58cff46cd6df | 0.14 | 222 | 56.8 | 12 |
| praxedo-grafana | 0.05 | 144 | 0.0 | 12 |
| praxedo-prometheus | 0.02 | 44 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 882 | 0.0 | 12 |

### Palier 130 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 2.96 | 1355 | 71.4 | 12 |
| praxedo-objectstore | 2.67 | 1374 | 288.6 | 12 |
| praxedo-backend | 1.73 | 520 | 433.8 | 12 |
| praxedo-postgres | 0.53 | 193 | 0.3 | 12 |
| k6-run-58cff46cd6df | 0.19 | 219 | 72.8 | 12 |
| praxedo-grafana | 0.00 | 190 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 883 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 46 | 0.0 | 12 |

### Palier 160 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.55 | 1380 | 36.4 | 12 |
| praxedo-objectstore | 1.41 | 1500 | 148.9 | 12 |
| praxedo-backend | 1.09 | 529 | 243.3 | 12 |
| praxedo-postgres | 0.31 | 206 | 0.2 | 12 |
| k6-run-58cff46cd6df | 0.20 | 378 | 54.7 | 12 |
| praxedo-keycloak | 0.02 | 885 | 0.0 | 12 |
| praxedo-grafana | 0.00 | 213 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 60 | 0.0 | 12 |

### Palier 200 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.10 | 1386 | 29.1 | 12 |
| praxedo-objectstore | 1.00 | 1510 | 119.8 | 12 |
| praxedo-backend | 0.81 | 531 | 199.6 | 12 |
| k6-run-58cff46cd6df | 0.26 | 428 | 56.6 | 12 |
| praxedo-postgres | 0.21 | 222 | 0.1 | 12 |
| praxedo-grafana | 0.09 | 216 | 0.0 | 12 |
| praxedo-prometheus | 0.08 | 70 | 0.0 | 12 |
| praxedo-keycloak | 0.01 | 884 | 0.0 | 12 |

