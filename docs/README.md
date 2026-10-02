# Documentation de cadrage

> **Comment lire ces documents.** Ce dossier garde l'**analyse** du projet,
> écrite pour l'essentiel **avant le code**. Ce que le système fait réellement
> est décrit ailleurs : [`README`](../README.md),
> [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md),
> [`frontend/ARCHITECTURE.md`](../frontend/ARCHITECTURE.md),
> [`contracts/`](../contracts/README.md) et les [ADR](adr/README.md).
>
> Tous les documents ont été **relus contre le code le 02/10** :
>
> - la **série 00** a été mise à jour : chaque section dit ce qui est livré et
>   ce qui a été écarté ;
> - la **série 20** est signée et datée (*Analyse : Claude (Opus 5)*) : elle
>   est gardée telle qu'elle a été rendue, et chaque recommandation est suivie
>   d'un paragraphe **« Livré »** qui fait foi en cas d'écart ;
> - la contre-analyse indépendante ([`chatgpt/`](chatgpt/)) et le journal des
>   prompts ([`prompts/`](prompts/)) sont des traces : ils ne sont pas
>   modifiés. Protocole : [`CONFRONTATION.md`](CONFRONTATION.md).

---

## Série 20 — Analyse par problématiques

Rédigée au format **problème → question → options → réponse → seuil de
bascule**, à la demande du porteur du projet.

