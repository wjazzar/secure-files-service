# 31 — Plan de tests : charge, ressources, pannes

> **Analyse : Claude (Opus 5)** — 2026-09-26 — proposition soumise au porteur
> du projet.
>
> **Ce document est écrit avant le code, et c'est délibéré.** Il définit ce
> qu'on doit pouvoir affirmer à la fin, donc ce que le code doit exposer pour
> que ce soit mesurable. Un service qu'on n'a pas conçu pour être mesuré ne se
> mesure pas après coup.
>
> Document autonome : utilisable dans une session dédiée, sans autre contexte
> que `AGENTS.md`, `backend/AGENTS.md` et `contracts/openapi.yaml`.

> ⚠️ **Ce plan n'a été exécuté qu'en partie, et le système livré s'en écarte
> sur plusieurs points** (constat du 26/09, relu contre le code le 02/10).
> Le texte ci-dessous est le plan tel qu'il a été écrit ; le **§13** dit, test
> par test, ce qui a été joué en charge, ce qui est couvert par la suite de
> tests automatisés, et ce qui n'a pas été fait. Les écarts de vocabulaire :
>
> - l'antivirus est consommé par une API HTTP, plus par le protocole `clamd`
>   (ADR-0004) : le « faux `clamd` » de `TST-2` est un double **WireMock** ;
> - `TST-1` est le profil `capacity` avec `ANTIVIRUS_MODE=instant-clean`, et
>   non un profil `loadtest` ;
> - les métriques sont préfixées `praxedo.`, pas `ecluse.` ;
>   `storage.operation.duration` n'est pas livrée ;
> - la liste est paginée par numéro de page (profondeur bornée à 10 000
>   lignes), pas par curseur ;
> - il n'y a ni quota de volume (`ADM-02`), ni objet temporaire de promotion
>   (`SOAK-01`, `REC-04`), ni Toxiproxy, ni harnais de gros fichiers.
>
> Résultats mesurés : [`capacity-planning/`](capacity-planning/README.md).

---

## 1. Ce qu'on doit pouvoir affirmer à la fin

Le but n'est pas de produire des chiffres, c'est de pouvoir tenir **six
affirmations** devant un architecte, chacune adossée à une mesure :

