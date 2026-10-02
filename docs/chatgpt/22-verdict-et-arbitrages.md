# 22 — Verdict ChatGPT et arbitrages recommandés

> **Analyse : ChatGPT (Codex)** — 2026-09-21 — confrontation :
> [`../CONFRONTATION.md`](../CONFRONTATION.md)
>
> Les valeurs ci-dessous sont des **recommandations ChatGPT**, pas des
> décisions finales. Elles ne changent aucun statut `OUVERT` du registre
> `D-01…D-17` et ne valent pas ADR.

---

## 1. Verdict général

> **GO conditionnel sur A2-R ; NO-GO sur le modèle actuel.**

Le projet doit conserver son architecture sobre, sans broker et sans stockage
objet au premier périmètre. Avant de coder, il faut toutefois accepter les
corrections structurantes suivantes :

1. trois rôles applicatifs (`ingest`, `delivery`, `worker`) ;
2. `CLEAN` devient un résultat, `AVAILABLE` devient le seul état servable ;
3. ajout de `RETRY_WAIT`, `PROMOTING` et `FAILED_FINAL` ;
4. token UUID par claim et CAS sur ce token ;
5. promotion par copie vérifiée et reprise ;
6. contraintes SQL totales, testées avec `NULL` ;
7. refus `413` des tailles connues comme non scannables ;
8. aucun cache de verdict au premier périmètre ;
9. sémaphore ou exécuteur borné devant ClamAV ;
10. configuration ClamAV épinglée et tests de ses limites.

Ces corrections renforcent la solution sans ajouter de composant externe.

---

## 2. Tableau de confrontation synthétique

| Réf. | Recommandation Claude | Verdict ChatGPT | Écart principal |
|---|---|---|---|
| P-08 | filesystem palier 1, S3 palier 2 | **D'accord sous condition** | filesystem annoncé mono-hôte ; stockage objet futur à re-comparer, MinIO n'est plus un choix par défaut |
| P-14 | aucun broker au palier 1 | **D'accord, plus strict** | retirer aussi Kafka du palier 3 tant qu'un besoin réel ne l'impose pas |
| P-18 | ClamAV via wrapper REST + stub | **ClamAV oui, wrapper non justifié** | préférer l'API officielle `clamd INSTREAM` ; stub uniquement en tests |
| P-19 | accepter les tailles excessives en `UNSCANNABLE` | **Désaccord** | taille connue hors contrat → `413` ; `UNSCANNABLE` réservé aux limites découvertes pendant l'analyse |
| P-21 | aucune bibliothèque de résilience | **D'accord avec correction** | retry persistant oui ; le bulkhead exige un sémaphore/exécuteur borné avec virtual threads |
| P-29 | MVC + virtual threads | **D'accord** | décision de simplicité, pas incompatibilité technique avec WebFlux ; ajouter keep-alive et mesure du pinning |
| A-xx | A2 puis A3 | **A2-R, sans trajectoire linéaire** | file, stockage, synchronisme et transit des octets sont quatre axes indépendants |
| Contrainte Java | lecture applicative + sobriété | **D'accord** | retenir la version Spring stable au moment du socle ; les langages des composants ne sont pas le critère |

---

## 3. Avis sur les décisions `D-01…D-17`

### D-01 — Infrastructure locale

**Avis ChatGPT** : application Spring en trois rôles/containers mais une image,
PostgreSQL, ClamAV officiel et front React. Filesystem Docker mono-hôte.

Pas de MinIO, broker, Redis, Keycloak ou bibliothèque de résilience.

### D-02 — Isolation

**Avis ChatGPT** : deux volumes + trois rôles. `delivery` ne monte que
`servable:ro`; `ingest` ne monte que `quarantine:rw`; `worker` monte les deux.
Ajouter des rôles PostgreSQL et une vue `downloadable_file`.

La proposition à deux containers est rejetée : l'API d'upload doit pouvoir
écrire la quarantaine.

### D-03 — Transport du scan

**Avis ChatGPT** : la ligne `stored_file` porte la file ; claim par
`SKIP LOCKED`, retry persistant, lease tokenisé, reaper. Aucun outbox distinct
et aucun broker.

### D-04 — Ingestion