| # | Document | Contenu |
|---|---|---|
| **20** | [Catalogue des problématiques](20-catalogue-problematiques.md) | **33 problématiques** classées par concern (ingestion, persistance, déclenchement, analyse, service, transverse), chacune avec ce qui est livré |
| **21** | [Architectures candidates](21-architectures-candidates.md) | **A0 → A4** : cinq architectures, ce qu'elles garantissent, coûtent, et les seuils de bascule. Livré : A2 avec le stockage objet d'A3, sans broker, dans un seul processus |
| **22** | [Comparatif des produits](22-comparatif-produits.md) | Kafka / RabbitMQ / Redis / Artemis / Postgres · FS / BLOB / S3 · Postgres / MySQL / H2 / Mongo · ClamAV / VirusTotal / stub · Resilience4j / rien · MVC+Loom / WebFlux |
| **23** | [Modèle de données](23-modele-de-donnees.md) | ⚠️ **remplacé par [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §6** (schéma livré). Gardé pour l'historique : DDL initial, index partiels, requêtes critiques |
| **24** | [Contrainte « écosystème Java »](24-contrainte-java-et-dependances.md) | « S3 n'est pas du Java » : les 3 lectures possibles, laquelle tient, et la règle de sobriété qui en découle |
| **25** | [Paliers de périmètre](25-paliers-de-perimetre.md) | 3 paliers, découpe verticale, règle d'arrêt — et, élément par élément, ce qui a été livré |
| **26** | [Réponse à la contre-analyse](26-reponse-a-la-contre-analyse.md) | 6 défauts concédés, 6 points contestés, **triage des corrections par coût**, et ce que chaque correction est devenue |
| **28** | [Précisions de cadrage](28-precisions-de-cadrage.md) | ⭐ **Référence de cadrage** : les sept points précisés le 21/09, et les décisions qui en découlent (stockage objet, 500 Mo, aucun broker) |
| **31** | [Plan de tests charge et résilience](31-plan-de-tests-charge-et-resilience.md) | ⭐ **Écrit avant le code** : 6 affirmations à prouver, SLO chiffrés, 8 exigences de testabilité, ~35 tests, méthode de calcul de capacité. Le §13 dit ce qui a été exécuté |
| **32** | [Audit de sécurité](32-audit-de-securite.md) | Audit du 29/09 (back, front, infrastructure) : **20 constats** (3 élevés, 8 moyens, 9 faibles), ce qui tient, **plan de correction en 6 lots** (T-01 à T-15), puis la revue du code du 01/10 (V-01 à V-10) |
| **33** | [Analyse des dépendances](33-analyse-des-dependances.md) | Analyse ponctuelle du 01/10 (pas de CI) : Trivy et `npm audit` sur le service, l'interface et chaque image ; Tomcat et Jackson corrigés ; versions épinglées par empreinte |
| **Capacité** | [Résultats de capacity planning](capacity-planning/README.md) | 130–160 fichiers/s par nœud de 2 CPU / 1 Gio avec ClamAV réel ; plafond mesuré à la validation des transactions de PostgreSQL sur un disque partagé ; dépendances, règle de dimensionnement, pistes d’amélioration |
| — | [Protocole de confrontation](CONFRONTATION.md) | Attribution, tableau de confrontation avec la décision finale, faits vérifiés |
| — | [`chatgpt/`](chatgpt/) | Contre-analyse indépendante ChatGPT (3 documents) |

### Les six conclusions de cette série, et ce qu'il en reste

1. **Aucun broker** : la base fait file d'attente de façon transactionnelle
   ([P-13](20-catalogue-problematiques.md#p-13), [P-14](20-catalogue-problematiques.md#p-14)).
   ✅ Livré.
2. **Aucune bibliothèque de résilience** : la file porte déjà délais, réessais
   et backoff ([P-21](20-catalogue-problematiques.md#p-21)). ✅ Livré ; la
   limite de concurrence est tenue par des bornes explicites, pas par la
   taille d'un pool.
3. ~~Système de fichiers au palier 1~~ → **révisé** : le cadrage du 21/09 demande un
   **stockage objet type S3 dès le départ** ([28](28-precisions-de-cadrage.md) §3).
4. **Isolation quarantaine / zone servable par identités de stockage
   distinctes** — trois, dans un seul processus ; l'argument des volumes Docker
   ([P-11](20-catalogue-problematiques.md#p-11)) ne s'applique plus.
5. **MVC + virtual threads**, pas WebFlux — ce sont deux réponses au même
   problème, pas des compléments ([P-29](20-catalogue-problematiques.md#p-29)).
   ✅ Livré.
6. **La sobriété** : un composant n'entre que s'il répond à une exigence que
   rien d'autre ne couvre. La série annonçait « 3 composants au lieu de 7 » ;
   le système livré compte l'application, PostgreSQL, le stockage objet,
   l'antivirus et Keycloak — les deux derniers ajouts venant du cadrage et
   d'une décision du porteur du projet.

---

## Série 00 — Analyse initiale *(mise à jour le 02/10)*

Écrite avant la série 20, qui a **corrigé son sur-outillage** (broker, outbox,
bibliothèque de résilience). Chaque document dit aujourd'hui ce qui est livré.

| # | Document | Contenu |
|---|---|---|
| 00 | [Énoncé](00-enonce.md) | Résumé (`D-17`) et table des exigences `EX-01…15` |
| 01 | [Analyse de l'énoncé](01-analyse-de-l-enonce.md) | Lecture ligne à ligne, les deux pièges, et ce que cette lecture est devenue |
| 02 | [Invariant et modèle de domaine](02-invariant-et-domaine.md) | L'invariant, les lignes de défense, l'automate à huit états, le modèle livré |
| 04 | [Idempotence](04-idempotence.md) | Les cinq dimensions, et ce qui est livré pour chacune |
| 05 | [HA et résilience](05-ha-et-resilience.md) | Disponibilités découplées, contre-pression, résilience sans bibliothèque, sondes, métriques |
| 06 | [Sécurité et angles morts](06-securite-et-angles-morts.md) | Limites de l'antivirus, ce qu'il ne protège pas, nom de fichier, type de contenu, authentification |
| 09 | [Stratégie de test](09-strategie-de-test.md) | Ce qui est testé, par quel test, et ce qui ne l'est pas |
| 12 | [Décisions ouvertes](12-decisions-ouvertes.md) | Registre `D-01…D-19`, **clos le 01/10** : tout est acté, sauf `D-08` reporté en piste |

---

## Répertoires annexes

- [`../contracts/`](../contracts/) — **contrat d'API** (`openapi.yaml`), source de vérité partagée par le back-end et le front-end.
- [`../frontend/AGENTS.md`](../frontend/AGENTS.md) — contexte du front-end : périmètre, 15 règles non négociables, commandes, design.
- [`../backend/AGENTS.md`](../backend/AGENTS.md) — contexte du back-end : automate, 4 protocoles critiques, base, antivirus, 12 règles non négociables, tests.

- [`adr/`](adr/) — Architecture Decision Records. **Écrits après fusion des
  deux analyses, signés par le porteur du projet.**
- [`prompts/`](prompts/) — journal des prompts (livrable `EX-13`).

---

## Comment naviguer

| Je veux… | Lire |
|---|---|
| Savoir ce que le système fait | [`README`](../README.md), [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) |
| Comprendre le raisonnement complet | [20](20-catalogue-problematiques.md) |
| Voir les architectures envisagées | [21](21-architectures-candidates.md) |
| Justifier un outil (ou son absence) | [22](22-comparatif-produits.md), [`README`](../README.md) §8 |
| Voir le schéma de base | [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §6 |
| Trancher la question « écosystème Java » | [24](24-contrainte-java-et-dependances.md) |
| Savoir ce qui a été décidé, et pourquoi | [12](12-decisions-ouvertes.md), [`adr/`](adr/README.md) |
| Voir comment les deux analyses ont été confrontées | [CONFRONTATION](CONFRONTATION.md), [26](26-reponse-a-la-contre-analyse.md) |

Les problématiques portent un identifiant (`P-01`…`P-33`), les architectures
(`A0`…`A4`), les décisions (`D-01`…`D-19`), les exigences (`EX-01`…`EX-15`).
**Référencer l'identifiant** plutôt que reparaphraser.
