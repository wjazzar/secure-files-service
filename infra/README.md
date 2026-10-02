# Environnement de développement local

Quatre composants d'infrastructure (base, stockage objet, Keycloak,
antivirus) et deux de supervision (Prometheus, Grafana), décrits dans
[`../docker-compose.yml`](../docker-compose.yml) :

Tous les ports sont publiés sur **127.0.0.1 seulement** (audit S-09). Toutes
les images sont épinglées : version exacte **et** empreinte (analyse :
[`../docs/33-analyse-des-dependances.md`](../docs/33-analyse-des-dependances.md)).

| Service | Image | Port | Rôle |
|---|---|---|---|
| `postgres` | `postgres:17.11-alpine` | 5432 | Métadonnées du service **et** base de Keycloak. **Un rôle par usage** ([`postgres/initdb`](postgres/initdb/01-roles-and-databases.sh)) : `praxedo_owner` migre, `praxedo_app` exécute le service (lignes seulement), `keycloak` possède sa seule base ; le superutilisateur `praxedo` ne sert qu'à l'administration |
| `objectstore` | `chrislusf/seaweedfs:4.47` | 8333 (S3), 19333 (console ; 9333 dans le conteneur) | Stockage objet compatible S3 |
| `keycloak` | `keycloak/keycloak:26.4.7` (image officielle, Docker Hub) | 8081 (IHM), 9001 (santé) | Fournisseur d'identité OpenID Connect |
| `antivirus` | dérivée de `ajilaag/clamav-rest:0.6.6` | 9000 | Moteur d'analyse, **exposé par une API HTTP** |
| `prometheus` | `prom/prometheus:v3.5.5` | 9090 | Collecte `/actuator/prometheus` toutes les 5 s sur `backend:8091`, le port de management du service en conteneur — [`prometheus/prometheus.yml`](prometheus/prometheus.yml) |
| `grafana` | `grafana/grafana:12.1.10` | 3000 | Tableau de bord « Praxedo — capacité du service », provisionné avec sa source de données ; consultation sans compte, `admin`/`admin` pour modifier. Généré par [`grafana/generate-dashboard.mjs`](grafana/generate-dashboard.mjs) |

Et le service lui-même, dans le **profil `app`** — pour que `docker compose up`
seul laisse le port 8080 libre au développement (`./mvnw spring-boot:run`) :

| Service | Image | Port | Rôle |
|---|---|---|---|
| `backend` | construite depuis [`../backend/Dockerfile`](../backend/Dockerfile) | 8080 (API), 8091 (actuator : santé, métriques) | L'API ; l'actuator a son propre port, jamais celui de l'API (audit S-08). N'attend **pas** l'antivirus : dépôts et téléchargements fonctionnent sans lui. **Bridé à 1 CPU et 2 Go** par défaut (`BACKEND_CPUS`, `BACKEND_MEMORY`) : sans limite, on mesurerait le poste. Les campagnes de capacité font varier ce gabarit (1 ou 2 CPU, 1 Gio, 4 ou 8 workers — [`../docs/capacity-planning/README.md`](../docs/capacity-planning/README.md)) |

```bash
docker compose up -d                          # infrastructure seule
docker compose --profile app up -d --build    # infrastructure + service
docker compose ps                             # état et santé
docker compose down                           # arrêt (les données restent)
docker compose down -v                        # arrêt + effacement des données
../scripts/demo.sh                            # le parcours de démonstration
```

> ⚠️ **Tous les identifiants de cet environnement sont des identifiants de
> développement local**, volontairement triviaux et versionnés pour que
> `docker compose up` suffise. Ils ne doivent jamais servir ailleurs.
>
> ⚠️ **Rôles PostgreSQL créés au premier démarrage seulement** : un volume
> créé avant le 01/10 n'a pas `praxedo_owner`, `praxedo_app` ni `keycloak`, et
> le service refuse de migrer. Une fois : `docker compose down -v`.

---

## 1. Choix du stockage objet

Le cadrage du 21/09 demande un stockage partagé de type objet (S3) plutôt
que le disque local
([`../docs/28-precisions-de-cadrage.md`](../docs/28-precisions-de-cadrage.md)).
Trois candidats ont été examinés :

