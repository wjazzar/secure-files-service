# Protocole de confrontation des analyses

> **Auteur de toutes les analyses numérotées `20-` à `25-` : Claude (Opus 5).**
> Produites le 2026-09-20. Les recommandations engagent cette analyse, pas le
> porteur du projet.

Ce projet fait l'objet d'une **double analyse indépendante** : une par Claude
(ce dépôt), une par ChatGPT, fusionnées par le porteur du projet. Ce document
fixe les règles qui ont rendu la fusion exploitable, et garde le tableau de
confrontation ; la colonne « Décision finale » et le statut des faits à
vérifier ont été complétés le 02/10 à partir du registre
([`12`](12-decisions-ouvertes.md)) et des ADR.

---

## 1. Convention d'attribution

Tout document d'analyse porte en tête un bloc d'attribution :

```markdown
> **Analyse : Claude (Opus 5)** — AAAA-MM-JJ — confrontation : `CONFRONTATION.md`
```

Règles :
- Un document **ne mélange jamais** deux sources. Une contre-analyse ChatGPT
  va dans un fichier distinct (`docs/chatgpt/NN-*.md`), jamais en édition d'un
  document Claude.
- La synthèse fusionnée va dans des fichiers **distincts**, qui deviennent la
  source de vérité : le registre des décisions
  ([`12`](12-decisions-ouvertes.md)) et les ADR.
- Les ADR (`docs/adr/`) ne sont écrits **qu'après** fusion, et signés par le
  porteur du projet. Ce sont des décisions humaines, pas des recommandations
  d'assistant.

---

## 2. Pourquoi cette séparation compte

Le livrable inclut le journal des prompts (`EX-13`). Un journal qui montre
**deux analyses indépendantes, leurs désaccords, et l'arbitrage humain** dit
bien davantage qu'un journal d'acceptation linéaire.

La manière dont l'IA a été utilisée se démontre par la trace, pas par la
déclaration.

---

## 3. Tableau de confrontation

Les colonnes d'écart sont celles où se concentre la valeur de la discussion.

**État** : contre-analyse ChatGPT reçue le 2026-09-21 (`docs/chatgpt/`).
Réponse Claude : [`26-reponse-a-la-contre-analyse.md`](26-reponse-a-la-contre-analyse.md).
La colonne « Décision finale » rapporte ce que le porteur du projet a acté
(registre clos le 01/10) et ce qui est livré.

| Réf. | Sujet | Recommandation Claude | Verdict ChatGPT | Position Claude après confrontation | Décision finale |
|---|---|---|---|---|---|
| P-08 | Où stocker les octets | Système de fichiers (palier 1), port vers S3 | D'accord ; mono-hôte à annoncer, MinIO à réévaluer | **Accord.** Ne pas qualifier le compose de « HA » | Stockage objet compatible S3 dès le départ (cadrage du 21/09), SeaweedFS — `D-01` |
| P-14 | Transport des travaux | Pas de broker : file en base | D'accord, plus strict : retirer Kafka même du palier 3 | **Accord** | Aucun broker — `D-03`, ADR-0001 |
| P-18 | Antivirus | ClamAV via wrapper REST + stub | ClamAV oui, wrapper non : `clamd INSTREAM` officiel | **Accord** — le risque de bufferisation du wrapper contredit le streaming | **API HTTP** (décision du 26/09) : l'énoncé dit « via une API ». Le chemin d'analyse de l'enrobage a été lu avant adoption : il relaie en flux — ADR-0004 |
| P-19 | Fichiers > limite AV | `UNSCANNABLE` | **Désaccord** : `413` si connu avant scan | **Concession partielle** : défaut `413`, politique configurable, à préciser au cadrage | `413` au-delà de 500 Mo — `D-15` |
| P-21 | Retry / circuit breaker | Aucune bibliothèque | D'accord, mais le bulkhead exige un `Semaphore` | **Concession** : mon argument « le pool est le bulkhead » était faux | Aucune bibliothèque ; `Semaphore` sur les dépôts, nombre fixe de boucles pour les analyses — ADR-0010 |
| P-29 | Concurrence web | MVC + virtual threads | D'accord ; pas d'incompatibilité technique avec WebFlux | **Concession de formulation**, conclusion inchangée | MVC + threads virtuels — ADR-0010 |
| P-11 | Isolation physique | 2 conteneurs, montages différenciés | **Impossible** : l'upload doit écrire la quarantaine | **Concession intégrale** — défaut réel. 3 rôles | Trois identités de stockage dans un seul processus — `D-02`, `D-18`, ADR-0002 |
| A-xx | Architecture | A2 → A3 | A2-R ; ce n'est pas une échelle mais 4 axes | **Concession** sur les axes ; trajectoire conservée comme récit | A2-R, un ADR par décision |
| — | Contrainte « écosystème Java » | Applicative + sobriété | D'accord | Accord des deux analyses | Confirmé par le cadrage : tout ajout est libre mais justifié |
| — | `CHECK` SQL | Présenté comme garantie absolue | **Contournable par `NULL`** | **Concession intégrale** — bug réel | Prédicats totaux, testés avec des `NULL` |
| — | Promotion | Non spécifiée | Copie vérifiée + renommage local + CAS | **Concession** — lacune réelle | Relecture vérifiée et écriture sous la clé finale, sans renommage, puis CAS — ADR-0006 |
| — | `CLEAN` | État final servable | `CLEAN` = verdict, `AVAILABLE` = état | **Concession** — correction élégante | `AVAILABLE`, `PROMOTING` — `D-19` |
| — | `SCAN_FAILED` | Un seul état | Boucle infinie ; scinder `RETRY_WAIT`/`FAILED_FINAL` | **Concession** — bug réel | `RETRY_WAIT` / `FAILED_FINAL` |
| — | Estimation palier 1 | 2 jours | Non crédible ; 4 à 6 jours | **Concession partielle** : ~3,5 j après retraits | — |
| — | Périmètre corrigé | — | 19 décisions à valider avant tout code | **Désaccord** : leur palier 1 est plus gros que le mien ; triage en [`26`](26-reponse-a-la-contre-analyse.md) §5 | Registre `D-01` à `D-19`, clos le 01/10 ; ce que le triage est devenu : [`26`](26-reponse-a-la-contre-analyse.md) §9 |

