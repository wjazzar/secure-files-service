# 010 — Code mort, duplication et réutilisation, sur tout le dépôt

- **Date** : 2026-09-27
- **Outil** : Claude Opus 5.5
- **Objectif** : retirer le code mort, réduire la duplication, vérifier que le code existant a été réutilisé plutôt que réécrit
- **Phase du projet** : qualité transverse (back-end, front-end, infrastructure)

## Prompt

> Prends la responsabilité de la qualité du dépôt sur trois axes :
>
> 1. **le code mort** : tout ce qui n'est plus utilisé, y compris les
>    packages vides — retire-le ;
> 2. **la duplication** : réduis-la au maximum ;
> 3. **la réutilisation** : vérifie que le code existant a été réutilisé
>    plutôt que réécrit, et rétablis-la là où elle manque.
>
> Appuie-toi sur des outils plutôt que sur une lecture à l'œil, et justifie
> chaque élément que tu choisis de conserver.

## Méthode : outiller plutôt que parcourir

Aucun outil d'analyse statique n'était disponible hors ligne. Plutôt que de
relire à l'œil, quatre analyses écrites pour l'occasion :

| Analyse | Principe |
|---|---|
| Exports morts (TypeScript) | Service de langage du compilateur du projet : références de chaque export, hors de son fichier |
| Fichiers inatteignables | Graphe d'imports depuis `main.tsx` et les tests |
| Libellés morts | Références de chaque clé de `messages.ts` (accès dynamiques `Record<Code, …>` écartés) |
| Méthodes Java jamais appelées | **Sites d'appel** seulement : une méthode déclarée dans un port et implémentée par un adaptateur reste morte si personne ne l'appelle |
| Duplication | Fenêtres glissantes de lignes normalisées, à la manière de CPD, sur le Java et le TypeScript |

Chaque candidat a été relu dans son contexte avant d'être retiré : la plupart
des « méthodes jamais appelées » sont des points d'entrée du framework
(`@Bean`, `@ExceptionHandler`, `@Scheduled`, surcharges).

## Ce que l'audit a montré

### Code mort

| Élément | Constat |
|---|---|
| `ObjectKey.promotionStaging` | Reliquat du protocole copie → renommage abandonné : la promotion écrit directement la clé finale (`backend/AGENTS.md` §5.4, « pas de zone temporaire ») |
| `Lease.extendedUntil`, `hasExpiredAt`, `isHeldBy` | Aucun appel en production. `hasExpiredAt(now)` jugeait l'expiration sur l'horloge d'un **nœud**, alors que le choix documenté est l'horloge de la **base** (`clock_timestamp()`) : la garder invitait au mauvais usage |
| `ScanVerdict.engineVersionIfKnown`, `signatureVersionIfKnown` | Enveloppes `Optional` des accesseurs du record, jamais appelées |
| `FileQuery.hasSearch`, `JdbcFileWorkQueue.ALL_COLUMNS` | Jamais utilisés (`RETURNING f.*` a remplacé la liste de colonnes) |
| `components/ui/separator.tsx`, `tooltip.tsx` | Jamais importés ; `TooltipProvider` enveloppait l'application sans aucune infobulle |
| Type `ScanVerdict` (front) | Jamais utilisé |
| 7 libellés de `messages.ts` | `close`, `leaveWarning`, `statusFilter`, `refreshing`, `verdictInfected`, `verdictUnscannable`, `verdictFailed` |
| Getter `UploadQueueStore.hasActiveUploads` | Jamais lu : le hook recalculait la même chose à côté |
| `infra/keycloak/realm-ecluse.json`, `infra/clamav/clamd.conf` | Listés « à retirer » par `infra/README.md` §8 depuis le 26/09 |

Aucun paquet vide : les seuls dossiers sans fichier sont des paquets
intermédiaires (`domain/file` contient `model/`, etc.).

### Réutilisation non faite

| Constat | Existant ignoré |
|---|---|
| `"application/octet-stream"` écrit en dur **quatre fois** en production | `ContentType.OCTET_STREAM` (domaine), utilisé **seulement par les tests** ; `MediaType.APPLICATION_OCTET_STREAM_VALUE` côté web |
| Barre de progression d'envoi écrite à la main (`role="progressbar"`) | `components/ui/progress.tsx`, installé et inutilisé |
| `TRUNCATE` recopié dans **onze** classes de test | Le motif existait déjà : `DatabaseFixture` vide les tables avant chaque test |
| Prédicat « envoi actif » écrit quatre fois (store, hook, panneau, élément) | Le `Set` `ACTIVE` du store |
| `VerdictBanner` redéclarait un type identique à `StatusTone` et recodait statut → ton et animation | `STATUS_DISPLAY` |
| Libellés de statut en dur dans `status-display.ts`, deux d'entre eux recopiés dans `messages.ts` | Le catalogue `messages.ts` (règle F-11) |