| Candidat | Verdict |
|---|---|
| **MinIO** | ❌ **Écarté.** Dépôt communautaire **archivé le 25 avril 2026**, plus maintenu, distribué en source uniquement. Le réflexe habituel n'est plus tenable |
| **LocalStack** (édition communautaire) | ❌ **Écarté.** N'applique pas les politiques IAM : n'importe quel identifiant accède à tout. Or **toute l'isolation entre la quarantaine et la zone servable repose sur des droits distincts par identifiant** |
| **SeaweedFS** | ✅ **Retenu.** Maintenu, léger, compatible S3, et surtout il **applique réellement** des permissions par identité et par zone |

Les ports de stockage rendent ce choix réversible : passer sur AWS S3 en
production est un changement de configuration.

### Pourquoi la 4.47 et pas la 3.97 d'origine

Mesuré le 27/09 (`docs/prompts/B-005`) : en **3.97**, une identité en lecture
seule ne peut **rien** lire — tout `GET` est refusé tant que l'identité ne
détient pas aussi `Write` sur la zone. Il aurait fallu donner `Write:servable`
au rôle de livraison, c'est-à-dire la possibilité d'écraser un fichier déjà
validé par un contenu non analysé. La **4.47** applique `Read` correctement.
Le test d'intégration `ObjectStorageTest` le vérifie à chaque construction,
dans les deux sens.

---

## 2. Isolation par les identifiants — le cœur de la garantie

L'invariant du service est qu'un fichier non analysé ne peut pas être servi.
La dernière ligne de défense ne doit pas être un `if` dans le code, mais le
**stockage lui-même** : le composant qui sert les fichiers n'a aucun droit de
lecture sur la quarantaine.

Quatre identités, définies dans
[`seaweedfs/s3-identities.json`](seaweedfs/s3-identities.json) :

| Identité | `quarantine` | `servable` | Utilisée par |
|---|---|---|---|
| `praxedo-ingest` | **écriture seule** | — | Le dépôt : il écrit, il ne relit jamais |
| `praxedo-worker` | lecture / écriture | **écriture seule** | L'analyse et la promotion : il copie dans la zone servable, il n'y relit jamais |
| `praxedo-delivery` | **aucun accès** | **lecture seule** | Le téléchargement |
| `praxedo-admin` | administration | administration | Création des zones, exploitation |

**Conséquence** : même avec un statut corrompu en base, même avec la
vérification applicative supprimée, le chemin de téléchargement **ne peut pas
atteindre** un fichier en quarantaine. Le stockage refuse.

La vérification de cette propriété est au §5.

---

## 3. Antivirus — un moteur derrière une API HTTP

L'énoncé impose de « déléguer l'analyse à un antivirus **disponible via une
API** ». C'est une **API HTTP REST**, servie par un conteneur distinct du
service : `ajilaag/clamav-rest` fait tourner ClamAV, tient ses signatures à
jour et l'expose en HTTP.

| Appel | Usage |
|---|---|
| `POST /scanHandlerBody` | Corps **brut**, relayé **en flux** vers le moteur — aucun tampon, ni côté service, ni côté antivirus |
| `GET /version` | Moteur et version des signatures, tracés dans le verdict |
| `GET /metrics` | Métriques Prometheus |
| `GET /` | Sonde de vie (utilisée par le `healthcheck`) |

**Pourquoi le corps brut et non le multipart** : `POST /v2/scan` existe et
accepte du `multipart/form-data`, mais le corps brut évite un encodage
intermédiaire sur un fichier de 500 Mo. Les deux chemins relaient en flux dans
l'implémentation amont (`ScanStream(r.Body, …)`), ce qui a été vérifié dans son
code source avant de retenir cette image.

### Codes de retour, et leur traduction

| Réponse | État du fichier | Remarque |
|---|---|---|
| `200` | `CLEAN` → promotion | |
| `406` + description **sans** `Heuristics.Limits.Exceeded` | `INFECTED` — définitif | La description porte le nom de la menace |
| `406` + description `Heuristics.Limits.Exceeded…` | **`UNSCANNABLE`** | ⚠️ Piège : une limite atteinte remonte comme une détection. La confondre avec une menace serait faux |
| `412` | `UNSCANNABLE` | Contenu que le moteur n'a pas su analyser |
| `413` | **Panne technique**, nouvelle tentative | ⚠️ L'implémentation amont renvoie aussi `413` sur une coupure de flux. Comme le service n'accepte jamais plus de 500 Mo et que le moteur accepte jusqu'à 2000 Mo, un vrai dépassement est impossible : on traite donc ce code comme une panne, jamais comme un verdict |
| Timeout, coupure, réponse illisible | Panne technique → nouvelle tentative | |

