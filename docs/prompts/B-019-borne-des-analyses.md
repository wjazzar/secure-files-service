# B-019 — La borne des analyses : un sémaphore qui ne pouvait jamais bloquer

- **Date** : 2026-09-30
- **Outil** : Claude Code (Claude Opus 5.5)
- **Objectif** : comprendre pourquoi deux mécanismes bornaient les analyses
  simultanées, puis n'en garder qu'un
- **Phase du projet** : back-end, qualité du code avant la livraison

## Prompts

> Les analyses simultanées semblent bornées deux fois : par un sémaphore, et
> par la boucle de `start()` dans `ScanWorkerPool`, qui garantit déjà ce
> plafond. Qu'apporte la double vérification ?

> Effectue la mise à jour complète.

> `recordFailure` me paraît trop abstraite pour ce qu'elle fait :
> l'enchaînement des erreurs est très encapsulé. Peut-on la simplifier ?

> Fais-le.

> Le bail est créé en base avec `lease_expires_at`, calculé d'après le
> fichier. Mais pour vérifier qu'il n'a pas expiré pendant l'analyse, le
> worker le recalcule localement à partir des réglages. Pourquoi ne pas lire
> celui de la base ? Le code et la base doivent concorder.

> Fais-le, puis commite.

## Ce qui s'est passé

**L'objection du porteur était juste.** `ScanWorkerPool` démarre
`praxedo.worker.concurrency` boucles ; chacune appelle `processNext()` et
attend son retour avant de prendre le fichier suivant ; rien d'autre n'appelle
le service. Le sémaphore de `FileScanService` avait le même nombre de permis,
lu dans la même propriété : `acquire()` trouvait toujours un permis libre.
Aucun test ne l'exerçait, et il était impossible d'en écrire un sans créer plus
de boucles que de permis.

**D'où il venait** : de la correction n°4 de la confrontation (« la taille du
pool est le *bulkhead* » : faux avec les threads virtuels). Juste pour des
tâches **soumises** à un exécuteur — un thread par tâche, rien ne borne —,
c'est le cas des dépôts, un thread par requête. Pas pour un nombre fixe de
boucles séquentielles : le modèle du worker est *pull*, un pic de dépôts
allonge la file, il ne crée aucune analyse. La Javadoc du sémaphore décrivait
un modèle *push* qui n'existe pas dans le code.

**Et il était mal placé** : pris après le claim et après l'ouverture du flux,
une attente aurait consommé le bail du fichier sans rien lire, jusqu'à
`LeaseOutlasted` et une tentative brûlée. Une borne propre au service se
prendrait avant le claim, sans attendre.

| Où | Ce qui a été fait |
|---|---|
| `FileScanService` | Sémaphore, `withPermit` et `InterruptedAnalysis` (levée seulement par `acquire()`) retirés ; Javadoc : une analyse par appel, la borne est chez l'appelant |
| `ScanFilesUseCase` | Le contrat est écrit sur le port : autant d'analyses simultanées que d'appelants simultanés, le service n'ajoute pas de limite |
| `ScanWorkerPool` | Javadoc : le nombre de boucles **est** la borne, et pourquoi les threads virtuels ne l'annulent pas ; `concurrency < 1` refusé au démarrage (la garde vivait dans `WorkerSettings`) |
| `WorkerSettings`, `ServiceProperties.Worker` | `concurrency` retiré : le cœur n'en a plus l'usage, la propriété appartient à l'adaptateur de planification (comme `poll-interval` et `drain-timeout`) |
| `ScanWorkerPoolTest` (nouveau) | La borne est **prouvée** : contre une file qui ne se vide jamais, jamais plus d'analyses simultanées que de boucles, y compris quand elles s'enchaînent |
| ADR-0010 (Proposé, donc révisable) | « sémaphore explicite » → « bornes explicites » : une borne par ressource, là où rien d'autre ne borne ; le sémaphore d'analyse passe dans les alternatives écartées, avec ce qui le rendrait nécessaire |
| Documentation vivante | `AGENTS.md` (§4.3 n°4, nuancé), `backend/AGENTS.md`, `ARCHITECTURE.md` (§10.2, §11, métriques), `README.md`, `backend/docs/03`, scénario 3 (ancres recalculées), `PLAN.md`, `docs/28`, `docs/30`, `docs/31`, ADR-0002, `application.yml`, Javadoc de `MeteredAntivirusScanner` et `DataSourceConfiguration`, tableau de bord Grafana (régénéré par son script) |

### Seconde objection : `recordFailure`, trop de couches pour ce qu'elle fait

**Juste, en partie.** `FilePromotionService.failed` fait la même chose en trois
lignes : transition du domaine, écriture, retour. `recordFailure` y ajoutait
deux couches accidentelles :

- `inFlight.getOrDefault(claim, file)` renvoyait **toujours** `file` :
  `recordFailure` n'est appelée qu'avant l'écriture du verdict, et `inFlight`
  n'est mis à jour qu'après, dans la branche `PROMOTING`, d'où elle ne l'est
  plus. Un cas cherché par le lecteur, qui n'existe pas ;
