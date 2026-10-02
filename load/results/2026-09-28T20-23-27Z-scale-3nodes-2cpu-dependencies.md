Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).

### Palier 150 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 3.64 | 1255 | 83.4 | 12 |
| praxedo-objectstore | 3.24 | 1447 | 335.9 | 12 |
| praxedo-backend-2 | 1.06 | 486 | 160.2 | 12 |
| praxedo-backend-3 | 1.05 | 553 | 178.3 | 12 |
| praxedo-backend | 0.97 | 492 | 163.6 | 12 |
| praxedo-postgres | 0.64 | 276 | 0.4 | 12 |
| k6-run-3ac9d80946ca | 0.25 | 559 | 85.1 | 12 |
| praxedo-keycloak | 0.04 | 849 | 0.0 | 12 |
| praxedo-grafana | 0.01 | 134 | 0.0 | 12 |
| praxedo-prometheus | 0.01 | 53 | 0.1 | 12 |

### Palier 200 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-objectstore | 3.73 | 1519 | 413.9 | 12 |
| praxedo-antivirus | 3.42 | 1307 | 103.8 | 12 |
| praxedo-backend-2 | 1.11 | 491 | 216.0 | 12 |
| praxedo-backend | 1.10 | 502 | 203.3 | 12 |
| praxedo-backend-3 | 1.09 | 552 | 208.8 | 12 |
| praxedo-postgres | 0.93 | 306 | 0.5 | 12 |
| k6-run-3ac9d80946ca | 0.35 | 1046 | 114.7 | 12 |
| praxedo-grafana | 0.02 | 149 | 0.0 | 12 |
| praxedo-prometheus | 0.02 | 58 | 0.1 | 12 |
| praxedo-keycloak | 0.02 | 849 | 0.0 | 12 |

### Palier 250 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 2.16 | 1335 | 45.5 | 12 |
| praxedo-objectstore | 2.10 | 1706 | 187.0 | 12 |
| praxedo-backend | 0.62 | 495 | 107.1 | 12 |
| praxedo-backend-3 | 0.57 | 555 | 103.5 | 12 |
| praxedo-backend-2 | 0.57 | 492 | 101.6 | 12 |
| praxedo-postgres | 0.44 | 326 | 0.2 | 12 |
| k6-run-3ac9d80946ca | 0.34 | 1271 | 82.1 | 12 |
| praxedo-keycloak | 0.08 | 851 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 57 | 0.1 | 12 |
| praxedo-grafana | 0.00 | 151 | 0.0 | 12 |

### Palier 300 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 0.95 | 1356 | 34.6 | 12 |
| praxedo-objectstore | 0.88 | 1676 | 147.0 | 12 |
| praxedo-backend-2 | 0.36 | 497 | 85.3 | 12 |
| k6-run-3ac9d80946ca | 0.34 | 1764 | 82.2 | 12 |
| praxedo-backend-3 | 0.33 | 543 | 90.8 | 12 |
| praxedo-backend | 0.31 | 497 | 88.9 | 12 |
| praxedo-postgres | 0.22 | 333 | 0.2 | 12 |
| praxedo-keycloak | 0.01 | 851 | 0.0 | 12 |
| praxedo-grafana | 0.00 | 153 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 60 | 0.1 | 12 |