**Avis ChatGPT** : un seul endpoint binaire streaming au premier périmètre.
`Content-Length` sert de préfiltre et un compteur porte la limite réelle.
Métadonnées en en-têtes bornés et encodés. Multipart reporté.

### D-05 — Téléchargement

**Avis ChatGPT** : relais streaming uniquement tant que le filesystem est
retenu. `200`/`206`, `Content-Disposition: attachment`, type
`application/octet-stream`, `nosniff`, contrôle de tenant et objet immuable.

### D-06 — Mode synchrone

**Avis ChatGPT** : ne pas coder. Il fragmente le contrat et ne résout ni la
durabilité, ni la reprise.

### D-07 — Fichiers infectés

**Avis ChatGPT** : politique configurable, après précision du cadrage. À défaut,
conserver isolé pour la démonstration et prévoir `purge_due_at`, sans promettre
une purge de 30 jours non implémentée. L'audit survit à la purge.

### D-08 — Déduplication

**Avis ChatGPT** : aucune déduplication au premier périmètre, ni contenu ni
verdict. SHA-256 sert à l'intégrité. Réexaminer le cache uniquement sur mesure
du coût réel, avec scope tenant, politique complète du scanner et canal
temporel traité.

### D-09 — Réponse pour un fichier infecté

**Avis ChatGPT** : `404` pour inconnu/autre tenant ; `409` avec code problème
stable pour le propriétaire. `403` reste réservé à l'autorisation.

### D-10 — Authentification

**Avis ChatGPT** : si le multi-tenant est retenu, JWT Resource Server sans IdP
local, avec clé de validation externalisée et `tenantId` issu exclusivement du
jeton. Aucun en-tête `X-Tenant-Id` de confiance.

Si ce mécanisme ne tient pas dans le budget, annoncer un démonstrateur
mono-tenant plutôt que simuler une isolation inexistante.

### D-11 — Multi-tenant

**Avis ChatGPT** : conserver `tenant_id` dans le schéma et dans toutes les
clés d'accès. L'activer fonctionnellement uniquement avec un principal fiable.
Les requêtes sont tenant-scopées dans le repository, jamais filtrées après
chargement.

### D-12 — Front React

**Avis ChatGPT** : interface mince mais complète : upload avec progression,
liste/statut par polling, erreurs terminales explicites, téléchargement activé
uniquement lorsque `downloadable=true`. Pas de dashboard ou design system.

### D-13 — Notification

**Avis ChatGPT** : polling avec `ETag` et `Retry-After`. Pas de SSE ou webhook
au premier périmètre.

### D-14 — Précisions de cadrage

**Avis ChatGPT** : les obtenir tôt. Priorités : taille maximale réelle,
antivirus imposé, latence asynchrone acceptable, rétention,
authentification/multi-tenant et temps attendu.

### D-15 — Taille scannable

**Avis ChatGPT** :

- taille connue supérieure au contrat : `413` avant ou pendant le flux ;
- archive ou limite découverte par ClamAV : `UNSCANNABLE` ;
- `AlertExceedsMax yes` et cas de limites testés ;
- jamais de découpage en morceaux.

### D-16 — Structure du dépôt

**Avis ChatGPT** : monorepo, Maven multi-module backend + frontend React,
compose à la racine et une commande de démarrage.

### D-17 — Énoncé public

**Avis ChatGPT** : publier un résumé et la matrice `EX-xx`, pas le texte
verbatim, sauf accord explicite de l'entreprise.

---

## 4. Deux décisions nouvelles à ajouter lors de la fusion

### D-18 — Topologie des rôles et promotion

**Question** : comment concilier l'upload en quarantaine avec l'absence d'accès
à la quarantaine depuis le chemin de livraison ?

**Avis ChatGPT** : trois rôles et protocole `PROMOTING` récupérable décrit dans
[`21`](21-invariant-domaine-et-donnees.md).

Cette décision est bloquante car elle modifie les profils Spring, le compose,
les droits filesystem, les tests et l'automate.

### D-19 — Sens de `CLEAN` et état téléchargeable

**Question** : `CLEAN` décrit-il le verdict ou la disponibilité complète ?

**Avis ChatGPT** : `CLEAN` est un résultat de scan ; `AVAILABLE` est le seul
état servable. La promotion se déroule dans `PROMOTING`.

