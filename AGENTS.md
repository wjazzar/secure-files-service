# AGENTS.md — Contexte et règles de travail

> Fichier de contexte pour tout agent (ou humain) intervenant sur ce dépôt :
> la nature du projet, l'état d'avancement, les conventions et les interdits.
> Les assistants d'IA qui ont participé au projet ont travaillé à partir de
> lui ([`docs/prompts/`](docs/prompts/README.md)).

---

# 1. Nature du projet

**Un test technique** pour un poste de développeur senior : un service de
fichiers sécurisés, en Java, Spring Boot et React. Le livrable est aussi le
support d'un échange technique : chaque choix doit pouvoir se défendre, avec
ses raisons, ses coûts et ce qui le remettrait en cause.

Trois principes en découlent :

- **un périmètre restreint, parfaitement exécuté**, plutôt qu'un périmètre
  large et approximatif ([`docs/25`](docs/25-paliers-de-perimetre.md)) ;
- **une hypothèse écrite plutôt qu'une question ouverte** : l'énoncé demande
  de formuler et documenter ses hypothèses. Les points que l'énoncé laissait
  ouverts ont été précisés le 21/09 ([`docs/28`](docs/28-precisions-de-cadrage.md)) ;
  le reste est au README (§9), chaque hypothèse avec ce qui l'a motivée ;
- **le « comment » appartient au développeur** : architecture, outils, tests,
  environnement. Chaque décision structurante a son ADR.

---

# 2. L'énoncé

Résumé (décision `D-17`) et matrice des exigences `EX-01` à `EX-15` :
[`docs/00-enonce.md`](docs/00-enonce.md).

## 2.1 Objectif fonctionnel

Un **micro-service de gestion de fichiers sécurisés** capable de :

1. recevoir et conserver des fichiers (utilisateurs ou systèmes tiers) ;
2. **garantir qu'aucun fichier n'est servi sans avoir été scanné par un
   antivirus** ;
3. permettre le téléchargement des fichiers validés via une API.

Avec : « de nombreux utilisateurs simultanés » et « des fichiers de tailles
très variables ».

## 2.2 Contraintes imposées

| Contrainte | Précision |
|---|---|
| Écosystème **Java / Spring Boot / React** | Précisé le 21/09 : Spring Boot, React et une base suffisent ; tout ajout est libre mais justifié (`docs/28` §6) |
| Dépôt **Git public** | — |
| Application **fonctionnelle** | Doit tourner |
| Antivirus **délégué via une API** | **Choix du moteur libre** |
| README : choix, hypothèses, pistes d'amélioration | Livrable à part entière |
| **Prompts d'IA stockés dans le dépôt** | Livrable explicite |

---

# 3. Barre de qualité

Le porteur du projet exige un code de qualité professionnelle. Traduction
opérationnelle :

- **Sécurité** : l'invariant « aucun fichier non scanné servi » doit être
  structurellement impossible à violer, pas garanti par un `if`.
- **Résilience** : composants stateless, tolérants aux pannes de leurs
  dépendances, auto-réparants.
- **Idempotence** : sur l'API, la consommation de travaux et les réessais.
  Cinq problèmes distincts — voir [`docs/04-idempotence.md`](docs/04-idempotence.md).
- **Tailles variables** : streaming de bout en bout, mémoire constante.
- **Testabilité** : domaine testable sans Spring ; cas infecté déterministe
  (EICAR).

> Nuance importante : « qualité » signifie ici **un périmètre restreint,
> parfaitement exécuté**, pas un périmètre large approximatif. Voir
> [`docs/25-paliers-de-perimetre.md`](docs/25-paliers-de-perimetre.md).

---

# 4. État d'avancement

