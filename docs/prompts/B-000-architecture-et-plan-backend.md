# B-000 — Architecture du back-end et plan d'exécution

- **Date** : 2026-09-26
- **Outil** : Claude Opus 5 (Claude Code)
- **Objectif** : produire les fichiers de contexte du back-end avant d'écrire la
  moindre ligne de code, et planifier les lots
- **Phase du projet** : ouverture du chantier back-end

## Prompt

> Tu prends en charge le back-end. Avant d'écrire du code, crée ses fichiers
> de contexte dans `backend/` — dont un `ARCHITECTURE.md` — à partir des
> décisions déjà prises et du contrat. Planifie ensuite les lots de
> développement, chacun avec sa définition de « terminé », et signale toute
> incohérence entre les documents existants.

## Ce que j'ai demandé de faire d'abord

Lire l'existant avant de proposer : `AGENTS.md`, `backend/AGENTS.md`,
`contracts/openapi.yaml` (1.3.0) et son README, `docs/23`, `docs/28`, `docs/30`,
`docker-compose.yml`, `infra/**`, et le contexte front (`frontend/AGENTS.md`,
`ARCHITECTURE.md` §14.7). Objectif : ne rien réinventer de ce qui est déjà
tranché, et repérer ce qui ne l'est pas.

## Ce que j'ai retenu

- **Deux fichiers**, pas un : `backend/ARCHITECTURE.md` (comment on construit) et
  `backend/PLAN.md` (dans quel ordre). `AGENTS.md` reste le contexte court.
- **Sept points ouverts fermés** dans `ARCHITECTURE.md` §0, chacun avec sa raison.
- **Versions vérifiées à la source** plutôt que de mémoire (§ Vérifications).
- Le plan tient compte de ce qui est **déjà fait** : l'infrastructure locale
  existe, le contrat est figé, le front v1 tourne. Les lots de `docs/30` ont été
  réécrits en conséquence.

## Ce que j'ai rejeté ou corrigé, et pourquoi

| Rejeté / corrigé | Raison |
|---|---|
| **JPA pour les écritures non critiques** (sous-entendu par « requêtes critiques en `JdbcClient` ») | Le *dirty checking* permet exactement ce que la règle `B-6` interdit : changer un statut hors de l'automate. Aucun JPA, `JdbcClient` partout |
| **WireMock pour simuler l'antivirus** (écrit dans `backend/AGENTS.md`) | WireMock ne parle que HTTP ; `clamd` est un protocole TCP binaire. Remplacé par un faux serveur `clamd` écrit à la main |
| **Copie serveur-à-serveur pour la promotion** | Elle ne rend pas l'empreinte de ce qui a été copié : on promouvrait sans vérifier. La promotion relit donc le contenu en flux |
| **URL présignée pour le téléchargement** | Elle rend impossible la revérification de l'état au moment de servir, que le contrat exige explicitement. Jeton HMAC, contenu servi par le service |
| **Apache Tika pour la détection de type** | Le type détecté n'est que descriptif : on sert toujours en `application/octet-stream`. Une dépendance ne se justifie pas (`B-11`) |
| **ShedLock pour les tâches planifiées** | Les tâches sont idempotentes par construction. Une dépendance de moins |
| **Déduplication de verdict par SHA-256 en v1** | Correcte seulement si conditionnée à la version des signatures et à l'âge du verdict. Mal faite, c'est une faille. Reportée en piste documentée |
| **Contraintes `CHECK` de `docs/23`** | Elles n'étaient pas totales : `scan_result = 'CLEAN'` vaut `UNKNOWN` sur `NULL`, et un `CHECK` accepte `UNKNOWN`. Réécrites avec `IS NOT DISTINCT FROM` |
| **`now()` dans les échéances** | Rend l'heure de début de transaction. Remplacé par `clock_timestamp()` partout |

Six dérives entre documents ont par ailleurs été relevées (`ARCHITECTURE.md`
§17) ; celles qui touchent `docs/**` sont **signalées, pas corrigées** — ce
dossier appartient à la session d'architecture.

## Vérifications effectuées

| Vérification | Source | Résultat |
|---|---|---|
| Version courante de Spring Boot | `start.spring.io/metadata/client` | **4.1.1** par défaut (4.0.8 sur la ligne précédente) ; Java 17/21/25/27 |
| Nom du starter web | Documentation Boot 4.1.1, « Build Systems » | `spring-boot-starter-web` **déprécié** au profit de `spring-boot-starter-webmvc` |
| Changements de fond de Boot 4 | Notes de version 4.0 | Jackson 3, Testcontainers 2, Jakarta partout, `spring.threads.virtual.enabled` conservé |
| springdoc compatible Boot 4 | Dépôt GitHub springdoc | Ligne **3.x** ; dernière version **3.1.1** (06/09/2026) |
| État de Testcontainers Java | Dépôt GitHub | Ligne **2.x** (2.0.5, avril 2026), apportée par le BOM de Boot 4 |
| Configuration antivirus livrée | `infra/clamav/clamd.conf` | `AlertExceedsMax true`, plafonds à 512 Mo : conforme à ce que l'architecture suppose |
| Identités du stockage | `infra/seaweedfs/s3-identities.json` | Quatre identités, `delivery` sans aucun droit sur la quarantaine : l'isolation est bien portée par le stockage |
| Contrat | `contracts/openapi.yaml` | Sept opérations, aucun `/limits`, `403` absent du contrat de téléchargement : l'architecture s'y conforme |

**Non vérifié, et c'est l'objet du spike B0** : le comportement réel de
SeaweedFS face aux sommes de contrôle du SDK AWS, et la réponse littérale de
`clamd` au-delà de `StreamMaxLength`. Deux inconnues mesurables, mesurées avant
d'écrire l'adaptateur.

## Décision restée humaine

Le porteur du projet arbitre quatre points listés dans `ARCHITECTURE.md` §16 :
nommage (`ecluse` / `praxedo`), Keycloak dans le périmètre ou non, devenir des
fichiers infectés, rythme du journal. Aucun n'est bloquant : chacun a une valeur
par défaut appliquée sauf avis contraire.
