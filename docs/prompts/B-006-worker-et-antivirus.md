# B-006 — Le worker : antivirus par API, verdict, promotion vérifiée

- **Date** : 2026-09-27
- **Outil** : Claude Opus 5.5
- **Objectif** : analyser les fichiers déposés et rendre servables ceux qui sont sains
- **Phase du projet** : back-end, étape 3 de la suite du cœur

## Prompt

> Poursuis avec l'étape 3 du plan : le worker d'analyse et la promotion des
> fichiers sains. Respecte les protocoles de `backend/AGENTS.md`, et prouve le
> parcours complet avec le vrai ClamAV : un fichier sain devient disponible,
> EICAR est bloqué.

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| **Le verdict est lié aux octets analysés** : le worker hache le flux qu'il envoie à l'antivirus, et c'est lui — pas l'adaptateur — qui construit l'attestation | Un adaptateur ne peut pas attester un contenu qu'il n'a pas mesuré. Un stockage qui altérerait un objet serait attrapé ici, pas servi |
| **Une panne n'est jamais un verdict** : c'est une exception distincte (`ScannerUnavailableException`), pas une valeur du résultat | Aucun chemin de code ne peut confondre « rien n'a été conclu » avec une conclusion |
| **Le portillon de santé précède le claim** | Une panne de l'antivirus ne consomme aucune tentative : aucun fichier n'est abandonné à tort pendant l'incident |
| **Sémaphore explicite** devant l'appel antivirus | Avec les threads virtuels, la taille d'un pool n'est plus une limite |
| **Promotion par relecture**, écrite directement sous sa clé finale | Une copie côté stockage ne rend pas d'empreinte. Et le point de validation est la base, pas l'objet : un objet de la zone servable n'est jamais servi tant que sa ligne n'est pas `AVAILABLE`, ce qui rend la zone temporaire (`.tmp/…`) du plan inutile |
| **Arrêt propre** : les baux en cours sont rendus, la tentative aussi | Un nœud qui s'arrête proprement ne doit pas faire payer ses fichiers ; un crash, lui, compte la tentative (direction prudente) |
| **Un seul client HTTP JDK, forcé en HTTP/1.1** | Il envoie un corps de longueur connue en flux, sans tampon — vérifié : WireMock reçoit `Content-Length: 300000` et les octets exacts |

## Ce que j'ai corrigé en chemin

| Écart au plan | Correction |
|---|---|
| Le plan prévoyait une zone temporaire `servable/.tmp/<id>-<jeton>` puis un « renommage » | S3 n'a pas de renommage atomique, et la zone temporaire ne protégeait rien que la base ne protège déjà. Supprimée, raisonnement documenté dans `PromotionService` |
| Le port antivirus exposait `capabilities()` (taille maximale) | À 500 Mo des deux côtés, la vérification serait une assertion morte ; la version des signatures, elle, est lue et tracée à chaque verdict |
| `backend/AGENTS.md` décrivait un `AntivirusScanner` qui rend un `ScanVerdict` | Il rend un `ScanOutcome` sans empreinte : l'empreinte appartient au worker, qui l'a mesurée |

## Vérifications effectuées

| Point | Résultat |
|---|---|
| **Parcours complet, vrai antivirus** (image dérivée de `infra/antivirus/Dockerfile`) | Fichier sain → `AVAILABLE`, copie servable **identique à l'octet** ; EICAR → `INFECTED`, **aucun objet** dans la zone servable |
| Table de traduction contre une API simulée (WireMock) | `200`, `406` menace, `406` limite → non analysable, `406` illisible → infecté, `412`, `413` → panne, `500`, lenteur, connexion coupée, moteur absent, version sans signatures |
| Octets altérés dans le stockage | Le verdict sain n'est **jamais** enregistré |
| Worker dépossédé de son bail pendant l'analyse | N'écrit rien, ne promeut rien |
| Les cinq points d'interruption de la promotion | Aucun ne rend un fichier servable sans copie vérifiée ; tous laissent un état dont le service se relève seul |
| Chaîne EICAR | Assemblée par `String.join` : des littéraux concaténés seraient fusionnés par `javac` en **une seule constante** dans le `.class`, que l'antivirus du poste mettrait en quarantaine |
| Suite complète | **305 tests verts**, plus la preuve mémoire (500 Mo, heap 256 Mo) |