| Phase | État |
|---|---|
| Cadrage / analyse | **Terminé** — série `docs/20-` à `docs/28-` |
| Contre-analyse indépendante (ChatGPT) | **Reçue et fusionnée** — `docs/chatgpt/`, réponse en `docs/26-` |
| Précisions de cadrage | **Fixées le 21/09** par le porteur du projet — [`docs/28-precisions-de-cadrage.md`](docs/28-precisions-de-cadrage.md) |
| Spikes techniques | **Faits** — stockage objet en flux (B-005), limites de l'antivirus mesurées (B-008) |
| Arbitrages (`D-01` … `D-19`) | **Clos le 01/10** — tous actés, sauf `D-08` reporté en piste : [`docs/12-decisions-ouvertes.md`](docs/12-decisions-ouvertes.md) |
| ADR | **0001 à 0015, acceptés le 01/10** (0007 et 0008 remplacés) — [`docs/adr/`](docs/adr/README.md) |
| Back-end | **Livré** — lots B0 à B9 : [`backend/PLAN.md`](backend/PLAN.md), [`backend/ARCHITECTURE.md`](backend/ARCHITECTURE.md) |
| Front-end | **Livré** — branché sur le vrai back-end, connexion Keycloak par session, **aucun mode sans authentification** (ADR-0014) : [`frontend/ARCHITECTURE.md`](frontend/ARCHITECTURE.md). Pas de tests Playwright (décision du 01/10) |
| Charge et résilience | Plan écrit avant le code ([`docs/31`](docs/31-plan-de-tests-charge-et-resilience.md)), capacité mesurée : [`docs/capacity-planning/`](docs/capacity-planning/README.md) |
| Audit de sécurité | **Clos le 01/10** — [`docs/32-audit-de-securite.md`](docs/32-audit-de-securite.md) : lots 1 et 2 faits (rôles PostgreSQL, aucun secret par défaut, ports locaux, port de management, pagination bornée, build sans bouchons, URL vérifiées), puis revue complète du code (§8, V-01 à V-10, tout corrigé) ; S-18 en attente ; antivirus et mise en production hors périmètre |
| Dépendances | **Analysées le 01/10** — [`docs/33-analyse-des-dependances.md`](docs/33-analyse-des-dependances.md) : Tomcat et Jackson corrigés, images épinglées par version et empreinte. **Pas de CI** (exercice) |
| README | **Écrit** — choix, hypothèses, limites avec leur déclencheur |
| Documentation | **Relue contre le code le 02/10** — README, `backend/`, `frontend/`, `contracts/`, `infra/` et `docs/` : chaque document dit ce qui est livré ; les analyses antérieures au code sont annotées « Livré » ([`docs/README.md`](docs/README.md)) |
| Dépôt Git | **Public** sur GitHub, branche `main` ; historique rejoué le 02/10 en commits atomiques (décision du porteur du projet, 01/10) |

## 4.1 Prochaine action recommandée

> **Finaliser.** Le code est livré, l'audit clos, l'historique rejoué.
> Restent quelques décisions du porteur du projet.

Sujets ouverts, dans l'ordre prévu :

1. **En-tête `sandbox` sur le téléchargement** (audit S-18) : expliqué au
   porteur du projet, en attente de sa décision.
2. **Pistes du README §10 à compléter** (jetons en clair dans la table des
   sessions, effacement des fichiers, préparation TLS) : à reprendre.
3. **Refonte du design du front** faite par le porteur du projet, à
   intégrer.

