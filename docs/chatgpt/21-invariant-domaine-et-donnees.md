# 21 — Invariant, domaine et données : contre-analyse et modèle corrigé

> **Analyse : ChatGPT (Codex)** — 2026-09-21 — confrontation :
> [`../CONFRONTATION.md`](../CONFRONTATION.md)
>
> Ce document décrit des corrections recommandées. Il ne constitue ni un ADR,
> ni une décision du porteur du projet.

---

## 1. Verdict

Le principe « contenu hors base, vérité métier et file dans PostgreSQL » est
bon. En revanche, le DDL et les protocoles actuels ne prouvent pas encore
l'invariant revendiqué.

Les défauts bloquants sont :

1. le `CHECK` principal peut être contourné par `NULL` ;
2. `CLEAN` mélange un verdict et la disponibilité physique ;
3. la promotion n'est pas reprise après crash ;
4. `SCAN_FAILED` est à la fois une attente de retry et une DLQ, ce qui produit
   une boucle infinie ;
5. le lease ne possède pas de token de fencing propre à chaque claim ;
6. le verdict n'est pas structurellement lié au hash des octets servis ;
7. l'idempotence d'upload demande un hash qui n'existe pas encore au moment où
   elle prétend réserver la clé ;
8. rescan et suppression contredisent l'automate et les contraintes de zone.

La base doit être considérée comme une défense importante, mais elle ne peut
pas garantir seule l'identité d'octets stockés hors base.

---

## 2. Invariant reformulé

La formulation existante est forte mais ambiguë sur le moment d'autorisation
d'un flux long. Je recommande :

> **Au moment d'autoriser une réponse de téléchargement ou une nouvelle
> requête `Range`, l'objet immuable exact doit être dans l'état `AVAILABLE`,
> appartenir au principal autorisé et porter une attestation `CLEAN` liée à son
> SHA-256.**

Conséquences :

- la réponse déjà ouverte continue sur la version immuable qu'elle a autorisée ;
- un rescan ou une suppression empêche toute nouvelle réponse ;
- exiger une réévaluation « avant chaque octet » nécessiterait des leases de
  téléchargement et un drainage des connexions, disproportionnés ici ;
- `CLEAN` est un **résultat antivirus** ; `AVAILABLE` est un **état métier**.

---

## 3. Le `CHECK` SQL central est contournable par `NULL`

Le DDL proposé contient l'équivalent de :

```sql
CHECK (
    status <> 'CLEAN'
    OR (scan_result = 'CLEAN'
        AND scanned_at IS NOT NULL
        AND scan_signature_db_ver IS NOT NULL)
)
```

En PostgreSQL, une contrainte `CHECK` refuse seulement `FALSE`. Elle accepte
`TRUE` **et `UNKNOWN`**. Si `status='CLEAN'`, `scan_result=NULL` et les deux
dates/versions sont renseignées, l'expression vaut `UNKNOWN` et la ligne passe.

Correction minimale : rendre chaque prédicat total, par exemple :

```sql
CHECK (
    status <> 'AVAILABLE'
    OR (
        scan_result IS NOT DISTINCT FROM 'CLEAN'
        AND scanned_at IS NOT NULL
        AND scan_engine IS NOT NULL
        AND scan_engine_version IS NOT NULL
        AND scan_signature_db_version IS NOT NULL
        AND scanned_content_sha256 IS NOT NULL
    )
)
```

Cette propriété doit être testée avec le vrai PostgreSQL via Testcontainers,
en injectant explicitement toutes les combinaisons comportant `NULL`.

La phrase « aucune migration mal écrite ne peut violer l'invariant » doit être
retirée : une migration propriétaire de la table peut toujours modifier ou
supprimer une contrainte. Les rôles de migration et d'exécution doivent être
distincts.

---

## 4. Automate recommandé

### 4.1 États du premier périmètre

| État | Sens | Servable | Travail automatique |
|---|---|---:|---:|
| `AWAITING_SCAN` | objet complet en quarantaine, prêt à être pris | non | oui |
| `SCANNING` | claim actif avec lease et token | non | en cours |
| `RETRY_WAIT` | panne transitoire, backoff persistant | non | oui, à échéance |
| `PROMOTING` | verdict sain obtenu, promotion à terminer/reprendre | non | oui |
| `AVAILABLE` | destination vérifiée + attestation saine | **oui** | non |
| `INFECTED` | menace détectée | non | non |
| `UNSCANNABLE` | analyse complète impossible | non | non |
| `FAILED_FINAL` | échec technique non retryable ou budget épuisé | non | non |

