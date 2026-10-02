# 24 — La contrainte « écosystème Java » et le niveau de dépendances

> **Analyse : Claude (Opus 5)** — 2026-09-20 — confrontation : [`CONFRONTATION.md`](CONFRONTATION.md)
>
> **Analyse écrite avant le code, gardée telle qu'elle a été rendue.** La
> lecture de la contrainte (L2 + discipline L3) et la règle de sobriété sont
> restées ; la liste des composants a changé avec le cadrage du 21/09. Les
> paragraphes **« Livré »**, relus contre le code le 02/10, disent ce qu'il en
> est.

Question soulevée par le porteur du projet :

> *L'énoncé impose l'écosystème Java, or S3 n'est pas du Java, et plusieurs
> composants envisagés ne le sont pas non plus. Faut-il lire la contrainte
> comme « aucune dépendance externe » ?*

C'est une question légitime et elle mérite d'être tranchée **avant** de choisir
quoi que ce soit, parce qu'elle conditionne tout le reste.

---

## 1. Les trois lectures possibles de la contrainte

Verbatim de l'énoncé :
> « L'application doit impérativement s'inscrire dans un écosystème
> Java / Spring Boot / React. »

| Lecture | Énoncé | Conséquence |
|---|---|---|
| **L1 — Littérale** | Tout composant du système doit être écrit en Java | Interdit PostgreSQL (C), ClamAV (C), MinIO (Go), Redis (C), nginx (C) |
| **L2 — Applicative** | *Le code que j'écris* est en Java/Spring Boot, avec un front React | Autorise toute infrastructure, comme n'importe quelle application Java en production |
| **L3 — Sobriété** | Java/Spring/React, et le moins de dépendances externes possible | Autorise l'infrastructure mais impose de justifier chaque ajout |

---

## 2. Pourquoi L1 est réfutée par l'énoncé lui-même

C'est l'argument décisif, et il est vérifiable :

> « déléguer leur analyse à **un antivirus disponible via une API** »

**Il n'existe pas de moteur antivirus sérieux écrit en Java.** ClamAV est en C,
les moteurs commerciaux sont en C/C++. L'énoncé impose donc explicitement une
dépendance à un composant non-Java, joignable par le réseau. La lecture
littérale s'auto-contredit dans la phrase même qui décrit le livrable.

Par ailleurs, le mot **« déléguer »** est un terme d'architecture : il désigne
le fait de confier une responsabilité à un composant externe. L'énoncé ne
demande pas d'éviter les dépendances, il en **prescrit une**.

### Le critère « écrit en Java » est de toute façon incohérent

Si l'on appliquait L1 sérieusement, on obtiendrait un classement absurde :

| Composant | Langage | Admis sous L1 ? |
|---|---|---|
| **Kafka** | Java + Scala, tourne sur JVM | ✅ oui |
| **ActiveMQ Artemis** | Java | ✅ oui |
| **H2 / HSQLDB** | Java | ✅ oui |
| **Elasticsearch** | Java | ✅ oui |
| PostgreSQL | C | ❌ non |
| RabbitMQ | Erlang | ❌ non |
| Redis | C | ❌ non |
| MinIO | Go | ❌ non |
| **ClamAV** | C | ❌ non — **alors que l'énoncé l'impose** |

Un critère qui autorise Elasticsearch mais interdit PostgreSQL ne décrit aucune
propriété d'ingénierie utile. Il décrit un détail d'implémentation des outils,
pas une contrainte d'architecture.

> **Conclusion** : L1 est écartée. Mais l'intuition derrière la question reste
> bonne, et c'est L3 qui la porte.

---

## 3. La lecture retenue : L2 + discipline L3

**L2 est la lecture correcte** : la contrainte porte sur la stack applicative
(le code produit, le framework, le front). C'est ainsi que la phrase serait
comprise par n'importe quel architecte : elle signifie « nous sommes une
maison Java/Spring/React, ne nous livrez pas du Go ou du .NET ».

**Mais L3 est la bonne discipline à s'imposer**, pour trois raisons propres à
cet exercice :

1. **Chaque composant ajouté est un composant à défendre.** Si
   je ne peux pas justifier RabbitMQ en une phrase avec un seuil chiffré, sa
   présence me dessert au lieu de me servir.
2. **Chaque composant ajouté est du temps non investi dans la qualité.**
   Le budget est fini ; un composant coûte sa configuration, son
   `docker-compose`, ses tests d'intégration et sa documentation.
