# 09 — Stratégie de test

> **Stratégie écrite avant le code** (série 00), **relue contre la suite de
> tests livrée le 02/10**. Les tests cités existent ; ce qui était prévu et n'a
> pas été fait est dit au §8. La matrice détaillée fait foi dans
> [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §14.

Principe : **l'invariant de sécurité n'est crédible que s'il est prouvé.**
La cible n'est pas un pourcentage de couverture, mais un jeu de tests dont on
peut dire, ligne par ligne, quel risque il couvre.

---

## 1. Pyramide

```
        ╱╲     charge         — campagnes k6, hors de la suite (load/, docs/capacity-planning)
       ╱──╲
      ╱    ╲   intégration    — Testcontainers : PostgreSQL, SeaweedFS, ClamAV réels ;
     ╱──────╲                   la vraie pile HTTP sur un port aléatoire
    ╱        ╲ unitaires      — domaine et cas d'usage, sans Spring : automate,
   ╱──────────╲                 règles, pannes injectées par des ports en mémoire
```

Plus une catégorie transverse : **tests d'architecture** (ArchUnit), qui
tiennent les frontières sur la durée.

Tout se lance par `./mvnw verify` (back-end) et `npm test` (interface). Il n'y
a **pas d'intégration continue** : exercice, décision du porteur du projet
(01/10).

---

## 2. Tests unitaires — le domaine et les cas d'usage, sans Spring

Le paquet `domain` n'a aucune dépendance : ses tests sont instantanés, sans
contexte à démarrer.

| Cible | Cas | Test |
|---|---|---|
| Automate d'états | Les transitions légales aboutissent ; les illégales lèvent `IllegalTransitionException` | `StoredFileTest` |
| Refus par défaut | **Pour chaque valeur de l'énumération**, `isDownloadable()` et la projection publique sont classés | `FileStatusTest`, `PublicStatusTest` |
| Verdict | Un verdict sain sans version de signatures est refusé ; un verdict rendu pour une autre empreinte n'atteste rien | `ScanVerdictTest` |
| Nettoyage du nom | Traversée de chemin, caractères de contrôle, marques bidirectionnelles, longueur | `FileNameTest` |
| Type de contenu | Détection sur les premiers octets, quel que soit le nom | `ContentSnifferTest` |
| Dépôt | L'objet est écrit avant la ligne ; corps plus court ou plus long qu'annoncé ; corps trop lent ; idempotence | `UploadFileServiceTest` |
| Analyse | Verdicts et pannes scriptés ; rien n'est réclamé quand l'antivirus est indisponible | `FileScanServiceTest` |
| Promotion | Les cinq points de panne | `FilePromotionServiceTest` |
| Balayage | Orphelins et sources supprimés, tout le reste gardé | `QuarantineSweeperTest` |

Ce qui était prévu et n'a plus d'objet : les conditions de réutilisation d'un
verdict (`D-08`, reportée) et la décision `UNSCANNABLE` sur
`capabilities().maxSize` (méthode retirée : un fichier trop gros est refusé à
la réception, `413`).

---

## 3. Tests d'intégration — Testcontainers

Conteneurs réels, pas de bouchon d'infrastructure : c'est ce qui donne leur
valeur à ces tests.

| Test | Ce qu'il prouve |
|---|---|
| **Parcours nominal** (`ScanPipelineTest`) : dépôt → analyse par le **vrai** ClamAV → promotion ; la copie servable est octet pour octet le fichier déposé | `EX-01`, `EX-03`, `EX-04` de bout en bout |
| **Parcours infecté, EICAR** (`ScanPipelineTest`) : `INFECTED`, jamais copié dans la zone servable | **Le test central de l'énoncé** — déterministe et sans danger |
| **Limites du moteur** (`AntivirusEngineLimitsTest`) : entrée d'archive de 600 Mo lue jusqu'au bout ; au-delà du volume analysé, non analysable ; angle mort zip64 caractérisé | ADR-0005 |
| **Lecture de la quarantaine** avec l'identité `delivery` → refusée par le stockage (`ObjectStorageTest`) | La ligne de défense que le code ne peut pas contourner |
| **Fichier de 500 Mo** : dépôt, promotion et téléchargement dans une JVM plafonnée à 256 Mo (`UploadMemoryTest`, `DownloadMemoryTest`) | `EX-07` — le test échoue si un tampon apparaît |
| **Contraintes SQL** : une ligne `AVAILABLE` avec chaque champ de verdict à `NULL` est refusée (`StoredFileConstraintsTest`) | Les prédicats sont totaux |
| **Claim concurrent**, verdict au jeton périmé, *reaper*, arrêt propre, pool de la file (`FilePersistenceTest`) | [`04`](04-idempotence.md) §3 |
| **Idempotence** : huit réservations concurrentes, une seule accordée ; purge (`JdbcIdempotencyStoreTest`) | [`04`](04-idempotence.md) §1 |
| **Journal d'audit** en ajout seul, attribution au *reaper* (`AuditTrailTest`) | ADR-0009 |
| **Rôles de la base** : le service ne peut ni modifier le schéma ni réécrire le journal (`DatabaseRolesTest`) | Audit S-03 |
| **Cloisonnement** : un utilisateur ne voit ni ne télécharge le fichier d'un autre, `404` (`OidcSecurityTest`, `DownloadApiTest`) | `D-11`, [`06`](06-securite-et-angles-morts.md) §5 |
| **Session du navigateur** : connexion, CSRF, revalidation, déconnexion (`BrowserSessionTest`) | ADR-0012 |
| **Conformité au contrat** : statuts, codes d'erreur, tris et champs sont ceux d'`openapi.yaml` (`ContractConformanceTest`) | Règle `B-12` |
| **Métriques et sondes** (`ObservabilityTest`) | [`05`](05-ha-et-resilience.md) §6 |

Toute la suite passe par un fournisseur d'identité simulé qui signe de vrais
jetons (`TestIdentityProvider`) : l'authentification n'est jamais coupée, même
en test.

### Le fichier EICAR
Chaîne standard de 68 octets, inoffensive, détectée comme menace par tous les
moteurs. Elle rend le chemin « infecté » testable **de façon déterministe et
sans manipuler de véritable malware**.

> La chaîne n'est pas écrite en clair dans un fichier du dépôt : l'antivirus du
> poste de qui clone le dépôt pourrait le mettre en quarantaine. Elle est
> **assemblée à l'exécution**.

---

## 4. Tests de la dépendance antivirus — WireMock

Le vrai ClamAV teste le chemin nominal. WireMock teste **les modes de panne**,
impossibles à provoquer de façon fiable avec un vrai service
(`HttpAntivirusScannerTest`) :

| Simulation | Comportement vérifié |
|---|---|
| Moteur plus lent que le délai de lecture | Une panne, pas un blocage |
| Moteur absent | Indisponible ; le portillon de santé le dit |
| `413` | Une **panne**, jamais un verdict |
| `412` | `UNSCANNABLE` |
| `406` avec `Heuristics.Limits.Exceeded` | `UNSCANNABLE` — ni menace ni, surtout, sain |
| `406` au corps illisible | Enregistré comme infecté : la direction prudente |
| Version de signatures absente | Aucun verdict sain ne peut être enregistré |

Côté cas d'usage (`FileScanServiceTest`, moteur scripté) : une panne mène à
`RETRY_WAIT` puis à `FAILED_FINAL`, et un antivirus indisponible ne consomme
**aucune** tentative. Antivirus arrêté pour de bon, le dépôt et le
téléchargement des fichiers déjà disponibles continuent :
`scripts/demo.sh --resilience` le rejoue contre l'environnement complet.

Il n'y a pas de coupe-circuit à tester : le portillon de santé en tient lieu
([`05`](05-ha-et-resilience.md) §3).

---

## 5. Tests de sécurité (tentatives d'attaque)

Écrits comme des tentatives explicites de violer l'invariant :

| Tentative | Attendu | Test |
|---|---|---|
| Télécharger dans chaque état non servable | `409`, avec le code de l'état | `DownloadApiTest` |
| Nom `../../etc/passwd` | Clé de stockage = UUID ; le chemin ne survit pas | `UploadApiTest`, `UploadFileServiceTest` |
| Type déclaré par le client | Ignoré : seuls les octets parlent | `UploadApiTest` |
| `Content-Length` mensonger | Refusé, rien n'est gardé | `UploadApiTest` |
| 600 Mo annoncés | `413` avant de lire un octet du corps | `UploadApiTest` |
| Bombe de décompression | `UNSCANNABLE`, jamais disponible | `AntivirusEngineLimitsTest` |
| Fichier d'un autre utilisateur | `404`, comme un fichier inconnu | `OidcSecurityTest`, `DownloadApiTest` |
| Requête sans identité, jeton forgé ou périmé | `401` | `OidcSecurityTest` |
| Écriture avec le cookie de session, sans jeton CSRF | `403 CSRF_TOKEN_INVALID` | `BrowserSessionTest` |
| Corps envoyé au compte-gouttes | `408` à l'échéance | `UploadFileServiceTest`, `DeadlineInputStreamTest` |
| Dépôts au-delà des bornes d'admission | `429`, avant la lecture du corps | `UploadAdmissionTest` |

---

## 6. Tests d'architecture — ArchUnit

La règle est exécutable, pas seulement écrite. Le back-end est **un seul
module** : ces règles ne doublent pas le classpath, elles le remplacent.

| Suite | Ce qu'elle tient |
|---|---|
| `LayeringRulesTest` | Dépendances vers l'intérieur (`config → infrastructure → application → domain`) ; `domain` et `application` sans aucun framework ; `org.springframework.jdbc` réservé à la persistance ; `java.io.File` et `java.nio.file` interdits ; pas de `ByteArray…Stream` dans le cœur |
| `HexagonalArchitectureTest` | Entrée par les ports d'entrée, sortie par les ports de sortie, adaptateurs indépendants, câblage dans `config` seulement |
| `CodeLayoutRulesTest` | Le rangement par concept puis par nature |

---

## 7. Performance et charge

Des campagnes **k6** en conteneur, hors de la suite de tests, pilotées par
`load/run-capacity.mjs` ; résultats archivés dans `load/results/` et commentés
dans [`capacity-planning/`](capacity-planning/README.md). Le plan avait été
écrit avant le code
([`31`](31-plan-de-tests-charge-et-resilience.md)).

- Paliers de dépôts à débit contrôlé, verdict « tient / ne tient pas » par
  palier, lu dans Prometheus.
- Métriques observées : retard de la file (`praxedo.queue.depth`,
  `praxedo.queue.oldest_pending_age`), lag par fichier
  (`praxedo.pipeline.lag`), refus d'admission.

> Même un résultat modeste, **mesuré et commenté**, vaut mieux qu'une
> affirmation non étayée. Les mesures ont d'ailleurs contredit plusieurs
> suppositions (README §9, « Ce que les mesures ont changé »).

---

## 8. Ce qui est volontairement **non** testé

- L'exactitude de détection de ClamAV : c'est sa responsabilité. Son angle
  mort connu (zip64) est caractérisé par un test, pas corrigé.
- L'interface dans un vrai navigateur : pas de tests Playwright (décision du
  01/10). Elle est testée avec Vitest et Testing Library contre une API
  simulée (MSW), elle-même tenue au contrat par un test de dérive.