- `persistently` renvoyait un `Optional<Boolean>` — vide : base injoignable,
  `false` : bail perdu, `true` : écrit — que chaque appelant décodait. Il
  cachait un trou : un échec dont le bail était perdu n'était **pas
  journalisé** (le verdict, lui, l'était).

Devenue `written`, elle renvoie un `boolean` et journalise elle-même les deux
raisons de ne rien écrire. `handle` passe de huit lignes à trois,
`recordFailure` n'a plus de recherche.

Gardé, parce que chaque maillon porte une garantie : la transition par
l'automate (`technicalFailure`, règle B-6), l'écriture conditionnée au jeton,
les exceptions des ports et le `catch` explicite (un vrai bug n'est pas
déguisé en réessai), `LeaseOutlasted` (sans elle, un stockage au
compte-gouttes serait enregistré « antivirus injoignable »), le réessai de
l'écriture quand la base est injoignable.

### Troisième objection : l'échéance locale recalculée au lieu d'être lue

**Juste sur la concordance, pas sur la source.** Le bail en base est le seul
qui garantisse quelque chose : il conditionne chaque écriture, le *reaper* s'en
sert, et il vaut même quand le worker est gelé. L'échéance locale n'est qu'une
économie — cesser de lire des octets dont le verdict serait refusé. Comparer
`lease_expires_at` à l'horloge du nœud serait faux : un nœud en avance de 40 s
couperait toutes les analyses de petits fichiers (bail minimum 30 s), qui
finiraient `FAILED_FINAL`.

Mais la durée était **recalculée** depuis les réglages, par une formule écrite
deux fois (SQL et Java, tenues par un seul test), alors que la ligne réclamée
porte déjà ce que la base a accordé. Désormais : durée =
`lease_expires_at − status_changed_at` (deux instants de l'horloge de la base),
mesurée sur l'horloge du nœud. Pour la promotion, la durée demandée à la base
avec le verdict est passée à `promote(...)` au lieu d'être recalculée. Les
réglages servent à demander un bail, plus à le deviner.

## Décisions restées humaines

- **Retirer plutôt que garder « par défense en profondeur »** : l'argument
  pour garder le sémaphore — le service ne connaît pas ses appelants — a été
  présenté ; il protège contre un appelant qui n'existe pas, et au mauvais
  endroit. Le porteur a demandé la suppression complète.

## Ce que j'ai rejeté ou corrigé, et pourquoi

- **Ma concession de la confrontation (`docs/26` §2.4), appliquée trop
  largement.** Elle était juste dans son cadre — un exécuteur Spring à threads
  virtuels ne borne rien —, mais l'implémentation a ensuite choisi des boucles
  en nombre fixe, qui bornent. Le sémaphore est resté, justifié par une phrase
  qui ne décrivait plus le code. Le catalogue initial (`docs/20`, P-17) le
  disait déjà : un sémaphore ne devient nécessaire que « si plusieurs chemins
  de code appelaient l'antivirus ».
- **Documents historiques non réécrits** : `docs/20`, `docs/26`,
  `docs/CONFRONTATION.md`, `docs/chatgpt/` et les entrées précédentes du
  journal racontent ce qui a été pensé à leur date ; cette entrée et la
  révision de l'ADR-0010 portent la correction.
- **Garde `concurrency < 1` perdue en retirant le champ** : `WorkerSettings`
  refusait un worker sans boucle ; sans report, `concurrency: 0` aurait
  démarré un nœud qui n'analyse rien, en silence. Reportée dans
  `ScanWorkerPool`, avec un message qui indique l'interrupteur à utiliser.

## Vérifications effectuées

- tests ciblés au vert : `ScanWorkerPoolTest` (2, nouveau),
  `FileScanServiceTest` (16), `FilePromotionServiceTest` (7), et les trois
  suites ArchUnit (`LayeringRulesTest`, `HexagonalArchitectureTest`,
  `CodeLayoutRulesTest`) ;
- suite complète au vert (`mvnw verify`) : 480 tests et 2 tests de mémoire,
  contre PostgreSQL, SeaweedFS et ClamAV réels (478 et 2 avant, plus les deux
  de `ScanWorkerPoolTest`). Le contexte Spring démarre : la propriété
  `praxedo.worker.concurrency`, retirée de `ServiceProperties`, n'en gêne pas
  la liaison ;
- une interruption pendant l'analyse reste une panne, jamais un verdict :
  le client HTTP du JDK la remonte en `IOException`, traduite en
  `ScannerUnavailableException` ;
- après la simplification de `recordFailure` : `FileScanServiceTest` (16),
  `FilePromotionServiceTest` (7), `ScanWorkerPoolTest` (2) et les trois suites
  ArchUnit au vert, puis la suite complète à nouveau (480 et 2, contre les
  vrais PostgreSQL, SeaweedFS et ClamAV) ;
- après l'échéance lue dans la ligne réclamée : mêmes suites au vert ;
- chaque ancre de ligne du scénario 3 relue contre le code, après chacune des
  trois modifications ;
- tableau de bord : le JSON régénéré ne diffère que de la ligne modifiée
  (script et JSON étaient en phase).
