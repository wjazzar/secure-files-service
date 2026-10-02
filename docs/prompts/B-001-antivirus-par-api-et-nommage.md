# B-001 — Antivirus consommé par une API, et nommage du produit

- **Date** : 2026-09-26
- **Outil** : Claude Opus 5 (Claude Code)
- **Objectif** : appliquer deux décisions du porteur du projet et en tirer
  toutes les conséquences, jusqu'à l'infrastructure
- **Phase du projet** : back-end, avant le premier code

## Prompt

> Deux décisions : le produit s'appellera Praxedo, et l'antivirus doit être
> consommé par une API HTTP, comme l'exige l'énoncé. Sa performance de
> détection importe peu ; ce qui compte, c'est la délégation par API. Choisis
> un moteur qui s'exécute dans Docker, et tires-en les conséquences jusqu'à
> l'infrastructure.

## Ce que j'ai retenu

**La contrainte est celle de l'énoncé, pas une préférence technique.** L'énoncé
dit « déléguer leur analyse à un antivirus **disponible via une API** ».
L'architecture précédente parlait au moteur par son protocole natif `clamd`
(TCP) : défendable, mais ce n'est pas ce que l'énoncé décrit. Le doute ne vaut
pas d'être couru pour un exercice dont l'énoncé est le barème.

**Retenu** : ClamAV **derrière une API HTTP**, conteneur `ajilaag/clamav-rest`
épinglé par digest, dérivé pour corriger sa configuration.

| Ce qui change | Ce qui ne change pas |
|---|---|
| L'adaptateur : client HTTP (`RestClient`) au lieu d'un client TCP écrit à la main | Le port `AntivirusScanner` — c'est précisément ce qu'on attend d'un port |
| Le double de test : **WireMock** revient, puisque le protocole est HTTP | Le moteur : ClamAV, donc EICAR reste démontrable hors ligne |
| `docker-compose.yml`, `infra/` | L'invariant, l'automate, le schéma, les protocoles |

**Nommage** : `praxedo` partout — conteneurs, identités de stockage, realm et
comptes Keycloak, préfixe des métriques, `groupId` et paquet Java.

## Ce que j'ai rejeté ou corrigé, et pourquoi

| Rejeté | Raison |
|---|---|
| **VirusTotal, MetaDefender Cloud, Cloudmersive** | De vraies API, mais clé requise, quotas, taille limitée, et le contenu part chez un tiers. Incompatible avec un `docker compose up` reproductible et hors ligne. Gardés comme second adaptateur de démonstration possible |
| **MetaDefender Core, Kaspersky Scan Engine** (Docker) | API HTTP sérieuses, licence commerciale requise |
| **Écrire nous-mêmes une façade HTTP devant `clamd`** | Ce serait fabriquer l'API, pas déléguer à un antivirus qui en expose une. L'énoncé demande le second |
| **`POST /v2/scan` (multipart)** de l'image retenue | `POST /scanHandlerBody` prend le corps brut : un encodage de moins sur 500 Mo |
| **Faire confiance à l'image amont telle quelle** | Son point d'entrée **ne règle pas `AlertExceedsMax`**. Sans ce réglage, une limite interne atteinte remonte comme une **analyse propre**. Image dérivée, dont le build échoue si le fichier de configuration disparaît |
| **Traiter un `413` comme un verdict** | Le code source amont renvoie aussi `413` sur une coupure de flux. Comme l'admission plafonne à 500 Mo et le moteur à 512 Mo, un vrai dépassement est impossible : `413` est donc traité en **panne technique**, jamais en verdict |
| **Traiter un `406` comme une menace, toujours** | Avec `AlertExceedsMax`, une limite atteinte remonte en `FOUND` — donc en `406`. La description `Heuristics.Limits.Exceeded…` doit donner `UNSCANNABLE`, pas `INFECTED` |
| **Conserver les rôles Keycloak** (`file.read`, `file.write`) et le compte de démonstration d'un `403` | Le contrat 1.3 est **sans rôle** : le cloisonnement est par propriétaire, et un fichier d'autrui répond `404`. Le realm portait une dérive, elle est corrigée |

