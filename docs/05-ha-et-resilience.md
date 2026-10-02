# 05 — Haute disponibilité et résilience

> **Analyse écrite avant le code** (série 00), **relue contre le code livré le
> 02/10**. Chaque section garde le raisonnement et dit ce qui est **livré**, ce
> qui a été **écarté** et pourquoi. Le système livré fait foi dans
> [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §11 et §12 ; les
> mesures sont dans [`capacity-planning/`](capacity-planning/README.md).
> Couvre : `EX-06` (nombreux utilisateurs simultanés), `EX-08` (dépendance à
> l'antivirus).

---

## 1. Principe directeur : découpler les disponibilités

L'architecture sépare **trois disponibilités indépendantes** :

| Capacité | Dépend de | Reste disponible si… |
|---|---|---|
| **Recevoir** un fichier | service + stockage + base | l'antivirus est en panne |
| **Analyser** un fichier | service + stockage + base + antivirus | — |
| **Servir** un fichier validé | service + stockage + base | l'antivirus est en panne |

C'est **l'argument central** : une panne totale de l'antivirus ne rend
indisponible **ni le dépôt, ni le téléchargement des fichiers déjà validés**.
Elle allonge la file d'attente. Le système dégrade au lieu de tomber
(`scripts/demo.sh --resilience` le rejoue).

Et il dégrade dans le bon sens : **fermé par défaut**. Ce qui est perdu, c'est
la fraîcheur (des fichiers restent en attente), jamais la garantie.

Il n'y a **ni broker ni outbox**
([ADR-0001](adr/0001-la-base-de-donnees-fait-file.md)) : la base fait file, la
ligne du fichier est le travail. La base est donc nécessaire aux trois
capacités — c'est la source de vérité de l'invariant, et c'est assumé.

---

## 2. Montée en charge horizontale

### Aucun état dans un nœud
- Aucun état en mémoire entre deux requêtes, aucune affinité. La session du
  navigateur existe, mais elle vit **dans PostgreSQL** (Spring Session JDBC,
  [ADR-0012](adr/0012-session-navigateur-client-confidentiel.md)) : n'importe
  quel nœud la sert. Un système tiers (`Bearer`) n'a pas de session.
- Aucun disque local : les fichiers vont en flux au stockage objet, sans
  fichier temporaire.
- Les baux et les échéances sont lus sur **l'horloge de la base**, pas sur
  celle d'un nœud.

Le multi-nœuds est **pensé, pas démontré** : le cadrage du 21/09 ne demandait
pas de le prouver, et la montée en charge horizontale n'a pas été mesurée
(README §10).

### Un seul processus, pas un découpage API / worker
L'analyse initiale proposait `N` réplicas d'API et `M` réplicas de worker,
dimensionnés séparément. **Écarté** (décision du 27/09,
[ADR-0002](adr/0002-un-processus-trois-identites.md)) : un seul processus porte
le dépôt, l'analyse et la livraison. La séparation qui protège est celle des
**identités de stockage**, pas celle des processus. Deux interrupteurs
(`WORKER_ENABLED`, `SCHEDULING_ENABLED`) permettraient de démarrer le même
artefact sans ses boucles d'analyse, si l'analyse devait un jour monter en
charge à part.

Les deux profils de charge restent distincts, et bornés chacun de son côté :

| | Dépôts et lectures | Analyse |
|---|---|---|
| Contrainte dominante | réseau, connexions à la base | CPU et mémoire de l'antivirus, débit de lecture |
| Borne | 50 dépôts simultanés par nœud ; pool `api` (10 connexions) | 4 boucles d'analyse par nœud ; pool `queue` (boucles + 2) |
| Critère de dimensionnement | latence, refus `429` | retard de la file, âge du plus vieux fichier en attente |

### Threads virtuels (Java 21)
Le service est massivement **bloquant sur des entrées-sorties** (stockage,
antivirus). Les threads virtuels (`spring.threads.virtual.enabled=true`)
tiennent beaucoup de requêtes concurrentes sans modèle réactif.

> WebFlux serait défendable, mais il complique le flux, le débogage et
> l'intégration de bibliothèques bloquantes pour un gain nul ici. MVC + threads
> virtuels est un arbitrage **explicite**
> ([ADR-0010](adr/0010-mvc-et-threads-virtuels.md)).

⚠️ Avec les threads virtuels, la taille d'un pool n'est **plus** une limite
pour des tâches soumises (un thread par requête) : d'où une borne explicite sur
les dépôts.

### Contre-pression
Refuser proprement vaut mieux que s'écrouler. Livré, **avant** la lecture du
corps :

- 51ᵉ dépôt simultané sur un nœud → `429 TOO_MANY_CONCURRENT_UPLOADS`,
  `Retry-After: 1`, sans toucher la base ;
- 500 fichiers déjà en attente (non terminaux) →
  `429 TOO_MANY_PENDING_FILES` + `Retry-After` ; le compte est relu au plus
  toutes les 250 ms ;
- un corps qui n'arrive pas dans son échéance (60 s + 8 s par Mio annoncé) →
  `408 UPLOAD_TOO_SLOW`.

**Non livrés**, et assumés (README §10) : quotas par utilisateur, limitation
de débit par client. Une limite de débit sur plusieurs nœuds demande des
compteurs partagés ; l'hypothèse est celle d'une passerelle d'API devant le
service (README §9). Bucket4j, cité dans la première version de ce document,
n'a pas été ajouté.

---

## 3. Résilience sur l'antivirus (le point de défaillance le plus probable)

C'est la dépendance la plus lente, la plus gourmande et la plus susceptible de
tomber. La première version de ce document proposait une bibliothèque de
résilience (coupe-circuit, *bulkhead*, réessais en mémoire). **Écarté**
([P-21](20-catalogue-problematiques.md#p-21)) : la file porte déjà ces
fonctions, et une bibliothèque aurait créé deux politiques de réessai
concurrentes, l'une en mémoire, l'autre persistée.

| Fonction | Ce qui est livré | Pourquoi |
|---|---|---|
| **Timeout de connexion** | 2 s | Détecter vite une indisponibilité |
| **Timeout de lecture** | 10 min, fixe : plus long que l'analyse d'un fichier de 500 Mo | Un client qui abandonne avant le moteur laisse celui-ci travailler pour personne |
| **Échéance du transfert** | La lecture de la quarantaine est bornée par le bail du fichier (30 s + 1,2 s par Mio) | Un délai de lecture ne mesure que le silence entre deux paquets |
| **Réessais** | 5 tentatives, backoff exponentiel de 10 s à 15 min, **avec ±20 % d'aléa**, dans les colonnes `attempts` et `next_attempt_at` | Persisté : survit au redémarrage. Sans aléa, tous les fichiers repartent en même temps après un incident |
| **Coupe-circuit** | **Portillon de santé** : le worker ne prend pas de travail quand l'antivirus ne répond pas (`GET /`, délai 2 s) | Sans lui, chaque fichier userait ses cinq tentatives pendant la panne et finirait `FAILED_FINAL` |
| **Limite de concurrence** | Un **nombre fixe de boucles** (4 par nœud), chacune menant une analyse à la fois | Protéger l'antivirus du service. Un sémaphore en plus ne pouvait jamais bloquer : retiré le 30/09 |
| **Échec définitif** | `FAILED_FINAL` (statut public `FAILED`), visible et audité | Il n'y a pas de file de rebut ni de point d'entrée de rejeu manuel |

### Distinguer les classes d'échec
Toutes les réponses ne se traitent pas de la même façon. Table livrée
(`HttpAntivirusScanner`), chaque ligne mesurée contre le moteur réel :

| Réponse de l'antivirus | Interprétation | Suite |
|---|---|---|
| `200` | verdict sain | promotion (`PROMOTING`, puis `AVAILABLE`) |
| `406`, nom de menace | `INFECTED` | **terminal**, pas de réessai |
| `406`, `Heuristics.Limits.Exceeded…` | `UNSCANNABLE` : une limite du moteur est atteinte | **terminal** |
| `412` | `UNSCANNABLE` : le moteur n'a pas su lire le contenu | **terminal** |
| `413` | **panne**, pas un verdict : l'enrobage le renvoie aussi quand le flux casse, et le service n'envoie jamais plus que le moteur n'accepte | réessai |
| toute autre réponse, délai dépassé, connexion rompue | panne | réessai, puis `FAILED_FINAL` à la cinquième tentative |

Un `406` dont le corps est illisible est enregistré comme infecté : la
direction prudente. Il n'y a pas de catégorie « erreur de contrat sans
réessai » : une réponse qui n'est pas un verdict est une panne comme une
autre, bornée par le nombre de tentatives.

---

## 4. Tolérance aux pannes des autres dépendances

| Dépendance | Panne | Comportement livré |
|---|---|---|
| **Stockage objet** | indisponible | Dépôt et téléchargement → `503 SERVICE_UNAVAILABLE` + `Retry-After`. Rien n'est écrit en base sans que l'objet existe. Les métadonnées restent consultables |
| **Base de données** | indisponible | Les requêtes répondent `503`. Pas de mode dégradé : la base est la source de vérité. Un worker réécrit un résultat déjà acquis trois fois, puis s'en remet au bail ; les jauges valent `NaN`, jamais un zéro rassurant |
| **Antivirus** | indisponible | Dépôts et téléchargements continuent ; la file attend, sans consommer de tentative |
| **Keycloak** | indisponible | Une session ouverte reste servie sur son dernier verdict, 30 min au plus ; pas de nouvelle connexion |
| **Un nœud** | mort | Les autres reprennent ses fichiers à l'expiration des baux (*reaper*, toutes les 30 s) |

### Ordre des opérations au dépôt (important)

```
1. écrire l'objet dans la quarantaine        ← si ça échoue : rien n'existe, 503
2. écrire { la ligne du fichier (+ la clé d'idempotence) } en UNE transaction
3. répondre 202
```

Une panne entre 1 et 2 laisse un **objet orphelin** dans la quarantaine —
inoffensif par construction (référencé nulle part, dans une zone non
servable), supprimé par un balayage toutes les 10 minutes quand il a plus de
deux heures. L'ordre inverse laisserait une **ligne sans contenu** : visible,
et dont le téléchargement échouerait de façon incompréhensible.

> Principe : **en cas de panne partielle, préférer l'orphelin invisible à la
> référence brisée visible.**

---

## 5. Cycle de vie des instances

| Sujet | Ce qui est livré | Piège évité |
|---|---|---|
| **Sonde de vivacité** | `/actuator/health/liveness` : le processus seul, aucune dépendance | Une sonde qui teste la base fait redémarrer en boucle tous les nœuds quand la base tombe |
| **Sonde de disponibilité** | `/actuator/health/readiness` : l'état de disponibilité de Spring Boot. Ni l'antivirus ni l'invariant n'y entrent | Retirer du service des nœuds capables de servir les fichiers déjà disponibles |
| **Santé agrégée** | `/actuator/health` : base, plus l'indicateur `invariant` (`DOWN` s'il est violé) | Tous les nœuds lisent la même base : l'alarme sonne, la décision reste humaine |
| **Port** | Les sondes et les métriques sont sur le port de management (8091), pas sur celui de l'API | `/actuator` lisible par l'adresse publique (audit S-08) |
| **Arrêt propre** | `server.shutdown=graceful`, 30 s par phase | — |
| **Worker à l'arrêt** | Plus de prise de travail ; 20 s laissées aux analyses en cours ; puis **libération explicite des baux**, sans consommer de tentative | Sans libération, le travail attend l'expiration du bail |
| **Migrations** | Flyway au démarrage, avec le rôle propriétaire du schéma | Le rôle d'exécution ne peut pas modifier le schéma |

Un dépôt de 500 Mo peut durer plus que le délai d'arrêt : il est alors coupé,
et le client le relance avec sa clé d'idempotence. Aucun déploiement en
production n'est livré (README §9) : sondes de démarrage et déploiement
progressif sont des sujets d'orchestrateur, non traités.

---

## 6. Observabilité (condition de la disponibilité réelle)

Un système « hautement disponible » dont on ne voit pas la dégradation ne l'est
pas. Métriques exposées (Micrometer → `/actuator/prometheus`, port de
management) ; liste complète dans
[`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §12 :

| Métrique | Type | Pourquoi elle compte |
|---|---|---|
| `praxedo.upload.bytes` / `praxedo.download.bytes` | compteur | Débit agrégé |
| `praxedo.scan.duration` | histogramme | Dimensionnement des boucles et des délais |
| `praxedo.scan.verdict{result}` | compteur | Sain / infecté / non analysable |
| `praxedo.queue.depth`, `praxedo.queue.depth.bytes` | jauges | Retard, en fichiers et en octets |
| `praxedo.queue.oldest_pending_age` | jauge | **La métrique d'alerte** : depuis quand le plus vieux fichier attend |
| `praxedo.pipeline.lag{outcome}` | histogramme | Du dépôt à l'état final, par fichier |
| `praxedo.antivirus.available` | jauge | État du portillon de santé |
| `praxedo.admission.rejected{reason}` | compteur | Contre-pression (`429`) |
| `praxedo.work.technical_failures`, `praxedo.scan.failures` | compteurs | Échecs techniques, appels sans verdict |
| `praxedo.invariant.violations` | jauge | **Doit rester à zéro** |

Journaux : identifiant de corrélation par requête (`X-Request-Id`), `fileId`
dans le contexte du worker, JSON au format ECS avec le profil `json-logs`.

**Non livré** : les traces distribuées (OpenTelemetry) proposées au départ.
Le parcours d'un fichier se lit dans le journal d'audit (`file_audit_event`),
qui garde chaque changement d'état avec son acteur.

**Objectif** : répondre vite à « pourquoi ce fichier n'est-il pas encore
téléchargeable ? » — par son statut, son motif (`statusReason`) et son
historique d'audit, sans lire de code.
