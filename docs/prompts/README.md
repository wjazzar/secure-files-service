# Journal des prompts d'IA

> Livrable explicite de l'énoncé (`EX-13`) :
> « nous aimerions que vous stockiez dans le repo github du projet les prompts
> utilisés ».

## Ce que montre ce journal

Pas le volume de prompts, mais la **méthode de travail** :

- comment le problème a été **cadré** avant d'être délégué ;
- ce qui a été **challengé, rejeté ou corrigé** dans les propositions reçues ;
- ce qui a été **vérifié indépendamment** (documentation, tests, mesures) ;
- quelles décisions sont restées **humaines**.

Les entrées ont été écrites au fil de l'eau, erreurs et corrections comprises.

**Prompts reformulés, sens conservé.** Les prompts cités ont été réécrits
pour la publication : tournures orales mises au propre, demandes structurées
(objectif, contraintes, résultat attendu). Leurs demandes, leurs objections
et les décisions qu'ils portent sont inchangées. Les échanges qui ne
portaient pas sur le projet lui-même ont été retirés.

## Principe assumé

L'IA est utilisée comme un **accélérateur de réflexion et de rédaction**, pas
comme un décideur. Les arbitrages d'architecture sont pris explicitement par
l'humain et tracés dans [`../12-decisions-ouvertes.md`](../12-decisions-ouvertes.md)
puis dans les ADR. Cette séparation est visible dans le journal : les échanges
produisent des **options argumentées**, les décisions sont prises ailleurs.

## Format d'une entrée

Un fichier par session ou par thème, nommé `NNN-<sujet>.md` :

```markdown
# NNN — <Sujet>

- **Date** : AAAA-MM-JJ
- **Outil** : <modèle / assistant>
- **Objectif** : ce que je cherchais à obtenir
- **Phase du projet** : cadrage | domaine | ingestion | …

## Prompt

> Le prompt, reformulé sans en changer le sens (ou son extrait significatif).

## Ce que j'ai retenu
…

## Ce que j'ai rejeté ou corrigé, et pourquoi
…

## Vérifications effectuées
Documentation consultée, test écrit, mesure réalisée…
```

La rubrique **« ce que j'ai rejeté »** est la plus importante du format : elle
distingue un usage critique d'une délégation aveugle.

## Numérotation (trois sessions)

Pour éviter les collisions : la session d'architecture numérote `NNN-`, la
session back-end préfixe **`B-`**, la session front-end préfixe **`F-`**.

## Index