`DELETING`, `DELETED`, `RESCAN_PENDING` et `RESCANNING` ne sont ajoutés que si
suppression et rescan entrent réellement dans le périmètre. Il vaut mieux ne
pas exposer ces endpoints au premier palier que les simuler avec des
transitions incohérentes.

### 4.2 Transitions

```text
upload finalisé
      │
      ▼
AWAITING_SCAN ──claim(token, lease)──▶ SCANNING
      ▲                                   │
      │                                   ├── menace ─────▶ INFECTED
      │                                   ├── limite ─────▶ UNSCANNABLE
      │                                   ├── erreur finale▶ FAILED_FINAL
      │                                   ├── panne ──────▶ RETRY_WAIT
      │                                   └── sain ───────▶ PROMOTING
      │                                                        │
      └──────────── backoff échu ◀── RETRY_WAIT                 │ copie temp
                                                               │ hash + rename
                                                               ▼
                                                          AVAILABLE
```

`PROMOTING` reste non servable. Son reaper reprend une copie interrompue ou
réconcilie une destination déjà créée avant un crash.

### 4.3 Pourquoi `CLEAN` ne doit pas être le statut final

Un verdict sain peut exister alors que :

- la copie vers la zone servable n'a pas commencé ;
- la copie est partielle ;
- le fichier final a été renommé mais la base n'a pas été finalisée ;
- le hash de la destination ne correspond pas au hash scanné.

Le téléchargement dépend de la disponibilité **et** du verdict. Un seul mot
ne doit pas masquer ces deux faits.

---

## 5. Protocole de claim et de lease

### 5.1 Claim

Le claim sélectionne uniquement les états réellement exécutables et produit
un token UUID unique par prise :

```sql
WITH candidate AS (
    SELECT id
      FROM stored_file
     WHERE status IN ('AWAITING_SCAN', 'RETRY_WAIT')
       AND next_attempt_at <= clock_timestamp()
       AND attempts < :max_attempts
     ORDER BY next_attempt_at, uploaded_at, id
     FOR UPDATE SKIP LOCKED
     LIMIT 1
)
UPDATE stored_file f
   SET status           = 'SCANNING',
       lease_token      = :claim_token,
       lease_holder     = :worker_instance,
       lease_expires_at = clock_timestamp() + :lease_duration,
       attempts         = attempts + 1,
       updated_at       = clock_timestamp(),
       version          = version + 1
  FROM candidate
 WHERE f.id = candidate.id
RETURNING f.*;
```

La garantie réaliste n'est pas « un seul scan exécuté » : après expiration
d'un lease, l'ancien worker et son remplaçant peuvent travailler en parallèle.
La garantie visée est :

> Exécution au moins une fois, effets idempotents, un seul résultat accepté.

### 5.2 Fencing

Chaque écriture de résultat doit vérifier le token du **claim**, pas seulement
un identifiant de worker réutilisable :

```sql
... WHERE id = :file_id
      AND status = 'SCANNING'
      AND lease_token = :claim_token
      AND lease_expires_at > clock_timestamp();
```

Pour les scans dont la durée peut approcher le timeout maximal, le worker
renouvelle son lease par un `UPDATE` conditionnel sur le même token. La durée
du lease doit, au minimum, être supérieure au deadline maximal de l'appel AV
plus une marge connue.

### 5.3 Contraintes de lease

La contrainte doit être bidirectionnelle :

```sql
CHECK (
  (status = 'SCANNING') =
  (lease_token IS NOT NULL
   AND lease_holder IS NOT NULL
   AND lease_expires_at IS NOT NULL)
)
```

Le modèle actuel autorise `SCANNING` sans lease, donc une ligne que le reaper
ne retrouvera jamais.

---

## 6. Retry, échec terminal et reaper

`SCAN_FAILED` ne peut pas signifier simultanément :

- « à reprendre automatiquement » ;
- « budget épuisé, équivalent DLQ ».

