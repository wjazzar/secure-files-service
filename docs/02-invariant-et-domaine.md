# 02 — Invariant de sécurité et modèle de domaine

> **Analyse écrite avant le code** (série 00), **relue contre le code livré le
> 02/10**. L'automate proposé au départ (`CLEAN` servable, `SCAN_FAILED`,
> `DELETED`) a été corrigé lors de la confrontation
> ([`26`](26-reponse-a-la-contre-analyse.md)) : ce document décrit l'automate
> **livré** et signale chaque écart. Le domaine livré fait foi dans
> [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §4.
> Couvre : `EX-03`, `EX-04`.

---

## 1. L'invariant

Tout le système découle d'une seule règle, formulée en termes de **contenu**,
pas de « fichier » :

> **Au moment d'autoriser une réponse de téléchargement, l'objet doit être
> `AVAILABLE` et porter une attestation `CLEAN` liée à son SHA-256.**

### Pourquoi cette formulation précise

| Formulation | Faille |
|---|---|
| « un fichier n'est téléchargeable que s'il est scanné » | « scanné » ≠ « sain ». Un fichier scanné et infecté serait servi |
| « un fichier validé est téléchargeable » | Ne dit rien de l'entité non validée : par défaut, tout le reste doit être refusé |
| « les métadonnées portent le statut » | Le verdict porte sur **le contenu**, pas sur l'enregistrement. Si le contenu peut changer après validation, le verdict devient caduc → **d'où l'immutabilité des objets** et l'empreinte portée par le verdict |
| « le verdict est `CLEAN` » *(première version de ce document)* | Confond le verdict de l'antivirus et la disponibilité : « sain, mais pas encore copié dans la zone servable » devient inexprimable |

### Trois propriétés dérivées, non négociables

1. **Refus par défaut** — l'état par défaut de toute entité est « non
   servable ». Un état ajouté est non servable tant qu'on ne l'a pas
   explicitement déclaré servable.
2. **Immutabilité du contenu** — un objet stocké n'est jamais modifié après
   écriture. Sinon le verdict ne porte plus sur ce qui est servi (TOCTOU).
3. **Défense en profondeur** — l'invariant est protégé par **plusieurs
   mécanismes indépendants**, de natures différentes : un bug dans l'un ne
   suffit pas à le violer.

---

## 2. Les lignes de défense

**Trois couches indépendantes**, chacune capable à elle seule de bloquer un
fichier non analysé :

| Couche | Mécanisme livré | Ce qu'elle protège | Échoue si… |
|---|---|---|---|
| **Domaine** | Automate à états : un seul état répond oui à `isDownloadable()`, et `AVAILABLE` exige un verdict sain portant l'empreinte du contenu | Erreur de logique métier | Le code contourne l'automate |
| **Base de données** | Contraintes `CHECK` totales : une ligne `AVAILABLE` sans verdict sain, sans empreinte ou hors de la zone servable est refusée par PostgreSQL ; le rôle du service ne peut pas retirer ces contraintes | Écriture SQL fautive, quel que soit le code qui l'émet | Migration fautive |
| **Stockage** | L'identité qui sert les fichiers (`delivery`) n'a **aucun droit de lecture sur la quarantaine** | Un statut corrompu, une vérification applicative disparue | Mauvaise configuration des identités |

S'y ajoute la **revérification au moment de servir** : `FileDownloadService`
relit la ligne **pour ce propriétaire** à chaque requête, et ne pose qu'une
question, `isDownloadable()`.

La base et le stockage tiennent face à un bug applicatif : *« même si le code
est faux, l'invariant tient »*. Arbitrage `D-02`, acté : deux zones, trois
identités de stockage ([ADR-0002](adr/0002-un-processus-trois-identites.md)).

---

## 3. L'automate à états

```
dépôt finalisé (objet écrit en QUARANTAINE, puis ligne créée)
      ▼
AWAITING_SCAN ──claim(jeton, bail)──▶ SCANNING
      ▲                                  ├── menace ───────────▶ INFECTED
      │                                  ├── limite du moteur ─▶ UNSCANNABLE
      │                                  ├── panne ────────────▶ RETRY_WAIT, ou FAILED_FINAL si les tentatives sont épuisées
      └── arrêt propre (bail rendu)      └── sain ─────────────▶ PROMOTING
                                                                   ├── panne ─▶ RETRY_WAIT ou FAILED_FINAL
                                                                   │ copie + vérification + bascule
                                                                   ▼
                                                               AVAILABLE   ✅ seul état servable
```

Un fichier en `RETRY_WAIT` est repris par le claim à son échéance, comme un
fichier en `AWAITING_SCAN`. Un bail expiré (`SCANNING` ou `PROMOTING`) est
ramené en `RETRY_WAIT` par le *reaper* — ou en `FAILED_FINAL` si les
tentatives sont épuisées. Un arrêt propre rend le bail, depuis `SCANNING`
comme depuis `PROMOTING`, sans compter de tentative.

### Table des états

| État interne | Signification | Servable | Objet dans | Statut public |
|---|---|---|---|---|
| `AWAITING_SCAN` | Contenu reçu, en attente d'analyse | ❌ | quarantaine | `PENDING` |
| `SCANNING` | Un worker détient un bail et analyse | ❌ | quarantaine | `SCANNING` |
| `RETRY_WAIT` | Panne technique ; nouvel essai planifié | ❌ | quarantaine | `PENDING` |
| `PROMOTING` | Verdict sain ; copie vérifiée vers la zone servable en cours | ❌ | quarantaine | `SCANNING` |
| `AVAILABLE` | Sain, copié, vérifié | ✅ | zone servable | `AVAILABLE` |
| `INFECTED` | Menace détectée. Terminal | ❌ | quarantaine | `INFECTED` |
| `UNSCANNABLE` | Le moteur n'a pas pu analyser le contenu (limite atteinte, contenu illisible). Terminal | ❌ | quarantaine | `UNSCANNABLE` |
| `FAILED_FINAL` | Toutes les tentatives ont échoué. Terminal | ❌ | quarantaine | `FAILED` |

Huit états internes, projetés sur six statuts publics : le contrat n'expose
pas la mécanique des réessais ni celle de la promotion.

### Écarts avec l'automate proposé au départ

| Proposé | Livré | Pourquoi |
|---|---|---|
| `CLEAN` comme état servable | `CLEAN` est un **verdict** ; l'état servable est `AVAILABLE`, précédé de `PROMOTING` | `D-19` : sans cela, une panne pendant la copie laisse un fichier « sain en base, absent de la zone servable » |
| `SCAN_FAILED`, à la fois transitoire et définitif | `RETRY_WAIT` (transitoire) et `FAILED_FINAL` (terminal) | Sans cette séparation, le claim reprend indéfiniment des travaux terminaux |
| `DELETED`, suppression logique | **Aucune suppression de fichier** | Décision du 01/10 |
| Réanalyse depuis `CLEAN` ou `UNSCANNABLE` | **Aucune réanalyse** : les quatre états terminaux n'ont pas de transition sortante | Piste au README §10 (état `RESCANNING`) |

### Points de conception à noter

- **Une panne technique n'est pas un verdict.** Elle mène à `RETRY_WAIT`, puis
  à `FAILED_FINAL`, jamais à `INFECTED`. Tous sont non servables, mais leur
  traitement diffère.
- **`UNSCANNABLE` est un état à part entière.** Le masquer en « sain » viole
  l'invariant ; le masquer en `INFECTED` ment à l'utilisateur.
- **Chaque état se classe lui-même** : servable ou non, terminal ou non, bail
  détenu ou non sont des arguments du constructeur de l'énumération
  `FileStatus`. Ajouter un état ne compile pas tant qu'on n'a pas répondu à
  « peut-il être servi ? ».
- Aucune transition ne mène à `AVAILABLE` autrement que depuis `PROMOTING`,
  lui-même atteint seulement depuis `SCANNING` avec un verdict sain.

---

## 4. Modèle de domaine livré

```
StoredFile (agrégat, immuable : chaque transition rend une nouvelle instance)
├── id               : FileId (UUID)   — identifiant public, opaque ; c'est aussi la clé de stockage
├── owner            : OwnerId         — le « sub » de l'appelant
├── filename         : FileName        — donnée hostile, nettoyée, jamais une clé
├── contentType      : ContentType     — détecté par le service sur les premiers octets
├── sizeBytes        : long
├── sha256           : Sha256          — calculé pendant la réception
├── area             : StorageArea     — QUARANTINE | SERVABLE
├── status           : FileStatus      — l'automate
├── statusReason     : StatusReason?   — motif d'un état non nominal
├── verdict          : ScanVerdict?    — nul tant qu'aucune analyse n'a abouti
├── attempts         : int
├── lease            : Lease?          — jeton, détenteur, échéance
├── uploadedAt, statusChangedAt : Instant
└── version          : long            — incrémentée à chaque écriture ; sert d'ETag au suivi

ScanVerdict (objet valeur, immuable)
├── result           : CLEAN | INFECTED | UNSCANNABLE
├── threatName       : String?   — ex. « Eicar-Test-Signature »
├── engine           : String    — ex. « ClamAV »
├── engineVersion    : String?
├── signatureVersion : String?   — obligatoire pour un verdict sain
├── scannedContent   : Sha256    — l'empreinte des octets réellement analysés
├── scannedAt        : Instant
└── duration         : Duration
```

Le domaine ne porte **aucune annotation** de persistance ni de framework : le
mapping JPA vit dans une entité distincte
([ADR-0015](adr/0015-domaine-sans-annotation-de-persistance.md)).

### Écarts avec le modèle proposé au départ

| Proposé | Livré |
|---|---|
| `TenantId` | `OwnerId` : pas de `tenantId`, cloisonnement par propriétaire (`D-11`) |
| `DeclaredContentType` + `DetectedContentType` (Tika) | Un seul `contentType`, détecté par un renifleur interne ; le type déclaré par le client n'est ni consulté ni conservé ([`06`](06-securite-et-angles-morts.md) §4) |
| `ScanJob`, entité de traitement à part | **Pas d'entité séparée** : tentatives et bail sont des champs de `StoredFile`. La ligne du fichier est le travail ([ADR-0001](adr/0001-la-base-de-donnees-fait-file.md)) |
| `Result` incluant `FAILED` | Une panne n'est pas un résultat d'analyse : `ScanResult` n'a que trois valeurs |
| `Version` comme verrou optimiste entre workers | L'exclusion entre workers tient au **jeton de bail** ([`04`](04-idempotence.md) §3) |

### Justification des champs non évidents

| Champ | Pourquoi il existe |
|---|---|
| `sha256` | Détection de corruption, vérification de la promotion, comparaison côté client. Coût quasi nul : calculé **pendant** la réception |
| `scannedContent` | Fait du verdict une **attestation** : « *ce contenu* a été analysé et trouvé sain ». La promotion vérifie que l'empreinte de la copie est celle-là |
| `signatureVersion` | « Sain selon quelle base de signatures, et quand ? » est la première question posée après un incident |
| `area` | Rend explicite dans le domaine qu'un objet change d'emplacement à la promotion |
| `lease` | Permet au *reaper* de reprendre un travail dont le worker est mort, sans coordination distribuée |

---

## 5. Règles métier explicites

| ID | Règle | Tenue par |
|---|---|---|
| **R-01** | Un `StoredFile` naît en `AWAITING_SCAN`, dans la zone `QUARANTINE` | `StoredFile.received` |
| **R-02** | `PROMOTING` exige un verdict de résultat `CLEAN`, rendu pour l'empreinte de ce fichier | `StoredFile.scanned` |
| **R-03** | Seul un `StoredFile` en `AVAILABLE` peut exposer son contenu | `FileStatus.isDownloadable` |
| **R-04** | `AVAILABLE` exige un verdict sain dont l'empreinte est celle du contenu, et la zone `SERVABLE` ; seul `AVAILABLE` vit en zone servable | Constructeur de `StoredFile` ; contraintes `CHECK` |
| **R-05** | Le contenu est immuable après écriture | Clé UUID écrite une fois |
| **R-06** | Tout changement d'état est journalisé avec horodatage, acteur et états | Trigger de la base ([ADR-0009](adr/0009-journal-d-audit-par-trigger.md)) |
| **R-07** | Un fichier de plus de 500 Mo n'entre pas : `413` à la réception (`D-15`) | Admission du dépôt |
| **R-08** | Une transition invalide lève `IllegalTransitionException` — jamais ignorée en silence | `StoredFile` |

---

## 6. Test de l'invariant

L'invariant n'est crédible que s'il est **prouvé par des tests** :

1. **Pour chaque état**, `isDownloadable()` rend la valeur attendue
   (`FileStatusTest`) ; par l'API, un téléchargement dans chaque état non
   servable répond `409` (`DownloadApiTest`).
2. De bout en bout contre le **vrai** moteur, avec **EICAR** : `INFECTED`,
   téléchargement refusé (`ScanPipelineTest`).
3. Lecture directe de la quarantaine avec les identifiants de `delivery` :
   refusée par le stockage (`ObjectStorageTest`).
4. Concurrence : huit workers, huit fichiers, jamais le même ; un worker dont
   le bail a été repris n'écrit rien (`FilePersistenceTest`).
5. Transitions invalides : exception (`StoredFileTest`).
6. Contraintes SQL : une ligne `AVAILABLE` incomplète est refusée, y compris
   avec des `NULL` (`StoredFileConstraintsTest`).