### Faits avancés par la contre-analyse, et leur vérification

| Fait | Source revendiquée | Statut le 21/09 | Vérification | Matérialité |
|---|---|---|---|---|
| ClamAV `StreamMaxLength` = 100 MiB (et non 25) | `clamd.conf.sample` amont | ChatGPT mieux sourcé que Claude | **Exact** pour la version 1.4. L'image livrée règle la valeur elle-même (2000M) | **Forte** (`D-15`) |
| `AlertExceedsMax` désactivé par défaut | idem | À confirmer | **Exact** : forcé à `yes` par l'image dérivée | **Forte** |
| Dépôt communautaire MinIO archivé | GitHub | Non corroboré par Claude | **Exact** (25 avril 2026) : SeaweedFS retenu | Forte |
| AWS S3 : 50 To par objet | doc AWS | Non corroboré par Claude (limite connue : 5 To) | **Exact** depuis décembre 2025 | Nulle |
| H2 supporte `SKIP LOCKED` | doc H2 | Incertain des deux côtés | Non vérifié : H2 n'est pas utilisé | Nulle |
| Spring Boot 4.1.1 stable | doc Spring | Plausible | Adopté | Moyenne |

---

## 4. Comment arbitrer un désaccord

Un désaccord entre deux analyses n'est utile que s'il est **réduit à un
critère falsifiable**. Grille à appliquer :

| Étape | Question à poser aux deux analyses |
|---|---|
| 1 | **Sur quel fait** repose la recommandation ? (une valeur par défaut, une limite documentée, un comportement d'outil) |
| 2 | Ce fait est-il **vérifiable** ? Si oui, le vérifier — c'est ce qui tranche, pas l'argumentation |
| 3 | Si le désaccord n'est pas factuel, **quel critère** est optimisé ? (temps de développement, robustesse, clarté de la démonstration, maîtrise personnelle) |
| 4 | Ce critère est-il le bon **pour cet exercice** ? |
| 5 | Quel **seuil** ferait basculer la réponse ? Si aucune des deux analyses ne sait le nommer, aucune n'a compris le problème |

> ⚠️ Piège à éviter : la fusion par moyenne. Prendre « un peu des deux »
> produit souvent une architecture incohérente. Sur un arbitrage structurant,
> **choisir une des deux et assumer**, ou identifier la troisième option que
> ni l'une ni l'autre n'avait vue.

---

## 5. Points sur lesquels je m'attends à un désaccord

Prédictions explicites — elles permettront de juger si la contre-analyse est
indépendante ou simplement convergente par conformisme.

| Sujet | Ma position | Position probable d'une contre-analyse | Ce qui tranche |
|---|---|---|---|
| **Broker** | Aucun broker : file en base | Kafka ou RabbitMQ, « c'est l'état de l'art » | Le volume réel. À quelques milliers de fichiers/jour, un broker est de la complexité non amortie. Demander le seuil chiffré |
| **Bibliothèque de résilience** | Aucune : la file porte retries et backoff | Resilience4j, « c'est le standard » | Compter les lignes de code économisées. Si la file a déjà `attempts` et `next_attempt_at`, la lib est redondante sur ce chemin |
| **WebFlux** | Non — MVC + virtual threads | Possible « oui pour le streaming » | JDBC est bloquant ; R2DBC + SDK réactif changent le coût total. Demander le plan complet, pas juste la couche web |
| **Stockage** | Système de fichiers au palier 1 | MinIO/S3 d'emblée | La démonstration d'isolation est-elle possible sans MinIO ? (oui, par montages Docker — cf. P-11) |
| **Contrainte « Java »** | Contrainte applicative | Peut-être lue comme « minimiser les dépendances » | L'énoncé impose un antivirus externe via API : la lecture littérale s'auto-contredit |
| **Périmètre** | 3 paliers, livrer le palier complet atteint | Souvent : tout faire | Le temps disponible. Un palier complet bat deux paliers à moitié |

---

## 6. Ce que je considère comme non négociable

Indépendamment de la contre-analyse, ces points relèvent de la correction, pas
du goût. Un désaccord ici doit être traité comme une erreur à corriger, pas
comme une préférence à arbitrer :

1. Le scan est **asynchrone** (un scan synchrone dans le thread HTTP ne tient
   ni la charge ni les gros fichiers).
2. Le contenu n'est **jamais** chargé intégralement en mémoire.
3. `SCAN_FAILED` (panne) et `INFECTED` (verdict) sont **deux états distincts**.
4. Un fichier trop gros pour l'antivirus n'est **jamais** `CLEAN`.
5. Le découpage d'un fichier en morceaux pour contourner la limite de taille de
   l'antivirus est **faux** (une signature peut chevaucher deux morceaux).
6. L'écriture du contenu précède le commit des métadonnées (orphelin invisible
   plutôt que référence brisée).
7. Réutiliser un verdict `CLEAN` sans contrôler la version de la base de
   signatures est une **faille**.
8. La clé de stockage n'est **jamais** dérivée du nom de fichier fourni.
