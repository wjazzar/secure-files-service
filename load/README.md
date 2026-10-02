# Essais de capacité

Ce répertoire contient les scénarios, leurs environnements et les résultats
bruts qui servent au dimensionnement du service. Le bilan chiffré et ses
limites sont dans
[`../docs/capacity-planning/README.md`](../docs/capacity-planning/README.md).

## Deux mesures complémentaires

| Campagne | Antivirus | Ce qui est mesuré |
|---|---|---|
| `real-antivirus-*` | conteneur ClamAV réellement appelé par HTTP | capacité de la chaîne complète |
| `instant-clean-*` | réponse `CLEAN` simulée dans le back-end | plafond du code, du stockage, du SHA-256, de la base et de la promotion, sans le coût de ClamAV |

Le simulateur ne court-circuite pas le fichier : il consomme entièrement son
flux avant de répondre. Le chemin stockage → lecture → empreinte → promotion
reste donc exercé.

## Lancer une campagne

Prérequis : Docker Desktop démarré et Node.js disponible. Depuis la racine du
dépôt :

```powershell
node load/run-capacity.mjs real-antivirus-confirmation
node load/run-capacity.mjs instant-clean-confirmation
```

Le lanceur est cross-platform. Chaque argument nomme un fichier
`load/environments/<variante>.env` ; plusieurs arguments s'enchaînent. Pour
chaque campagne, il :

1. refuse de démarrer si les conteneurs d'un autre projet Compose tournent
   (les noms de conteneurs sont fixes) ;
2. repart de **volumes neufs** : aucune file, aucun objet ni aucune ligne hérités ;
3. démarre le ou les nœuds (`NODES`, jusqu'à 3), puis attend PostgreSQL, le
   stockage objet, Keycloak, Prometheus et, selon le cas, ClamAV ;
4. envoie 50 dépôts simultanés de 2 Mio, répartis entre les nœuds ;
5. attend que la file soit vide ;
6. exécute l'échauffement puis les paliers, en relevant `docker stats` de
   chaque conteneur toutes les 5 s, et `pg_stat_activity` toutes les 2 s :
   ce que chaque session de la base attend, et qui la bloque ;
7. conserve sous `load/results/` : résumés k6, rapport Prometheus
   (`*-throughput.md`), rapport des conteneurs (`*-dependencies.md`), rapport
   de la base (`*-database.md` : sessions par pool et par attente, blocages,
   temps de fsync, journal de PostgreSQL, connexions gardées plus de 2 s),
   métadonnées, journaux de chaque nœud (`*-node<N>.log`, non versionnés) ;
   avec `JFR=true`, l'enregistrement du nœud 1 (`*.jfr`, non versionné) et
   ses vues texte (`*-jfr.txt`, si l'outil `jfr` du JDK est dans le `PATH`) ;
8. **si la file ne s'est pas vidée 180 s après la charge**, écrit les
   fichiers restés en attente dans `*-FAILED.md`, **laisse la pile démarrée**
   pour le diagnostic et arrête la série ;
9. sinon, supprime le projet avant la campagne suivante ; la dernière reste
   démarrée pour Grafana.

Les seuils d'une campagne sont exclusivement définis dans son fichier
`load/environments/*.env`. Pour le protocole de confirmation, faire trois
passages et retenir la médiane — chaque passage repart de volumes neufs.

## Où part le temps, et montée en charge

Six campagnes, à lancer en une commande (environ 55 minutes). Tous les nœuds
sont limités à 1 Gio : les mesures ont montré que la mémoire ne bride pas.

```powershell
node load/run-capacity.mjs profile-real scale-1cpu-8w scale-2cpu-4w scale-2cpu-8w scale-3nodes scale-3nodes-2cpu
```

| Variante | Question posée |
|---|---|
| `profile-real` | Où part le temps sur le nœud de référence ? (JFR + dépendances, paliers de 180 s) |
| `scale-1cpu-8w` | Le nœud 1 CPU est-il bridé par son processeur ou par ses 4 workers ? |
| `scale-2cpu-4w` | Que gagne-t-on avec 2 CPU (G1 imposé : à 1 Gio, la JVM choisirait SerialGC) ? |
| `scale-2cpu-8w` | Que tient un nœud réaliste ? |
| `scale-3nodes` | Le débit triple-t-il avec trois nœuds sur une seule base, un seul stockage, un seul antivirus ? |
| `scale-3nodes-2cpu` | Trois nœuds réalistes (2 CPU, 8 workers) : quelle dépendance cède la première vers 250–300 fichiers/s ? |

À la fin, le projet de la dernière campagne reste démarré ; l'arrêter
avec :

```powershell
docker compose --project-name praxedo-capacity-scale-3nodes -f docker-compose.yml -f load/compose.multinode.yml --env-file load/environments/scale-3nodes.env --profile app --profile load down -v
```

La suppression vise uniquement le projet Compose isolé de la campagne. Elle
ne touche pas aux volumes du projet de développement normal.

## Inventaire

- `k6/concurrency.js` : une requête par utilisateur virtuel, déclenchée
  simultanément ;
- `k6/capacity.js` : débit d'arrivée contrôlé, échauffement, montées et
  paliers ;
- `report.mjs` : lecture de Prometheus par fenêtre et décision « tient / ne
  tient pas » ;
- `run-capacity.mjs` : orchestration complète et archivage des preuves ;
- `stages.mjs` : fenêtres de mesure des paliers, partagées par les rapports ;
- `docker-stats.mjs` : relevé et synthèse CPU / mémoire / réseau des conteneurs ;
- `pg-activity.mjs` : relevé de `pg_stat_activity`, compteurs de disque
  (`pg_stat_wal`, `pg_stat_io`) et synthèse `*-database.md` ;
- `compose.multinode.yml`, `prometheus-multinode.yml` : nœuds 2 et 3, et leur
  collecte ;
- `environments/real-antivirus.env` et `instant-clean.env` : exploration ;
- `environments/*-saturation.env` : recherche du plafond ;
- `environments/*-confirmation.env` : paliers entourant la limite retenue.

## Lire les résultats

Un palier tient si le débit traité suit le débit accepté, si la file ne croît
pas durablement et si aucun `429` n'apparaît. Le débit doit être lu à la fois
en fichiers/s et en Mo/s (10⁶ octets, l'unité de `report.mjs`) : deux charges
avec le même nombre de fichiers peuvent avoir un coût très différent.

Sur un palier de 60 s, la tolérance de `report.mjs` (croissance de la file
inférieure à 5 % des fichiers déposés) sépare un palier sain d'un palier
saturé, mais pas un palier soutenable d'un palier juste au-dessus du genou. Le
palier retenu comme limite se confirme par un palier long.

Les contrôles incontournables restent :

- `praxedo_invariant_violations == 0` pendant tout l'essai (maximum imprimé
  par `report.mjs`) ;
- aucune analyse sans verdict ni verdict refusé pour bail perdu ;
- la file se vide après le palier ;
- le processus ne dépasse pas sa limite de 1 Gio.

Un code de sortie k6 `99` signifie qu'un seuil de performance est dépassé ; le
lanceur conserve quand même les preuves afin que la saturation soit
observable et documentée.

## Nettoyer après une campagne

Adapter le nom et le fichier d'environnement à la variante exécutée :

```powershell
docker compose --project-name praxedo-capacity-instant-clean-confirmation --env-file load/environments/instant-clean-confirmation.env --profile app --profile load down -v
```

Les fichiers de `load/results/` ne sont pas supprimés : ils constituent les
preuves reproductibles du dimensionnement.
