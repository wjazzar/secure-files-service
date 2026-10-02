# 004 — Recadrage : un exercice à borner, pas un besoin métier à découvrir

- **Date** : 2026-09-21
- **Outil** : Claude (Opus 5) via Claude Code
- **Objectif** : corriger la lecture de l'énoncé, qui le traitait comme un
  projet client, et ne garder comme points ouverts que ceux qui peuvent
  invalider le travail
- **Phase du projet** : cadrage

---

## Prompt

> Ton analyse traite l'énoncé comme un projet client. C'est un test technique :
> il n'y a pas de besoin métier à découvrir, seulement un périmètre à borner.
> Reprends-la en conséquence :
>
> - **Retire les questions métier** (but de l'application, utilisateurs
>   finaux, priorités) : que les utilisateurs soient internes ou externes, le
>   travail est le même.
> - **Le moteur antivirus est un choix libre** : l'énoncé demande un
>   antivirus accessible par une API, sans en nommer aucun.
> - **L'environnement d'exécution et la stratégie de test relèvent du
>   développeur** : ce ne sont pas des points à faire préciser.
> - **L'authentification** peut être omise dans un exercice, à condition de
>   l'écrire explicitement et de dire ce qu'elle coûterait.
> - **Ne garde comme points ouverts que ceux qui peuvent invalider le
>   travail**, par exemple une contrainte « uniquement Java » non écrite dans
>   l'énoncé. Pour tout le reste, pose une hypothèse et documente-la.
>
> Enfin, résume ce cadre dans `AGENTS.md`, pour qu'il ne se perde pas d'une
> session à l'autre.

---

## L'erreur de fond

J'avais lu l'énoncé comme l'expression d'un **besoin métier** à explorer. Les
points ouverts que j'en tirais (but de l'application, utilisateurs finaux,
priorités) en découlaient tous.

La nature réelle : **un test technique pour un poste de développeur senior**.
Il n'y a rien à découvrir sur le domaine ; il y a un périmètre à borner et des
choix à justifier.

---

## Corrections

| Point | Correction |
|---|---|
| « L'antivirus est-il imposé ? » | **Sans objet.** L'énoncé demande « un antivirus disponible via une API » sans en nommer aucun : le choix est libre. Corrigé aussi dans `docs/00-enonce.md` |
| Points métier (but, utilisateurs, priorités) | Retirés : ils ne changent pas le travail |
| Authentification | Non demandée ; son absence éventuelle devient une hypothèse documentée |
| Fonctionnement sans Docker | Retiré : l'environnement de test appartient au développeur |
| Nombre de points ouverts | De neuf à **trois** : portée de la contrainte technique, taille maximale des fichiers, ordre de grandeur de la concurrence |

---

## Ce que j'ai produit

1. **`AGENTS.md`** : une section « Nature du projet » qui fixe ce qu'est le
   projet, qui décide quoi, et la règle « une hypothèse écrite plutôt qu'une
   question ouverte ».
2. **Les trois points réellement ouverts**, chacun avec la raison pour
   laquelle il peut invalider le travail ; les autres deviennent des
   hypothèses documentées.

---

## Ce que j'ai maintenu, et pourquoi

| Point | Position |
|---|---|
| **Faire préciser les points ouverts** | Oui : l'énoncé y invite, et le risque d'une contrainte non écrite (« uniquement Java ») est réel. Mais trois points, pas neuf |
| **Documenter l'absence d'authentification** | D'accord pour ne pas l'imposer, mais le README doit porter la réponse : c'est l'une des premières questions que pose un service de fichiers |

---

## Vérification factuelle

L'hypothèse « un autre antivirus n'aurait pas la limite de taille de ClamAV »
mérite d'être vérifiée, mais **la tendance est inverse** : les API antivirus
hébergées sont généralement **plus** contraintes qu'un moteur auto-hébergé
dont on règle soi-même les plafonds (VirusTotal : 32 Mo en gratuit ; services
commerciaux : typiquement 100 à 250 Mo). La limite est inhérente : un moteur
doit disposer de l'artefact complet pour décompresser et analyser.

→ À confirmer par le spike antivirus.

---

## Décisions restées humaines

Le choix des trois points à faire préciser, et la suite donnée à
l'authentification (finalement livrée et toujours exigée : ADR-0014).
