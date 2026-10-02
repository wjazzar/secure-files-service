# B-005 — Stockage objet et dépôt en flux

- **Date** : 2026-09-27
- **Outil** : Claude Opus 5 (sous-agent, spike), puis Claude Opus 5.5 (reprise)
- **Objectif** : l'adaptateur de stockage, et `POST /api/v1/files` en flux
- **Phase du projet** : back-end, étape 2 de la suite du cœur

## Prompts

> Confie l'étape 2 — le stockage objet et le dépôt en flux — à un autre
> sous-agent, en parallèle.

Puis, après l'interruption des deux sous-agents par la limite de session :

> Reprends et termine les deux étapes, puis enchaîne sur les lots restants.
> Un agent ou toi-même : choisis l'option la plus sûre.

Choix fait : **tout reprendre dans la session principale**, en séquence. Deux
agents écrivant les mêmes fondations web dans deux worktrees auraient demandé
une fusion plus coûteuse que le gain de parallélisme.

## Le spike — ce qu'il a mesuré, et ce qu'il avait manqué

Le sous-agent a mené le spike avant d'être interrompu ; ses mesures étaient
perdues (affichées, pas consignées). Elles ont été **récupérées dans le rapport
XML de Surefire**, puis Q1 et Q2 **rejouées** avec un heap plafonné à 256 Mo.

| Question | Mesure |
|---|---|
| **Q1** — SDK AWS par défaut sur SeaweedFS 3.97 | 1 048 576 octets envoyés, **1 049 503 stockés, empreinte différente, `PUT` réussi**. La somme de contrôle finale de l'encodage aws-chunked est stockée *dans* l'objet |
| **Q2** — 500 Mo en flux, heap 256 Mo | `PUT` 2,6 s, heap **23 → 23 Mo** ; relecture 2,3 s, empreinte identique |
| **Q3** — droits réels de chaque identité | `delivery` refusée sur la quarantaine ; `ingest` écrit, ne lit pas, ne liste pas, **peut supprimer** |

### Ce que le spike avait manqué, et comment les tests l'ont trouvé

**1. Le corps lu deux fois.** Le spike recommandait `chunkedEncoding=false`.
Sur un endpoint HTTP, le SDK doit alors signer l'empreinte de **tout** le
contenu avant de l'envoyer : il lit le corps deux fois. Le générateur du spike
se recréait à chaque appel et l'a masqué. Un vrai dépôt sort d'une socket qu'on
ne relit pas. Le garde-fou `SingleUseContent` (un corps ne se lit qu'une fois,
sinon exception) l'a attrapé **au premier test**. Configuration retenue :
encodage *chunked* conservé, sommes de contrôle `WHEN_REQUIRED` — signature
chunk par chunk, **un seul passage**, octets intacts.

**2. Une identité de livraison incapable de livrer.** En écrivant les tests de
plages d'octets, `delivery` s'est vu refuser la lecture de la zone servable…
alors que c'est son seul droit. Mesures successives sur un conteneur jetable,
avec sept variantes d'identité :

- en **SeaweedFS 3.97**, **aucune** identité en lecture seule ne peut lire, pas
  même avec `Read` sans restriction ; seule une identité détenant **aussi
  `Write`** sur la zone lit ;
- en **SeaweedFS 4.47** (14/09/2026), les sept variantes lisent, et un objet
  absent répond enfin `404` au lieu de `403`.

Donner `Write:servable` à `delivery` aurait permis au chemin de téléchargement
d'**écraser un fichier validé** par du contenu non analysé. La correction
retenue est la mise à jour, épinglée par digest — pas l'affaiblissement des
droits. Le contournement « 403 = absent » écrit entre-temps a été **retiré**.

**La leçon** : la procédure de vérification de l'infrastructure
ne testait que le cas **négatif** (`delivery` refusée sur la quarantaine). Elle
aurait été verte avec un rôle de livraison qui ne livre rien. La contre-épreuve
positive est maintenant dans `infra/README.md` et dans `ObjectStorageTest`.

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| **Trois ports, un par identité** (`QuarantineWriter`, `WorkerStorage`, `ServableReader`) plutôt qu'un port générique injecté trois fois | La séparation est portée par le **typage** : `ServableReader` n'a aucune méthode qui puisse nommer la quarantaine |
| Les `S3Client` ne sont **pas** des beans | Aucun composant ne peut demander « un client S3 » et recevoir celui du worker |
| Écrire l'objet **avant** de commiter la ligne | Une panne laisse un orphelin invisible, balayé plus tard — jamais une référence brisée |
| Reniflage et écriture **sous la même garde** | Un client qui coupe dans les 512 premiers octets doit recevoir la même réponse que s'il coupait à 400 Mo. Défaut trouvé par les tests |
| `persist` explicite au lieu de `save` | Avec un `@Version` primitif et un identifiant assigné, `save` ne sait pas qu'une entité est neuve et fait un `merge` : un `SELECT` avant chaque dépôt |
| L'idempotence rejoue **le fichier**, pas un instantané de réponse (`V5`) | Un client qui relance doit voir l'état actuel de son fichier, pas une photo « en attente » |
| Le `Content-Type` de la requête n'est **pas** contraint | Il est ignoré par conception (règle B-5) : il n'y a rien à faire respecter |

## Ce que j'ai laissé, et pourquoi

Quand un client coupe sa connexion en plein corps, Tomcat marque l'échange en
erreur et **remplace le document d'erreur** par sa propre page. Le statut
`400` survit ; le code `CONTENT_LENGTH_MISMATCH` non. Le test vérifie ce que le
service garantit — `400`, **aucune ligne, aucun objet** — et documente le
reste : le client qui a provoqué la situation est parti.

## Vérifications effectuées

| Point | Résultat |
|---|---|
| **Preuve mémoire** — exécution Surefire dédiée, `-Xmx256m` | **500 Mo déposés en 3,5 s**, empreinte vérifiée. Tout tampon aurait provoqué un `OutOfMemoryError` |
| Octets stockés = octets envoyés (3 Mo, plusieurs chunks) | Vérifié sur SeaweedFS réel |
| `delivery` : lecture quarantaine refusée, écriture servable refusée, **lecture servable autorisée** | Vérifié, dans les deux sens |
| `ingest` : ne peut pas relire ce qu'il a écrit | Vérifié |
| Pas de `Content-Length`, 600 Mo annoncés, corps tronqué | `411`, `413` avant lecture, `400` sans rien conserver — sur socket brute |
| Huit réservations concurrentes de la même clé d'idempotence | Exactement une accordée |
| Suite complète | **272 tests verts**, plus la preuve mémoire |