- Les tests de mutation.
- La montée en charge à plusieurs nœuds : pensée, non mesurée (README §10).

Prévu dans la première version de ce document, et sans objet dans le système
livré : le test de l'outbox (pas de broker), celui de l'URL présignée (le
contenu est servi par le service), ceux du coupe-circuit.

---

## 9. Qualité, sans intégration continue

La première version prévoyait une chaîne d'intégration continue avec un seuil
de couverture (JaCoCo) sur le domaine. **Non livrée** : les vérifications
tournent sur le poste.

| Vérification | Commande | Outil |
|---|---|---|
| Compilation, tests unitaires, d'intégration et d'architecture | `./mvnw verify` | Maven, JUnit 5, Testcontainers, ArchUnit, WireMock |
| Preuve mémoire | incluse dans `./mvnw verify`, exécution dédiée à `-Xmx256m` | Surefire, étiquette `memory` |
| Interface : types, style, tests | `npm run typecheck`, `npm run lint`, `npm test` | TypeScript, ESLint, Vitest |
| Environnement complet | `scripts/check`, `scripts/demo.sh` | Docker Compose |
| Vulnérabilités des dépendances | analyse ponctuelle du 01/10 ([`33`](33-analyse-des-dependances.md)) | Trivy, `npm audit` |

Il n'y a pas de seuil de couverture : chaque test prouve une propriété nommée,
la couverture est un sous-produit.
