# 12 — Registre des décisions ouvertes

> **Registre clos le 01/10.** Chaque décision est actée ou reportée ; le
> détail vit dans les ADR ([`adr/`](adr/README.md)) et les documents cités.
> Les sections ci-dessous gardent les options et la recommandation **du
> moment** : l'écart entre la recommandation et la décision fait partie de
> l'histoire du projet.

**Statuts** : `OUVERT` (à trancher) · `ACTÉ` (décidé, en vigueur) ·
`REPORTÉ` (hors périmètre de l'exercice, documenté en piste d'amélioration).

---

## Tableau de bord

| ID | Sujet | Décision retenue | Référence | Statut |
|---|---|---|---|---|
| [D-01](#d-01) | Infrastructure locale | PostgreSQL, stockage objet S3 (SeaweedFS), ClamAV par API HTTP, Keycloak ; supervision Prometheus + Grafana | `docker-compose.yml`, ADR-0004 | `ACTÉ` |
| [D-02](#d-02) | Isolation des fichiers non validés | Deux zones, trois identités de stockage ; promotion par relecture vérifiée | ADR-0002, ADR-0006 | `ACTÉ` |
| [D-03](#d-03) | Transport des travaux de scan | La base fait file (`FOR UPDATE SKIP LOCKED`), aucun broker ; reaper | ADR-0001 | `ACTÉ` |
| [D-04](#d-04) | Chemin d'ingestion | Dépôt en flux à travers le service, une seule requête | [`28`](28-precisions-de-cadrage.md) §1 | `ACTÉ` |
| [D-05](#d-05) | Chemin de téléchargement | Servi par le service, sous l'identité de l'appelant, sans lien signé | ADR-0013 | `ACTÉ` |
| [D-06](#d-06) | Mode synchrone optionnel | Non : analyse asynchrone seulement | [`28`](28-precisions-de-cadrage.md) §4 | `ACTÉ` |
| [D-07](#d-07) | Devenir des fichiers infectés | Gardés en quarantaine, jamais servis, audit permanent ; purge en piste | ADR-0009, README §10 | `ACTÉ` |
| [D-08](#d-08) | Déduplication par empreinte | Aucune en v1 ; réutilisation de verdict en piste | README §10 | `REPORTÉ` |
| [D-09](#d-09) | Réponse HTTP sur fichier infecté | `409 FILE_INFECTED` au propriétaire, `404` à tout autre | [`contracts/README.md`](../contracts/README.md) §2.7 | `ACTÉ` |
| [D-10](#d-10) | Authentification | Keycloak, toujours exigée : session pour le navigateur, `Bearer` pour les tiers | ADR-0012, ADR-0014 | `ACTÉ` |
| [D-11](#d-11) | Multi-tenant | Pas de `tenantId` : cloisonnement par propriétaire | [`contracts/README.md`](../contracts/README.md) §2.10 | `ACTÉ` |
| [D-12](#d-12) | Périmètre du front React | Dépôt → suivi → téléchargement, plus tableau paginé et détail | [`frontend/ARCHITECTURE.md`](../frontend/ARCHITECTURE.md) | `ACTÉ` |
| [D-13](#d-13) | Notification de changement de statut | Polling + `ETag` + `Retry-After` | [`contracts/README.md`](../contracts/README.md) §2.9 | `ACTÉ` |
| [D-14](#d-14) | Précisions de cadrage | Obtenues le 21/09 | [`28`](28-precisions-de-cadrage.md) | `ACTÉ` |
| [D-15](#d-15) | Au-delà de la taille scannable | 500 Mo pour l'admission comme pour l'analyse, `413` au-delà | ADR-0005, [`28`](28-precisions-de-cadrage.md) §1 | `ACTÉ` |
| [D-16](#d-16) | Structure du dépôt | Monorepo, un seul module Maven, un seul processus | ADR-0002, ADR-0011 | `ACTÉ` |
| [D-17](#d-17) | Publication de l'énoncé | Résumé + table des exigences `EX-xx` | [`00`](00-enonce.md) | `ACTÉ` |
| [D-18](#d-18) | Topologie des rôles | Un seul processus ; l'isolation tient par les identités du stockage | ADR-0002 | `ACTÉ` |
| [D-19](#d-19) | Sens de `CLEAN` | `CLEAN` est un verdict, `AVAILABLE` le seul état servable, `PROMOTING` intercalé | [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §4 | `ACTÉ` |

---

<a id="d-01"></a>
## D-01 — Ambition de l'infrastructure locale 🔴

> ✅ **Acté** : PostgreSQL, stockage objet compatible S3 (SeaweedFS, choisi plutôt que MinIO), ClamAV derrière une API HTTP et Keycloak ; Prometheus et Grafana pour la supervision. Le stockage objet est entré au palier 1 avec le cadrage du 21/09 ([`28`](28-precisions-de-cadrage.md) §3). Voir `docker-compose.yml` et ADR-0004.

**Question** : quels composants dans le `docker-compose` ?

| Option | Composants | Ce qu'on démontre | Coût |
|---|---|---|---|
| A. Complet | PG + MinIO + RabbitMQ + ClamAV | Architecture cible entière : outbox, broker, DLQ, worker séparé | ~2-3 j |
| **B. Intermédiaire** ⭐ | PG + MinIO + ClamAV | Async réel, isolation, streaming, résilience. Broker en évolution | ~1,5-2 j |
| C. Minimal | PG + ClamAV | Cœur fonctionnel seul | ~1 j |

**Impact** : `D-03`, `D-02`, plan de livraison.
**Argument** : l'écart d'apport entre B et A est plus faible que l'écart de coût.
📄 Détail : [`21-architectures-candidates.md`](21-architectures-candidates.md)

---

<a id="d-02"></a>
## D-02 — Isolation des fichiers non validés 🔴

> ✅ **Acté** : option A, deux zones et trois identités de stockage (`ingest`, `worker`, `delivery`) : l'identité qui sert les fichiers n'a aucun droit sur la quarantaine. Promotion par relecture vérifiée, sans zone temporaire. ADR-0002, ADR-0006.

**Question** : comment empêcher physiquement le service d'un fichier non validé ?

| Option | Défense | Contrepartie |
|---|---|---|
| **A. Deux zones + credentials séparés** ⭐ | 3 niveaux — un bug applicatif ne suffit pas | Copie serveur-à-serveur à la promotion |
| B. Zone unique + garde applicative | 2 niveaux | L'invariant ne tient que par le code |
| C. Hybride par seuil de taille | 3 niveaux sur la majorité du volume | Le gros fichier, le plus exposé, est le moins protégé |

**Impact** : modèle de domaine (`StorageZone`), tests de sécurité.
**Argument** : c'est le point d'architecture le plus fort du projet.
📄 Détail : [`21-architectures-candidates.md`](21-architectures-candidates.md), [`02-invariant-et-domaine.md`](02-invariant-et-domaine.md) §2

---

<a id="d-03"></a>
## D-03 — Transport des travaux de scan 🔴

> ✅ **Acté** : option B sans outbox : la ligne du fichier **est** le travail, état et travail s'écrivent dans la même transaction. `FOR UPDATE SKIP LOCKED`, bail à jeton unique, reaper. Le cadrage a confirmé qu'aucun broker n'était attendu. ADR-0001.

| Option | Avantages | Contreparties |
|---|---|---|
| **A. Outbox + RabbitMQ** ⭐ (si `D-01`=A) | Découplage réel, DLQ, backpressure visible | Un composant de plus |
| **B. Outbox + `SKIP LOCKED`** ⭐ (si `D-01`=B) | Transactionnel nativement, un composant en moins | Latence de polling, DLQ à coder |
| C. Kafka | Replay, très haut débit | Sur-dimensionné pour ce volume |

**Non négociable dans tous les cas** : le pattern outbox **et** le reaper.
📄 Détail : [`21-architectures-candidates.md`](21-architectures-candidates.md)

---

<a id="d-04"></a>
## D-04 — Chemin d'ingestion 🔴

> ✅ **Acté** : option A, dépôt en flux à travers le service, en une seule requête : ni URL présignée ni envoi par morceaux ([`28`](28-precisions-de-cadrage.md) §1).

| Option | Avantages | Contreparties |
|---|---|---|
| **A. Proxy streaming** ⭐ | Contrôle total : taille réelle, hash, quotas, authz | L'API porte la bande passante |
| B. URL présignée directe | Scalabilité maximale | Validation *a posteriori*, uploads abandonnés, CORS |
| C. Hybride par seuil | Le meilleur des deux | Deux chemins à maintenir |

**Impact** : cœur de l'implémentation, phase 3 du plan.
📄 Détail : [`21-architectures-candidates.md`](21-architectures-candidates.md)

---

<a id="d-05"></a>
## D-05 — Chemin de téléchargement 🟠

> ✅ **Acté** : ni redirection présignée ni lien signé : le contenu est servi par le service, sous l'identité de l'appelant, avec l'état relu juste avant l'ouverture (ADR-0013, qui remplace ADR-0007). L'URL présignée émise après revérification reste en piste (README §10).

| Option | Avantages | Contreparties |
|---|---|---|
| A. Redirection présignée | Bande passante déportée ; `Range` et CDN gratuits | URL = porteur d'autorisation ; comptage à l'émission |
| B. Proxy streaming | Contrôle et audit exacts | L'app porte la bande passante de sortie |
| **C. Les deux** ⭐ | A par défaut, B en option | Deux chemins, mais B est trivial |

📄 Détail : [`21-architectures-candidates.md`](21-architectures-candidates.md), [`06-securite-et-angles-morts.md`](06-securite-et-angles-morts.md) §8

---

<a id="d-06"></a>
## D-06 — Mode synchrone optionnel 🟢

> ✅ **Acté** : non codé. L'analyse est asynchrone, le dépôt répond `202` ; confirmé lors du cadrage ([`28`](28-precisions-de-cadrage.md) §4).

Offrir en plus un `?wait=true` bloquant pour les petits fichiers ?

> ⭐ **Recommandation** : ne pas coder, documenter avec ses garde-fous.

📄 Détail : [`21-architectures-candidates.md`](21-architectures-candidates.md)

---

<a id="d-07"></a>
## D-07 — Devenir des fichiers infectés 🟠

> ✅ **Acté** : conservés en quarantaine, jamais servis ; le balayage de la quarantaine les épargne, comme preuve. Le journal d'audit est écrit par la base, en ajout seul (ADR-0009). La purge planifiée reste une piste (README §10).

| Option | Pour | Contre |
|---|---|---|
| Purge immédiate | Aucun stockage de malware | Perte de la preuve, aucune analyse post-incident possible |
| **Quarantaine + rétention 30 j + audit permanent** ⭐ | Auditabilité, analyse d'incident | On héberge du contenu malveillant (isolé, jamais servi) |
| Conservation indéfinie | — | Coût et risque croissants sans bénéfice |

📄 Détail : [`06-securite-et-angles-morts.md`](06-securite-et-angles-morts.md)

---

<a id="d-08"></a>
## D-08 — Déduplication par empreinte 🟠

> ⏸️ **Reporté** : aucune déduplication en v1 : deux dépôts identiques font deux fichiers et deux analyses. La réutilisation de verdict, conditionnée à la version des signatures et à l'âge du verdict, est en piste (README §10).

Le SHA-256 est calculé gratuitement pendant l'upload. Qu'en fait-on ?

| Niveau | Recommandation | Justification |
|---|---|---|
| Dédup de **verdict** | ⭐ **Oui**, sous conditions (résultat, version de signatures, âge < 24 h) | Économise les scans sans périmer la garantie |
| Dédup de **stockage** | ❌ Non | Comptage de références, suppression, **canal auxiliaire inter-tenant** |

⚠️ Sans le contrôle de version de signatures, la dédup de verdict est une
**faille** : on servirait indéfiniment un fichier déclaré sain avant que sa
signature n'existe.
📄 Détail : [`04-idempotence.md`](04-idempotence.md) §2

---

<a id="d-09"></a>
## D-09 — Réponse HTTP sur fichier infecté 🟢

> ✅ **Acté** : `409 FILE_INFECTED` au propriétaire ; `404 FILE_NOT_FOUND` à tout autre utilisateur, puisque le cloisonnement par propriétaire rend le fichier invisible ([`contracts/README.md`](../contracts/README.md) §2.7).

| Option | Pour | Contre |
|---|---|---|
| `403` explicite | Expérience claire | Révèle l'existence et le verdict |
| `404` opaque | Aucune divulgation | Incompréhensible pour le propriétaire légitime |
| **Mixte** ⭐ | `403` au propriétaire, `404` aux autres | Une règle de plus à tester |

📄 Détail : [`06-securite-et-angles-morts.md`](06-securite-et-angles-morts.md) §5, [`contracts/README.md`](../contracts/README.md) §2.7

---

<a id="d-10"></a>
## D-10 — Authentification 🟠

> ✅ **Acté** : OAuth2 avec Keycloak, **toujours exigée**, sans mode anonyme. Le navigateur n'a qu'un cookie de session, le service étant client confidentiel ; les systèmes tiers présentent un jeton `Bearer`. ADR-0012, ADR-0014 (qui remplace ADR-0008).

| Option | Effort | Apport |
|---|---|---|
| OAuth2 Resource Server + Keycloak | Élevé | Fort mais hors sujet central |
| **JWT validé par clé de test, sans IdP** ⭐ | Faible | Prise en compte démontrée sans monter une infra d'identité |
| Clé d'API (tiers) + JWT (utilisateurs) | Moyen | Réaliste vis-à-vis de `EX-02` |
| Aucune | Nul | **Affaiblit** un service dont la sécurité est le sujet |

**Dans tous les cas** : le contrôle d'appartenance est obligatoire.
📄 Détail : [`06-securite-et-angles-morts.md`](06-securite-et-angles-morts.md) §7

---

<a id="d-11"></a>
## D-11 — Multi-tenant 🔴

> ✅ **Acté** : pas de `tenantId`. Chaque fichier appartient à un propriétaire, tiré du jeton ; toutes les lectures filtrent sur lui ([`contracts/README.md`](../contracts/README.md) §2.10). Le passage à des organisations serait une colonne et un filtre de plus.

**Question** : porte-t-on un `tenantId` dès le départ ?

> ⭐ **Recommandation** : **oui**. L'énoncé parle de « clients » de
> l'entreprise. Ajouter la notion après coup impose une migration et la revue
> de **chaque** requête d'autorisation. Le coût initial est d'un champ et
> d'un filtre systématique ; le coût différé est bien plus élevé.

**Impact** : schéma de base, toutes les requêtes, tests de cloisonnement. **Bloquant.**

---

<a id="d-12"></a>
## D-12 — Périmètre du front React 🟢

> ✅ **Acté** : le parcours dépôt → suivi → téléchargement, plus le tableau paginé (recherche, filtre par statut, tri) et le panneau de détail, connexion Keycloak comprise ([`frontend/ARCHITECTURE.md`](../frontend/ARCHITECTURE.md)).

| Option | Contenu |
|---|---|
| **Fonctionnel, soigné, mince** ⭐ | Dépôt + progression, liste avec badges d'état rafraîchis, téléchargement conditionnel, cas d'erreur explicites |
| Riche | + authentification, filtres, historique, tableau de bord |
| Strictement minimal | Une page brute |

⚠️ L'écosystème React est une **contrainte explicite** (`EX-09`) : le front
mérite le même soin que le reste.

---

<a id="d-13"></a>
## D-13 — Notification de changement de statut 🟢

> ✅ **Acté** : polling avec `ETag` et `Retry-After` ([`contracts/README.md`](../contracts/README.md) §2.9). SSE et webhook restent hors périmètre.

| Option | Effort | Limite |
|---|---|---|
| **Polling + `ETag` + `Retry-After`** ⭐ | Faible | Latence, trafic |
| SSE | Moyen | Avec N réplicas, nécessite un fanout ou un polling serveur |
| Webhook (systèmes tiers) | Moyen | Retry, signature, idempotence côté récepteur |

📄 Détail : [`contracts/README.md`](../contracts/README.md) §2.9

---

<a id="d-14"></a>
## D-14 — Précisions de cadrage 🟠

> ✅ **Acté** : précisions obtenues le 21/09 ([`28`](28-precisions-de-cadrage.md)).

Les points que l'énoncé laissait ouverts (taille, volumétrie, déploiement,
analyse, authentification, outils, interface) sont précisés tôt, pendant la
construction du socle, plutôt que tranchés seuls par hypothèse.

---

<a id="d-15"></a>
## D-15 — Comportement au-delà de la taille scannable 🔴

> ✅ **Acté** : le cadrage a borné la taille à 500 Mo ([`28`](28-precisions-de-cadrage.md) §1). L'antivirus est configuré pour analyser tout ce qui est admis : au-delà de 500 Mo, `413 FILE_TOO_LARGE` dès la réception. `UNSCANNABLE` ne sert plus qu'aux limites découvertes pendant l'analyse (archives). ADR-0005.

**Contexte** : ClamAV plafonne (par défaut, en version 1.4 : `StreamMaxLength`
et `MaxFileSize` à 100 Mo, `MaxScanSize` à 400 Mo). Un fichier de plusieurs Go
n'est pas analysable tel quel.

| Option | Pour | Contre |
|---|---|---|
| Refus à l'ingestion (`413`) | Honnête et simple | Contredit « tailles très variables » (`EX-07`) |
| **Accepter en `UNSCANNABLE`** ⭐ | Fidèle à `EX-07` **et** à `EX-03` : conservé, jamais servi, motif explicite | Un état de plus dans l'automate |
| Découpage en morceaux | — | ❌ **Faux sentiment de sécurité** : une signature peut chevaucher deux morceaux |

**Impact** : automate, contrat d'API, plafonds ClamAV, README.
**Argument** : probablement le meilleur contenu du README — il prouve qu'on a
lu la documentation de la dépendance.
📄 Détail : [`06-securite-et-angles-morts.md`](06-securite-et-angles-morts.md) §1

---

<a id="d-16"></a>
## D-16 — Structure du dépôt 🟠

> ✅ **Acté** : monorepo ; côté back-end, un seul module Maven et un seul processus, les couches vérifiées par ArchUnit. ADR-0002, ADR-0011.

**Monorepo** (`backend/` + `frontend/`) ⭐ vs deux dépôts.
Critère dominant : tout doit se lancer en une commande.

---

<a id="d-17"></a>
## D-17 — Publication de l'énoncé dans le dépôt public 🟢

Le dépôt est public (`EX-10`). Publier l'énoncé verbatim d'un test de
recrutement peut déplaire à l'entreprise (contenu d'évaluation indexé).

| Option | Pour | Contre |
|---|---|---|
| Verbatim | Traçabilité maximale des exigences | Peut contrarier l'entreprise |
| **Résumé + table des exigences `EX-xx`** ⭐ | Traçabilité conservée, discrétion respectée | — |
| Exclu du dépôt | Discrétion maximale | Le lecteur perd le contexte |

**Décision (01/10)** : résumé + table des exigences `EX-xx`. Le texte
intégral est retiré de [`00-enonce.md`](00-enonce.md) ; les extraits cités
dans la table se limitent à quelques mots.

📄 Détail : [`00-enonce.md`](00-enonce.md)

---

<a id="d-18"></a>
## D-18 — Topologie des rôles 🟠

> ✅ **Acté** : un seul processus et un seul module. L'isolation ne passe pas
> par des processus séparés mais par trois identités de stockage aux droits
> disjoints : l'identité de livraison ne peut pas lire la quarantaine.
> ADR-0002.

**Question** (issue de la contre-analyse, [`chatgpt/22`](chatgpt/22-verdict-et-arbitrages.md) §4) :
comment concilier l'écriture en quarantaine au dépôt avec l'absence d'accès à
la quarantaine depuis le chemin de livraison ? Deux rôles sont impossibles ;
trois rôles en processus séparés étaient la première réponse. Depuis le
passage au stockage objet ([`28`](28-precisions-de-cadrage.md) §3), des
identifiants distincts suffisent.

---

<a id="d-19"></a>
## D-19 — Sens de `CLEAN` et état téléchargeable 🟠

> ✅ **Acté** : `CLEAN` est un résultat d'analyse ; `AVAILABLE` est le seul état
> servable, atteint après une promotion vérifiée dans l'état `PROMOTING`
> ([`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §4,
> [`26`](26-reponse-a-la-contre-analyse.md) §2.6).

**Question** (issue de la contre-analyse) : `CLEAN` décrit-il le verdict ou la
disponibilité ? Les confondre crée un état incohérent : « sain en base, mais
absent de la zone servable ».

---

## Procédure de clôture d'une décision

1. Mettre à jour la ligne du tableau de bord : `OUVERT` → `ACTÉ`.
2. Créer `docs/adr/NNNN-<titre>.md` (Contexte / Décision / Alternatives
   écartées / Conséquences / Statut).
3. Répercuter dans les documents impactés (listés sous « 📄 Détail »).
4. Mettre à jour l'en-tête du registre s'il était clos.