Décidés le 01/10 et à ne pas rouvrir : pas de CI ; pas de tests Playwright
(les bouchons MSW suffisent) ; React Router 7 conservé (ligne maintenue) ;
pas de suppression de fichier ni de webhook ; l'antivirus est hors périmètre ;
pas de mise en production (l'interface est servie par Vite, via les scripts
ou l'IDE).

**Règle de propriété des fichiers** (deux sessions, un seul dossier) :
`backend/` et `contracts/` au back, `frontend/` au front, `docs/` et
`AGENTS.md` à la session d'architecture. Journal des prompts préfixé `B-` et
`F-` pour éviter les collisions de numéros.

## 4.2 Orientation technique retenue

Après révision et confrontation. Règle directrice :

> **Un composant externe n'entre que s'il répond à une exigence nommée de
> l'énoncé que rien de déjà présent ne couvre.**

| Décision | Référence |
|---|---|
| **Aucun broker** — la base fait file (`FOR UPDATE SKIP LOCKED`) | `docs/20-…#p-14` |
| **Aucune bibliothèque de résilience** — la file porte réessais et backoff | `docs/20-…#p-21` |
| **Stockage objet compatible S3 dès le départ** (précisé le 21/09 : un stockage partagé de type objet plutôt que le disque local) | `docs/28` §3 |
| **Spring MVC + virtual threads**, pas WebFlux | `docs/20-…#p-29` |
| Isolation quarantaine / zone servable par **trois identités de stockage** (`ingest`, `worker`, `delivery`) dans **un seul processus** | ADR-0002, `docs/28` §3 |
| `AVAILABLE` distinct de `CLEAN`, état `PROMOTING` intercalé | `docs/26-…` §2.6 |
| Architecture : **A2-R** (file en base), hexagonale vérifiée par ArchUnit | `docs/21-`, ADR-0011 |
| **Interface React** consommant **uniquement l'API** : dépôt, suivi, tableau paginé (recherche, filtre, tri), détail, téléchargement par navigation native avec la session (sans lien signé, ADR-0013). Stack : Vite, React 19, TanStack Query/Table, axios, Zod, shadcn/ui + Tailwind | [`frontend/ARCHITECTURE.md`](frontend/ARCHITECTURE.md) |
| **Authentification toujours exigée** : **Keycloak** (OpenID Connect), sans mode anonyme ni réglage pour la couper (29/09) ; au contrat : `security`, `401`/`403`, cloisonnement par propriétaire | [`contracts/README.md`](contracts/README.md) §2.10, [`docs/adr/0014-…`](docs/adr/0014-authentification-toujours-exigee.md) |
| **v2, navigateur : le service est le client confidentiel** (id + secret, code + PKCE) par le **client OAuth2 de Spring Security** ; le navigateur n'a qu'un cookie `HttpOnly`, la session vit dans PostgreSQL (**Spring Session JDBC**) ; systèmes tiers en `Bearer`, sans état ; pas de rotation des jetons de rafraîchissement. Rien de ce que Spring fournit ne se réécrit à la main | [`docs/adr/0012-…`](docs/adr/0012-session-navigateur-client-confidentiel.md) |

**Composants : application + PostgreSQL + stockage objet compatible S3 +
antivirus + Keycloak + front** (plus Prometheus et Grafana pour la
supervision). Spring Boot, React et une base suffisent (précision du 21/09) :
**aucun broker**.

### Cadrage précisé le 21/09 ([`docs/28`](docs/28-precisions-de-cadrage.md))

| Point | Valeur |
|---|---|
| Taille maximale | **500 Mo** — pas de Go, pas d'envoi par morceaux. Limite d'admission = limite d'analyse |
| Volumétrie | **Non imposée** : hypothèse à poser, documenter et dimensionner en cohérence (~50 dépôts simultanés, ~5 000 fichiers/jour) |
| Stockage | **Objet, type S3** ; multi-nœuds à penser sans avoir à le démontrer |
| Analyse | **Asynchrone**, confirmé |
| Authentification | **Non indispensable** pour l'exercice ; ajoutée quand même (**Keycloak**), et **toujours exigée** depuis le 29/09 — plus de mode anonyme (ADR-0014). Choix assumé |
| Outils | Spring Boot + React + base de données suffisent ; ajouts libres mais à justifier |
| Interface | **Dépôt → suivi → téléchargement** ; liste, tri et recherche en plus, **intégrés** ensuite (tableau paginé) |

## 4.3 Défauts corrigés lors de la confrontation

À connaître : ce sont des pièges sur lesquels il ne faut pas retomber.

1. Isolation à deux rôles **impossible** (l'upload doit écrire la zone que la
   livraison ne doit pas lire) → trois rôles.
2. Contrainte `CHECK` **contournable par `NULL`** (SQL ne rejette que `FALSE`)
   → prédicats totaux (`IS NOT DISTINCT FROM`).
3. Claim reprenant les travaux **terminaux** à l'infini → `RETRY_WAIT` /
   `FAILED_FINAL`.
4. « La taille du pool est le bulkhead » : **faux** avec les virtual threads
   pour des tâches **soumises** (un thread par requête) → `Semaphore` explicite
   sur les dépôts. Mais **vrai** pour un nombre fixe de boucles séquentielles :
   les analyses sont bornées par leurs boucles, le sémaphore d'analyse, qui ne
   pouvait jamais bloquer, a été retiré le 30/09 (ADR-0010 révisé).
5. Promotion **ni atomique ni récupérable** → protocole reprenable : relecture
   de la quarantaine → écriture sous la clé finale → vérification (taille,
   SHA-256) → CAS sur la ligne. Pas de renommage : avec un stockage objet, le
   point de validation est la ligne (ADR-0006).
6. `CLEAN` confondait verdict antivirus et disponibilité → `AVAILABLE`.

---

# 5. Environnement

| Outil | Version |
|---|---|
| JDK | Temurin 21.0.11 LTS |
| Node | 22.16.0 |
| Docker | 29.8.0 |
| Git | 2.47.1 |
| Maven | **absent** → Maven Wrapper (`mvnw`) obligatoire |

Plateforme : Windows 11, PowerShell. Les scripts livrés doivent être
cross-platform.

---

# 6. Conventions

**Langue** — documentation et ADR en français ; code, noms, Javadoc, logs,
libellés d'API et messages de commit **en anglais**.

**Documentation** — toute décision structurante → un ADR dans `docs/adr/`.
Toute hypothèse → section « Hypothèses » du README, **avec ce qui l'a
motivée**. Toute limite connue → « Pistes d'amélioration », **avec le
déclencheur** qui la lèverait.

**Prompts** — livrable explicite. `docs/prompts/`, alimenté au fil de l'eau.

**Double analyse** — le projet fait l'objet d'analyses indépendantes (Claude et
ChatGPT) fusionnées par le porteur du projet. Un document ne mélange jamais
deux sources ; les ADR sont écrits après fusion et signés par le porteur du
projet. Protocole : [`docs/CONFRONTATION.md`](docs/CONFRONTATION.md).

**Git** — dépôt public sur GitHub. Commits atomiques, messages en anglais,
branche `main`.

---

# 7. Interdits techniques

1. ❌ Contenu de fichier en `byte[]`, `String`, ou chargé intégralement en
   mémoire. Tout est flux.
2. ❌ `MultipartFile` sur le chemin des gros fichiers (bufferise sur disque).
3. ❌ Nom de fichier utilisateur comme clé de stockage ou comme chemin.
   Clé = UUID ; nom d'origine en métadonnée seulement.
4. ❌ `Content-Type` client utilisé tel quel pour servir un fichier.
5. ❌ Secret en dur.
6. ❌ Transition vers un état servable ailleurs que dans l'automate du domaine.
7. ❌ Appel réseau sortant sans timeout explicite (connexion **et** lecture).
8. ❌ Dépendance ajoutée sans justification écrite.
9. ❌ Contrainte SQL reposant sur un prédicat pouvant valoir `UNKNOWN`.
10. ❌ Découpage d'un fichier en morceaux pour contourner la limite de taille
    de l'antivirus (une signature peut chevaucher deux morceaux).

---

# 8. Arborescence

```
PRAXEDO/
├── AGENTS.md                     ← ce fichier
├── README.md                     ← livrable principal : choix, hypothèses, limites
├── README.txt                    ← guide de démarrage, brique par brique
├── docker-compose.yml            ← environnement complet (profils « app » et « load »)
├── contracts/                    ← openapi.yaml : contrat d'API partagé back/front (source de vérité)
├── docs/
│   ├── README.md                 ← index
│   ├── 00-enonce.md              ← résumé de l'énoncé + exigences EX-01…EX-15
│   ├── 01 … 09                   ← analyse initiale (énoncé, invariant, idempotence, résilience, sécurité, tests)
│   ├── 12-decisions-ouvertes.md  ← registre D-01…D-19, clos le 01/10
│   ├── 20 … 26                   ← problématiques, architectures, produits, données, confrontation
│   ├── 28-precisions-de-cadrage.md
│   ├── 31-…                      ← plan de tests de charge et de résilience
│   ├── 32-, 33-                  ← audit de sécurité, analyse des dépendances
│   ├── CONFRONTATION.md
│   ├── capacity-planning/        ← mesures de capacité
│   ├── chatgpt/                  ← contre-analyse indépendante
│   ├── adr/                      ← ADR-0001 … ADR-0015
│   └── prompts/                  ← livrable explicite
├── backend/                      ← Spring Boot, hexagonal — contexte : backend/AGENTS.md
├── frontend/                     ← Vite + React + TypeScript — contexte : frontend/AGENTS.md
├── infra/                        ← antivirus, stockage, Keycloak, PostgreSQL, Prometheus, Grafana
├── load/                         ← essais de capacité (k6), résultats
└── scripts/                      ← start, check, stop (bash et PowerShell), demo.sh
```

---

# 9. Glossaire

| Terme | Définition retenue |
|---|---|
| **Invariant** | Au moment d'autoriser une réponse de téléchargement, l'objet doit être `AVAILABLE` et porter une attestation `CLEAN` liée à son SHA-256 |
| **Quarantaine** | Zone des objets non validés, inaccessible au rôle de livraison |
| **Promotion** | Copie vérifiée de la quarantaine vers la zone servable, puis bascule d'état |
| **Verdict** | Résultat d'analyse, horodaté, qualifié par moteur et version de signatures |
| **Lease** | Réservation temporaire d'un travail par un worker, avec token unique par prise |
| **Reaper** | Tâche repêchant les travaux orphelins (bail expiré, promotion interrompue) |
| **EICAR** | Chaîne de test standard reconnue comme menace par tous les antivirus — rend le chemin « infecté » testable sans danger |
