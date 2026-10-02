Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).

### Palier 100 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 2.16 | 2176 | 58.3 | 12 |
| praxedo-objectstore | 1.85 | 1256 | 228.8 | 12 |
| praxedo-backend | 1.23 | 474 | 350.3 | 12 |
| praxedo-postgres | 0.30 | 146 | 0.3 | 12 |
| praxedo-grafana | 0.16 | 129 | 0.0 | 12 |
| k6-run-99c004d81008 | 0.13 | 190 | 59.3 | 12 |
| praxedo-prometheus | 0.13 | 45 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 817 | 0.0 | 12 |

### Palier 130 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 2.66 | 1239 | 72.8 | 12 |
| praxedo-objectstore | 2.61 | 1359 | 286.7 | 12 |
| praxedo-backend | 1.58 | 477 | 444.5 | 12 |
| praxedo-postgres | 0.39 | 165 | 0.3 | 12 |
| k6-run-99c004d81008 | 0.18 | 202 | 72.2 | 12 |
| praxedo-grafana | 0.17 | 130 | 0.0 | 12 |
| praxedo-prometheus | 0.15 | 44 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 817 | 0.0 | 12 |

### Palier 160 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.22 | 1253 | 40.1 | 12 |
| praxedo-backend | 1.07 | 481 | 256.0 | 12 |
| praxedo-objectstore | 0.92 | 1415 | 159.8 | 12 |
| praxedo-postgres | 0.29 | 179 | 0.2 | 12 |
| k6-run-99c004d81008 | 0.20 | 395 | 54.7 | 12 |
| praxedo-grafana | 0.16 | 131 | 0.0 | 12 |
| praxedo-prometheus | 0.12 | 45 | 0.0 | 12 |
| praxedo-keycloak | 0.02 | 820 | 0.0 | 12 |

### Palier 200 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-backend | 0.85 | 482 | 237.7 | 12 |
| praxedo-objectstore | 0.78 | 1407 | 121.6 | 12 |
| k6-run-99c004d81008 | 0.25 | 354 | 114.7 | 12 |
| praxedo-grafana | 0.14 | 131 | 0.0 | 12 |
| praxedo-prometheus | 0.14 | 46 | 0.1 | 12 |
| praxedo-antivirus | 0.02 | 1257 | 3.6 | 12 |
| praxedo-postgres | 0.02 | 182 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 818 | 0.0 | 12 |