Le claim proposé reprend tous les `SCAN_FAILED`, sans prédicat sur le nombre
de tentatives. Une ligne déclarée terminale repart donc indéfiniment.

Correction :

- `RETRY_WAIT` pour une panne transitoire avec `next_attempt_at` ;
- `FAILED_FINAL` pour un contrat invalide, une erreur non retryable ou un
  budget épuisé ;
- backoff exponentiel **borné** avec jitter ;
- `max_attempts` issu d'une seule configuration ;
- reaper par petits lots, avec `SKIP LOCKED`, pour éviter une transaction
  massive ;
- métrique d'âge incluant `AWAITING_SCAN`, `RETRY_WAIT`, `SCANNING` et
  `PROMOTING`.

La file persistée reste la bonne politique de retry. Aucune bibliothèque de
retry en mémoire n'est nécessaire.

---

## 7. Lier le verdict aux octets servis

Le schéma actuel autorise plusieurs enregistrements à pointer vers la même
`object_key`, ne valide pas réellement le format du hash et ne conserve pas le
hash observé pendant le scan.

Mesures recommandées :

1. clé générée exclusivement par le serveur ;
2. unicité de la clé physique, ou utilisation directe de l'UUID du fichier ;
3. création sans écrasement (`CREATE_NEW`) ;
4. hash d'upload stocké dans un type contraint (`bytea` de 32 octets ou domaine
   hexadécimal contrôlé) ;
5. calcul du SHA-256 sur les octets effectivement envoyés à ClamAV ;
6. comparaison avec le hash d'upload avant d'accepter le verdict ;
7. nouveau calcul pendant la copie de promotion ;
8. comparaison taille + hash avant renommage final ;
9. API de livraison en lecture seule sur le volume servable.

Un `UNIQUE (object_key)` est le minimum. Une simple convention UUID dans le
code n'est pas une contrainte.

La défense doit être décrite honnêtement : PostgreSQL porte l'autorisation de
servir ; le hash et le stockage immuable prouvent quels octets cette
autorisation concerne.

---

## 8. Promotion idempotente

Pour un objet source `Q/<id>` et une destination finale `S/<id>` :

1. copier vers `S/.tmp/<id>-<claimToken>` avec création exclusive ;
2. calculer taille et hash pendant la copie ;
3. refuser si ceux-ci diffèrent de l'attestation du scan ;
4. fermer le flux et synchroniser le fichier si le provider le permet ;
5. renommer atomiquement le temporaire vers `S/<id>` dans le même volume ;
6. si `S/<id>` existe déjà, vérifier taille + hash, ne jamais l'écraser ;
7. finaliser en base `PROMOTING → AVAILABLE` par CAS sur le token ;
8. supprimer `Q/<id>` après le commit ; une absence est un succès ;
9. un réconciliateur supprime les temporaires anciens et reprend les lignes
   `PROMOTING` expirées.

Scénarios de panne obligatoires : avant copie, à mi-copie, après fermeture,
après renommage mais avant commit, après commit mais avant suppression source.

---

## 9. Contraintes SQL à rendre totales

La matrice suivante doit être codée par des contraintes sans logique
tri-valuée accidentelle :

| État | Zone | Verdict | Lease | `next_attempt_at` |
|---|---|---|---|---|
| `AWAITING_SCAN` | `QUARANTINE` | absent | absent | présent |
| `SCANNING` | `QUARANTINE` | absent | complet | absent |
| `RETRY_WAIT` | `QUARANTINE` | absent | absent | présent |
| `PROMOTING` | `QUARANTINE` | `CLEAN` complet | token de promotion | selon protocole |
| `AVAILABLE` | `SERVABLE` | `CLEAN` complet | absent | absent |
| `INFECTED` | `QUARANTINE` | `INFECTED` complet | absent | absent |
| `UNSCANNABLE` | `QUARANTINE` | motif complet | absent | absent |
| `FAILED_FINAL` | `QUARANTINE` | pas de verdict AV | absent | absent |

Autres contraintes :

- `attempts >= 0` ;
- `size_bytes >= 0` ;
- `scan_duration_ms >= 0` lorsqu'elle existe ;
- version moteur et version de signatures distinctes ;
- `status_changed_at` séparé de `uploaded_at` pour la rétention ;
- index de file exactement aligné sur les états du claim ;
- aucune valeur de résultat ou d'état en `text` libre.

