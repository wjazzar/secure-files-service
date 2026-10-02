Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).

### Palier 70 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.78 | 2056 | 39.3 | 12 |
| praxedo-objectstore | 1.27 | 1199 | 157.0 | 12 |
| praxedo-backend | 0.93 | 422 | 236.2 | 12 |
| praxedo-postgres | 0.26 | 135 | 0.2 | 12 |
| k6-run-42c1af1394f6 | 0.08 | 146 | 38.0 | 12 |
| praxedo-keycloak | 0.02 | 880 | 0.0 | 12 |
| praxedo-grafana | 0.01 | 118 | 0.0 | 12 |
| praxedo-prometheus | 0.01 | 27 | 0.0 | 12 |

### Palier 85 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.82 | 1195 | 48.8 | 12 |
| praxedo-objectstore | 1.45 | 1283 | 196.2 | 12 |
| praxedo-backend | 0.91 | 438 | 294.0 | 12 |
| praxedo-postgres | 0.31 | 149 | 0.2 | 12 |
| k6-run-42c1af1394f6 | 0.10 | 326 | 49.0 | 12 |
| praxedo-keycloak | 0.01 | 905 | 0.0 | 12 |
| praxedo-grafana | 0.01 | 132 | 0.0 | 12 |
| praxedo-prometheus | 0.01 | 26 | 0.0 | 12 |

### Palier 100 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.80 | 1227 | 54.8 | 12 |
| praxedo-objectstore | 1.66 | 1368 | 223.2 | 12 |
| praxedo-backend | 0.99 | 448 | 335.7 | 12 |
| praxedo-postgres | 0.34 | 162 | 0.3 | 12 |
| k6-run-42c1af1394f6 | 0.10 | 339 | 55.5 | 12 |
| praxedo-grafana | 0.02 | 182 | 0.0 | 12 |
| praxedo-keycloak | 0.01 | 904 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 26 | 0.0 | 12 |

### Palier 120 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.58 | 1255 | 51.0 | 12 |
| praxedo-objectstore | 1.52 | 1407 | 199.7 | 12 |
| praxedo-backend | 1.00 | 463 | 315.9 | 12 |
| praxedo-postgres | 0.30 | 177 | 0.2 | 12 |
| k6-run-42c1af1394f6 | 0.16 | 352 | 65.6 | 12 |
| praxedo-grafana | 0.03 | 229 | 0.0 | 12 |
| praxedo-prometheus | 0.03 | 43 | 0.0 | 12 |
| praxedo-keycloak | 0.02 | 904 | 0.0 | 12 |