### L'image est dérivée, et c'est délibéré

[`antivirus/Dockerfile`](antivirus/Dockerfile) part de l'image amont **épinglée
par digest** et corrige deux choses :

> 1. `AlertExceedsMax` n'est pas réglé par le point d'entrée de l'image amont ;
> 2. rien n'empêche `MAX_FILE_SIZE` ≤ `MAX_SCAN_SIZE`, qui tronque en silence.

Sans ce réglage, une limite interne atteinte (archive trop profonde, volume
décompressé trop gros, trop de fichiers) peut remonter comme une **analyse
propre** : le service déclarerait sain un fichier analysé partiellement. C'est
exactement le genre de défaut de configuration qui rend une garantie de
sécurité fausse sans qu'aucun test du chemin nominal ne s'en aperçoive.

Le `Dockerfile` **échoue à la construction** s'il ne trouve plus la directive
`AlertExceedsMax` dans le fichier de configuration amont, ou si la correction
n'a pas pris : une évolution de l'image amont casse le build au lieu de
désactiver la garantie en silence.

Les limites de taille, elles, sont pilotées par variables d'environnement dans
[`../docker-compose.yml`](../docker-compose.yml) — donc versionnées :

| Variable | Valeur | Pourquoi |
|---|---|---|
| `MAX_SCAN_SIZE` | 1024M | Volume cumulé après décompression : protège des bombes de décompression. Doit rester ≥ 500 Mo, la taille acceptée par le service |
| `MAX_FILE_SIZE` | 2000M | **Strictement supérieur à `MAX_SCAN_SIZE`** (voir ci-dessous). Règle aussi `StreamMaxLength` : le service plafonne déjà à 500 Mo |
| `MAX_RECURSION` / `MAX_FILES` | 16 / 10 000 | Archives imbriquées |

#### Ce que la mesure a révélé (27/09, ClamAV 1.4.6 — détail en `docs/prompts/B-008`)

Sonde : une signature maison ajoutée au moteur, un marqueur placé **à la fin**
d'une entrée d'archive remplie de zéros. Si le marqueur est trouvé, la fin de
l'entrée a été lue.

| Archive | `MAX_FILE_SIZE` 512M (ancienne valeur) | `MAX_FILE_SIZE` 2000M |
|---|---|---|
| Entrée de 100 Mo + marqueur | détecté | détecté |
| Entrée de 600 Mo + marqueur | ❌ **`OK` — faux négatif** | détecté |
| Entrée de 1 100 Mo + marqueur | ❌ **`OK` — faux négatif** | `Heuristics.Limits.Exceeded.MaxScanSize` → non analysable |
| 3 entrées de 400 Mo | `Heuristics.Limits.Exceeded.MaxScanSize` | idem |

**Le mécanisme** : une entrée plus grosse que `MaxFileSize` est tronquée à
l'extraction, et la partie extraite, désormais sous la limite, est analysée
normalement — `AlertExceedsMax` ne lève rien sur ce chemin. La limite de volume
cumulé, elle, alerte. D'où la règle : **`MaxFileSize` > `MaxScanSize`**, pour que
ce soit toujours la limite qui alerte qui cède la première. Le point d'entrée
de l'image ([`antivirus/praxedo-entrypoint.sh`](antivirus/praxedo-entrypoint.sh))
refuse de démarrer si la relation est violée, et un test contre le vrai moteur
(`AntivirusEngineLimitsTest`) la vérifie à chaque build.

**Angle mort qui demeure, quelle que soit la configuration** : une entrée
**compressée** dont l'en-tête local est au format **zip64** n'est pas analysée
du tout — EICAR dans une archive valide de 206 octets est déclaré sain. C'est une
limite du moteur, pas de la configuration ; elle est caractérisée par un test
(qui échouera le jour où une version du moteur la corrigera) et listée dans les
pistes d'amélioration du README.

### Ce qui a été écarté

