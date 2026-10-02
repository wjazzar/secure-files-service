# B-016 — Effondrement en saturation : refuser moins cher, bail proportionnel

- **Date** : 2026-09-28
- **Outil** : Claude Code (Claude Opus 5.5)
- **Objectif** : corriger ce que la série de campagnes « où part le temps /
  montée en charge » a révélé, avant de la relancer
- **Phase du projet** : back-end, charge et résilience

## Prompts

> Prépare la campagne des points 2 et 3 ; je l'exécute, tu analyses les
> résultats.

puis, après une campagne bloquée et une interruption :

> Le back-end s'est bloqué avec 3 fichiers en attente. Analyse son état sans
> l'arrêter, pour ne pas perdre les preuves. […] Exploite les campagnes qui ont
> déjà produit des résultats. […] Corrige ensuite les points 1 à 4.

## Ce que la série a montré

| Constat | Mesure |
|---|---|
| Au-delà de la saturation, le débit utile **s'effondre** au lieu de plafonner | 89 → 40, 88 → 63, 100 → 59 fichiers/s selon le gabarit |
| Le pool de connexions à la base se vide | jusqu'à 211 demandes en attente, CPU du nœud à 41–64 % |
| Refuser coûte presque autant qu'accepter | k6 envoie ~83 Mo/s, le service en accepte 28 : le reste, ce sont des corps refusés que Tomcat lit en entier |
| Trois fichiers bloqués en `SCANNING` sans analyse en cours | erreur de base non rattrapée, puis bail fixe de 10 min |
| Les dépendances coûtent plus que l'application | à 100 fichiers/s : ClamAV 2,5 cœurs, SeaweedFS 1,7, back-end 1,4 |

## Corrections

1. **Refuser sans base** : le compte des fichiers en attente est relu au plus
   tous les 250 ms, par une seule requête à la fois (`UploadAdmission`).
   Tomcat ne lit plus que 64 Ko d'un corps refusé, et ne répond « 100
   Continue » qu'une fois le dépôt admis (`continueResponseTiming=onRead`).
2. **Dépôts simultanés bornés par nœud** : `Semaphore` pris sans attendre,
   50 par défaut, `429 TOO_MANY_CONCURRENT_UPLOADS` (nouveau code au contrat,
   front mis à jour).
3. **Worker** : `WorkQueueUnavailableException` au port ; un résultat acquis
   est réécrit trois fois avant de s'en remettre au bail ; bail proportionnel
   à la taille (30 s + 1,2 s/Mio, promotion 30 s + 1,8 s/Mio), calculé en SQL
   au moment du claim puisque la taille n'est connue qu'à ce moment-là.
4. **Lanceur** : journaux de chaque nœud conservés ; file non vidée → fichiers
   restants écrits dans `*-FAILED.md`, pile laissée démarrée, série arrêtée ;
   vue JFR trop volumineuse corrigée.

## Suite — deux pools de connexions

> Sur la partie BDD, on peut isoler la partie pool : un pour les requêtes
> normales, un dédié à la file. T'en penses quoi ? […] Go.

Proposition du porteur du projet, retenue. Recommandation de l'assistant :
d'abord mesurer les correctifs 1 à 4 seuls, pour pouvoir dire lequel a
supprimé l'effondrement. Le porteur a préféré une seule relance.

- `DataSourceConfiguration` : pool `api` (réglages Spring Boot habituels) et
  pool `queue` (workers + 2), tous deux construits sur les
  `JdbcConnectionDetails` quand ils existent — sans cela, les tests visaient
  `localhost:5432` au lieu du conteneur de test, défaut vu au premier
  lancement ;
- premier jet fautif, attrapé par la suite de tests : un bean `JdbcClient`
  sur le pool de la file a fait retirer à Spring Boot le sien ; le registre
  d'idempotence est passé en silence sur ce pool, hors de la transaction du
  dépôt (violation de clé étrangère). Correction : le dépôt de la file crée
  son client lui-même, aucun bean n'est exposé ;
- second effet, réel en production aussi : 16 connexions ouvertes par nœud au
  lieu de 10. La suite de tests, qui garde plusieurs contextes Spring en vie,
  a dépassé les 100 clients de PostgreSQL (`too many clients`) ; le conteneur
  de test passe à 500 ;
- `report.mjs` distingue l'attente par pool ;
- gabarits de test ramenés à 1 Gio (G1 imposé pour les nœuds à 2 CPU), tas
  de l'image à 60 % ; nouvelle campagne `scale-3nodes-2cpu`.

Limite : les campagnes n'envoient que des dépôts. Le bénéfice du second pool
ne se verra qu'avec des lectures dans la charge (interrogation d'état, comme
l'interface).

## Ce que j'ai rejeté ou corrigé, et pourquoi

- **Monter le pool de connexions** : aurait déplacé le seuil sans rien
  changer au mécanisme — un refus qui prend une connexion reste un refus qui
  concurrence les workers.
- **Répondre `503`** au nœud plein : le `429` du contrat est déjà « admission
  refusée pour protéger le service » ; un code distinct suffit à le
  distinguer de la file pleine.
- **Borne de simultanéité à 32** : aurait refusé une partie de la rafale de
  50 dépôts simultanés, qui est l'hypothèse du projet ; 50 correspond aussi
  au pool du client S3 d'ingestion.
- **Réessais du worker en boucle infinie** : un résultat acquis mérite
  quelques secondes, pas plus ; au-delà, le bail — désormais court pour un
  petit fichier — est le bon mécanisme.
- **Premier essai d'édition du lanceur** : le script de modification a
  affiché « ok » sans rien écrire ; repéré en relisant le fichier avant de
  conclure, et refait par éditions directes.

## Vérifications effectuées

- tests ajoutés : bornes d'admission (4, sans Spring), refus avant le corps
  avec `Expect: 100-continue` et nœud plein (2, serveur réel), base hors
  d'atteinte côté worker (3), bail calculé en SQL (1) ;
- contrat : `ContractConformanceTest` a échoué tant que le nouveau code
  n'était pas déclaré ; front : typage et tests de contrat au vert ;
- `mvnw verify` complet.