| N° | Sujet | Date | Phase |
|---|---|---|---|
| [000](000-cadrage-initial.md) | Cadrage initial : architecture, contraintes, questions | 2026-09-20 | Cadrage |
| [001](001-revision-reduction-outillage.md) | Révision : réduction de l'outillage (7 → 3 composants), modèle de données, format problème→réponse | 2026-09-20 | Cadrage |
| [002](002-contre-analyse-chatgpt.md) | Contre-analyse indépendante ChatGPT, vérifications factuelles et verdict A2-R | 2026-09-21 | Confrontation |
| [003](003-reponse-claude-a-la-contre-analyse.md) | Réponse Claude : 6 défauts concédés, 6 points contestés, triage par coût | 2026-09-21 | Confrontation |
| [004](004-recadrage-exercice.md) | Recadrage : un exercice à borner, pas un besoin métier à découvrir ; points ouverts ramenés de 9 à 3, le reste en hypothèses | 2026-09-21 | Cadrage |
| [005](005-preparation-frontend.md) | Préparation du front : recherche sur Praxedo, contrat OpenAPI (liste), contexte front, agent design | 2026-09-21 | Front-end |
| [006](006-agent-design-premier-draft.md) | Agent design : premier draft « Écluse », vérifications de l'orchestrateur, points ouverts | 2026-09-21 | Front-end |
| [007](007-precisions-de-cadrage.md) | **Nouvelles contraintes du 21/09**, transmises par le porteur du projet : ce qu'elles tranchent — stockage objet, 500 Mo, analyse asynchrone, liste en plus | 2026-09-23 | Cadrage |
| [008](008-plan-de-developpement.md) | Plan de développement en deux chantiers, contexte back-end, amorces de session | 2026-09-23 | Développement |
| [009](009-architecture-frontend.md) | Architecture du front : axios + Zod, shadcn/Tailwind, Keycloak, TanStack Table, triple vérification de l'organisation du code | 2026-09-25 | Front-end |
| [010](010-code-mort-et-duplication.md) | **Code mort, duplication, réutilisation** : analyses outillées (compilateur TS, sites d'appel Java, détecteur de copies) ; méthodes, composants, libellés et fichiers d'infrastructure morts retirés ; 6 réutilisations manquées rétablies (`ContentType.OCTET_STREAM`, `Progress`, nettoyage des tables de test…), écriture S3 factorisée ; 8 éléments volontairement conservés, avec leur raison | 2026-09-27 | Qualité |
| [011](011-guide-et-scripts-de-demarrage.md) | **Guide et scripts de démarrage** : `README.txt`, `start` / `check` / `stop` en bash et PowerShell ; `check` teste des propriétés (isolation, EICAR, audience, vrai branchement du front) ; 7 défauts trouvés en exécutant (port réservé par Windows, octets en PS 5.1, front sur bouchons pris pour branché, cache Vite effacé…) | 2026-09-27 | Livraison |
| [012](012-authentification-client-confidentiel.md) | **Authentification v2 de bout en bout** : le service client confidentiel de Keycloak (id + secret, PKCE), cookie `HttpOnly` seul côté navigateur, empreinte SHA-256 en base et jeton de rafraîchissement scellé ; revalidation par Keycloak, CSRF, realm réécrit ; 472 tests back, 118 front, parcours vérifié dans un navigateur contre un vrai Keycloak (ADR-0012) | 2026-09-28 | Développement |
| [013](013-authentification-briques-standard.md) | **Authentification remise en question par le porteur du projet** (le client OAuth2 de Spring couvre déjà le besoin) : couche maison (~40 fichiers) remplacée par le client OAuth2 de Spring et Spring Session JDBC ; cookie sans état envisagé puis écarté ; *refresh* concurrent identifié, rotation désactivée ; deux chaînes (`Bearer` sans état, navigateur avec session) ; secret client trouvé dans la sérialisation par défaut de Spring et écarté ; 446 tests back, 120 front | 2026-09-28 | Développement |
| [014](014-suppression-du-mode-sans-authentification.md) | **Mode sans authentification supprimé** (audit S-01) : plus de `SECURITY_MODE=none` ni d'utilisateur `anonymous`, ni côté service, ni au contrat (1.7), ni dans l'interface, ni dans les scripts ; toute la suite de tests passe par un Keycloak simulé ; historique et migration Flyway volontairement laissés (ADR-0014) | 2026-09-29 | Développement |
| [015](015-cloture-des-decisions.md) | **Clôture** : registre `D-01…D-19` clos (vérifié au code et aux ADR), énoncé résumé (`D-17`), `AGENTS.md` remis à l'état réel  | 2026-10-01 | Livraison |
| [016](016-cloture-audit-et-dependances.md) | **Clôture de l'audit et des dépendances** : questions détaillées une à une sur demande du porteur, puis traitées — trois rôles PostgreSQL (toute la suite tourne avec celui du service), aucun secret par défaut, ports locaux, port de management, pagination bornée, recherche sans accents, build sans bouchons, URL vérifiées ; CI rouge depuis le premier jour trouvée puis retirée ; Trivy : 3 failles critiques de Tomcat corrigées, images épinglées | 2026-10-01 | Livraison |
| [017](017-revue-du-code-et-corrections.md) | **Revue complète du code, puis corrections** : deux sous-agents en lecture seule, chaque constat prouvé ; délais vers PostgreSQL, balayage de la quarantaine repris là où il s'arrête, déconnexion confirmée par le service, aucun code du mode bouchon dans un build, journal non forgeable, `HEAD` refusé ; code mort et inutilisé retiré, variables CSS obsolètes comprises ; ancres des scénarios recalées | 2026-10-01 | Livraison |
| [018](018-relecture-de-la-documentation-contre-le-code.md) | **Relecture de toute la documentation contre le code**, puis seconde validation : documents d'exploitation corrigés en place, série 00 réécrite sur le livré, série 20 annotée « Livré », `frontend/ARCHITECTURE.md` remis sur le code ; faits extérieurs vérifiés à la source (défauts de ClamAV, MinIO archivé) ; une limite trouvée en mesurant — une archive chiffrée est déclarée saine | 2026-10-02 | Livraison |
| [B-000](B-000-architecture-et-plan-backend.md) | **Architecture du back-end et plan d'exécution** : 7 points tranchés (dont « aucun JPA »), versions vérifiées à la source, 6 dérives entre documents relevées | 2026-09-26 | Back-end |
| [B-001](B-001-antivirus-par-api-et-nommage.md) | **Antivirus consommé par une API HTTP** (contrainte de l'énoncé prise au mot) et nommage unifié sur *praxedo* : image dérivée forçant `AlertExceedsMax`, pièges `406`/`413` documentés, realm Keycloak corrigé | 2026-09-26 | Back-end |
| [B-002](B-002-socle-domaine-persistance.md) | **V0 auditable** : socle multi-module, domaine (invariant en Java) et persistance (invariant en SQL, prédicats totaux), 128 tests. Trois surprises de Boot 4 et un bug d'ordre statique trouvés en exécutant | 2026-09-27 | Back-end |
| [B-003](B-003-audit-un-processus-et-jpa.md) | **Audit de la V0** : un seul processus (la séparation passe aux identifiants de stockage), et **retour de JPA** pour les lectures après contradiction argumentée — les trois requêtes critiques restent en SQL | 2026-09-27 | Back-end |
| [B-004](B-004-api-de-lecture.md) | **API de lecture** (liste paginée, détail avec ETag, compteurs) : sous-agent interrompu, repris et relu ; écart au contrat corrigé, test de conformité qui lit `openapi.yaml` | 2026-09-27 | Back-end |
| [B-005](B-005-stockage-et-depot.md) | **Stockage et dépôt en flux** : spike récupéré et rejoué ; corps lu deux fois par le SDK attrapé par un garde-fou ; SeaweedFS 3.97 incapable de lecture seule → 4.47 ; 500 Mo à travers un heap de 256 Mo | 2026-09-27 | Back-end |
| [B-006](B-006-worker-et-antivirus.md) | **Worker** : antivirus par API, verdict lié aux octets analysés, promotion par relecture ; parcours complet avec le vrai ClamAV (fichier sain disponible, EICAR bloqué) | 2026-09-27 | Back-end |
| [B-007](B-007-telechargement.md) | **Téléchargement** : liens HMAC servis par le service (état revérifié à l'usage), contenu direct, plages d'octets, `409` par état ; 500 Mo promus et téléchargés à travers un heap de 256 Mo | 2026-09-27 | Back-end |
| [B-008](B-008-limites-antivirus-mesurees.md) | **Limites de l'antivirus mesurées** : faux négatif trouvé (entrée d'archive de 600 Mo tronquée sans alerte, archive déclarée saine) et fermé par l'ordre des limites, une garde au démarrage et un test contre le vrai moteur ; angle mort zip64 du moteur caractérisé | 2026-09-27 | Back-end |
| [B-009](B-009-finition-et-livraison.md) | **Finition et livraison** : métriques Prometheus, Keycloak activable (vérifié contre le vrai realm), journal d'audit par trigger en ajout seul, pile en une commande, front vérifié sur le vrai service, README et ADR | 2026-09-27 | Back-end |
| [B-010](B-010-organisation-du-web.md) | **Organisation de l'adaptateur web** demandée en relecture : par concept (`file`, `download`, `common`) puis par nature (`controller`, `dto`, `mapper`, `exception`) ; tenue par ArchUnit | 2026-09-27 | Back-end |
| [B-011](B-011-hexagone-et-organisation.md) | **Hexagone vérifié** : six ports d'entrée, adaptateurs pilotants et pilotés indépendants, planification et métriques qui ne contournent plus le cœur ; organisation par concept puis par nature dans toutes les couches ; 19 règles ArchUnit, une violation volontaire attrapée | 2026-09-27 | Back-end |
| [B-012](B-012-documentation-des-parcours-backend.md) | **Documentation des parcours back-end** : six cas d'utilisation suivis de leur déclencheur jusqu'aux adaptateurs, avec flux de bout en bout, états, pannes, garanties et tests de référence | 2026-09-27 | Back-end |
| [B-013](B-013-scenarios-pas-a-pas.md) | **Scénarios pas à pas**, un par port d'entrée, dans `backend/scenarios/` : un cas concret suivi ligne à ligne du déclencheur au SQL et au stockage, branches d'erreur, état final, points d'arrêt ; 236 liens et 99 ancres vérifiés | 2026-09-27 | Back-end |
| [B-014](B-014-capacity-planning.md) | **Capacity planning 1 CPU / 1 Gio** : campagnes ClamAV réel et antivirus simulé, 50 dépôts simultanés, débit soutenu, saturation, preuves brutes et règle de dimensionnement | 2026-09-28 | Back-end |
| [B-015](B-015-relecture-capacity-planning.md) | **Relecture critique du capacity planning** : chaque chiffre confronté aux résultats bruts ; 80 fichiers/s requalifié en genou, coût propre de ClamAV non mesuré, démarrage à froid, passage inexpliqué documenté ; défaut de promotion (réessai SDK d'un flux non rejouable) reproduit par un test puis corrigé par une politique de réessai par identité | 2026-09-28 | Back-end |
| [B-016](B-016-effondrement-en-saturation.md) | **Effondrement en saturation** : le débit utile chute de moitié au-delà du plafond (refus qui lisent la base et le corps, dépôts simultanés non bornés) ; admission sans base, borne par nœud, `Expect: 100-continue` honoré, bail proportionnel à la taille, base hors d'atteinte gérée par le worker, lanceur qui garde les preuves | 2026-09-28 | Back-end |
| [B-017](B-017-diagnostic-base-et-disque.md) | **Diagnostic corrigé par le porteur du projet** : « sur quoi repose la conclusion que la base est le goulot ? » renverse une conclusion tirée sans mesure de latence ; instrumentation de PostgreSQL ; plafond établi à la validation des transactions sur un disque partagé (fsync de 497/s à 60/s, hypothèse du porteur mesurée) ; rapport de capacité réécrit (130–160 fichiers/s par nœud de 2 CPU) | 2026-09-28 | Back-end |
| [B-018](B-018-relecture-guidee-du-code.md) | **Relecture guidée du code** : neuf remarques du porteur, un avis sur chacune, puis appliquées ; domaine sans annotation de persistance (R-003, option 2 retenue par le porteur contre l'avis rendu, ADR-0015), composition regroupée dans `config` et vérifiée, échéance de transfert sur les flux (dépôt `408`, analyse et promotion dans leur bail ; audit S-06 corrigé), vocabulaire « en attente » unifié ; `Retry-After` de suivi proportionnel à la taille pour les clients de l'API, `304` compris (R-001 : avis d'abord contraire, révisé sur l'objection du porteur) ; ancres des scénarios déjà fausses reprises une par une | 2026-09-30 | Back-end |
| [B-019](B-019-borne-des-analyses.md) | **La borne des analyses, question du porteur** (« qu'apporte la double vérification ? ») : le sémaphore devant l'antivirus avait autant de permis que de boucles et ne pouvait jamais bloquer — et, pris après le claim, il aurait brûlé le bail en attendant ; retiré, la borne est le nombre de boucles, écrite sur le port et **prouvée** par un test ; concession de la confrontation reconnue trop large (vraie pour les dépôts, pas pour des boucles en nombre fixe) ; ADR-0010 révisé. Puis `recordFailure` simplifiée sur une seconde objection : une recherche morte et un `Optional<Boolean>` à décoder retirés, un échec au bail perdu qui passait sans trace désormais journalisé. Enfin, l'échéance locale lue dans la ligne réclamée (durée accordée par la base, mesurée sur l'horloge du nœud) au lieu d'être recalculée depuis les réglages | 2026-09-30 | Back-end |
| [F-001](F-001-frontend-v1.md) | **Front v1 sans authentification** : contrat 1.1 (auth prévue, lien signé, pagination), logo, socle, code, 77 tests, 6 défauts corrigés en vérifiant | 2026-09-25 | Front-end |
| [F-002](F-002-design-enrichi.md) | Design enrichi : en-tête et bandeau de marque, cartes de synthèse filtrantes, icônes de type, verdict et frise dans le détail | 2026-09-25 | Front-end |
| [F-003](F-003-ergonomie-filtres.md) | Ergonomie : haut de page compact, onglets de statut en un clic et visibles, réinitialisation en un clic ; bug de recherche corrigé | 2026-09-25 | Front-end |
| [F-004](F-004-connexion-profils.md) | Page de connexion, fournisseur d'identité simulé, 4 profils d'accès appliqués par le serveur, contrat 1.2 (`/me`, propriétaire) | 2026-09-25 | Front-end |
| [F-005](F-005-cloisonnement-par-utilisateur.md) | Authentification **sans rôle** : un espace de fichiers privé par utilisateur ; contrat 1.3, profils et permissions retirés | 2026-09-25 | Front-end |
| [F-006](F-006-design-connexion-et-themes.md) | **Connexion redessinée et thèmes** : coffre SVG animé, palette claire par défaut après retour du porteur du projet, choix Clair / Sombre partagé et mémorisé, 125 tests front | 2026-09-30 | Front-end |
| [F-007](F-007-themes-liste-fichiers.md) | **Thèmes de la liste de fichiers** : mêmes composants, palettes claire et sombre accordées à la connexion, en-tête, dépôt, tableau et détail harmonisés ; vérification sur ordinateur et téléphone | 2026-09-30 | Front-end |
| [F-008](F-008-relecture-et-corrections-du-design.md) | **Relecture du design et corrections** : sept défauts que tests et lint ne voyaient pas (contrastes du thème sombre à 1,1:1 sur l’écran EICAR, deux systèmes de style qui se contredisaient, textes de 8 px, éclair de thème au chargement) ; règle « une propriété, un seul endroit », nettoyage prouvé sans écart de rendu par comparaison des styles calculés sur dix états ; 128 tests | 2026-10-01 | Front-end |

## Double analyse

Ce projet fait l'objet d'une **double analyse indépendante** (Claude et
ChatGPT), fusionnée par le porteur du projet. Le protocole, le tableau de
confrontation et les désaccords anticipés sont dans
[`../CONFRONTATION.md`](../CONFRONTATION.md).

Montrer deux analyses, leurs désaccords et l'arbitrage humain dit davantage
qu'un journal d'acceptation linéaire.
