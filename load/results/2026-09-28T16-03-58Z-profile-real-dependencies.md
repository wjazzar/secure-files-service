Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).

### Palier 50 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.13 | 2190 | 29.1 | 36 |
| praxedo-objectstore | 0.93 | 1240 | 117.1 | 36 |
| praxedo-backend | 0.75 | 501 | 175.2 | 36 |
| praxedo-postgres | 0.15 | 147 | 0.2 | 36 |
| k6-run-b32dea1e0379 | 0.07 | 148 | 29.1 | 36 |
| praxedo-keycloak | 0.01 | 882 | 0.0 | 36 |
| praxedo-prometheus | 0.00 | 50 | 0.1 | 36 |
| praxedo-grafana | 0.00 | 146 | 0.1 | 36 |

### Palier 80 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.57 | 1354 | 46.5 | 36 |
| praxedo-objectstore | 1.37 | 1401 | 185.4 | 36 |
| praxedo-backend | 0.93 | 497 | 276.2 | 36 |
| praxedo-postgres | 0.26 | 176 | 0.3 | 36 |
| k6-run-b32dea1e0379 | 0.10 | 150 | 46.3 | 36 |
| praxedo-prometheus | 0.03 | 67 | 0.1 | 36 |
| praxedo-grafana | 0.02 | 217 | 0.0 | 36 |
| praxedo-keycloak | 0.00 | 883 | 0.0 | 36 |

