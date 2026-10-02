# 001 — Révision : réduction de l'outillage et analyse par problématiques

- **Date** : 2026-09-20
- **Outil** : Claude (Opus 5) via Claude Code
- **Objectif** : corriger le sur-outillage de la première analyse, produire le
  chapitre base de données manquant, et restructurer au format
  problème → question → réponse.
- **Phase du projet** : cadrage (phase 0)

---

## Prompt (critique du porteur du projet)

> Ta première analyse sur-outille le projet. Reprends-la avec ces exigences :
>
> - **Sobriété** : chaque composant doit répondre à une exigence nommée de
>   l'énoncé. RabbitMQ et Resilience4j, en particulier, doivent justifier leur
>   présence face à ce qui existe déjà ; si un broker se justifie, compare-le
>   à Kafka.
> - **Persistance** : le modèle de données manque. Où les fichiers sont-ils
>   enregistrés, et comment leur état d'analyse est-il tracé ?
> - **Antivirus** : le moteur importe peu ; ce qui compte, c'est la
>   délégation par API.
> - **Périmètre** : un périmètre restreint, parfaitement exécuté, plutôt
>   qu'une couverture exhaustive — cela reste un exercice.
> - **Séparation des sujets** : pour chaque API, ce qui peut arriver, et le
>   schéma de données associé.
> - **Comparatif** : RabbitMQ, Kafka, Redis et les alternatives, sur des
>   propriétés vérifiables.
> - **Contrainte Java** : l'énoncé impose l'écosystème Java, or S3 n'est pas
>   du Java. Examine la lecture « aucune dépendance externe » et ce qu'elle
>   impliquerait (virtual threads, Kafka, WebFlux pour le pipeline).
> - **Format** : problématique → question → réponse, avec plusieurs
>   architectures, leur raison d'être et leur objectif.
> - **Attribution** : ChatGPT produira une contre-analyse ; signe la tienne.

---

## Ce que j'ai accepté de la critique

| Point | Correction apportée |
|---|---|
| **Sur-outillage** | De 7 composants d'infrastructure à **3** au palier 1. Règle écrite : un composant n'entre que s'il couvre une exigence que rien d'existant ne couvre |
| **Base de données absente** | Document dédié `23-modele-de-donnees.md` : DDL complet, index partiels, 3 requêtes critiques, contraintes `CHECK` portant l'invariant |
| **RabbitMQ contesté** | Retiré. Remplacé par la file en base — **et il s'avère que c'est techniquement supérieur ici**, pas seulement plus simple |
| **Resilience4j contesté** | Retiré : la bibliothèque est **redondante**, 3 de ses 4 fonctions existent déjà dans la file |
| **L'outil n'est pas le sujet** | Entièrement d'accord. Ce qui compte est la **délégation** (le port), pas le taux de détection du moteur |
| **Périmètre restreint** | Formalisé en 3 paliers, avec la distinction découpe **verticale** (acceptable) / **horizontale** (inacceptable) |
| **Séparation des concerns** | Le catalogue est organisé par concern, pas par ordre d'implémentation |
| **Comparatif produits** | Document dédié, par catégorie, avec propriétés vérifiables |
| **Format problème → réponse** | Appliqué, avec une rubrique supplémentaire : le **seuil de bascule** |
| **Attribution** | Bloc de signature sur chaque document + protocole `CONFRONTATION.md` |

### Découverte issue de la critique

En cherchant à supprimer MinIO, j'ai réalisé que **l'argument d'isolation
physique — le plus fort du projet — ne nécessitait pas de stockage objet** :
deux volumes Docker montés différemment produisent la même propriété. Ma
première analyse considérait MinIO comme nécessaire à cet argument. C'était
une erreur, et c'est la critique sur l'outillage qui l'a fait apparaître.

---

## Ce que j'ai rejeté ou nuancé, et pourquoi

| Point | Ma position |
|---|---|
| **Lecture « aucune dépendance externe »** | **Non.** L'énoncé impose lui-même un antivirus externe via API, et il n'existe pas de moteur antivirus sérieux en Java. La lecture littérale s'auto-contredit. Le critère « écrit en Java » est par ailleurs incohérent : il autoriserait Kafka et Elasticsearch mais interdirait PostgreSQL et ClamAV. **L'intuition reste bonne** : c'est une exigence de sobriété, pas d'absence de dépendances — et je l'ai adoptée comme règle |
| **WebFlux pour le pipeline** | **Non.** Virtual threads et WebFlux sont deux réponses au **même** problème, pas des compléments. Les combiner ajoute la complexité du réactif à un modèle qui n'en a plus besoin. WebFlux imposerait par ailleurs R2DBC et un client antivirus réactif : le coût dépasse largement la couche web |
| **Kafka** | **Non pour ce projet** — mais pour une raison technique, pas de préférence : `max.poll.interval.ms` (5 min par défaut) est incompatible avec des traitements à durée variable pouvant dépasser cette valeur, et le blocage en tête de partition fait qu'un fichier de 5 Go bloque tous les fichiers derrière lui. **Recommandation stratégique** : la maîtrise de Kafka se démontre mieux en expliquant pourquoi ses garanties ne conviennent pas ici qu'en le câblant |
| **RabbitMQ « moins bon que Kafka »** | Nuance : RabbitMQ est techniquement **mieux adapté** au job dispatch (acquittement par message, `prefetch=1`, DLX). Mais l'argument de maîtrise l'emporte, et de toute façon aucun broker n'est retenu au palier 1 |

---

## Vérifications effectuées

- Langages d'implémentation des produits comparés, pour répondre à la question
  « écosystème Java » : Kafka (Java/Scala), Artemis (Java), H2/HSQLDB (Java),
  PostgreSQL (C), RabbitMQ (Erlang), Redis (C), MinIO (Go), ClamAV (C).
  → Constat : le critère sélectionne sur une propriété sans rapport avec
  l'ingénierie. H2 est en Java mais ne supporte pas `SKIP LOCKED`.
- Environnement : JDK 21.0.11, Node 22.16.0, Docker 29.8.0, Maven absent.

### Points à vérifier empiriquement avant implémentation

- [ ] `StreamMaxLength` réel de l'image ClamAV retenue, et comportement exact
      au dépassement (valeur par défaut usuelle : 25 Mo — **non vérifiée**)
- [ ] Durée réelle du premier démarrage de ClamAV (téléchargement des
      signatures) et forme du `healthcheck`
- [ ] Faisabilité du streaming multipart sans `MultipartFile` sur Spring Boot
      3.5 / Tomcat 11
- [ ] Comportement de `FOR UPDATE SKIP LOCKED` sous forte concurrence sur
      PostgreSQL 16
- [ ] Limite réelle de `bytea` (1 Go annoncée) et comportement mémoire du
      pilote JDBC — sert à justifier le rejet du stockage en base

---

## Décisions restées humaines

Aucun arbitrage n'a été acté. Les 17 décisions du registre
[`../12-decisions-ouvertes.md`](../12-decisions-ouvertes.md) restent ouvertes,
plusieurs étant désormais éclairées par la série 20. Les recommandations de ce
tour sont soumises à une **contre-analyse ChatGPT** avant toute décision —
protocole dans [`../CONFRONTATION.md`](../CONFRONTATION.md), qui contient
notamment une liste de **désaccords que j'anticipe**, pour permettre de juger
si la contre-analyse est réellement indépendante.