| Option | Raison |
|---|---|
| **Protocole `clamd` en TCP** (`INSTREAM`), client écrit à la main | Techniquement le plus direct, mais ce n'est pas « une API » au sens attendu. Reste disponible en repli : le conteneur écoute aussi en interne sur 3310, et l'adaptateur est derrière le port `AntivirusScanner` |
| **VirusTotal, MetaDefender Cloud, Cloudmersive** | De vraies API, mais : clé requise, quotas, taille limitée, et le contenu part chez un tiers. Inutilisable pour un `docker compose up` reproductible. Envisageable en **second adaptateur** de démonstration |
| **MetaDefender Core, Kaspersky Scan Engine** (Docker) | API HTTP sérieuses, mais licence commerciale requise |
| **Écrire nous-mêmes la façade HTTP devant `clamd`** | Ce serait fabriquer l'API, pas déléguer à un antivirus qui en expose une |

### Premier démarrage

Le conteneur télécharge puis charge les signatures : compter une à deux minutes
et **1 à 2 Go de RAM**. Un volume nommé conserve la base entre les
redémarrages. La sonde tolère ce délai (`start_period` de 4 minutes) :
`docker compose ps` affiche `starting`, ce n'est pas une erreur.

---

## 4. Keycloak (authentification)

Le realm `praxedo` est importé au démarrage depuis
[`keycloak/realm-praxedo.json`](keycloak/realm-praxedo.json).

- Console d'administration : <http://localhost:8081> — `admin` / `admin`
- **Côté service**, l'authentification est **toujours exigée** : aucun réglage
  ne la coupe (ADR-0014). Les identifiants du client `praxedo-web` par défaut
  sont ceux de ce realm, rien à régler. Les identifiants (`sub`) des comptes
  de démonstration sont **fixés** dans `realm-praxedo.json` : une
  réimportation ne change pas le propriétaire de leurs fichiers. L'émetteur
  attendu est `http://localhost:8081/realms/praxedo` (l'adresse vue du
  navigateur, comme la connexion et la fin de session, où c'est lui qu'on
  envoie), les clés et les jetons passent par `http://keycloak:8080/…`
  (l'adresse vue du conteneur) — les deux diffèrent, c'est voulu.
- `KC_HOSTNAME=http://localhost:8081` et `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true` :
  l'émetteur des jetons est **toujours** l'adresse du navigateur, même pour un
  jeton obtenu par le service sur le réseau Docker. Vérifié : la découverte lue
  depuis un conteneur annonce l'émetteur `localhost:8081` et un point de jetons
  en `keycloak:8080`.
- ⚠️ **Le realm n'est importé que s'il n'existe pas encore.** Après une
  modification de `realm-praxedo.json` (comme le passage au client confidentiel
  du 28/09), appliquez la configuration, supprimez le realm, puis redémarrez
  Keycloak — sans toucher aux autres données :

  ```bash
  docker compose up -d keycloak      # nouvelle configuration (KC_HOSTNAME, secrets)
  docker exec praxedo-keycloak /opt/keycloak/bin/kcadm.sh config credentials \
    --server http://localhost:8080 --realm master --user admin --password admin
  docker exec praxedo-keycloak /opt/keycloak/bin/kcadm.sh delete realms/praxedo
  docker compose restart keycloak    # réimporte realm-praxedo.json
  ```
- Découverte OpenID Connect :
  <http://localhost:8081/realms/praxedo/.well-known/openid-configuration>

### Clients

| Client | Type | Usage |
|---|---|---|
| `praxedo-web` | **Confidentiel** (secret), code d'autorisation **avec PKCE (S256)** | Le service, pour la connexion du navigateur (ADR-0012). Retours autorisés, **exacts** : `http://127.0.0.1:5173/api/v1/auth/callback`, `http://localhost:5173/…`, `http://localhost:8080/…` ; après déconnexion, `…/login?signed-out` sur les mêmes origines. Aucun jeton ne va au navigateur |
| `praxedo-integration` | **Confidentiel**, *client credentials* (compte de service) | Un système tiers de démonstration : il obtient un jeton `Bearer` et appelle l'API ; son compte de service possède ses propres fichiers |
| `praxedo-files-api` | Ressource protégée (`bearerOnly`) | Le service vu comme API : il reçoit des jetons, il n'en demande jamais |

Les secrets ne sont pas écrits dans le realm : `realm-praxedo.json` contient
des variables (`OIDC_CLIENT_SECRET`, `PRAXEDO_INTEGRATION_SECRET`) que
Keycloak résout à l'import, depuis l'environnement que lui passe
`docker-compose.yml` — valeurs de développement par défaut, à fournir
ailleurs.

Un *mapper d'audience* ajoute `praxedo-files-api` à l'audience des jetons du
système tiers, afin que le service puisse valider le champ `aud`. Jetons
d'accès de 5 minutes : c'est le rythme auquel le service revalide une session
de navigateur. **Pas de rotation** des jetons de rafraîchissement
(`revokeRefreshToken: false`) : deux nœuds qui renouvellent la même session au
même instant réussissent tous les deux, au lieu de déconnecter l'utilisateur ;
chez un client confidentiel, un jeton de rafraîchissement volé ne sert à rien
sans le secret (ADR-0012).

### Utilisateurs — et pas de rôle

**Le realm ne définit aucun rôle**, et c'est conforme au contrat 1.3 : le
cloisonnement se fait **par propriétaire**, pas par permission. Chaque
utilisateur authentifié possède un espace de fichiers privé ; un fichier
déposé par quelqu'un d'autre répond `404`, comme un fichier inconnu. Le seul
`403` du contrat est `CSRF_TOKEN_INVALID` (une écriture portée par le cookie de
session sans son jeton CSRF) : jamais un refus d'accès à un fichier.

Les comptes de démonstration sont ceux que déclare
[`keycloak/realm-praxedo.json`](keycloak/realm-praxedo.json) (section `users`),
avec leur mot de passe. Le fournisseur d'identité simulé du front (mode
bouchon, `npm run dev:mock`) a ses propres comptes, indépendants de ce realm.