Cette séparation supprime l'état incohérent « sain en base mais absent de la
zone servable ».

---

## 5. Périmètre recommandé

### Palier 1 — invariant démontrable

| Inclus | Motif |
|---|---|
| domaine pur et automate corrigé | cœur de la garantie |
| trois rôles issus d'une image | isolation physique réellement applicable |
| PostgreSQL + Flyway + rôles DB | état, file et privilèges |
| upload binaire streaming | exigence de taille avec surface minimale |
| taille, hash et création exclusive | intégrité des octets |
| ClamAV réel via `INSTREAM` | délégation effective à un antivirus |
| claim, lease tokenisé, retry, reaper | convergence après panne |
| promotion vérifiée et récupérable | lien verdict → objet servi |
| statut, liste minimale, téléchargement | parcours fonctionnel complet |
| polling React avec progression | contrainte React démontrée |
| idempotence d'effet de l'upload | appelants systèmes et retries réseau |
| backpressure disque/file | panne AV bornée |
| métriques métier, logs structurés et trace corrélée `ingest → job → worker → AV` | exploitation de l'invariant et exigence qualité du projet |
| tests EICAR, concurrence, mémoire et kill points | preuve plutôt que promesse |
| compose et CI | reproductibilité |

### Hors palier 1

- multipart ;
- Tika ;
- suppression et rescan ;
- déduplication ;
- Range multiple complexe si le temps manque ;
- SSE/webhook ;
- stockage objet ;
- Kafka ou tout autre broker ;
- deuxième adapter uniquement démonstratif ;

### Estimation

L'estimation Claude de deux jours n'est pas crédible pour son propre palier 1 :
elle inclut backend, front, compose, vrai antivirus, idempotence, concurrence,
tests de gros fichier, CI et documentation.

Sans mesure préalable, je retiendrais **4 à 6 jours concentrés** pour le palier
ci-dessus avec une qualité solide. Si la contrainte réelle est de
deux jours, il faut réduire encore le contrat, pas bâcler les garanties :
statut + upload + scan + téléchargement, mono-tenant, sans liste, auth,
suppression, rescan ou multipart.

---

## 6. Ordre de livraison recommandé

1. arbitrer les décisions bloquantes et écrire les ADR humains ;
2. réaliser deux spikes courts : streaming brut et limites ClamAV réelles ;
3. écrire l'automate, le DDL et les tests de contraintes ;
4. construire le stockage de quarantaine et l'ingestion ;
5. construire le claim, le scan et les pannes ;
6. construire la promotion avec tests de kill points ;
7. construire la vue/repository de téléchargement et les tests default-deny ;
8. ajouter le front minimal ;
9. mesurer mémoire, concurrence et saturation ;
10. finaliser README, prompts et démonstration.

La CI doit être ajoutée avec le socle, mais elle ne doit pas dicter
l'architecture avant les deux spikes.

---

## 7. Conditions de levée du NO-GO

Le code applicatif peut commencer lorsque le porteur du projet a explicitement
validé au minimum :

- D-01, D-02, D-03, D-04, D-11 et D-15 ;
- la nouvelle D-18 (trois rôles + promotion) ;
- la nouvelle D-19 (`AVAILABLE` distinct de `CLEAN`) ;
- le choix mono-hôte assumé ou stockage partagé ;
- le périmètre d'authentification ;
- le temps disponible et donc le périmètre réel.

À ce moment seulement : mise à jour du registre, ADR signés par le porteur et
levée de la règle de gel d'`AGENTS.md`.

---

## 8. Conclusion

> « J'ai retenu une file PostgreSQL parce qu'elle rend atomiques l'état métier
> et le travail, sans broker inutile. La contre-analyse a cependant révélé que
> l'isolation initiale à deux rôles était impossible : le processus d'upload
> devait accéder à la quarantaine. J'ai donc séparé ingestion, livraison et
> worker dans trois containers issus de la même image. J'ai également séparé
> le verdict CLEAN de l'état AVAILABLE et rendu la promotion récupérable. Les
> corrections ont ajouté des garanties, pas des produits. »

Ce récit est plus convaincant qu'une architecture présentée comme parfaite dès
la première itération : il montre une critique factuelle, une correction et un
arbitrage humain traçable.
