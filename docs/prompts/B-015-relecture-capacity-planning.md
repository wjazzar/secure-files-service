# B-015 — Relecture critique du capacity planning, défaut de promotion corrigé

- **Date** : 2026-09-28
- **Outil** : Claude Code (Claude Opus 5.5)
- **Objectif** : obtenir un avis indépendant sur les résultats de B-014 avant
  de les défendre, puis corriger ce qui ne tenait pas
- **Phase du projet** : back-end, charge et dimensionnement

## Prompt

> La campagne de capacité est faite, sans être entièrement finalisée. Relis
> ses résultats de façon critique : chaque chiffre doit être confronté aux
> données brutes avant d'être défendu.

puis, après la relecture :

> Lance les étapes 1 et 2 : corriger le défaut de promotion, avec un test qui
> le reproduit d'abord ; corriger le rapport, sans nouvelle mesure.

## Ce que la relecture a trouvé

La relecture a confronté chaque phrase du rapport aux fichiers bruts de
`load/results/` (résumés k6, tableaux Prometheus, métadonnées) et au code.

| Constat | Conséquence |
|---|---|
| 80 fichiers/s passe le critère 3 fois sur 3, mais la file croît dans 2 passages (+95, +124) et le dépôt p95 est multiplié par dix | 80 requalifié en **genou** ; 50 reste la cible soutenable |
| Le critère tolère +5 % des dépôts du palier : 240 fichiers à 80/s, la moitié de la file | Sur 60 s, il ne sépare pas « soutenable » de « juste au-dessus du genou » ; palier long ajouté au plan |
| ClamAV tournait hors du quota de 1 CPU et n'était pas collecté | La comparaison réel/simulé mesure le coût de l'appel pour le nœud, pas le coût de ClamAV ; l'antivirus n'est pas dimensionné |
| « 120 tient » en simulé : 116 déposés/s, 1 152 envois abandonnés par k6 | Contre-pression côté client, invisible dans les `429` : 120 est le genou simulé |
| Premier palier en échec dans toutes les campagnes qui en ont un | Le démarrage à froid devient un résultat (un nœud neuf n'apporte pas sa capacité tout de suite) |
| Passage `13-33-29Z` : zéro fichier terminé pendant deux paliers, non mentionné | Documenté dans « Passages non retenus », rattaché au défaut de promotion |
| Rapport en « Mio », script en Mo (10⁶) ; métrique `unscanned_servable_files` inexistante | Unités et nom de métrique (`praxedo_invariant_violations`) corrigés |
| Seuil `p95 < 2 s` de la rafale en échec à chaque passage | Physiquement inatteignable (105 Mo par un nœud) ; seuil dérivé du volume au débit nominal (≈ 3,7 s) |
| Admission bornée en nombre de fichiers | 500 fichiers de 500 Mo = ~1 h 30 de retard sous la borne ; ajouté aux limites |
| `MaxRAMPercentage=75` sur 1 Gio | Tas max + hors tas > 1 Gio : risque en endurance, ajouté aux limites |

## Le défaut de promotion — reproduit avant d'être corrigé

Cause lue dans le code : le SDK S3 réessaie par défaut ; sur un `503`, il
redemande le corps d'une écriture en flux ; `SingleUseContent` refuse avec une
`IllegalStateException` ; `FilePromotionService` ne rattrape que les pannes
promises par le port ; le fichier reste en `PROMOTING` jusqu'au bail de 15 min.

Un test (`StorageRetriesTest`, stockage simulé par WireMock répondant
`503 SlowDown`, clients pris dans le câblage de production) a d'abord été
écrit **contre le code non corrigé**. Il a échoué comme prévu, et montré plus
que prévu :

- l'`IllegalStateException` remonte aussi sur le **dépôt** (réponse `500` au
  lieu de `503` + `Retry-After`) ;
- le client de lecture faisait **4** tentatives : le mode « legacy » du SDK,
  jamais écrit nulle part.

Correction : `S3Clients.Retries`, une politique déclarée par identité.
`ingest` et `worker` n'ont aucun réessai SDK (le client et la file réessaient) ;
`delivery` garde le mode standard (3 tentatives), une lecture se rejouant sans
risque. C'est l'application de la règle déjà écrite dans `backend/AGENTS.md`
§8 : pas deux politiques de réessai concurrentes, une en mémoire et une
persistée.

## Ce que j'ai rejeté ou corrigé, et pourquoi

- **Couper les réessais SDK pour les trois identités** : plus simple, mais le
  chemin de téléchargement n'a personne au-dessus pour réessayer dans la
  requête, et une connexion réutilisée périmée y deviendrait une erreur
  visible. Rejeté au profit d'une politique par identité.
- **Rattraper l'`IllegalStateException` dans la promotion** : traiterait le
  symptôme et banaliserait le garde-fou. `SingleUseContent` reste un fil-piège
  qu'aucun câblage ne doit atteindre, et le test le vérifie.
- **Copie serveur à serveur (`CopyObject`)** pour la promotion : écartée à
  nouveau, pour la raison déjà écrite dans `ARCHITECTURE.md` §7.4 (elle ne
  rend pas l'empreinte de ce qui a été copié).
- **Rejouer les campagnes** : hors du périmètre demandé. Le rapport dit
  explicitement que les chiffres ont été mesurés avant la correction.
- Pour le passage `13-33-29Z`, le lien avec le défaut est une **déduction**
  (symptôme identique, seule saturation non isolée), pas une preuve : les
  journaux n'ont pas été conservés. Le rapport le formule ainsi.

## Vérifications effectuées

- chaque chiffre du rapport recalculé depuis `load/results/` (médianes, p95 de
  rafale, rejets, utilisateurs virtuels, itérations abandonnées) ;
- `StorageRetriesTest` : 3 échecs avant correction, 3 succès après ;
  `ObjectStorageTest` (vrai SeaweedFS) : 12 succès ;
- `mvnw verify` complet : 454 tests, puis 2 en intégration, construction au
  vert ;
- `node --check` sur les scripts de `load/` ; liens et ancres du rapport
  vérifiés. La nouvelle requête d'invariant de `report.mjs` n'a pas pu être
  exécutée : aucun Prometheus de campagne n'était démarré.
