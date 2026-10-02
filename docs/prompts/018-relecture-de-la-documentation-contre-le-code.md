# 018 — Relecture de toute la documentation contre le code

- **Date** : 2026-10-02
- **Outil** : Claude Opus 5.5 (Claude Code)
- **Objectif** : qu'aucun document du dépôt ne dise autre chose que ce que le
  code fait ; puis une seconde vérification complète
- **Phase du projet** : livraison — documentation (racine, back, front, contrat, infrastructure, `docs/`)

## Prompts

> En lisant le code, je vois des choses qui ne sont pas correctes dans la
> documentation. Deux exemples : le README renvoie à « ARCHITECTURE §13 », que
> je ne trouve pas ; `docs/06` annonce une détection du type par Apache Tika,
> qui n'est pas implémentée. Revérifie toutes les docs : il ne doit y figurer
> aucune erreur. Une fois terminé, revérifie une seconde fois — double
> validation.

## Le raisonnement

| Étape | Position | Ce qui l'a fait évoluer |
|---|---|---|
| 1. La vérité de référence | Le code, lu en entier (domaine, cas d'usage, adaptateurs, SQL, configuration, interface), et non les autres documents | Deux documents faux qui se citent l'un l'autre restent cohérents |
| 2. Les contrôles mécaniques | Scripts jetables : liens et ancres, renvois « §N », ancres de ligne vers le code, chemins et identifiants cités | Ils trouvent ce qui est cassé, pas ce qui est faux : la lecture reste nécessaire |
| 3. Les documents d'exploitation | README, guide de démarrage, `backend/`, `contracts/`, `infra/`, `frontend/` : corrigés en place, phrase par phrase | — |
| 4. Les analyses écrites avant le code | Deux traitements. Série 00 : réécrite, chaque section dit ce qui est livré et ce qui a été écarté. Série 20, signée et datée : gardée telle quelle, chaque recommandation suivie d'un paragraphe « Livré » | Réécrire une analyse signée effacerait le raisonnement et l'écart avec la décision, qui font partie du livrable |
| 5. Les faits extérieurs | Vérifiés à la source plutôt que recopiés : valeurs par défaut de ClamAV 1.4, archivage de MinIO, taille maximale d'un objet S3, RFC 10017 | Deux d'entre eux étaient faux ou périmés dans les analyses |
| 6. La seconde passe | Contrôles mécaniques rejoués, valeurs de configuration recherchées dans tous les documents, termes hérités de l'analyse revus un par un, suites de tests relancées | — |

## Ce que j'ai retenu

| Correction | Raison |
|---|---|
| Les renvois « ARCHITECTURE §N » du README nomment `backend/ARCHITECTURE` | Le §13 existe, mais dans `backend/ARCHITECTURE.md` : le libellé, placé à côté d'un lien vers `docs/06`, laissait croire le contraire |
| `docs/06` : Tika remplacé par ce qui est livré (un renifleur interne de quarante lignes), et tout le document relu de la même façon | Le type n'est qu'une donnée descriptive : une dépendance ne se justifiait pas |
| Idempotence décrite telle qu'elle est : empreinte sur le nom et la taille, rejeu qui rend le fichier dans son état courant | La documentation décrivait encore l'instantané de réponse et une empreinte incluant le contenu |
| Port de management (8091) partout où l'actuator est cité | Un document disait encore qu'il partageait le port de l'API |
| Automate, sondes, métriques, bail proportionnel, périodes de balayage, deux bornes d'admission, `408` | Valeurs et noms repris de `application.yml` et du code |
| `frontend/ARCHITECTURE.md` : sections 0 à 13 réécrites sur le code livré | Elles décrivaient encore le client public `keycloak-js`, le jeton `Bearer` dans le navigateur et des composants supprimés |
| Série 20 et `docs/31` : un paragraphe « Livré » par problématique, par architecture, par palier, par fiche de test | Le lecteur voit la recommandation du 20/09 et ce qu'elle est devenue |
| `CONFRONTATION.md` : décision finale et vérification des faits renseignées | Le tableau annonçait une colonne « à remplir » alors que le registre est clos |
| Valeurs par défaut de ClamAV corrigées (100 Mo, et non 25) | Vérifiées dans le `clamd.conf.sample` de la version 1.4 |
| Capacité d'un nœud de 1 CPU reformulée | « 85 à 100 fichiers/s » ne correspondait pas aux rapports bruts : 80 tenus, 100 au genou |
| Scripts de démarrage : comptes de démonstration renvoyés au fichier du realm | Ils annonçaient des comptes qui n'existent plus dans le realm |

## Ce que la relecture a trouvé dans le code

| Constat | Preuve | Suite |
|---|---|---|
| Une **archive chiffrée est déclarée saine** : `AlertEncryptedArchive` n'est pas activé dans l'image, et l'adaptateur ne classe « non analysable » que `Heuristics.Limits.Exceeded` | Mesuré contre le conteneur : `200` pour une archive dont l'entrée est marquée chiffrée ; `406` pour EICAR dans la même session | Documenté comme limite connue (README §10, `docs/06` §2) ; la correction du code reste à décider |
| Le motif `ENCRYPTED_ARCHIVE` est publié au contrat mais jamais émis | Même mesure ; contraire à la règle appliquée en 1.10 (« un code publié doit pouvoir être reçu ») | Noté au contrat comme réservé |
| Deux Javadoc citaient l'ADR-0008, remplacé | Lecture | Corrigé (ADR-0014) |
| Commentaires de deux migrations devenus faux (`anonymous`, lien signé) | Lecture | Laissés : modifier une migration appliquée change son empreinte Flyway |

## Ce que j'ai rejeté ou laissé, et pourquoi

| Point | Pourquoi |
|---|---|
| Réécrire la série 20 comme la série 00 | Analyse signée et datée, confrontée à une contre-analyse : la garder et l'annoter montre l'écart entre recommandation et décision |
| Modifier `docs/prompts/` et `docs/chatgpt/` | Ce sont des traces ; une trace corrigée après coup n'en est plus une |
| Recaler les ancres de ligne de l'audit (`docs/32`) | Elles décrivent le code au commit audité ; un bandeau le dit |
| Réécrire l'ADR-0011, qui cite encore l'adaptateur de signature | Un ADR accepté ne se modifie pas ; l'ADR-0013 dit que le signataire est retiré |
| Corriger le code pour les archives chiffrées dans cette relecture | Change le comportement de l'antivirus et demande un test contre le vrai moteur : décision du porteur du projet |

Une exception à la règle des ADR : l'en-tête de l'ADR-0004 renvoyait à la
décision `D-15` ; le registre le rattache à `D-01`. Référence corrigée, texte
de la décision inchangé.

## Vérifications effectuées

- Liens et ancres Markdown : 0 cassé. Renvois « §N » : 153 vérifiés, 0
  manquant. Ancres de ligne vers le code : 237, toutes sur une ligne existante.
- Références internes du contrat (`$ref`) : 68, toutes résolues.
- Back-end : `./mvnw clean verify` — 517 tests et les 2 tests mémoire. Un
  premier lancement sans `clean` échouait : un ancien fichier de migration
  resté dans `target/` donnait deux versions 8 à Flyway.
- Front-end : `npm test` — 159 tests, dont le test de dérive du contrat.
- Antivirus : conteneur démarré seul, archive marquée chiffrée et EICAR
  envoyés par l'API ; EICAR assemblé à la volée, jamais écrit sur le disque.
- Faits extérieurs vérifiés à la source : `clamd.conf.sample` de ClamAV 1.4,
  archivage du dépôt MinIO (25 avril 2026), objets S3 de 50 To (décembre
  2025), RFC 10017 (août 2026).
