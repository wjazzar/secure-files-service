# B-002 — V0 : socle, domaine, persistance (lots B1 à B3)

- **Date** : 2026-09-27
- **Outil** : Claude Opus 5 (Claude Code)
- **Objectif** : écrire la première version auditable du back-end
- **Phase du projet** : développement, lots B1 à B3

## Prompt

> Écris la V0 du back-end : le socle, le domaine et la persistance (lots B1
> à B3), un commit par lot, build vert à chaque étape. J'en ferai l'audit
> avant la suite.

## Ce qui a été livré

Trois lots, trois commits, build vert à chaque étape.

| Lot | Contenu | Tests |
|---|---|---|
| B1 | Socle Maven multi-module, Spring Boot 4.1.1, Flyway, sondes, CI, ArchUnit | 7 |
| B2 | Domaine : automate à 8 états, attestation, objets valeur | 86 |
| B3 | Schéma avec contraintes totales, trois requêtes critiques | 35 |

## Ce que j'ai retenu

**L'invariant est écrit deux fois, dans deux technologies indépendantes.** Le
record `StoredFile` refuse de construire un fichier `AVAILABLE` sans attestation
propre liée à son empreinte ; PostgreSQL refuse la même ligne par les
contraintes C1 à C5. Ni l'un ni l'autre ne connaît l'existence de la seconde
barrière. C'est l'argument central de la conception.

**La classification se fait dans le constructeur de l'énumération.** Ajouter un
neuvième état ne compile pas tant que personne n'a répondu à « celui-là,
peut-on le servir ? ». Le test exhaustif vérifie ensuite la *cohérence* de la
réponse (un état servable est terminal, un état qui détient un bail ne l'est
pas), ce que le compilateur ne sait pas faire.

**La frontière entre couches est vérifiée par le build**, pas par une revue :
le module `domain` n'a **aucune dépendance de compilation** (vérifié par
`dependency:list`), et le plugin enforcer casse la construction si Spring,
Jakarta, le SDK AWS ou JDBC y apparaissaient.

## Ce que j'ai rejeté ou corrigé, et pourquoi

| Rejeté / corrigé | Raison |
|---|---|
| **JPA, même pour les écritures simples** | Le *dirty checking* permettrait de changer un statut en affectant un champ, ce que la règle `B-6` interdit. Aucune dépendance JPA n'est présente |
| **H2 pour les tests de persistance** | Les garanties testées — `FOR UPDATE SKIP LOCKED`, index partiels, prédicats totaux — sont des comportements de PostgreSQL. Les tester ailleurs ne prouverait rien. Conteneur réel, démarré une fois pour toute la JVM |
| **`now()` dans les requêtes** | Rend l'heure de début de transaction. Tout passe par `clock_timestamp()` |
| **L'horloge applicative pour les baux** | Avec plusieurs nœuds, une horloge décalée ferait voler un bail dont le travail est encore en cours. **La base est la seule horloge** |
| **`CHECK (scan_result = 'CLEAN')`** | Vaut `UNKNOWN` sur `NULL`, et un `CHECK` accepte `UNKNOWN` : la ligne interdite passe. Réécrit en `IS NOT DISTINCT FROM` |
| **Un test de contraintes sans cas témoin** | Une suite qui n'insère que des lignes refusées passerait aussi si *toutes* les insertions échouaient. Le premier test vérifie que la ligne de référence est bien acceptée |
| **Garder `claimedBy` comme code mort** | Le claim est exécuté en SQL (atomicité oblige). Plutôt que de supprimer la transition du domaine, un test vérifie que **les deux expressions de la même règle concordent** — c'est ce qui les empêche de diverger |
| **Une méthode privée inutilisée** laissée dans l'adaptateur | Retirée : du code mort dans un livrable audité est un défaut |

## Vérifications effectuées — et ce que le build a appris

Trois découvertes viennent de l'exécution, pas de la documentation :

| Découverte | Conséquence |
|---|---|
| `spring-boot-starter-web` est **déprécié** en Boot 4.1 au profit de `-webmvc`, et Flyway a désormais son propre starter | Dépendances corrigées avant d'écrire une ligne de code métier |
| **Testcontainers 2** a renommé ses artefacts (`testcontainers-postgresql`) et ses paquets (`org.testcontainers.postgresql`), et Boot 4 ne gère plus leurs versions | BOM épinglé en 2.0.5 ; la classe n'est plus générique |
| Flyway 10+ livre le support de chaque moteur **à part** | Sans `flyway-database-postgresql`, le démarrage échoue sur « Unsupported Database ». Trouvé en lançant le premier test de contexte |

Et un vrai bug, attrapé par les tests du domaine : `ContentType` initialisait sa
constante `OCTET_STREAM` **avant** le motif qui la valide, donc la classe ne
s'initialisait pas. Un défaut d'ordre statique qui ne se voit qu'à l'exécution.

Le premier `Dockerfile` de l'antivirus (B-001) avait échoué de la même façon :
ce sont les garde-fous qui ont parlé, pas la relecture.

## Décision restée humaine

Le porteur du projet auditera cette V0 avant que les lots B4 (ingestion en flux)
et B5 (worker) ne soient écrits. Le spike de stockage objet (B0.4) reste à
mener avant l'adaptateur S3 : deux inconnues mesurables — les sommes de
contrôle du SDK AWS face à SeaweedFS, et la tenue en mémoire sur 500 Mo.