### Réservé au développement

- `start-dev` : HTTP en clair, pas de cache distribué.
- Aucun client n'accepte le *password grant* (`directAccessGrantsEnabled`
  est désactivé partout) : le mot de passe ne se saisit que chez Keycloak.

---

## 5. PostgreSQL

Une seule instance, deux bases : `praxedo` (le service) et `keycloak`. Au
premier démarrage,
[`postgres/initdb/01-roles-and-databases.sh`](postgres/initdb/01-roles-and-databases.sh)
crée la base `keycloak` et trois rôles : `praxedo_owner` (propriétaire du
schéma, utilisé par Flyway seul), `praxedo_app` (le service : lecture et
écriture des lignes, rien d'autre) et `keycloak` (propriétaire de sa seule
base).

En production, la base du fournisseur d'identité serait séparée de celle du
service : cycles de vie, sauvegardes et surfaces de risque n'ont rien à voir.
Pour un environnement local, une instance suffit et évite un conteneur de plus.

---

## 6. Vérifier que l'environnement fait ce qu'il promet

### 6.1 Les services sont sains

```bash
docker compose ps
```

### 6.2 Les deux zones de stockage existent

```bash
docker run --rm --network praxedo_default \
  -e AWS_ACCESS_KEY_ID=praxedo-admin -e AWS_SECRET_ACCESS_KEY=praxedo-admin-secret \
  -e AWS_DEFAULT_REGION=us-east-1 amazon/aws-cli \
  --endpoint-url http://objectstore:8333 s3 ls
```

### 6.3 ⭐ L'isolation est réelle — le test qui compte

Le chemin de livraison ne doit **pas** pouvoir lire la quarantaine.

```bash
# 1. Le dépôt écrit dans la quarantaine
docker run --rm --network praxedo_default \
  -e AWS_ACCESS_KEY_ID=praxedo-ingest -e AWS_SECRET_ACCESS_KEY=praxedo-ingest-secret \
  -e AWS_DEFAULT_REGION=us-east-1 amazon/aws-cli \
  --endpoint-url http://objectstore:8333 \
  s3api put-object --bucket quarantine --key demo.txt --body /etc/hostname

# 2. La livraison tente de la lire  →  doit ECHOUER (AccessDenied)
docker run --rm --network praxedo_default \
  -e AWS_ACCESS_KEY_ID=praxedo-delivery -e AWS_SECRET_ACCESS_KEY=praxedo-delivery-secret \
  -e AWS_DEFAULT_REGION=us-east-1 amazon/aws-cli \
  --endpoint-url http://objectstore:8333 \
  s3api get-object --bucket quarantine --key demo.txt /tmp/out
```

La seconde commande **doit échouer**. Si elle réussit, l'isolation n'est pas
effective et il faut corriger avant d'aller plus loin.

⚠️ **Le test négatif ne suffit pas.** Il resterait vert avec une identité de
livraison incapable de livrer quoi que ce soit — c'est exactement ce qui se
passait en SeaweedFS 3.97. La contre-épreuve est indispensable :