3. **Chaque composant ajouté est un risque au démarrage.** Si
   `docker compose up` échoue sur une machine à cause d'un broker mal
   configuré, le reste ne sera pas lu.

### Règle opérationnelle adoptée

> **Un composant externe n'entre dans le projet que s'il répond à une exigence
> nommée de l'énoncé que rien de déjà présent ne couvre.**
> À défaut, il va dans « pistes d'amélioration » avec le seuil qui le
> déclencherait.

---

## 4. Application de la règle

| Composant | Exigence servie | Déjà couvert par autre chose ? | Verdict |
|---|---|---|---|
| **Spring Boot** | `EX-09` | — | ✅ imposé |
| **React** | `EX-09` | — | ✅ imposé |
| **Une base relationnelle** | `EX-03` (état de scan, atomicité, unicité) | Non — rien d'autre ne donne des transactions | ✅ **indispensable** |
| **Un antivirus externe** | `EX-08` | Non — imposé par l'énoncé | ✅ **indispensable** |
| **Un stockage de contenu** | `EX-01`, `EX-07` | Le système de fichiers suffit au palier 1 | ✅ mais **pas forcément S3** |
| ~~Broker de messages~~ | `EX-06` | **Oui** : la base fait file d'attente (`SKIP LOCKED`) | ❌ **retiré** — cf. [P-14](20-catalogue-problematiques.md#p-14) |
| ~~MinIO / S3~~ | isolation, scalabilité | Partiellement : montages Docker au palier 1 | 🟠 **palier 2** — cf. [P-08](20-catalogue-problematiques.md#p-08) |
| ~~Resilience4j~~ | `EX-08` (résilience) | **Oui** : la file porte `attempts` et `next_attempt_at` | ❌ **retiré** — cf. [P-21](20-catalogue-problematiques.md#p-21) |
| ~~Redis~~ | cache, verrou | **Oui** : la base couvre les deux | ❌ retiré |
| ~~Keycloak~~ | authentification | Oui : JWT validé par clé de test | ❌ retiré |
| Apache Tika | sécurité (type réel) | Non, mais remplaçable par ~30 lignes de détection par nombres magiques | 🟠 optionnel |

**Bilan** : de 7 composants d'infrastructure dans la première version de
l'analyse, on passe à **3 au palier 1** (application + base + antivirus, plus
le front). C'est le point de ton retour que je retiens entièrement : la
première proposition était sur-outillée.

**Livré** — La règle a été appliquée, et trois verdicts du tableau ont changé :

| Composant | Verdict du 20/09 | Livré | Ce qui l'a fait entrer ou sortir |
|---|---|---|---|
| Stockage objet compatible S3 | 🟠 palier 2 | ✅ **dès le départ** (SeaweedFS, et non MinIO) | Cadrage du 21/09 : un stockage partagé de type objet ([`28`](28-precisions-de-cadrage.md) §3) |
| Keycloak | ❌ retiré | ✅ **livré, toujours exigé** | Décision du porteur du projet ([ADR-0014](adr/0014-authentification-toujours-exigee.md)) ; le cadrage ne l'exigeait pas |
| Apache Tika | 🟠 optionnel | ❌ non retenu | Une quarantaine de lignes de détection suffisent pour une donnée descriptive |
| Prometheus, Grafana | — | ✅ supervision | Lire la capacité mesurée ([`capacity-planning/`](capacity-planning/README.md)) |

Le broker, la bibliothèque de résilience et Redis sont restés dehors. Le
système livré compte donc : application, PostgreSQL, stockage objet, antivirus,
Keycloak et l'interface.

---

## 5. Le cas particulier du stockage — la question de fond

C'est ici que la question « est-ce vraiment Java ? » a le plus de mordant,
parce qu'il existe une vraie alternative sans composant supplémentaire.

Trois façons de conserver les octets, analysées en détail en
[P-08](20-catalogue-problematiques.md#p-08) et
[`22-comparatif-produits.md`](22-comparatif-produits.md) §2 :

| Option | Composant ajouté | Streaming | Isolation démontrable | Scalabilité multi-nœuds |
|---|---|---|---|---|
| **Système de fichiers** (volumes Docker) | **aucun** | ✅ natif JDK | ✅ par montages séparés | ❌ nécessite NFS |
| Base (`bytea` / Large Object) | aucun | 🟠 Large Object seulement | 🟠 faible | ✅ mais coût élevé |
| Stockage objet (MinIO/S3) | 1 | ✅ | ✅ par IAM | ✅ |

**Point important** : l'argument d'isolation physique — le plus fort de tout le
projet — **ne nécessite pas MinIO**. Deux conteneurs issus de la même image,
avec des montages de volumes différents, produisent la même démonstration :

```yaml
api:      # sert les fichiers
  volumes:
    - servable:/data/servable:ro        # lecture seule
    # la quarantaine n'est PAS montée → inaccessible, quoi que fasse le code
worker:   # analyse les fichiers
  volumes:
    - quarantine:/data/quarantine
    - servable:/data/servable
```

Le conteneur qui sert les fichiers **n'a pas la quarantaine dans son système
de fichiers**. Un bug applicatif ne peut pas la lire : le chemin n'existe pas.
C'est exactement la même propriété qu'une politique IAM S3, obtenue avec zéro
composant supplémentaire, et elle est plus facile à démontrer
(`docker exec api ls /data/quarantine` → « No such file or directory »).

> C'est le genre d'arbitrage que je n'avais pas vu dans la première version :
> j'avais supposé que l'isolation exigeait S3. Elle n'exige que **deux
> périmètres d'accès distincts**, et Docker en fournit un.

**Livré** — Le raisonnement tient, mais ce n'est pas la solution livrée : le
cadrage du 21/09 a demandé un stockage objet, et un seul processus porte
le dépôt, l'analyse et la livraison
([ADR-0002](adr/0002-un-processus-trois-identites.md)). Il n'y a ni volume de
quarantaine ni second conteneur : l'isolation est tenue par **trois identités
de stockage**, celle qui sert les fichiers n'ayant aucun droit sur la
quarantaine.

---

## 6. Sur « virtual threads + Kafka + WebFlux »

La proposition évoquée était : virtual threads, Kafka et WebFlux pour la
gestion du pipeline. Analyse honnête des trois, séparément :

| Élément | Verdict | Raison courte |
|---|---|---|
| **Virtual threads** | ✅ **oui, franchement** | Charge I/O-bound, gain réel, zéro complexité ajoutée, Java 21 déjà installé |
| **Kafka** | ❌ **non pour ce projet** | Ses garanties ne correspondent pas à du *job dispatch* à durée variable — détail complet en [P-14](20-catalogue-problematiques.md#p-14) et [`22-comparatif-produits.md`](22-comparatif-produits.md) §1 |
| **WebFlux** | ❌ **non** | Incompatible avec virtual threads dans le même flux, et impose R2DBC + client AV réactif pour produire un gain — détail en [P-29](20-catalogue-problematiques.md#p-29) |

Point technique à souligner : **virtual threads et WebFlux sont deux réponses
au même problème**, pas des compléments. Les combiner n'additionne pas leurs
bénéfices ; cela ajoute la complexité du réactif à un modèle qui n'en a plus
besoin. Choisir les deux serait le signe qu'on n'a pas compris ce que Loom
rend obsolète — c'est précisément le genre de point qu'un architecte teste.

---

## 7. Réponse synthétique à la question posée

> *Attendent-ils un code sans dépendance ?*

**Non.** Mais ils attendent probablement que chaque dépendance soit
justifiable, et la première version de mon analyse ne passait pas ce test.

La formulation retenue pour le README :

> « J'ai lu la contrainte "écosystème Java/Spring Boot/React" comme portant sur
> la stack applicative, pas sur l'infrastructure — l'énoncé impose lui-même un
> antivirus externe, qui n'existe pas en Java. J'ai en revanche appliqué une
> règle de sobriété : un composant d'infrastructure n'entre que s'il répond à
> une exigence que rien d'existant ne couvre. C'est ce qui m'a conduit à
> écarter le broker de messages — la base de données fait file d'attente de
> façon transactionnelle et suffisante à cette échelle — et à écarter une
> bibliothèque de résilience, dont la file porte déjà les fonctions. Le
> système tourne avec trois composants. Voici le seuil à partir duquel j'en
> ajouterais un quatrième. »

**Livré** — Le README (§8.1) reprend la règle de sobriété, avec la liste réelle
des composants. La formulation ci-dessus, écrite le 20/09, n'y figure pas
telle quelle : elle annonce « trois composants », le système livré en compte
davantage (stockage objet et Keycloak, voir §4).
