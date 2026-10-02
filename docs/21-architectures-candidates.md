# 21 — Architectures candidates

> **Analyse : Claude (Opus 5)** — 2026-09-20 — confrontation : [`CONFRONTATION.md`](CONFRONTATION.md)
>
> **Analyse écrite avant le code, gardée telle qu'elle a été rendue.** Le
> système livré est **A2 avec le stockage objet d'A3, sans broker, dans un seul
> processus** (« A2-R »). Les paragraphes **« Livré »**, relus contre le code le
> 02/10, disent où il s'écarte des schémas ci-dessous ; la description qui fait
> foi est [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §1.

Cinq architectures complètes, de la plus simple à la plus élaborée. Pour
chacune : **ce qu'elle garantit, ce qu'elle coûte, dans quel but on la choisit,
et le seuil précis qui fait passer à la suivante.**

L'objectif n'est pas de désigner « la meilleure » dans l'absolu : c'est de
montrer qu'on sait **où l'on se situe sur cette échelle et pourquoi**. C'est
exactement ce qu'un architecte cherche à entendre.

---

## Tableau comparatif

| | **A0** Synchrone | **A1** Async in-process | **A2** File en base | **A3** Broker + objet | **A4** Pilotée par événements |
|---|---|---|---|---|---|
| Composants | app + AV | app + AV + BDD | app + BDD + AV | + broker + stockage objet | + événements de bucket |
| Tient la charge (`EX-06`) | ❌ | 🟠 | ✅ | ✅ | ✅ |
| Tient les tailles (`EX-07`) | ❌ | 🟠 | ✅ | ✅ | ✅ |
| Survit à un redémarrage | ❌ | ❌ | ✅ | ✅ | ✅ |
| Scaling indépendant API/scan | ❌ | ❌ | ✅ | ✅ | ✅ |
| Isolation physique | ❌ | ❌ | ✅ (montages) | ✅ (IAM) | ✅ |
| Octets hors de l'application | ❌ | ❌ | ❌ | 🟠 (sortie) | ✅ (entrée + sortie) |
| Effort | 0,5 j | 1 j | **2 j** | 3,5 j | 5 j+ |
| **Verdict** | contre-exemple | insuffisant | **✅ cible** | palier 2 | piste documentée |

---

## A0 — Scan synchrone dans le contrôleur

```
Client ──POST──▶ [ Contrôleur ] ──scan bloquant──▶ [ AV ]
                       │
                       ├── attend le verdict (200 ms … 4 min)
                       ▼
                 stockage + réponse 201
```

**Pourquoi la documenter** : c'est la solution la plus naturelle, et la
première à mettre à l'épreuve. **Savoir nommer précisément ses trois défauts est un prérequis** — et
plus convaincant que de simplement livrer autre chose.

| Défaut | Mécanisme exact |
|---|---|
| Ne tient pas la charge | Le thread HTTP est mobilisé pendant tout le scan. Le pool s'épuise ; le service devient indisponible **y compris pour les lectures**, qui n'ont rien demandé |
| Ne tient pas les gros fichiers | Dépassement des timeouts de proxy et de load balancer (typiquement 60 s). Le client reçoit une erreur alors que le traitement a peut-être réussi → état ambigu, et le rejeu impose de renvoyer tout le fichier |
| Aucune reprise | Un crash pendant le scan perd le travail. Rien ne sait qu'il restait à faire |

**Quand elle serait acceptable** : fichiers plafonnés à quelques Mo, faible
concurrence, tolérance à la perte. Aucune de ces conditions n'est réunie ici.

---

## A1 — Asynchrone dans le même processus (`@Async`)

```
Client ──POST──▶ [ Contrôleur ] ──202──▶
                       │
                       └─▶ file en mémoire ──▶ [ pool @Async ] ──▶ [ AV ]
```

**Apport réel** : le thread HTTP est libéré. C'est le bon premier réflexe.

**Pourquoi elle ne suffit pas** — un seul défaut, mais rédhibitoire :

> **La file est en mémoire.** Un redémarrage, un déploiement, un `OOMKilled`,
> et tous les travaux en attente disparaissent définitivement. Les fichiers
> restent en quarantaine, jamais analysés, jamais servis, **et personne ne le
> sait**. C'est une panne silencieuse : l'invariant de sécurité tient
> (rien n'est servi), mais la promesse fonctionnelle est rompue sans alerte.

Défauts secondaires : impossible de scaler l'analyse indépendamment de l'API ;
pas de visibilité sur la file ; pas de reprise après échec.

**Seuil de bascule vers A2** : dès qu'un redémarrage est possible — donc
immédiatement, en pratique.

---

## A2 — File d'attente portée par la base *(architecture recommandée)*

```
┌────────┐  ① POST (flux)   ┌──────────────────┐
│ React  │─────────────────▶│  conteneur API   │
│        │◀──── 202 ────────│  (profil api)    │
└───┬────┘                  └───┬──────────┬───┘
    │ ② GET statut (polling)    │ flux     │ ② transaction unique :
    │                           ▼          │    métadonnées + travail
    │                   ┌───────────────┐  │        ┌──────────────┐
    │                   │ volume        │  └───────▶│ PostgreSQL   │
    │                   │ quarantine/   │           │              │
    │                   └───────▲───────┘           │ stored_file  │
    │                           │                   │ (= la file)  │
    │                   ┌───────┴───────┐           └──────┬───────┘
    │                   │ volume        │                  │ ③ claim atomique
    │                   │ servable/     │                  │  FOR UPDATE
    │                   └───────▲───────┘                  │  SKIP LOCKED
    │                           │                          ▼
    │                     lecture + promotion    ┌────────────────────┐
    │                           └────────────────│ conteneur worker   │
    │  ④ GET /content                            │ (profil worker)    │
    │     (relais, si CLEAN)                     └─────────┬──────────┘
    └────────────────────────────────────────────          │ flux HTTP
                                                           ▼
                                                  ┌──────────────────┐
                                                  │  ClamAV + REST   │
                                                  └──────────────────┘
```

### Ce qu'elle garantit

| Garantie | Mécanisme |
|---|---|
| Aucun travail perdu | Le travail **est** une ligne de la table, écrite dans la même transaction que la métadonnée (outbox implicite : la file et l'état ne font qu'un) |
| Aucun double traitement | Claim atomique `FOR UPDATE SKIP LOCKED` + transition conditionnelle ([P-15](20-catalogue-problematiques.md#p-15)) |
| Reprise après mort d'un worker | Bail à expiration + balayage ([P-16](20-catalogue-problematiques.md#p-16)) |
| Réessais avec délai | Colonnes `attempts` / `next_attempt_at` — **la file porte la politique de résilience** ([P-21](20-catalogue-problematiques.md#p-21)) |
| Isolation physique | Le conteneur API ne monte pas le volume de quarantaine ([P-11](20-catalogue-problematiques.md#p-11)) |
| Scaling indépendant | Deux conteneurs, même image, profils différents ([P-30](20-catalogue-problematiques.md#p-30)) |
| Observabilité de la file | **C'est du SQL.** `SELECT status, count(*) … GROUP BY status` — un broker n'offre pas cela |

### Ce qu'elle coûte

| Coût | Ampleur | Atténuation |
|---|---|---|
| Latence de polling | 200 ms – 5 s | Polling adaptatif ; `LISTEN`/`NOTIFY` si besoin |
| Charge de polling sur la base | faible | Index partiel sur les états actifs ([P-12](20-catalogue-problematiques.md#p-12)) |
| Stockage non partagé entre nœuds | réel | Limite assumée, levée par A3 |
| Pas de rejeu d'historique | réel | Non requis |

### Dans quel but on la choisit

1. **Trois composants seulement** — chacun justifiable en une phrase.
2. **La file est transactionnelle avec l'état** : il n'y a pas deux sources de
   vérité à réconcilier. C'est une simplification de fond, pas une économie.
3. **Elle démontre tout ce que l'énoncé demande** : asynchronisme, résistance à
   la charge, tailles variables, isolation, reprise sur panne.
4. **Elle est intégralement testable** avec Testcontainers, sans simulation.

### Livré : A2, révisée

Le schéma ci-dessus date du 20/09. Ce qui a changé depuis :

| Schéma du 20/09 | Livré | Pourquoi |
|---|---|---|
| Deux conteneurs (profils `api` et `worker`) | **Un seul processus** qui reçoit, analyse et sert | Décision du 27/09 ([ADR-0002](adr/0002-un-processus-trois-identites.md)) |
| Volumes Docker `quarantine/` et `servable/` | **Stockage objet compatible S3** (SeaweedFS), deux zones | Cadrage du 21/09 ([`28`](28-precisions-de-cadrage.md) §3) |
| Isolation par montages : l'API ne monte pas la quarantaine | Isolation par **trois identités de stockage** : celle qui sert n'a aucun droit sur la quarantaine | Même propriété, portée par le stockage |
| Téléchargement « si `CLEAN` » | Téléchargement si `AVAILABLE`, après une promotion vérifiée | `D-19` |
| Polling adaptatif de 200 ms à 5 s | Boucles d'analyse qui reprennent aussitôt après un fichier, et attendent 2 s quand la file est vide | — |
| — | Authentification par Keycloak, toujours exigée | Décision du porteur du projet ([ADR-0014](adr/0014-authentification-toujours-exigee.md)) |

Les garanties du tableau « Ce qu'elle garantit » tiennent, avec ces mêmes
corrections : l'isolation est celle des identités de stockage, et le
dimensionnement séparé de l'analyse n'est pas livré (un seul processus ; deux
interrupteurs permettraient de démarrer un nœud sans ses boucles d'analyse).
La limite « stockage non partagé entre nœuds » est levée.

### Seuil de bascule vers A3

- Le stockage doit être partagé entre plusieurs nœuds sans NFS, **ou**
- la bande passante de téléchargement portée par l'API devient le facteur
  limitant mesuré, **ou**
- un second consommateur métier apparaît (indexation, notification), **ou**
- le débit dépasse ~1 000 travaux/s.

> **Aucune de ces conditions n'est établie par l'énoncé.** C'est la raison
> pour laquelle A2 est la cible, et A3 la piste documentée.

---

## A3 — Broker de messages + stockage objet

```
API ──▶ PostgreSQL (métadonnées + outbox)
 │              │
 │              └── relais ──▶ Kafka / RabbitMQ ──▶ worker (M réplicas)
 └──▶ MinIO/S3 : bucket quarantine ──(promotion)──▶ bucket servable
                                                          │
                              téléchargement : 302 vers URL présignée
```

**Ce qu'elle ajoute par rapport à A2** :

| Apport | Valeur réelle ici |
|---|---|
| Stockage partagé entre nœuds | **réelle** — c'est le principal apport |
| URL présignées (bande passante déportée) | **réelle** à volume élevé |
| Découplage de plusieurs consommateurs | nulle (il n'y en a qu'un) |
| Débit du broker | nulle à cette échelle |
| DLQ native | faible — une colonne `attempts` la remplace |

**Ce qu'elle coûte** : deux composants supplémentaires, deux modes de panne
supplémentaires, un outbox devenu explicite (relais vers le broker), et
l'observabilité de la file qui passe du SQL à un outillage dédié.

**Point important** : l'apport majeur d'A3 vient du **stockage objet**, pas du
broker. Il est donc parfaitement cohérent d'adopter **A3 partiellement** —
stockage objet sans broker. C'est ce que propose le palier 2 de
[`25-paliers-de-perimetre.md`](25-paliers-de-perimetre.md).

**Si un broker est introduit, lequel ?** Voir
[P-14](20-catalogue-problematiques.md#p-14) et
[`22-comparatif-produits.md`](22-comparatif-produits.md) §1. Résumé :
RabbitMQ conviendrait techniquement mieux au job dispatch, **Kafka reste le
bon choix pour ce projet** car c'est l'outil maîtrisé — à condition d'écrire
explicitement les mitigations de `max.poll.interval.ms` et du blocage en tête
de partition.

---

## A4 — Pilotée par les événements du stockage

```
① Client demande une URL présignée d'écriture  ──▶ API
② Client écrit DIRECTEMENT dans le bucket quarantine  (l'app ne voit aucun octet)
③ L'événement de création d'objet déclenche le scan
④ Téléchargement par URL présignée de lecture
```

**Ce qu'elle apporte** : l'application ne transporte **aucun octet**, ni à
l'entrée ni à la sortie. C'est l'architecture correcte à très grande échelle :
la capacité d'ingestion devient celle du stockage, pas celle de l'application.

**Ce qu'elle coûte** :

| Coût | Détail |
|---|---|
| Validation *a posteriori* | Taille réelle, type, quota : on ne peut plus les vérifier pendant l'écriture ([P-02](20-catalogue-problematiques.md#p-02) devient impossible en amont) |
| Uploads abandonnés | Le client peut demander une URL et ne jamais écrire → réconciliation nécessaire |
| Dépendance aux événements du bucket | Fiabilité et ordre à gérer ; en local, MinIO les fournit mais la configuration s'alourdit |
| CORS, sécurité des URL d'écriture | Surface d'attaque nouvelle |
| Démonstrabilité | Nettement plus difficile à démontrer de bout en bout |

**Verdict** : **piste documentée**, avec son déclencheur. C'est la bonne
réponse à la question « et si le volume était dix fois supérieur ? » —
pouvoir y répondre précisément vaut mieux que de l'implémenter.

---

## Synthèse : la trajectoire, pas le point

Ce qui se défend n'est pas un point sur l'échelle, mais la
**trajectoire et ses déclencheurs** :

```
A0 ──────▶ A1 ──────▶ A2 ──────▶ A3 ──────▶ A4
   dès qu'il   dès qu'un   dès un stockage   dès que l'app
   y a de la   redémarrage  partagé ou une   ne doit plus
   charge      est possible bande passante   voir les octets
                            limitante
   (immédiat)  (immédiat)   (non établi)     (non établi)
```

**Position retenue le 20/09 : A2**, avec A3 en palier optionnel et A4 documentée.

**Livré** — A2, avec le stockage objet d'A3 : le premier seuil de bascule (un
stockage partagé entre nœuds) a été posé par le cadrage du 21/09. Le reste
d'A3 n'est pas livré : ni broker, ni outbox, ni URL présignée (le contenu est
servi par le service, [ADR-0013](adr/0013-telechargement-par-l-identite-de-l-appelant.md)).
A4 reste une piste.

La position, en une phrase :

> « Je me situe en A2. A0 et A1 sont éliminées par la charge et par la
> persistance de la file. A3 n'apporte ici que le stockage partagé et les URL
> présignées — je l'ai préparée derrière un port, et voici les trois seuils
> qui me feraient basculer. A4 est la bonne réponse si le volume change d'ordre
> de grandeur, et voici ce qu'elle coûterait en validation d'entrée. »