| # | Affirmation | Test qui la prouve |
|---|---|---|
| A1 | « `POST /files` soutient **N déposants simultanés** à p95 < 300 ms d'accusé, quelle que soit la taille » | [PERF-01](#perf-01) → [PERF-04](#perf-04) |
| A2 | « La mémoire du service est **bornée** et ne dépend pas de la taille ni du nombre d'envois » | [PERF-05](#perf-05), [SOAK-01](#soak-01) |
| A3 | « La latence du dépôt est **indépendante de la durée d'analyse** » | [PERF-06](#perf-06) |
| A4 | « Les téléchargements restent servis **pendant que la file d'analyse est saturée** » | [CHAOS-01](#chaos-01), [CHAOS-03](#chaos-03) |
| A5 | « Au-delà du seuil, le service **refuse proprement** au lieu de se dégrader » | [ADM-01](#adm-01) → [ADM-03](#adm-03) |
| A6 | « **Aucun fichier non analysé n'a été servi**, y compris pendant les pannes » | [INV-01](#inv-01), exécuté pendant **tous** les tests |

La sixième est la plus importante : c'est l'invariant de l'énoncé. Les cinq
autres sont des propriétés de performance ; celle-là est une propriété de
sécurité, et elle doit tenir **pendant** le chaos, pas seulement après.

### Formule de l'affirmation de capacité

À remplir avec les valeurs mesurées, une ligne par opération :

> `POST /api/v1/files` soutient **___ envois simultanés** de fichiers de
> ___ Mo, à un débit agrégé de **___ Mo/s**, avec un accusé de réception à
> p95 = ___ ms et une mémoire de service plafonnée à ___ Mo.
> Au-delà de ___ envois simultanés, le service répond `429` au lieu de se
> dégrader.
> *Mesuré sur : ___ (processeur, RAM, tout en local sur une machine).*

---

## 2. Définir « utilisateur simultané » avant de compter

C'est le piège classique : « 50 utilisateurs » ne veut rien dire tant qu'on
n'a pas dit ce qu'ils font. Un déposant occupe une connexion pendant 40
secondes ; un consultant fait une requête toutes les 2 secondes. À nombre
égal, la charge n'a rien à voir.

Trois profils de référence, à utiliser partout :

| Profil | Comportement | Ce qu'il coûte |
|---|---|---|
| **P1 — Déposant** | 1 envoi, puis suivi toutes les 2 s jusqu'à état terminal, puis 1 téléchargement | Connexion longue + trafic de suivi |
| **P2 — Consultant** | Suivi toutes les 2 s sur 3 fichiers, 1 téléchargement sur 5 | Beaucoup de petites requêtes |
| **P3 — Système tiers** | 1 envoi avec `Idempotency-Key`, suivi toutes les 10 s, pas d'interface | Connexions longues, peu de suivi |

**Mélange réaliste retenu** : 10 % P1, 80 % P2, 10 % P3. C'est ce mélange qui
sert pour l'affirmation de capacité globale ; les profils purs servent à
isoler les goulots.

---

## 3. Objectifs de service (SLO)

Fixés **avant** la mesure, sinon on regarde des chiffres sans savoir s'ils sont
bons. Hypothèse de volumétrie assumée
([`28-precisions-de-cadrage.md`](28-precisions-de-cadrage.md) §2) : ~50
dépôts simultanés en pointe, ~5 000 fichiers/jour, 500 Mo maximum.

| Réf. | Objectif | Seuil |
|---|---|---|
| SLO-1 | Accusé de réception d'un dépôt | p95 < 300 ms **hors temps de transfert** |
| SLO-2 | Débit par connexion (dépôt et téléchargement) | ≥ 50 Mo/s en local |
| SLO-3 | Mémoire du service | < 512 Mo de tas, **plate** quelle que soit la charge |
| SLO-4 | Statut (`GET /files/{id}`) | p95 < 50 ms, p99 < 150 ms |
| SLO-5 | Liste paginée | p95 < 200 ms à 1 million de lignes |
| SLO-6 | Délai de mise à disposition | p95 < 2 min à charge nominale |
| SLO-7 | Taux d'erreur | 0 %, hors `429`/`503` délibérés |
| SLO-8 | Après un pic, retour à la normale | < 60 s |

---

## 4. Protocole de mesure — les règles à ne pas enfreindre

| Règle | Pourquoi |
|---|---|
| **Écrire l'environnement de mesure dans chaque résultat** | Processeur, RAM, tout tourne en local : le chiffre n'a de sens qu'avec son contexte |
| **Brider les conteneurs** (`cpus`, `mem_limit`) | Sans limite, tu mesures ton portable. Avec limites, tu mesures un dimensionnement reproductible |
| **Chauffer avant de mesurer** | La JVM compile à chaud : les 30 premières secondes ne comptent pas |
| **Trois exécutions, garder la médiane** | Une mesure unique sur un poste de travail n'est pas une mesure |
| **Une variable à la fois** | Mesurer dépôt + analyse + téléchargement ensemble d'emblée n'apprend rien |
| **Mesurer l'antivirus séparément et en premier** | C'est la ressource rare : elle plafonne tout le reste |
| **[INV-01](#inv-01) tourne pendant chaque test** | Une propriété de sécurité se vérifie sous contrainte, pas au repos |
| **Rien n'est « validé » sans chiffre écrit** | « Ça tient » n'est pas un résultat |

### Le piège de la bande passante

En local, la boucle locale monte à plusieurs Go/s : **tu ne satureras jamais
le réseau**, tu satureras le disque ou le processeur. Deux traitements
honnêtes, à appliquer tous les deux :

1. **Brider délibérément** avec Toxiproxy (§9) : 100 Mb/s et 20 ms de latence
   entre le client et le service. Tu retrouves un goulot réseau reproductible.
2. **Raisonner** : 50 envois × 500 Mo = 25 Go. À 1 Gb/s, 200 s de réseau
   incompressible. Aucun code ne rattrape ça — et le dire vaut mieux que de
   présenter des débits de loopback comme une preuve de scalabilité.

---

## 5. Ce que le code doit exposer — à prévoir **avant** de coder

C'est la raison d'être de ce document. Sans ces points, les tests ci-dessous
sont impossibles à écrire.

| # | Exigence de testabilité | Sert à |
|---|---|---|
| TST-1 | **Profil `loadtest`** activant un antivirus bouchon (verdict immédiat, configurable) | Isoler l'ingestion de l'analyse |
| TST-2 | **Faux `clamd` contrôlable** : un serveur TCP de test qui sait répondre `OK`, `FOUND`, `size limit exceeded`, se taire, répondre lentement, couper en plein flux, renvoyer du charabia | Toute la famille CHAOS-AV, impossible avec le vrai antivirus |
| TST-3 | **Métriques Micrometer nommées** (§6) | Toutes les mesures côté serveur |
| TST-4 | **Toutes les limites externalisées** : taille max, nombre de boucles d'analyse, seuil d'admission, taille de pool, timeouts, durée de bail | Faire varier un paramètre sans recompiler |
| TST-5 | **Requêtes SQL d'inspection documentées** (profondeur de file, âge du plus vieux, incohérences) | [INV-01](#inv-01) et le suivi pendant les tests |
| TST-6 | **Arrêt propre déclenchable** et worker tuable indépendamment | Famille REC |
| TST-7 | **Journal structuré avec `fileId`** | Reconstituer un cas précis après coup |
| TST-8 | **Jeu de fichiers générable** (1 Mo → 500 Mo, plus EICAR assemblé à l'exécution) | Tous les tests |

> Si l'une de ces huit exigences manque, le test correspondant n'est pas
> « à faire plus tard » : il est **infaisable**. C'est pour ça qu'elles sont
> écrites avant le code.

---

## 6. Métriques à instrumenter

Sans elles, on ne mesure que le point de vue du client, qui ne dit pas
*pourquoi* ça ralentit.

| Métrique | Type | Ce qu'elle révèle |
|---|---|---|
| `http.server.requests` (par route, par statut) | histogramme | Latences, taux d'erreur |
| `jvm.memory.used` (tas) | jauge | **SLO-3** — la courbe qui prouve le streaming |
| `jvm.gc.pause` | histogramme | Pauses sous charge |
| `executor.active` / `executor.queued` | jauge | Saturation du traitement |
| `hikaricp.connections.active` / `.pending` | jauge | Pool de base épuisé |
| `ecluse.scan.duration` | histogramme | Débit réel de l'antivirus |
| `ecluse.scan.inflight` | jauge | La borne des analyses tient-elle (jamais plus que de boucles) |
| `ecluse.scan.verdict{result}` | compteur | Répartition des verdicts |
| `ecluse.queue.depth` | jauge | Profondeur de file |
| **`ecluse.queue.oldest_pending_age`** | jauge | **La métrique d'alerte** : depuis combien de temps un utilisateur attend |
| `ecluse.upload.bytes` / `ecluse.download.bytes` | compteur | Débit agrégé |
| `ecluse.admission.rejected{reason}` | compteur | Contre-pression |
| `ecluse.storage.operation.duration{op}` | histogramme | Le stockage est-il le goulot |

Compléter par `docker stats` (CPU, mémoire, réseau par conteneur) : c'est là
qu'on voit **qui** sature.

---

## 7. Catalogue des tests

Format de chaque fiche : **objectif → montage → profil → critère de réussite →
ce que ça prouve**.

### A. Invariant continu

<a id="inv-01"></a>
#### INV-01 — L'invariant tient sous contrainte ⭐

| | |
|---|---|
| **Objectif** | Aucun fichier non analysé n'est servable, à aucun instant |
| **Montage** | Un vérificateur tourne en boucle (toutes les 2 s) **pendant tous les autres tests** |
| **Contrôles** | ① `SELECT count(*) FROM stored_file WHERE storage_area='SERVABLE' AND status<>'AVAILABLE'` → **toujours 0** ; ② `… WHERE status='AVAILABLE' AND scan_result IS DISTINCT FROM 'CLEAN'` → **toujours 0** ; ③ un client tente en boucle de télécharger un fichier connu comme infecté et un en attente → **toujours refusé** |
| **Réussite** | Zéro violation sur la durée totale de la campagne |
| **Prouve** | A6 — la garantie de l'énoncé résiste au chaos |

> Un test de charge qui casse l'invariant est un échec, même si toutes les
> latences sont bonnes.

---

### B. Capacité par opération

<a id="perf-01"></a>
#### PERF-01 — Dépôt : montée en charge

| | |
|---|---|
| **Objectif** | Trouver le nombre d'envois simultanés soutenables |
| **Montage** | Antivirus **bouchon instantané** (TST-1) — on isole l'ingestion |
| **Profil** | Paliers de 2 min : 1, 5, 10, 20, 50, 100 envois simultanés. Fichiers de 10 Mo |
| **Mesures** | p95/p99 de l'accusé, débit agrégé Mo/s, tas, CPU, connexions base |
| **Réussite** | SLO-1 et SLO-2 tenus jusqu'au palier annoncé ; **genou identifié** (premier palier qui casse un SLO) |
| **Prouve** | A1 |

<a id="perf-02"></a>
#### PERF-02 — Dépôt : sensibilité à la taille

| | |
|---|---|
| **Profil** | 10 envois simultanés, tailles 1 / 10 / 100 / 500 Mo |
| **Réussite** | L'accusé reste sous SLO-1 **quelle que soit la taille** ; le débit par connexion ne s'effondre pas |
| **Prouve** | Le temps de réponse ne dépend pas de la taille — donc rien n'est mis en tampon |

<a id="perf-03"></a>
#### PERF-03 — Téléchargement : montée en charge

| | |
|---|---|
| **Profil** | 1, 5, 10, 20, 50 téléchargements simultanés × 10 Mo, puis × 500 Mo |
| **Mesures** | Débit agrégé, tas, réseau conteneur |
| **Réussite** | SLO-2 ; tas plat ; requêtes `Range` fonctionnelles sous charge |
| **Prouve** | A2 côté sortie |

<a id="perf-04"></a>
#### PERF-04 — Statut, liste et compteurs

| | |
|---|---|
| **Montage** | Base **pré-remplie** : 10 000, puis 100 000, puis 1 000 000 de lignes |
| **Profil** | 200 requêtes/s sur `GET /files/{id}` ; 50/s sur `GET /files` ; 20/s sur `/summary` |
| **Mesures** | p95/p99, plans d'exécution SQL, connexions base |
| **Réussite** | SLO-4 et SLO-5 tenus **à 1 million de lignes** ; les index partiels sont utilisés (vérifier le plan, pas seulement le temps) |
| **Prouve** | La pagination par curseur et les index tiennent à l'échelle |

<a id="perf-05"></a>
#### PERF-05 — Mémoire sous gros fichiers ⭐

| | |
|---|---|
| **Objectif** | **Le test le plus parlant du projet** |
| **Montage** | Conteneur limité à 512 Mo de mémoire ; harnais dédié (pas k6, §9) |
| **Profil** | 20 envois simultanés de 500 Mo, puis 20 téléchargements simultanés de 500 Mo |
| **Mesures** | Courbe du tas, mémoire du conteneur, pauses GC |
| **Réussite** | **Aucun dépassement mémoire**, courbe plate, aucune corrélation avec la taille |
| **Prouve** | A2 — le streaming est réel. Une courbe plate pendant 10 Go de trafic vaut tous les discours |

<a id="perf-06"></a>
#### PERF-06 — Découplage dépôt / analyse ⭐

| | |
|---|---|
| **Montage** | Deux exécutions identiques : antivirus instantané, puis antivirus à 30 s par fichier (TST-2) |
| **Profil** | 20 déposants, fichiers de 10 Mo |
| **Réussite** | **Le p95 de l'accusé est identique dans les deux cas** (écart < 10 %) |
| **Prouve** | A3 — l'analyse ne bloque pas l'ingestion. Si l'écart est grand, l'asynchronisme est décoratif |

<a id="perf-07"></a>
#### PERF-07 — Débit réel de l'antivirus

| | |
|---|---|
| **Objectif** | Mesurer le plafond réel — **à faire en premier**, tout se dimensionne dessus |
| **Montage** | Attaque directe du vrai `clamd`, hors service |
| **Profil** | 1, 2, 4, 8 analyses simultanées × 1 / 10 / 100 / 500 Mo |
| **Mesures** | Mo/s par analyse, Mo/s agrégés, mémoire de clamd, effet de `MaxThreads` |
| **Réussite** | Valeur chiffrée écrite dans le rapport ; le nombre de boucles d'analyse du service est calé dessus |
| **Prouve** | Le dimensionnement n'est pas arbitraire |

<a id="perf-08"></a>
#### PERF-08 — Mélange réaliste

| | |
|---|---|
| **Profil** | Mélange 10/80/10 (§2), montée jusqu'à rupture d'un SLO |
| **Réussite** | **C'est ce test qui donne le chiffre de l'affirmation de capacité globale** |
| **Prouve** | A1 en conditions réalistes |

---

### C. Limites et durée

<a id="stress-01"></a>
#### STRESS-01 — Point de rupture

| | |
|---|---|
| **Profil** | Montée continue jusqu'à effondrement (erreurs > 5 % ou latence × 10) |
| **Réussite** | La dégradation est **progressive et annoncée** (`429`), pas un effondrement. Le service **se rétablit** seul après l'arrêt de la charge |
| **Prouve** | A5, et surtout : le service ne meurt pas, il refuse |

<a id="spike-01"></a>
#### SPIKE-01 — Pic brutal

| | |
|---|---|
| **Profil** | 0 → 100 déposants en 5 s, 60 s, retour à 0 |
| **Réussite** | Pas d'erreur `5xx` ; retour à la normale en < 60 s (SLO-8) ; la file se résorbe |

<a id="soak-01"></a>
#### SOAK-01 — Endurance

| | |
|---|---|
| **Profil** | Charge nominale (50 % du genou) pendant **2 heures** |
| **Mesures** | Tas après GC, descripteurs de fichiers, connexions base, objets temporaires du stockage, taille des tables |
| **Réussite** | Aucune dérive : mémoire stable, **aucun temporaire orphelin accumulé**, connexions stables |
| **Prouve** | Pas de fuite — le seul test qui les révèle |

---

### D. Contraintes de ressources

<a id="res-01"></a>
#### RES-01 — Processeur bridé

| | |
|---|---|
| **Montage** | Service limité à 1 cœur, puis 2, puis 4 |
| **Réussite** | Le débit décroît **proportionnellement**, sans erreur ni effondrement. Le calcul d'empreinte SHA-256 ne devient pas le goulot |

<a id="res-02"></a>
#### RES-02 — Mémoire bridée

| | |
|---|---|
| **Montage** | 256 Mo, puis 512 Mo, puis 1 Go |
| **Réussite** | Fonctionne à 512 Mo sous charge nominale ; à 256 Mo, dégradation propre et non OOM silencieux |

<a id="res-03"></a>
#### RES-03 — Pool de connexions saturé

| | |
|---|---|
| **Montage** | Pool réduit à 5 connexions |
| **Réussite** | Attente bornée puis `503` propre avec `Retry-After` ; **aucune connexion fuitée** (`hikaricp.connections.pending` revient à 0) |

<a id="res-04"></a>
#### RES-04 — Disque du stockage plein

| | |
|---|---|
| **Montage** | Volume du stockage objet rempli artificiellement |
| **Réussite** | `503` à l'ingestion, **aucune métadonnée créée sans contenu**, message explicite. Les téléchargements continuent |

---

### E. Réseau dégradé

Tous via Toxiproxy (§9), sur chacun des trois liens : client↔service,
service↔stockage, service↔antivirus.

<a id="net-01"></a>
#### NET-01 — Bande passante limitée

| | |
|---|---|
| **Montage** | 100 Mb/s client↔service |
| **Réussite** | Les débits correspondent à la limite (écart < 15 %) ; pas de timeout intempestif ; les accusés restent sous SLO-1 |
| **Prouve** | Le comportement en réseau contraint est compris, pas supposé |

<a id="net-02"></a>
#### NET-02 — Latence élevée

| | |
|---|---|
| **Montage** | +200 ms sur service↔stockage, puis service↔antivirus |
| **Réussite** | Dégradation proportionnelle, aucun timeout déclenché à tort, la file absorbe |

<a id="net-03"></a>
#### NET-03 — Coupure en plein transfert

| | |
|---|---|
| **Montage** | Coupure du lien à 50 % d'un envoi de 500 Mo, puis à 50 % d'un téléchargement |
| **Réussite** | **Aucune métadonnée créée** pour l'envoi interrompu ; objet partiel nettoyé ; relance avec la même `Idempotency-Key` → un seul fichier |

<a id="net-04"></a>
#### NET-04 — Client lent (attaque par lenteur)

| | |
|---|---|
| **Montage** | 50 clients envoyant 1 octet toutes les 5 s |
| **Réussite** | Les connexions lentes sont coupées par timeout ; **le service reste disponible pour les autres** |
| **Prouve** | Le pool de connexions n'est pas monopolisable |

<a id="net-05"></a>
#### NET-05 — `Content-Length` mensonger

| | |
|---|---|
| **Montage** | Annonce 1 Ko, envoie 600 Mo ; puis annonce 600 Mo, envoie 1 Ko |
| **Réussite** | Coupure au dépassement réel, `413` ou `400` propre, rien de partiel conservé |

---

### F. Pannes de dépendances

<a id="chaos-01"></a>
#### CHAOS-01 — Antivirus arrêté ⭐

| | |
|---|---|
| **Montage** | Conteneur antivirus stoppé, charge nominale maintenue 10 min |
| **Réussite** | **L'ingestion continue** ; **les fichiers déjà disponibles restent téléchargeables** ; la file grandit puis l'admission refuse (`429`) au seuil ; **aucune tentative consommée inutilement** ; au redémarrage, la file se résorbe **sans intervention** |
| **Prouve** | A4 et A5 — les trois disponibilités sont découplées |

<a id="chaos-02"></a>
#### CHAOS-02 — Antivirus qui déconne

Six variantes, toutes via le faux `clamd` (TST-2) :

| Variante | Comportement attendu |
|---|---|
| Ne répond pas (silence) | Timeout de lecture → `RETRY_WAIT`, pas `INFECTED` |
| Répond très lentement (200 s) | Timeout franchi proprement, bail non volé par le reaper |
| Coupe en plein flux | Nouvelle tentative, aucun verdict écrit |
| Renvoie du charabia | `RETRY_WAIT` puis `FAILED_FINAL`, **jamais `CLEAN`** |
| `size limit exceeded` | **`UNSCANNABLE`**, sans nouvelle tentative |
| Refuse la connexion | Portillon de santé actif : le worker **ne prend plus de travail** |

> La ligne « charabia » est la plus importante : un analyseur laxiste qui
> interprète une réponse inconnue comme un succès transforme une panne en
> faille.

<a id="chaos-03"></a>
#### CHAOS-03 — Base de données indisponible

| | |
|---|---|
| **Montage** | Conteneur stoppé 60 s sous charge, puis redémarré |
| **Réussite** | `503` propres, sonde de disponibilité en échec (retrait du répartiteur), **sonde de vivacité inchangée** (pas de redémarrage en boucle), reprise automatique, **aucun travail perdu** |

<a id="chaos-04"></a>
#### CHAOS-04 — Stockage objet indisponible ou refusant

| | |
|---|---|
| **Montage** | ① Conteneur stoppé ; ② identifiants de livraison utilisés à tort sur la quarantaine |
| **Réussite** | ① `503` à l'ingestion, téléchargements en échec propre ; ② **accès refusé par le stockage** — c'est la dernière ligne de défense, elle doit tenir |

<a id="chaos-05"></a>
#### CHAOS-05 — Keycloak indisponible *(quand l'authentification sera là)*

| | |
|---|---|
| **Réussite** | `401`/`503` propres ; les jetons déjà valides continuent d'être acceptés jusqu'à expiration (clés en cache) ; aucun accès ouvert par défaut |

---

### G. Admission et contre-pression

<a id="adm-01"></a>
#### ADM-01 — Seuil de file

| | |
|---|---|
| **Montage** | Antivirus ralenti pour faire monter la file |
| **Réussite** | Au seuil, `429` + `Retry-After` exploitable ; **la file ne dépasse pas le plafond** ; les opérations de lecture ne sont pas affectées |

<a id="adm-02"></a>
#### ADM-02 — Quota de volume

| | |
|---|---|
| **Réussite** | Refus au dépassement, message explicite, comptage exact |

<a id="adm-03"></a>
#### ADM-03 — Fichier au-delà de la taille maximale

| | |
|---|---|
| **Montage** | 600 Mo, avec et sans `Content-Length` honnête |
| **Réussite** | `413 FILE_TOO_LARGE`, **coupure sans avoir tout transféré**, rien conservé |

---

### H. Reprise et concurrence

<a id="rec-01"></a>
#### REC-01 — Worker tué sous charge

| | |
|---|---|
| **Montage** | `kill -9` sur le worker avec 20 analyses en cours, à répéter 5 fois |
| **Réussite** | Tous les fichiers finissent dans un état terminal ; **aucun ne reste bloqué** ; les baux expirés sont repris |

<a id="rec-02"></a>
#### REC-02 — Service arrêté proprement en plein envoi

| | |
|---|---|
| **Réussite** | Les envois en cours se terminent ou échouent proprement ; aucun objet orphelin non nettoyé ; aucune métadonnée sans contenu |

<a id="rec-03"></a>
#### REC-03 — Deux workers, un seul verdict

| | |
|---|---|
| **Montage** | 4 workers, 500 fichiers, baux volontairement courts pour forcer les reprises |
| **Réussite** | **Exactement un verdict par fichier** ; aucun worker zombie n'écrase un verdict ; compteur de tentatives cohérent |

<a id="rec-04"></a>
#### REC-04 — Promotion interrompue

| | |
|---|---|
| **Montage** | Tuer le worker à chacun des **cinq** interstices du protocole de promotion |
| **Réussite** | Convergence dans tous les cas ; **jamais de fichier partiel servi** ; temporaires nettoyés |

<a id="rec-05"></a>
#### REC-05 — Idempotence sous concurrence

| | |
|---|---|
| **Montage** | 50 envois simultanés avec la **même** `Idempotency-Key` |
| **Réussite** | **Un seul fichier créé**, 50 réponses identiques ; puis même clé avec contenu différent → `422` |

---

## 8. Déterminer la capacité : la méthode

1. **Montée par paliers** jusqu'à rupture du premier SLO → c'est le **genou**.
2. **Capacité annoncée = 70 % du genou.** On n'annonce pas un service à son
   point de rupture.
3. **Convertir en utilisateurs** avec la loi de Little :
   `utilisateurs simultanés = débit (req/s) × durée d'une requête (s)`.
   Exemple : 40 dépôts/s soutenus, 1,2 s par dépôt → ~48 déposants simultanés.
4. **Vérifier la conversion** en rejouant le profil P1 au nombre calculé : la
   mesure doit confirmer le calcul. Si elle ne le confirme pas, c'est le
   modèle d'utilisateur qui est faux.
5. **Écrire l'affirmation** avec son environnement de mesure (§1).

---

## 9. Outillage

| Besoin | Outil | Remarque |
|---|---|---|
| Charge HTTP, paliers, seuils | **k6** (conteneur) | ⚠️ **charge les fichiers en mémoire par VU** : inutilisable pour 20 × 500 Mo. À réserver aux fichiers ≤ 20 Mo |
| Gros fichiers | **Harnais dédié** : `curl` en parallèle, ou ~100 lignes de Java/Go générant le flux **sans le matérialiser** | Le seul moyen de faire PERF-05 |
| Dégradation réseau | **Toxiproxy** (conteneur) | Bande passante, latence, coupure, par lien |
| Pannes de dépendances | `docker compose stop/start`, `pause/unpause` | `pause` simule un gel, plus vicieux qu'un arrêt |
| Antivirus contrôlable | **Faux `clamd` maison** (TST-2) | Le vrai ne sait pas échouer sur commande |
| Contraintes de ressources | `deploy.resources.limits` du compose | CPU et mémoire |
| Observation serveur | Micrometer + `docker stats` | §6 |
| Mémoire fine | JFR ou `jcmd GC.heap_info` en échantillonnage | Courbe de PERF-05 |
| Base pré-remplie | Script SQL de génération | PERF-04 à 1 M de lignes |

### Organisation proposée

```
load/
├── README.md              résultats mesurés, environnement, limites
├── k6/
│   ├── lib/               profils P1, P2, P3, seuils communs
│   ├── perf-01-upload-ramp.js
│   ├── perf-04-read-paths.js
│   ├── perf-08-mixed.js
│   ├── stress-01-breaking-point.js
│   └── soak-01-endurance.js
├── harness/               gros fichiers (PERF-05), flux généré à la volée
├── fakeclamd/             faux antivirus contrôlable (TST-2)
├── chaos/                 scripts d'injection de pannes
├── sql/                   pré-remplissage, requêtes d'inspection, INV-01
└── toxiproxy/             profils de dégradation réseau
```

---

## 10. Ordre d'exécution

L'ordre compte : chaque étape conditionne le dimensionnement de la suivante.

| # | Étape | Pourquoi ici |
|---|---|---|
| 1 | [PERF-07](#perf-07) — débit de l'antivirus | Le plafond de tout le reste |
| 2 | [PERF-05](#perf-05) — mémoire sur gros fichiers | Si le streaming est faux, rien d'autre ne sert |
| 3 | [PERF-01](#perf-01) à [PERF-04](#perf-04) — capacité par opération | Les chiffres par API |
| 4 | [PERF-06](#perf-06) — découplage | Valide l'architecture asynchrone |
| 5 | Famille CHAOS | La résilience, une fois le nominal connu |
| 6 | Famille ADM | Les seuils, calés sur les mesures précédentes |
| 7 | Famille REC + [REC-05](#rec-05) | La correction sous concurrence |
| 8 | Famille NET et RES | Les environnements contraints |
| 9 | [STRESS-01](#stress-01), [SPIKE-01](#spike-01) | Les limites |
| 10 | [SOAK-01](#soak-01) | En dernier, il dure 2 h |
| — | [INV-01](#inv-01) | **Pendant tous les autres** |

**Budget réaliste** : 1 à 1,5 jour pour l'outillage (faux antivirus, harnais,
scripts), 1 jour d'exécution et de rédaction. Si le temps manque, l'ordre
ci-dessus est aussi l'ordre de priorité : les étapes 1 à 5 suffisent à tenir
les six affirmations.

---

## 11. Ce qui va dans le README final

Une demi-page, pas plus :

1. **Le tableau des six affirmations**, chacune avec sa mesure.
2. **L'affirmation de capacité** par opération, remplie (§1).
3. **L'environnement de mesure**, en toutes lettres.
4. **Le paragraphe d'honnêteté**, qui est celui qui fait la différence :

> « Tout tournait sur une seule machine : ces chiffres mesurent autant le
> poste que le service. Ce qu'ils établissent, ce ne sont pas des débits
> absolus, mais des propriétés : la mémoire reste bornée quelle que soit la
> taille des fichiers, la latence du dépôt est indépendante de la durée
> d'analyse, les téléchargements survivent à une file saturée, la
> contre-pression s'active au seuil prévu, et l'invariant de sécurité n'a
> jamais été violé — y compris pendant les injections de pannes. »

Un candidat qui donne des chiffres bruts sans ce paragraphe se fait démonter.
Un candidat qui l'écrit de lui-même a déjà gagné l'échange.

---

## 12. Amorce de session dédiée

> Nous travaillons sur la **validation en charge et en résilience** du
> back-end.
>
> Lis d'abord : `AGENTS.md`, `backend/AGENTS.md`,
> `docs/31-plan-de-tests-charge-et-resilience.md` (ce plan, en entier) et
> `contracts/openapi.yaml`.
>
> Commence par l'outillage, dans cet ordre : le **faux `clamd` contrôlable**
> (TST-2), le **harnais gros fichiers** (PERF-05), puis les scripts k6. Tout
> va dans `load/`.
>
> Ensuite exécute dans l'ordre du §10, en écrivant les résultats dans
> `load/README.md` au fur et à mesure — chiffres **et** environnement de
> mesure. N'annonce aucun test comme réussi sans chiffre à l'appui, et signale
> tout écart au plan plutôt que d'ajuster le seuil après coup.

---

## 13. Ce qui a été exécuté (relu le 02/10)

### Joué en charge

Les campagnes du 28/09 ([`capacity-planning/`](capacity-planning/README.md),
[`load/README.md`](../load/README.md)) : deux scénarios k6 —
`k6/capacity.js` (dépôts à débit d'arrivée contrôlé, par paliers) et
`k6/concurrency.js` (rafale de 50 dépôts simultanés de 2 Mio) — pilotés par
`load/run-capacity.mjs`.

| Fiche | Ce qui a été fait |
|---|---|
| PERF-01 | Paliers de dépôts jusqu'au genou, fichiers de 128 Kio à 2 Mio, avec ClamAV **réel** et avec le verdict immédiat |
| PERF-06 | La comparaison « avec et sans ClamAV » existe (`real-antivirus-*`, `instant-clean-*`) ; l'antivirus ralenti à 30 s par fichier n'a pas été joué |
| RES-01 | Nœuds de 1 et de 2 CPU, 4 et 8 boucles d'analyse ; un et trois nœuds |
| STRESS-01 | Paliers au-delà du plafond : le débit **s'effondre** au lieu de plafonner — le critère de réussite n'est pas tenu, c'est une limite connue (README §10) |
| INV-01 | La jauge `praxedo_invariant_violations` est relue sur toute la durée de chaque campagne : **0** partout. Le client qui tente en boucle un téléchargement interdit n'a pas été écrit |

Les SLO du §3 n'ont pas tous été vérifiés : le rapport de campagne retient le
délai de mise à disposition (SLO-6, deux minutes), l'absence de `429` et la
stabilité de la file.

### Couvert par la suite de tests, pas par un essai en charge

| Fiche | Test |
|---|---|
| PERF-05 (mémoire) | `UploadMemoryTest`, `DownloadMemoryTest` : **un** fichier de 500 Mo déposé, promu et téléchargé dans une JVM plafonnée à 256 Mo — pas vingt en parallèle |
| CHAOS-01 | `FileScanServiceTest` (rien n'est réclamé, aucune tentative consommée), `scripts/demo.sh --resilience` |
| CHAOS-02 | `HttpAntivirusScannerTest` (WireMock) : lenteur, moteur absent, `412`, `413`, `406 Heuristics.Limits.Exceeded`, corps illisible |
| CHAOS-03 | `DependencyFailureMappingTest` (`503`), `FileScanServiceTest` (base hors d'atteinte). ⚠️ La sonde de disponibilité **ne passe pas** en échec quand la base tombe : elle ne contient pas la base |
| CHAOS-04 ② | `ObjectStorageTest` : la quarantaine est refusée à l'identité `delivery` |
| CHAOS-05 | `BrowserSessionTest` : Keycloak arrêté, la session ouverte reste servie, 30 min au plus |
| ADM-01 | `UploadAdmissionTest`, `UploadFileServiceTest` |
| ADM-03, NET-05 | `UploadApiTest` : `413` sur 600 Mo annoncés ; `Content-Length` obligatoire (`411`) ; corps différent de l'annonce → `400` |
| NET-03 | `UploadApiTest` (corps tronqué : rien n'est gardé) |
| NET-04 | `UploadFileServiceTest`, `DeadlineInputStreamTest` : un corps au compte-gouttes est coupé à son échéance, `408` |
| REC-01, REC-03 | `FilePersistenceTest` : bail expiré repris, worker zombie sans effet, huit workers sur huit fichiers |
| REC-04 | `FilePromotionServiceTest` : les cinq points de panne |
| REC-05 | `JdbcIdempotencyStoreTest` : huit réservations concurrentes, une seule accordée. Les autres reçoivent `409` pendant le dépôt, puis le même fichier — pas « 50 réponses identiques ». Le `422` porte sur le nom ou la taille, pas sur le contenu |

### Non fait

PERF-02 et PERF-03 (tailles jusqu'à 500 Mo, téléchargements en charge),
PERF-04 (lectures sur une base d'un million de lignes), PERF-07 (antivirus
attaqué directement), PERF-08 (mélange des trois profils), SPIKE-01, SOAK-01,
RES-02 (seul le gabarit de 1 Gio a été mesuré), RES-03, RES-04, NET-01 et
NET-02, REC-02, ADM-02 (pas de quota). L'outillage
du §9 se réduit à k6, `docker stats`, `pg_stat_activity` et JFR : ni Toxiproxy,
ni harnais de gros fichiers, ni faux antivirus.
