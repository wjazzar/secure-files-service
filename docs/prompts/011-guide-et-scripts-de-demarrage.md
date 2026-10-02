# 011 — Guide de démarrage et scripts

- **Date** : 2026-09-27
- **Outil** : Claude Opus 5.5
- **Objectif** : que n'importe qui sache tout démarrer seul — base, Keycloak, antivirus, interface, et leur couplage — idéalement en une commande
- **Phase du projet** : livraison

## Prompt

> Écris un `README.txt` qui explique comment démarrer l'ensemble : les bases
> de données, Keycloak, l'antivirus, le front-end, et leur branchement.
> N'importe qui doit pouvoir tout démarrer sans aide. Idéalement, fournis des
> scripts qui le font en une commande, sous Linux comme sous Windows.

## Ce qui a été livré

| Fichier | Rôle |
|---|---|
| [`README.txt`](../../README.txt) | Le pas-à-pas : en bref, prérequis, scripts, ce que fait `start` étape par étape (pour le refaire à la main), chaque brique avec adresse, identifiants et vérification, Keycloak, autres modes de lancement, dépannage |
| `scripts/start.{sh,ps1}` | Prérequis vérifiés → `docker compose --profile app up -d --build` → attente de la santé des cinq conteneurs → adresses → interface branchée sur le service. Options `--oidc` / `--no-front` |
| `scripts/check.{sh,ps1}` | Vérifie que chaque brique **fait ce qu'elle promet**, pas seulement qu'elle répond : isolation du stockage dans les deux sens, EICAR détecté, audience du jeton, jeton accepté en mode oidc, interface réellement branchée |
| `scripts/stop.{sh,ps1}` | Arrêt, `--purge` pour effacer les données |

## Choix

- **Deux versions de chaque script, sans fichier `.cmd`.** L'environnement
  impose des scripts multiplateformes (`AGENTS.md` §5). La forme
  `powershell -ExecutionPolicy Bypass -File …` passe sur un poste Windows
  à la politique d'exécution par défaut, sans modifier aucun réglage.
- **Le script attend la santé réelle** (`docker inspect`, les `healthcheck` du
  compose et du `Dockerfile`), et échoue tout de suite sur un conteneur
  `unhealthy` ou arrêté, avec la commande de journaux à lancer.
- **`check` teste des propriétés, pas des ports.** Un antivirus qui répond
  `200` à tout, ou une identité de livraison incapable de lire, passeraient
  un simple test de vie. D'où EICAR et la contre-épreuve du stockage, repris
  d'`infra/README.md` §6.
- **En mode `--oidc`, l'interface n'est pas lancée.** Son adaptateur
  Keycloak n'est pas livré (README §10) : branchée sur un service qui exige un
  jeton, elle ne recevrait que des `401`. Le script le dit et affiche les
  commandes pour appeler l'API avec un jeton — vérifiées en copier-coller.

## Ce que l'exécution a révélé et corrigé

Tout a été lancé pour de vrai, en bash (Git Bash) et en Windows PowerShell 5.1.

| Constat | Correction |
|---|---|
| Une autre pile occupait 5432, 8081 et 9001 ; Docker gardait 8333 réservé | Les surcharges de ports du compose sont reprises par les scripts ; le dépannage le documente. Le test a été mené avec les ports déplacés, ce qui valide ce chemin |
| **9333 refusé par Windows** (« forbidden by its access permissions ») : plage réservée par Hyper-V, qui change à chaque redémarrage | Entrée de dépannage avec la commande `netsh` qui le montre |
| PowerShell 5.1 rend le corps en **octets** pour `application/vnd.spring-boot.actuator.v3+json` : la santé du service lue comme « 123 34 115… » | Décodage explicite |
| La version de ClamAV ne s'affichait pas : le JSON de `/version` a une espace après les deux-points | Extraction tolérante |
| `check` disait « branchée sur le service » pour une interface lancée **sur bouchons** : le relais `/api` répondait, mais le navigateur n'appelait jamais le service | Détection par la configuration que Vite injecte dans chaque module servi (`VITE_API_MOCKING`) |
| Lancer Vite sur un 5173 déjà pris : il **commence par effacer son cache de dépendances** avant d'échouer — le cache d'un serveur déjà lancé | Contrôle du port **avant** de lancer Vite, avec un message qui dit quoi faire |
| En PowerShell, l'échec de `npm run dev` sortait avec le code 0 | Code de sortie propagé |
| Fichiers `.ps1` sans BOM : Windows PowerShell 5.1 les lit en ANSI et casse les accents | Enregistrés en UTF-8 avec BOM |

## Vérifications effectuées

- `start.sh --no-front`, `start.sh --oidc`, `start.ps1` : pile saine en ~35 s
  une fois les images en cache.
- `check.sh` et `check.ps1` : tout au vert en v1 et en v2 (sans jeton → `401`,
  jeton d'alice → `200`).
- Couplage prouvé dans un navigateur : une instance de l'interface en mode
  réel (port 5174, cache séparé pour ne pas toucher au serveur déjà lancé)
  affiche les fichiers du vrai service ; un fichier sain déposé **à travers le
  relais** devient disponible, EICAR est bloqué.
- Chemins d'erreur : port 5173 déjà pris, argument inconnu.
