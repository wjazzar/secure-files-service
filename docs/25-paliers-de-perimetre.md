# 25 — Paliers de périmètre

> **Analyse : Claude (Opus 5)** — 2026-09-20 — confrontation : [`CONFRONTATION.md`](CONFRONTATION.md)
>
> **Plan de périmètre écrit avant le code, gardé tel qu'il a été rendu.** Le
> cadrage du 21/09 a déplacé plusieurs éléments d'un palier à l'autre (le
> stockage objet est entré dès le départ, le broker est sorti). Le §6, relu
> contre le code le 02/10, dit **élément par élément ce qui a été livré**.

Principe posé par le porteur du projet :

> *Un périmètre restreint, parfaitement exécuté, plutôt qu'une couverture
> exhaustive : c'est un exercice.*

D'accord sur le principe. Mais un périmètre restreint doit signifier **un
système complet et cohérent de périmètre réduit**, jamais **un système
complet réalisé à moitié**. La différence est tout l'enjeu.

---

## 1. Le principe de découpe

Il y a deux façons de réduire, et une seule est acceptable :

| Découpe | Exemple | Résultat |
|---|---|---|
| ❌ **Horizontale** (tout, à moitié) | Tous les états implémentés, aucun testé ; l'authentification commencée ; le front à moitié | Rien n'est démontrable. Chaque question tombe sur un trou |
| ✅ **Verticale** (moins, entièrement) | Trois composants, un parcours complet, testé de bout en bout, documenté | Tout ce qui existe fonctionne et se défend |

> **Un palier n'est atteint que s'il est complet.** Mieux vaut livrer le
> palier 1 intégralement que le palier 2 à moitié.

Corollaire : le passage au palier suivant ne commence **qu'après** la
`definition of done` du palier courant.

---

## 2. Les trois paliers

### Palier 1 — « Le système est correct » (~2 jours)

**Composants** : application (2 conteneurs, 1 image) + PostgreSQL + ClamAV + front.
**Architecture** : [A2](21-architectures-candidates.md) — file portée par la base.
**Stockage** : système de fichiers, volumes Docker séparés.