```bash
# 3. Le worker écrit dans la zone servable
docker run --rm --network praxedo_default   -e AWS_ACCESS_KEY_ID=praxedo-worker -e AWS_SECRET_ACCESS_KEY=praxedo-worker-secret   -e AWS_DEFAULT_REGION=us-east-1 amazon/aws-cli   --endpoint-url http://objectstore:8333   s3api put-object --bucket servable --key demo.txt --body /etc/hostname

# 4. La livraison la lit  →  doit RÉUSSIR
docker run --rm --network praxedo_default   -e AWS_ACCESS_KEY_ID=praxedo-delivery -e AWS_SECRET_ACCESS_KEY=praxedo-delivery-secret   -e AWS_DEFAULT_REGION=us-east-1 amazon/aws-cli   --endpoint-url http://objectstore:8333   s3api get-object --bucket servable --key demo.txt /tmp/out
```

### 6.4 ⭐ L'antivirus détecte — test EICAR par l'API

EICAR est une chaîne de test standard, inoffensive, que tous les antivirus
reconnaissent comme une menace. Elle rend le chemin « infecté » démontrable
sans manipuler de véritable logiciel malveillant.

Elle est **assemblée à l'exécution** : écrite en clair dans le dépôt, elle
ferait mettre le dépôt en quarantaine par l'antivirus de qui le clone.

```bash
# Fichier sain  →  doit répondre 200 et "OK"
printf 'bonjour' | curl -s -i --data-binary @- http://localhost:9000/scanHandlerBody

# EICAR  →  doit répondre 406 et le nom de la menace
printf '%s%s%s' 'X5O!P%@AP[4\PZX54(P^)7CC)7}' '$EICAR-STANDARD-ANTIVIRUS-TEST-FILE' '!$H+H*' \
  | curl -s -i --data-binary @- http://localhost:9000/scanHandlerBody
```

La seconde commande **doit** signaler une détection. Si elle répond `200`, la
base de signatures n'est pas chargée et rien de ce que promet le service ne
tient.

Résultat observé le 2026-09-26, conteneur sain en ~20 s :

```
sain   → HTTP 200   {OK   200}
EICAR  → HTTP 406   {FOUND Eicar-Test-Signature  406}
```

⚠️ Le corps annonce `application/json` mais n'en est pas : c'est le rendu par
défaut d'une structure Go. Le **code HTTP fait foi** ; le corps ne sert qu'au
nom de la menace et à distinguer `Heuristics.Limits.Exceeded`.

```bash
# Moteur et version des signatures — tracés dans chaque verdict
curl -s http://localhost:9000/version
# { "Clamav": "1.4.6", "Signature": "28098", "Signature_date": "Thu Aug 20 08:24:22 2026" }

# Les limites effectivement appliquées par le moteur
docker exec praxedo-antivirus \
  grep -E '^(AlertExceedsMax|StreamMaxLength|MaxFileSize|MaxScanSize) ' /clamav/etc/clamd.conf
# StreamMaxLength 2000M / MaxFileSize 2000M / MaxScanSize 1024M / AlertExceedsMax yes
```

### 6.5 Keycloak délivre un jeton au système tiers

```bash
curl -s -X POST -u praxedo-integration:praxedo-local-integration-secret-do-not-reuse \
  http://localhost:8081/realms/praxedo/protocol/openid-connect/token \
  -d grant_type=client_credentials
```

Le jeton retourné doit contenir `praxedo-files-api` dans son audience. Le
navigateur, lui, n'obtient jamais de jeton : `http://localhost:8080/api/v1/auth/login`
doit répondre `302` vers Keycloak (avec `code_challenge_method=S256`).

---

## 7. Ports utilisés

| Port | Service |
|---|---|
| 5173 | Interface React (hors compose) |
| 8080 | Service Spring Boot — l'API (profil compose `app`, ou `./mvnw spring-boot:run`) |
| 8091 | Service Spring Boot — actuator (santé, métriques) |
| 8081 | Keycloak |
| 8333 | API S3 |
| 9000 | **Antivirus — API HTTP** |
| 9001 | Keycloak — santé et métriques |
| 19333 | Console du stockage objet (port interne : 9333) |
| 5432 | PostgreSQL |
| 9090 | Prometheus |
| 3000 | Grafana |

Chacun, sauf le 5173 de l'interface, est surchargeable par variable
d'environnement (voir le `docker-compose.yml`).
