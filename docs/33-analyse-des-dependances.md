# 33 — Analyse des dépendances et des images

> **Analyse ponctuelle du 01/10/2026**, faite à la place d'une chaîne
> d'analyse continue. Décision du porteur du projet : pas de CI/CD pour un
> exercice. On analyse une fois, on corrige ce qui doit l'être, on épingle des
> versions stables, et on écrit ce qu'il reste. Répond au constat S-11 de
> l'[audit](32-audit-de-securite.md).

---

## 1. Méthode

| Périmètre | Outil | Ce qui est analysé |
|---|---|---|
| Service (Java) | **Trivy 0.75.0**, base Java du 01/10 | Le JAR exécutable : toutes les bibliothèques réellement embarquées, transitives comprises |
| Interface (npm) | **Trivy** et **`npm audit`** | `package-lock.json` : 771 paquets, dont 57 de production |
| Images | **Trivy**, base du 01/10 | Chaque image de `docker-compose.yml`, et les images de base des deux images construites |

Les bases OSV, NVD et Sonatype OSS Index n'étaient pas joignables depuis
l'environnement d'analyse. Trivy agrège les mêmes sources (NVD, GitHub
Advisories, distributions) : le résultat ne s'en trouve pas appauvri.

Ce qui n'a **pas** été fait, et pourquoi : pas d'analyse statique du code
(CodeQL), pas de détection de secrets dans l'historique (gitleaks). Ce sont
des outils de chaîne continue, hors du périmètre retenu.

---

## 2. Résultats

### 2.1 Le service — corrigé

| Bibliothèque | Version (BOM Spring Boot 4.1.1) | Failles | Correction |
|---|---|---|---|
| Tomcat (`tomcat-embed-core`) | 11.0.24 | **3 critiques** : contournement d'une contrainte de sécurité, contournement d'authentification par rejeu, accès par l'authentification par formulaire (CVE-2026-65182, -65905, -68525) | **11.0.26** |
| Jackson (`jackson-core`, `jackson-databind`) | 3.1.5 | **5 hautes**, 2 moyennes, dont deux dénis de service sur l'analyse JSON | **3.1.7** |

Spring Boot 4.1.1 est la dernière version stable (la 4.2 n'existe qu'en
milestones) : les deux versions sont surchargées dans `backend/pom.xml`, sur la
**même ligne**, correctif seulement. La surcharge se retire dès qu'un Spring
Boot 4.1.x les embarque.

**Après correction : 0 vulnérabilité** (§2.4).

### 2.2 L'interface — rien à corriger

**0 vulnérabilité**, en production comme en développement : Trivy et
`npm audit` concordent.

### 2.3 Les images

| Image | Avant | Après | Lecture |
|---|---|---|---|
| `eclipse-temurin:21-jre-alpine` (base du service) | 0 | 0 | — |
| `chrislusf/seaweedfs:4.47` | 0 critique, 1 haute | inchangé | — |
| `ajilaag/clamav-rest:0.6.6` (base de l'antivirus) | 0 critique, 28 hautes | inchangé | Paquets du système de l'image amont |
| `postgres:17.11-alpine` | 1 critique, 21 hautes | inchangé | La critique est dans `gosu`, l'outil qui change d'utilisateur au démarrage, pas dans le moteur. Dernier correctif de la ligne 17 |
| `prom/prometheus` | v3.5.0 : 4 critiques, 92 hautes | **v3.5.5 : 0 critique, 48 hautes** | Dernier correctif de la ligne LTS 3.5 |
| `grafana/grafana` | 12.1.1 : 14 critiques, 142 hautes | **12.1.10 : 5 critiques, 128 hautes** | Dernier correctif de la ligne 12.1, dont la faille de Grafana lui-même (CVE-2025-41115) |
| `keycloak/keycloak:26.4.7` | 7 critiques, 79 hautes | inchangé | Netty, Bouncy Castle, FreeMarker. 26.4.7 est le dernier correctif de la ligne 26.4, qui n'en recevra plus |
| `amazon/aws-cli:2.32.9` | 0 critique, 202 hautes | inchangé | Conteneur éphémère : il crée les deux zones de stockage et s'arrête |

**Comment le lire.** Tout ce qui reste est de l'**outillage de
développement local** : fournisseur d'identité, supervision, outil de
création des zones. Aucun de ces services n'est atteignable hors du poste,
puisque tous les ports sont publiés sur `127.0.0.1` seulement (audit S-09).
Le service lui-même, lui, est corrigé.

**Ce qui le ferait reprendre** : toute exposition hors du poste. Il faudrait
alors monter Keycloak sur sa ligne courante (le realm s'importe à l'identique,
mais le parcours de connexion se revérifie), Grafana sur une ligne maintenue,
et remplacer `aws-cli` par la création des zones au démarrage du stockage.

### 2.4 Contre-analyse du service corrigé

Le JAR reconstruit embarque `tomcat-embed-core-11.0.26` et
`jackson-databind-3.1.7`. Trivy, même base : **0 vulnérabilité**, toutes
gravités confondues. La suite complète passait avec ces versions le 01/10
(497 tests et les 2 tests mémoire) ; elle passe toujours le 02/10, après la
revue du code (517 tests et les 2 tests mémoire, `./mvnw clean verify`).

---

## 3. Versions : toutes épinglées, toutes stables

| Où | Règle | État |
|---|---|---|
| Images (`docker-compose.yml`, Dockerfiles, tests) | Version exacte **et** empreinte : une image ne change jamais sans commit | ✅ toutes, y compris `postgres`, `keycloak` et `aws-cli`, qui ne l'étaient pas |
| Maven (`backend/pom.xml`) | Versions explicites, aucune version flottante, aucune pré-version | ✅ (seule la version du projet lui-même est un `SNAPSHOT`) |
| npm (`package-lock.json`) | Versions exactes, installées par `npm ci` | ✅ aucune pré-version, à une exception transitive près (`gensync 1.0.0-beta.2`, outil de Babel, dont c'est l'unique version publiée) |
| Keycloak | Image officielle, prise sur Docker Hub plutôt que `quay.io` : même éditeur, et son empreinte a pu être vérifiée | ✅ |

**React Router (question du porteur du projet)** : la ligne 7 est maintenue.
La 7.18.4 est sortie le 15/09/2026, le même jour que la 8.4.0, et npm publie
une étiquette dédiée (`version-7`). Elle est donc stable et à jour. La 8 exige
Node ≥ 22.22, sans rien apporter à l'application : **on garde la 7**.

---

## 4. Refaire l'analyse

```bash
# Bases de vulnérabilités, une fois
docker run --rm -v trivy-cache:/root/.cache aquasec/trivy:0.75.0 image --download-db-only
docker run --rm -v trivy-cache:/root/.cache aquasec/trivy:0.75.0 image --download-java-db-only

# Le service : le JAR exécutable (après ./mvnw package)
docker run --rm -v trivy-cache:/root/.cache -v "$PWD/backend/target:/jar:ro" \
  aquasec/trivy:0.75.0 rootfs --scanners vuln /jar/securefiles-0.1.0-SNAPSHOT.jar

# L'interface
(cd frontend && npm audit)

# Une image
docker run --rm -v trivy-cache:/root/.cache -v /var/run/docker.sock:/var/run/docker.sock \
  aquasec/trivy:0.75.0 image --scanners vuln postgres:17.11-alpine
```
