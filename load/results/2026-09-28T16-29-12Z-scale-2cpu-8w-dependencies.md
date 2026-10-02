Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).

### Palier 100 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 2.49 | 2164 | 57.4 | 12 |
| praxedo-objectstore | 1.71 | 1264 | 235.7 | 12 |
| praxedo-backend | 1.40 | 595 | 346.8 | 12 |
| praxedo-postgres | 0.38 | 126 | 0.3 | 12 |
| k6-run-a8ba84a9f3e3 | 0.13 | 155 | 56.4 | 12 |
| praxedo-grafana | 0.00 | 125 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 789 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 44 | 0.1 | 12 |

### Palier 130 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 3.60 | 1248 | 56.1 | 12 |
| praxedo-objectstore | 1.79 | 1304 | 228.8 | 12 |
| praxedo-backend | 1.48 | 604 | 357.7 | 12 |
| praxedo-postgres | 0.41 | 142 | 0.3 | 12 |
| k6-run-a8ba84a9f3e3 | 0.22 | 1348 | 70.5 | 12 |
| praxedo-keycloak | 0.06 | 804 | 0.0 | 12 |
| praxedo-grafana | 0.00 | 128 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 43 | 0.0 | 12 |

### Palier 160 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.88 | 1251 | 45.8 | 8 |
| praxedo-objectstore | 1.20 | 1353 | 182.5 | 8 |
| praxedo-backend | 0.98 | 749 | 307.9 | 8 |
| praxedo-postgres | 0.26 | 152 | 0.3 | 8 |
| k6-run-a8ba84a9f3e3 | 0.17 | 1784 | 82.9 | 8 |
| praxedo-keycloak | 0.04 | 807 | 0.0 | 8 |
| praxedo-grafana | 0.00 | 131 | 0.0 | 8 |
| praxedo-prometheus | 0.00 | 44 | 0.1 | 8 |

### Palier 200 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|

