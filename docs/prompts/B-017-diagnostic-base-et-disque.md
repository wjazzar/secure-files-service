# B-017 — Du « pool saturé » au disque : un diagnostic corrigé par le porteur du projet

- **Date** : 2026-09-28
- **Outil** : Claude Code (Claude Opus 5.5)
- **Objectif** : comprendre pourquoi trois nœuds ne font pas mieux qu'un, puis
  fixer les chiffres de capacité à défendre
- **Phase du projet** : back-end, charge et dimensionnement

## Prompts

> Analyse les données de la campagne.

> Sur quoi repose la conclusion que la base est le goulot ? Sans mesure de
> latence côté base, ce n'est qu'une hypothèse.

> Prépare l'instrumentation de PostgreSQL et du stockage. […] Analyse les
> nouveaux résultats.

> Mon hypothèse : le disque de la machine. Dès qu'il sature, tout le reste
> s'effondre. Vérifie-la par la mesure.

> Nous nous arrêtons là : 150 fichiers par seconde dépasse largement le
> besoin. […] Finalise le rapport et les corrections.

## Ce qui s'est passé

| Étape | Affirmation | Ce qui l'a confirmée ou renversée |
|---|---|---|
| 1 | « Le stockage de test est plein » en fin de campagne | Confirmée : SeaweedFS à 20 volumes sur 20, zone servable à 7 × 2 Go ; erreurs `500` uniquement au dernier palier de chaque campagne, dès ~25 000 fichiers |
| 2 | « Quelque chose dans le service garde les connexions ; PostgreSQL est au repos, ce n'est pas la base » | **Renversée.** Un processeur au repos ne dit rien de la latence. Mesure faite après la question du porteur : les deux pools gardent leurs connexions longtemps, et les trois nœuds se figent ensemble 17 s — ni le GC, ni l'antivirus. Côté base, donc |
| 3 | Mécanisme inconnu : verrou, disque, ou application | Instrumentation (relevé de `pg_stat_activity`, journal de PostgreSQL, détection de fuite Hikari, sessions nommées par pool). Série 4 : toutes les sessions bloquées sont des `COMMIT` en attente d'écriture du journal ; aucun verrou ; blocages juste après les checkpoints |
| 4 | Hypothèse du porteur : le disque de la machine | **Confirmée par une mesure directe** : `pg_test_fsync`, 497 fsync/s au repos, 60/s pendant une écriture concurrente. À ~2 fsync par fichier, le modèle retrouve les paliers observés (160–176 tenus, 40–60 à l'effondrement) |

## Décisions restées humaines

- **Deux pools de connexions** : idée du porteur (B-016).
- **L'hypothèse du disque** : du porteur ; l'assistant avait conclu à la base
  sans pouvoir dire pourquoi.
- **S'arrêter à ~150 fichiers/s par nœud** : du porteur. La campagne de
  contrôle proposée (base sans attente du disque, pour trancher la montée en
  charge horizontale) n'a pas été menée ; elle figure dans les pistes
  d'amélioration.

## Ce que j'ai rejeté ou corrigé, et pourquoi

- **Conclure sur la cause d'une saturation de pool sans mesure de latence** :
  un pool plein dit que les connexions sont gardées, pas par qui ni pourquoi.
  La mesure qui tranchait — temps de détention comparé des deux pools, qui
  interrogent la même base — était disponible dès le départ.
- **Écarter la base parce qu'elle consommait peu de processeur** : une base
  qui attend un verrou ou son disque ne consomme rien.
- **« Budget réseau interne de 3 fois le dépôt »** du premier rapport :
  mesuré à 4 fois pour le stockage, 6 fois pour le back-end ; corrigé.
- **Rapport de capacité** entièrement réécrit : il annonçait encore 50
  fichiers/s, un genou à 80 et « le service ne s'effondre pas ».

## Vérifications effectuées

- chiffres du rapport recalculés depuis les fichiers bruts : fichiers
  acceptés (résumés k6), fsync (`pg_stat_wal`), attentes par pool
  (`*-database.md`), latence des rafales ;
- `pg_test_fsync` au repos puis sous écriture concurrente (conteneur et volume
  de test supprimés ensuite) ;
- suite complète au vert après l'instrumentation (466 tests, plus 2
  d'intégration) ; requêtes et rapports de la base testés contre le vrai
  PostgreSQL 17.
