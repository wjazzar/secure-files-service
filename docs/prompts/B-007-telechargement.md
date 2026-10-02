# B-007 — Le téléchargement : liens signés, contenu direct, état revérifié

- **Date** : 2026-09-27
- **Outil** : Claude Opus 5.5
- **Objectif** : servir les fichiers disponibles — et seulement eux — aux navigateurs et aux clients d'API
- **Phase du projet** : back-end, étape 4 de la suite du cœur (lot B6)

## Prompt

> Étape suivante : le téléchargement (lot B6). Seuls les fichiers disponibles
> peuvent être servis, aux navigateurs comme aux clients d'API, et leur état
> doit être revérifié au moment de servir. La mémoire doit rester constante,
> même pour un fichier de 500 Mo.

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| **Liens signés HMAC, servis par le service**, pas d'URL présignée du stockage | Le contrat exige de revérifier l'état **à l'usage** du lien. Une URL présignée va droit au stockage, qui ignore tout des états : un lien émis avant un changement d'état resterait valable jusqu'à expiration. Elle publierait aussi l'adresse du stockage. Le coût — la bande passante passe par le service — est assumé et listé en piste d'amélioration |
| **Le lien lie fichier, propriétaire et échéance** ; comparaison de signature en temps constant | Un lien volé ne vaut que pour un fichier, une minute ; un lien signé pour autrui n'ouvre pas le fichier d'un autre (`404`, comme un fichier inconnu) |
| **La réponse est écrite à la main** sur le flux du servlet | Aucun convertisseur ne peut bufferiser le corps, ni traiter un `Range` sur un flux qu'il ne sait pas rembobiner. Tout ce qui peut échouer échoue **avant** le premier en-tête : chaque refus est un document de problème propre |
| **Toujours `attachment` + `application/octet-stream` + `nosniff`** | Un fichier sain pour l'antivirus peut être dangereux une fois *interprété* par un navigateur (HTML ou SVG porteur de script) : il n'est jamais interprété |
| **Une seule plage d'octets** ; plusieurs plages, plage suffixe ou en-tête malformé → fichier entier (`200`) | Plusieurs plages exigent un corps `multipart` assemblé en mémoire (règle B-1). RFC 9110 autorise à ignorer un `Range` |
| **`416` porte `Content-Range: bytes */<taille>`** | Un client qui reprend un téléchargement peut se corriger seul |
| **`AVAILABLE` sans objet servable = anomalie**, journalisée `INVARIANT ANOMALY`, répondue `503` | La promotion écrit l'objet avant la ligne : ce cas ne doit pas arriver. S'il arrive, on le crie, on ne le maquille pas en `404` |
| **Secret des liens uniquement par `DOWNLOAD_LINK_SECRET`** ; à défaut, secret aléatoire par processus **avec avertissement** | Règle B-5 (aucun secret en dur). Le repli est sans danger sur un poste, faux en production — et le journal le dit au démarrage |
| **`ETag` fort = SHA-256** sur `/content` | Le contenu d'un fichier servi ne change jamais : son empreinte est la meilleure étiquette possible |

## Vérifications effectuées

| Point | Résultat |
|---|---|
| Fichiers servis **réels** : déposés par l'API, promus par la vraie `PromotionService` (copie relue et comparée à l'empreinte) | Seul le verdict antivirus est écrit par le test |
| Refus `409` pour les sept états non servables (y compris `RETRY_WAIT` et `PROMOTING`, projetés en `PENDING`/`SCANNING`) | Code, `fileStatus`, et `Retry-After` **seulement** quand attendre sert à quelque chose |
| Jeton illisible, signature altérée, revendication forgée, lien expiré | `404 DOWNLOAD_LINK_INVALID`, sans dire lequel |
| ⭐ Lien **authentique** pour un fichier infecté | `409 FILE_INFECTED` : le lien n'est pas une permission, l'état est relu |
| Nom non ASCII (`Relevé d'été.pdf`) | Repli ASCII sûr + `filename*=UTF-8''…` exact (RFC 6266 / 8187) |
| **500 Mo promus puis téléchargés à travers un heap de 256 Mo** | Vert — promotion ~4,6 s, téléchargement ~5 s |
| Parcours de bout en bout avec le vrai ClamAV | Le fichier sain est désormais **téléchargé par l'API** et comparé à l'octet ; EICAR répond `409 FILE_INFECTED` sur le lien comme sur `/content` |
| Suite complète | **354 tests verts**, plus les deux preuves mémoire |

## Ce que j'ai rejeté

| Option | Pourquoi |
|---|---|
| `ResponseEntity<Resource>` / `InputStreamResource` | Le convertisseur de ressources gère lui-même `Range` en rembobinant le flux, ce qu'un flux réseau ne sait pas faire ; le contrôle des en-têtes serait partiel |
| URL présignée S3 | Voir ci-dessus : pas de revérification d'état à l'usage |
| Réponse `multipart/byteranges` | Assemblage en mémoire ; aucun client réel de ce service n'en a besoin |