Les requêtes critiques doivent être écrites explicitement avec Spring JDBC ou
`JdbcClient`, plutôt que confiées à des transitions implicites de JPA. La
documentation Spring expose `JdbcClient` depuis Spring Framework 6.1 :
[référence JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html).

---

## 10. Rôles PostgreSQL et vues de sécurité

La défense en profondeur peut être renforcée sans nouveau composant :

- rôle `ingest_app` : insertion des métadonnées initiales, aucune écriture de
  verdict ;
- rôle `worker_app` : claim, verdict et promotion ;
- rôle `delivery_app` : lecture des métadonnées et audit de téléchargement,
  aucune transition métier ;
- rôle `migration` séparé, seul propriétaire des tables.

Le chemin de contenu peut interroger une vue dédiée ne retournant la clé que
pour les objets disponibles :

```sql
CREATE VIEW downloadable_file AS
SELECT id, tenant_id, object_key, size_bytes, detected_content_type,
       content_sha256
  FROM stored_file
 WHERE status = 'AVAILABLE'
   AND storage_zone = 'SERVABLE'
   AND scan_result IS NOT DISTINCT FROM 'CLEAN';
```

Le rôle de livraison reçoit `SELECT` sur cette vue, pas sur les colonnes de
localisation de la table complète. Le statut général peut passer par une autre
vue qui n'expose jamais `object_key`.

Dans le code, le repository de téléchargement doit retourner un type
`DownloadableFile`, pas un `StoredFile` général suivi d'un `if`.

---

## 11. Idempotence d'upload

### 11.1 Contradiction actuelle

Le fingerprint proposé contient le SHA-256 du corps, mais l'algorithme insère
une ligne `IN_PROGRESS` **avant** d'avoir lu ce corps. Le DDL exige pourtant un
fingerprint non nul. Sur un rejeu terminé, renvoyer immédiatement le snapshot
empêche également de vérifier que le nouveau corps est identique.

Un `IN_PROGRESS` abandonné n'a ni lease, ni protocole de reprise.

### 11.2 Solution minimale et exacte

Pour le premier périmètre, arbitrer après streaming :

1. chaque tentative écrit vers un candidat physique unique en calculant taille
   et hash ;
2. à la fin, une transaction courte tente d'insérer la ressource et le snapshot
   d'idempotence `COMPLETED` ;
3. la clé primaire `(principal, idempotency_key)` désigne le gagnant ;
4. le perdant compare son fingerprint complet au snapshot ;
5. fingerprint égal : candidat supprimé, réponse d'origine renvoyée ;
6. fingerprint différent : candidat supprimé, `422` ;
7. rollback ou crash avant transaction : simple orphelin nettoyable.

Cette solution accepte que deux appels concurrents transmettent tous leurs
octets, mais garantit un seul effet métier. C'est cohérent et démontrable.

### 11.3 Optimisation pour les systèmes tiers

