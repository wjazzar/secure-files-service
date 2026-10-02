Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).

### Palier 80 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.46 | 2163 | 47.0 | 12 |
| praxedo-objectstore | 1.21 | 1174 | 189.5 | 12 |
| praxedo-backend | 0.86 | 487 | 282.8 | 12 |
| praxedo-postgres | 0.20 | 118 | 0.3 | 12 |
| k6-run-5c3b8c8900f5 | 0.10 | 147 | 46.0 | 12 |
| praxedo-grafana | 0.00 | 123 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 799 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 41 | 0.0 | 12 |

### Palier 100 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.91 | 1263 | 48.6 | 12 |
| praxedo-objectstore | 1.52 | 1260 | 197.9 | 12 |
| praxedo-backend | 1.03 | 491 | 306.9 | 12 |
| praxedo-postgres | 0.28 | 134 | 0.3 | 12 |
| k6-run-5c3b8c8900f5 | 0.14 | 590 | 55.2 | 12 |
| praxedo-grafana | 0.00 | 129 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 805 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 44 | 0.0 | 12 |

### Palier 120 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.77 | 1237 | 50.4 | 12 |
| praxedo-objectstore | 1.56 | 1300 | 199.6 | 12 |
| praxedo-backend | 0.97 | 506 | 319.4 | 12 |
| praxedo-postgres | 0.29 | 145 | 0.3 | 12 |
| k6-run-5c3b8c8900f5 | 0.14 | 783 | 70.4 | 12 |
| praxedo-keycloak | 0.01 | 806 | 0.0 | 12 |
| praxedo-grafana | 0.00 | 129 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 46 | 0.0 | 12 |

### Palier 150 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.42 | 1260 | 36.4 | 12 |
| praxedo-objectstore | 0.96 | 1329 | 147.0 | 12 |
| praxedo-backend | 0.86 | 590 | 266.8 | 12 |
| praxedo-postgres | 0.24 | 154 | 0.3 | 12 |
| k6-run-5c3b8c8900f5 | 0.17 | 1188 | 83.7 | 12 |
| praxedo-keycloak | 0.01 | 807 | 0.0 | 12 |
| praxedo-grafana | 0.00 | 130 | 0.0 | 12 |
| praxedo-prometheus | 0.00 | 46 | 0.1 | 12 |