## Vérifications effectuées

| Vérification | Source | Résultat |
|---|---|---|
| Le chemin de scan est-il vraiment en flux ? | Code source `clamrest.go` | `response, err := c.ScanStream(r.Body, abort)` — le corps est relayé, pas mis en tampon |
| Codes de retour de l'API | Code source, `getHTTPStatusByClamStatus` | `200` sain, `406` détection, `412` illisible, `413` limite **ou coupure**, `400` erreur |
| Moteur et signatures exposés ? | Endpoint `/version` | `{"Clamav": …, "Signature": …, "Signature_date": …}` — de quoi remplir le verdict du contrat |
| `AlertExceedsMax` réglé par l'image ? | `entrypoint.sh` amont | **Non.** Il règle `StreamMaxLength`, `MaxFileSize`, `MaxScanSize`, `MaxRecursion`, `MaxFiles`… mais pas celui-là. D'où l'image dérivée |
| Projet maintenu ? | Dépôt GitHub, Docker Hub | Version `0.6.6` publiée le 2026-08-20, versionnage sémantique depuis décembre 2025, images multi-architectures |
| Digest de l'image | Docker Hub API **et** `docker buildx imagetools inspect` | `sha256:f5aea983…836359` — concordant sur les deux sources |
| Le `docker-compose.yml` reste valide | `docker compose config` | Valide |
| Le front référence-t-il l'ancien nom ? | Recherche dans `frontend/` | Aucune occurrence : le renommage ne casse rien côté interface |

### Vérifié en exécutant, pas seulement en lisant

Le premier `Dockerfile` écrit **a échoué à la construction** : il corrigeait
`/clamav/etc/clamd.conf`, chemin utilisé par le point d'entrée amont… mais vide
dans l'image. La lecture du point d'entrée **dans l'image** (et non dans le
README) a donné la réponse : `cp /etc/clamav/* /clamav/etc/` à chaque démarrage.
La source à corriger est donc `/etc/clamav/clamd.conf`. Le garde-fou du
`Dockerfile` a fait exactement son travail : il a cassé le build au lieu de
laisser passer une garantie inopérante.

| Mesure | Résultat (2026-09-26) |
|---|---|
| Construction de l'image dérivée | ✅ après correction du chemin |
| Démarrage du conteneur | **sain en ~20 s** — les signatures sont pré-embarquées, pas téléchargées |
| Limites effectivement appliquées | `StreamMaxLength 512M`, `MaxFileSize 512M`, `MaxScanSize 1024M`, `MaxRecursion 16`, `MaxFiles 10000`, **`AlertExceedsMax yes`** |
| Fichier sain | `HTTP 200`, corps `{OK   200}` |
| EICAR (assemblé à l'exécution, jamais écrit sur disque) | `HTTP 406`, corps `{FOUND Eicar-Test-Signature  406}` |
| `/version` | `{ "Clamav": "1.4.6", "Signature": "28098", "Signature_date": "Thu Aug 20 08:24:22 2026" }` |

**Découverte non anticipée** : le corps du scan annonce `application/json`
**mais n'en est pas** — c'est le rendu par défaut d'une structure Go. L'adaptateur
s'appuiera donc sur le **code HTTP** comme signal faisant foi, et ne lira le
corps que comme du texte, pour le nom de la menace et la discrimination
`Heuristics.Limits.Exceeded`. Un `406` inintelligible vaudra `INFECTED` : la
direction sûre.

**Reste au spike B0.3** : le comportement sur 500 Mo (mémoire du client et du
conteneur, durée), et la vérification qu'un dépassement de `MaxScanSize` ne
remonte pas en `200`.

## Ce que ça coûte, et pourquoi c'est assumé

On place un composant tiers sur le chemin critique de sécurité — exactement ce
que l'analyse précédente refusait. Quatre garde-fous compensent : image épinglée
par digest, chemin de scan lu dans son code source, configuration dangereuse
corrigée par une image dérivée qui échoue à la construction si elle ne peut plus
l'être, et port inchangé permettant de revenir au protocole natif sans toucher
au reste du service.

C'est un compromis, il est traçable, et il se défend.
