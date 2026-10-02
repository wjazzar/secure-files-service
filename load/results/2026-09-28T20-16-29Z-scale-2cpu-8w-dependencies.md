Relevé `docker stats` par palier. CPU en cœurs (1,00 = un cœur plein) ; réseau = entrée + sortie du conteneur, en Mo/s (10⁶ octets).

### Palier 100 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 2.60 | 2111 | 56.5 | 12 |
| praxedo-objectstore | 1.97 | 1237 | 226.2 | 12 |
| praxedo-backend | 1.34 | 591 | 334.0 | 12 |
| praxedo-postgres | 0.32 | 152 | 0.3 | 12 |
| k6-run-87551312c776 | 0.12 | 212 | 56.4 | 12 |
| praxedo-prometheus | 0.01 | 46 | 0.1 | 12 |
| praxedo-grafana | 0.00 | 129 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 851 | 0.0 | 12 |

### Palier 130 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 2.75 | 1231 | 73.8 | 12 |
| praxedo-objectstore | 2.54 | 1357 | 294.2 | 12 |
| praxedo-backend | 1.59 | 592 | 443.1 | 12 |
| praxedo-postgres | 0.46 | 173 | 0.3 | 12 |
| k6-run-87551312c776 | 0.18 | 224 | 73.2 | 12 |
| praxedo-prometheus | 0.01 | 60 | 0.1 | 12 |
| praxedo-keycloak | 0.01 | 852 | 0.0 | 12 |
| praxedo-grafana | 0.00 | 131 | 0.0 | 12 |

### Palier 160 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 3.19 | 1282 | 91.1 | 12 |
| praxedo-objectstore | 2.91 | 1450 | 361.2 | 12 |
| praxedo-backend | 1.85 | 595 | 542.8 | 12 |
| praxedo-postgres | 0.55 | 199 | 0.4 | 12 |
| k6-run-87551312c776 | 0.22 | 242 | 92.8 | 12 |
| praxedo-prometheus | 0.01 | 67 | 0.0 | 12 |
| praxedo-grafana | 0.00 | 133 | 0.0 | 12 |
| praxedo-keycloak | 0.00 | 850 | 0.0 | 12 |

### Palier 200 fichiers/s

| Conteneur | CPU moyen (cœurs) | Mémoire max (Mio) | Réseau (Mo/s) | Échantillons |
|---|---:|---:|---:|---:|
| praxedo-antivirus | 1.51 | 1300 | 27.3 | 12 |
| praxedo-objectstore | 1.35 | 1435 | 123.4 | 12 |
| praxedo-backend | 1.00 | 601 | 205.1 | 12 |
| praxedo-postgres | 0.26 | 263 | 0.1 | 12 |
| k6-run-87551312c776 | 0.25 | 839 | 54.8 | 12 |
| praxedo-keycloak | 0.01 | 857 | 0.0 | 12 |
| praxedo-prometheus | 0.01 | 69 | 0.0 | 12 |
| praxedo-grafana | 0.00 | 134 | 0.0 | 12 |