| Inclus | Référence |
|---|---|
| Upload en flux, deux points d'entrée (binaire + multipart) | [P-01](20-catalogue-problematiques.md#p-01) |
| Plafond de taille appliqué pendant la lecture | [P-02](20-catalogue-problematiques.md#p-02) |
| Ordre d'écriture contenu → métadonnées | [P-03](20-catalogue-problematiques.md#p-03) [P-10](20-catalogue-problematiques.md#p-10) |
| Automate complet à 7 états, contraintes `CHECK` en base | [`23`](23-modele-de-donnees.md) |
| File en base, claim `SKIP LOCKED`, bail, reaper, backoff | [P-15](20-catalogue-problematiques.md#p-15) [P-16](20-catalogue-problematiques.md#p-16) |
| Appel antivirus en flux + stub déterministe | [P-18](20-catalogue-problematiques.md#p-18) |
| Distinction `INFECTED` / `SCAN_FAILED` / `UNSCANNABLE` | [P-20](20-catalogue-problematiques.md#p-20) |
| Isolation physique par montages Docker | [P-11](20-catalogue-problematiques.md#p-11) |
| Téléchargement par relais, table de décision complète | [P-25](20-catalogue-problematiques.md#p-25) [P-26](20-catalogue-problematiques.md#p-26) |
| En-têtes de sécurité, nom assaini, clé UUID | [P-27](20-catalogue-problematiques.md#p-27) |
| Front : dépôt + progression + états + téléchargement conditionnel | |
| Tests : domaine exhaustif, EICAR, gros fichier, concurrence | [P-32](20-catalogue-problematiques.md#p-32) |
| `docker compose up` en une commande | [P-33](20-catalogue-problematiques.md#p-33) |

**Exclu, et documenté comme tel** : stockage objet, URL présignées,
authentification réelle, broker, métriques, traces, rescan planifié.

**Ce qu'on peut démontrer** : le parcours complet, y compris EICAR ; la
résistance à l'arrêt de l'antivirus ; la reprise après mort d'un worker ;
l'inaccessibilité physique de la quarantaine.

> **Ce palier répond déjà à toutes les exigences `EX-01` à `EX-14`.** Ce qui
> suit relève du renforcement, pas de la complétude.

---

### Palier 2 — « Le système est exploitable » (+1,5 jour)

**Ajout** : MinIO. **Composants** : 4 + front.

| Ajout | Apport |
|---|---|
| Adapter S3 derrière `FileContentStore` | Stockage partagé entre nœuds, **deux adapters pour un port** — la meilleure démonstration d'hexagone |
| Isolation par IAM (deux jeux de credentials) | Même propriété que les montages, en version production |
| Téléchargement par URL présignée + mode relais conservé | Bande passante déportée |
| JWT + `tenantId` + cloisonnement testé | [P-28](20-catalogue-problematiques.md#p-28) |
| Métriques Micrometer, dont `oldest_pending_age` | [P-31](20-catalogue-problematiques.md#p-31) |
| Limite de débit, quotas, `429` sur file saturée | [P-07](20-catalogue-problematiques.md#p-07) |
| Déduplication de verdict avec contrôle de version de signatures | [P-23](20-catalogue-problematiques.md#p-23) |
| OpenAPI committé, ArchUnit, CI complète | |

**Note** : le `tenantId` est présent **dès le palier 1** dans le schéma, même
sans authentification réelle. L'ajouter après coup imposerait une migration et
la revue de chaque requête — c'est le seul élément du palier 2 qu'on anticipe.

---

### Palier 3 — « Le système est démonstratif » (+2 jours)

**Ajout** : Kafka. **Composants** : 5 + front.

| Ajout | Apport |
|---|---|
| Adapter Kafka derrière le port de file | **Deux implémentations, un seuil de bascule documenté** |
| SSE pour le suivi temps réel | [P-13 / `D-13`](20-catalogue-problematiques.md#p-13) |
| Traces OpenTelemetry | |
| Test de charge k6 avec résultats commentés | [`09`](09-strategie-de-test.md) §7 |
| Rescan planifié | [P-23](20-catalogue-problematiques.md#p-23) |

> Ce palier est celui où Kafka trouve sa place : **en second adapter**, avec
> l'analyse de ses frictions (`max.poll.interval.ms`, blocage en tête de
> partition) écrite dans le README. C'est plus valorisant que de l'avoir mis
> d'emblée.

---

## 3. Ce qui n'est dans aucun palier

À documenter en « pistes d'amélioration », **chacune avec son déclencheur** —
c'est cette formulation qui distingue un architecte d'un collectionneur de
technologies :

| Piste | Déclencheur |
|---|---|
| Upload direct au stockage ([A4](21-architectures-candidates.md)) | Quand la bande passante d'ingestion limite l'API |
| Rescan sur mise à jour des signatures | Dès que la rétention dépasse quelques jours |
| Chiffrement par clé dédiée par tenant | Exigence de conformité |
| Reprise d'upload (tus.io) | Clients mobiles, réseaux instables |
| Analyse multi-moteur | Exigence de sécurité renforcée |
| Bac à sable comportemental (zero-day) | Menace ciblée |
| CDN devant la zone servable | Fichiers populaires relus fréquemment |
| Déduplication de **stockage** | Jamais sans traiter le canal auxiliaire inter-tenant |

---

## 4. Règle d'arrêt

Le passage au palier suivant est conditionné à la `definition of done` du
palier courant :

- [ ] `git clone` + `docker compose up` + `mvnw verify` sur une machine
      vierge — **testé, pas supposé**
- [ ] Parcours de démonstration complet, EICAR compris
- [ ] Tests verts en CI
- [ ] README à jour : choix, hypothèses, pistes (`EX-12`)
- [ ] Journal des prompts à jour (`EX-13`)

Si le temps manque au milieu d'un palier : **revenir au dernier palier
complet** et documenter le reste comme piste. Livrer un palier propre avec une
section « ce que je ferais ensuite, et pourquoi dans cet ordre » vaut mieux
qu'un palier entamé — et c'est exactement la conversation
qu'annonce l'énoncé (« explorer ensemble les pistes d'évolution »).

---

## 5. Réponse synthétique

> *Un périmètre restreint, parfaitement exécuté.*

Le palier 1 représente environ **40 % de l'ambition technique** d'une solution
de production — mais **100 % des exigences de l'énoncé**, et 100 % de la
capacité à défendre les choix.

Les 60 % restants ne sont pas absents : ils sont **analysés, chiffrés et
déclenchables** dans cette documentation. Pouvoir dire
« je m'arrête ici, voici pourquoi, et voici le seuil qui me ferait continuer »
vaut mieux que d'avoir tout construit à moitié.

---

## 6. Ce qui a été livré (relu le 02/10)

Le livrable ne correspond à aucun des trois paliers tel qu'il était écrit : il
couvre le palier 1, une bonne part du palier 2, et un élément du palier 3.

### Palier 1

| Élément | Livré | Écart |
|---|---|---|
| Dépôt en flux | ✅ | **Un seul** point d'entrée (corps brut), pas de multipart |
| Plafond de taille pendant la lecture | ✅ | 500 Mo ; `Content-Length` obligatoire |
| Ordre contenu → métadonnées | ✅ | — |
| Automate, contraintes `CHECK` | ✅ | **Huit** états, et non sept ; schéma de [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §6.3 |
| File en base, claim, bail, *reaper*, backoff | ✅ | Bail à jeton, proportionnel à la taille |
| Antivirus en flux | ✅ | Pas de bouchon détectant EICAR : le vrai moteur dans les tests, WireMock pour les pannes |
| Panne distincte du verdict | ✅ | `RETRY_WAIT` / `FAILED_FINAL` à la place de `SCAN_FAILED` |
| Isolation de la quarantaine | ✅ | Par identités de stockage, pas par montages Docker ; **un** conteneur applicatif, pas deux |
| Téléchargement par relais, table de décision | ✅ | — |
| En-têtes de sécurité, nom assaini, clé UUID | ✅ | — |
| Interface : dépôt, suivi, téléchargement | ✅ | Plus un tableau paginé et le détail |
| Tests : domaine, EICAR, gros fichier, concurrence | ✅ | — |
| Démarrage en une commande | ✅ | `scripts/start` |

### Palier 2

| Élément | Livré | Écart |
|---|---|---|
| Stockage objet compatible S3 | ✅ dès le départ | SeaweedFS plutôt que MinIO ; un port par rôle, pas deux adaptateurs derrière `FileContentStore` |
| Isolation par identités | ✅ | **Trois** identités, pas deux |
| Téléchargement par URL présignée | ❌ | Écarté ([ADR-0013](adr/0013-telechargement-par-l-identite-de-l-appelant.md)) ; piste au README §10 |
| Authentification et cloisonnement | ✅ | Keycloak (OpenID Connect) ; **pas de `tenantId`**, ni au schéma ni ailleurs : cloisonnement par propriétaire |
| Métriques Micrometer | ✅ | Plus Prometheus et Grafana |
| `429` sur file saturée | ✅ | Et sur les dépôts simultanés d'un nœud |
| Limite de débit, quotas | ❌ | Limitations acceptées (README §10) |
| Déduplication de verdict | ❌ | `D-08`, reportée |
| OpenAPI versionné, ArchUnit | ✅ | — |
| Intégration continue | ❌ | Décision du 01/10 |

### Palier 3

| Élément | Livré | Écart |
|---|---|---|
| Adaptateur Kafka | ❌ | Aucun broker ([ADR-0001](adr/0001-la-base-de-donnees-fait-file.md)) |
| SSE | ❌ | Suivi par polling, `ETag` et `Retry-After` |
| Traces OpenTelemetry | ❌ | — |
| Essais de charge k6, résultats commentés | ✅ | [`capacity-planning/`](capacity-planning/README.md) |
| Réanalyse planifiée | ❌ | Piste au README §10 |

La règle d'arrêt du §4 parlait de « tests verts en CI » : sans intégration
continue, ce sont `./mvnw verify`, `npm test` et `scripts/check` sur le poste.
