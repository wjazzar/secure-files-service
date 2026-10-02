# 000 — Cadrage initial : architecture, contraintes, questions

- **Date** : 2026-09-20
- **Outil** : Claude (Opus 5) via Claude Code
- **Objectif** : produire une première analyse d'architecture avant toute
  ligne de code — identifier l'invariant central, les axes non fonctionnels,
  les angles morts, et les points de cadrage à préciser.
- **Phase du projet** : cadrage (phase 0)

---

## Prompt

> Nous démarrons un nouveau projet : un test technique pour un poste de
> développeur senior. Je te transmets l'énoncé complet. Avant toute ligne de
> code, je veux mener avec toi une réflexion d'architecture, étape par étape :
>
> 1. l'invariant central du service, et tout ce qui pourrait le violer ;
> 2. les exigences non fonctionnelles : haute disponibilité, idempotence,
>    résilience, sécurité, fichiers de tailles très variables ;
> 3. les architectures possibles, avec leurs compromis ;
> 4. les angles morts de l'énoncé, et les points à faire préciser.
>
> Le niveau attendu est celui d'un code de production : chaque choix doit
> pouvoir être défendu.
>
> *(suivi de l'énoncé intégral du test — voir [`../00-enonce.md`](../00-enonce.md))*

Prompt de suite :

> Avant d'aller plus loin, écris ce contexte dans le dépôt : un `AGENTS.md`
> qui résume la nature du projet et les règles de travail, puis un document
> par sujet, dont les architectures proposées. Nous les reprendrons point par
> point.

---

## Cadrage explicite donné à l'assistant

Points de méthode imposés en amont, qui ont structuré la réponse :

1. **Réflexion avant code** — aucune implémentation tant que les arbitrages ne
   sont pas tranchés.
2. **Axes de qualité nommés explicitement** : HA, idempotence, standards de
   qualité. Cela a orienté la réponse vers les propriétés non fonctionnelles
   plutôt que vers les fonctionnalités.
3. **Nature de l'exercice précisée** (test technique) — détermine que la
   documentation et l'argumentation ont autant de valeur que le code.
4. **Discussion itérative demandée** — d'où la structuration en un document
   par sujet et un registre de décisions numérotées, plutôt qu'un document
   monolithique.

---

## Ce que j'ai retenu

| Apport | Pourquoi retenu |
|---|---|
| Formulation de l'invariant en termes d'**octets** et de **contenu**, pas de « fichier » | Ferme les failles TOCTOU et impose l'immutabilité |
| Distinction **`SCAN_FAILED` / `INFECTED` / `UNSCANNABLE`** | Trois causes de non-service radicalement différentes, souvent confondues |
| **Idempotence décomposée en 5 dimensions** | Correspond à la demande explicite ; évite le mot-valise |
| **Limite de taille de ClamAV** (`StreamMaxLength` = 25 Mo par défaut) | Tension directe avec « tailles très variables » de l'énoncé — angle mort majeur |
| **Confidentialité vis-à-vis de l'antivirus tiers** | Argument de sécurité rarement soulevé, à fort impact |
| Découplage des **trois disponibilités** (ingérer / analyser / servir) | Traduit « HA » en propriété démontrable plutôt qu'en slogan |
| **Outbox + reaper** | Répond au vrai mode de panne (perte d'événement), pas au cas nominal |
| Pattern **claim atomique par `UPDATE` conditionnel** | Verrou distribué sans infrastructure de verrouillage |
| Structuration en **registre de décisions numérotées** | Permet la discussion incrémentale demandée |

---

## Ce que j'ai rejeté ou corrigé, et pourquoi

| Rejeté | Raison |
|---|---|
| **Découpage des gros fichiers en morceaux pour contourner la limite AV** | Une signature peut chevaucher deux morceaux → faux sentiment de sécurité, pire que l'absence de solution. Retenu à la place : un état `UNSCANNABLE` explicite |
| **Kafka** | Le besoin est du *job dispatch*, pas du flux rejouable. Le surdimensionner serait une erreur |
| **WebFlux / modèle réactif** | Charge I/O-bound ; les virtual threads de Java 21 donnent le même résultat sans le coût de débogage et de maintenance |
| **Redis** (cache / verrou distribué) | Postgres couvre les deux besoins ; dépendance non justifiée |
| **Keycloak** dans le compose | Monte une infrastructure d'identité pour un sujet qui n'est pas l'identité |
| **Déduplication de stockage** par empreinte | Canal auxiliaire inter-tenant (test d'existence par temps de réponse) + complexité de comptage de références |
| **Réutilisation inconditionnelle d'un verdict par empreinte** | Faille réelle : un fichier déclaré sain avant publication de sa signature resterait servi indéfiniment. Corrigé par la contrainte de version de base de signatures |
| **`MultipartFile` de Spring** | Bufferise sur disque au-delà d'un seuil → I/O doublées, incompatible avec l'exigence de tailles variables |
| **Décision prise par l'assistant sur les arbitrages structurants** | Les quatre arbitrages proposés en question directe ont été écartés par moi à ce stade : la décision reste humaine et sera prise après discussion point par point |

---

## Vérifications effectuées

- Environnement de développement vérifié en ligne de commande : JDK 21.0.11,
  Node 22.16.0, Docker 29.8.0, Git 2.47.1, **Maven absent** → décision
  d'utiliser le Maven Wrapper.
- Limites ClamAV (`StreamMaxLength`, `MaxFileSize`, `MaxScanSize`,
  `MaxRecursion`, `MaxFiles`) : **à re-vérifier dans la documentation officielle
  et empiriquement sur l'image Docker retenue** avant de figer `D-15`. Les
  valeurs citées dans la documentation de cadrage sont les valeurs usuelles par
  défaut et doivent être confirmées.

### Points à vérifier avant implémentation

- [ ] Valeurs par défaut réelles de l'image ClamAV retenue, et comportement
      exact au dépassement (`INSTREAM size limit exceeded`)
- [ ] Comportement de `S3TransferManager` sur un `InputStream` de taille
      inconnue (absence de `Content-Length`)
- [ ] Faisabilité du streaming multipart sans `MultipartFile` sous Spring Boot
      3.5 / Tomcat 11
- [ ] Support de `SKIP LOCKED` et comportement sous forte concurrence sur
      PostgreSQL 16

---

## Décisions restées humaines

Les dix-sept arbitrages du registre
[`../12-decisions-ouvertes.md`](../12-decisions-ouvertes.md) sont **tous
ouverts** à la date de cette entrée. L'assistant a produit des options
argumentées avec une recommandation ; aucune décision n'a été actée.