### Duplication

Le bloc d'écriture S3 en flux (`putObject` + corps à usage unique + type
opaque + traduction d'erreur) et la suppression étaient recopiés dans
`S3QuarantineWriter` et `S3WorkerStorage`.

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| **`S3Objects`** dans `storage/common/support`, à côté de `S3Failures` et `SingleUseContent` qu'il compose | Chaque adaptateur passe **son propre** client : la séparation des identités, cœur de la garantie, reste entière ; l'aide ne détient aucun identifiant |
| Nettoyage des tables dans **`PostgresTestcontainer`**, client HTTP dans **`FullStackTest`** | Toutes les sous-classes de `FullStackTest` tournent sur un port aléatoire. La méthode de base porte un nom distinct : sous JUnit 5, un `@BeforeEach` de même signature dans une sous-classe *supplante* celui de la base, quelle que soit la visibilité Java — `FileCatalogQueryTest` avait justement un `emptyTheTables()` |
| `statusLabels` dans `messages.ts`, `STATUS_DISPLAY` le référence | Même motif `Record<Code, string>` que les erreurs et les raisons : un statut ajouté sans libellé ne compile pas |
| `isActiveUpload` et `uploadRatio` exportés du store, avec leurs tests | Une définition, quatre consommateurs ; la division par une taille nulle est verrouillée par un test |
| `Progress` avec `getAriaValueText` branché sur `formatPercent` | Le texte lu par un lecteur d'écran est formaté comme le texte visible (« 45 % ») |

## Ce que j'ai rejeté, et pourquoi

| Option | Pourquoi |
|---|---|
| Fusionner `FileSummaryResponse` et `FileDetailResponse` (`@JsonUnwrapped`) | Les deux records reflètent `FileSummary` et `FileDetail` du contrat ; `ContractConformanceTest` les compare composant par composant. Un record Java n'hérite pas : les neuf champs répétés sont le prix d'un contrat vérifiable |
| Factoriser les fragments `SET` répétés de `JdbcFileWorkQueue` | Ce sont les instructions qui portent la garantie : chacune doit se relire seule, en entier. Des fragments concaténés rendraient l'audit plus difficile pour quelques lignes |
| Remplacer `CountingInputStream` (web) par `InspectingInputStream` (cœur) | Deux responsabilités : une métrique à la frontière HTTP, et le hachage plafonné dans le cœur. L'hexagone interdit au contrôleur de voir l'outil du cœur |
| Retirer `StoredFile.claimedBy` | Conservé par décision tracée (B-002) : un test vérifie que la règle Java et la règle SQL concordent |
| Retirer `StoredFile.area()` | Accesseur de l'état de l'agrégat, sur lequel les tests de transition s'appuient |
| Élaguer les sous-composants inutilisés de `dropdown-menu`, `select`, `card`… | Code shadcn copié tel quel et exclu du lint : le garder intact permet de le resynchroniser. Seuls les fichiers entièrement inutilisés sont retirés |
| Supprimer `lib/utils.ts` (aucun import) | `components.json` y fait pointer l'alias `utils` : la CLI shadcn réécrit les imports de `cn` des nouveaux composants vers ce fichier |
| Retirer la branche `loginMode === 'redirect'` de la page de connexion | Couture prévue pour Keycloak (v2), documentée ; aucun adaptateur ne l'emprunte encore |

## Vérifications effectuées

| Point | Résultat |
|---|---|
| `mvnw verify` (comme la CI) | **BUILD SUCCESS** : 399 tests + 2 preuves mémoire (500 Mo à travers un heap de 256 Mo), règles ArchUnit comprises — `S3Objects` respecte l'hexagone et la disposition |
| Front : `typecheck`, `lint`, `prettier`, `test`, `build` | Tout vert ; 105 tests (2 ajoutés) |
| Navigateur, mode bouchon | Barre de progression : rôle, nom, `aria-valuetext` « 0 % », piste 6 px, dégradé ; valeurs 0/25/60/100 → largeur et `aria-valuenow` exactes. Bannière : « Analyse en cours » animée au ton `scanning`, puis EICAR bloqué au ton `infected`, **sans bouton Télécharger** |
| Limite constatée, non introduite | Sous MSW, `onUploadProgress` ne se déclenche pas : la barre reste à 0 % en mode bouchon (comportement antérieur, la progression est couverte par les tests du store) |