Un appelant automatisé peut fournir `Idempotency-Key`, `Content-Length` et le
champ standard `Content-Digest` défini par la
[RFC 9530](https://datatracker.ietf.org/doc/html/rfc9530). Le serveur réserve
alors la clé avec un lease et vérifie le digest pendant le flux.

Une réservation ne doit être utilisée que si :

- le digest est obligatoire avec la clé ;
- le fingerprint des en-têtes est canonique et non ambigu ;
- la réservation expire et peut être reprise ;
- le digest déclaré est vérifié sur le premier upload ;
- une clé terminée avec un digest différent produit immédiatement `422`.

### 11.4 Déduplication de verdict

Je recommande de la retirer du premier périmètre :

- elle n'est pas demandée ;
- l'index proposé n'empêche pas deux scans concurrents ;
- elle crée aussi un canal temporel inter-tenant ;
- une signature ou un faux positif peut être corrigé, donc « un verdict
  infecté est valable à vie » est trop absolu ;
- la validité dépend de la politique complète du scanner, pas seulement de la
  version de signatures.

Le SHA-256 reste indispensable pour l'intégrité, sans devenir une optimisation
de cache.

---

## 12. Rescan et suppression

Le modèle actuel permet `CLEAN → INFECTED` ou `DELETED`, tandis que la
contrainte exige qu'un objet en zone servable reste `CLEAN`. Ces transitions
ne peuvent donc pas être exécutées honnêtement.

Recommandation de périmètre : ne pas exposer `rescan` et `DELETE` au premier
palier.

S'ils sont ajoutés :

- rescan : décider si l'ancien verdict reste utilisable jusqu'au nouveau ou si
  la révocation est immédiate ; modéliser `RESCAN_PENDING/RESCANNING` ;
- suppression : `DELETING` non servable, purge physique idempotente, puis
  `DELETED` ;
- ajouter `deleted_at` et `purge_due_at` ; ne jamais calculer la rétention à
  partir de `uploaded_at` ;
- si l'historique des rescans devient métier, utiliser une table
  `scan_attempt`, pas seulement un événement JSON.

---

## 13. Contrat HTTP recommandé

### Upload

Une ressource de fichier existe dès que contenu et métadonnées sont durables,
même si elle n'est pas encore téléchargeable. La
[RFC 9110](https://datatracker.ietf.org/doc/html/rfc9110#section-15.3.2)
décrit `201 Created` pour ce cas. Je recommande :

```http
HTTP/1.1 201 Created
Location: /api/v1/files/{id}
Retry-After: 2
```

avec `status=AWAITING_SCAN` et `downloadable=false` dans le corps.

`202 Accepted` reste défendable si le serveur n'a créé qu'une opération et que
la ressource finale n'existe pas encore. Ce n'est pas le modèle décrit ici.

Un seul format `application/octet-stream` suffit au palier 1. React peut
envoyer un objet `File` brut avec `XMLHttpRequest` et conserver la progression.
Le multipart streaming peut être ajouté si un besoin client réel l'impose.

### Téléchargement

| Cas | Réponse recommandée |
|---|---|
| autre tenant ou inconnu | `404` |
| `AVAILABLE` | `200` ou `206`, flux avec headers de sécurité |
| attente, scan, retry ou promotion | `409` + code métier + `Retry-After` |
| infecté, non scannable, échec final | `409` + code métier non retryable |
| service réellement indisponible | `503` |

`403` doit rester un refus d'autorisation, pas devenir un synonyme de verdict
infecté. `503` décrit la disponibilité actuelle du service, pas l'historique
d'un fichier dont le scan a échoué. Les codes machine de la RFC 9457 portent
la distinction métier.

Pour le premier palier, servir systématiquement
`application/octet-stream`, `Content-Disposition: attachment` et `nosniff` est
plus sûr et plus simple que d'ajouter Tika. La détection précise peut être
introduite lorsqu'un cas d'usage en dépend.

---

## 14. Preuves attendues avant de revendiquer l'invariant

1. tentative d'insertion SQL `AVAILABLE` avec chaque champ de verdict à
   `NULL` ; toutes échouent ;
2. ajout d'un nouvel état : le test exhaustif de téléchargeabilité échoue ;
3. EICAR réel : jamais disponible ;
4. archive dépassant taille, profondeur ou nombre de fichiers :
   `UNSCANNABLE`, jamais sain ;
5. contenu de quarantaine impossible à ouvrir depuis `delivery` ;
6. mutation/corruption du fichier entre upload et scan : hash refusé ;
7. mort du worker à chaque étape de promotion : convergence sans fichier
   partiel servi ;
8. token de lease expiré : ancien worker incapable d'écrire ou de promouvoir ;
9. plusieurs workers : plusieurs exécutions possibles, un seul effet accepté ;
10. antivirus arrêté : anciens fichiers disponibles, nouveaux bornés par la
    politique d'admission ;
11. fichier volumineux : heap borné et aucune copie temporaire implicite ;
12. rôle `delivery_app` incapable de modifier un verdict ou d'interroger une
    clé de quarantaine.

---

## 15. Conclusion

Le modèle initial présente des intentions solides, mais appelle « invariants »
plusieurs conventions encore contournables. La correction n'exige aucun
nouveau composant d'infrastructure. Elle exige surtout :

- des états plus précis ;
- des contraintes SQL totales ;
- un token de fencing ;
- une promotion idempotente ;
- un hash vérifié à chaque frontière ;
- des privilèges applicatifs séparés ;
- des tests de panne qui exécutent réellement les interstices du protocole.

Après ces corrections, PostgreSQL reste une excellente file pour le périmètre
de l'exercice.
