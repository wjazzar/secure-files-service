# 06 — Sécurité et angles morts

> **Analyse écrite avant le code** (série 00), **relue contre le code livré le
> 02/10**. Chaque section garde le raisonnement et dit ce qui est **livré**, ce
> qui a été **écarté** et pourquoi. La description du système livré fait foi
> dans [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §13 ; les limites
> assumées sont au [`README`](../README.md) §10.

Ce document rassemble les sujets qu'une implémentation naïve ignore. Les
traiter — **ou les documenter explicitement comme non traités** — montre qu'on
a réfléchi au-delà du chemin nominal.

---

## 1. ⚠️ La limite de taille de l'antivirus (l'angle mort principal)

L'énoncé demande des « fichiers de tailles très variables » **et** une analyse
antivirus. Ces deux exigences sont **en tension directe**.

ClamAV impose plusieurs plafonds. Valeurs par défaut de la version 1.4, lues
dans le `clamd.conf.sample` amont :

| Paramètre | Défaut (1.4) | Effet au dépassement |
|---|---|---|
| `StreamMaxLength` | 100 Mo | La connexion est coupée — pas de verdict |
| `MaxFileSize` | 100 Mo | Le fichier (ou l'entrée d'archive) n'est pas analysé au-delà |
| `MaxScanSize` | 400 Mo | Volume cumulé analysé, décompression comprise |
| `MaxRecursion` | 17 | Profondeur d'archives imbriquées |
| `MaxFiles` | 10 000 | Nombre de fichiers dans une archive |
| `AlertExceedsMax` | **non** | Sans lui, une limite atteinte remonte comme une analyse **propre** |

**Un fichier de plusieurs centaines de Mo n'est pas analysable par un moteur
laissé à sa configuration par défaut** — et, plus grave, il peut être déclaré
sain sans avoir été lu en entier.

### Les fausses solutions
- **Découper en morceaux et analyser chaque morceau** : une signature peut
  chevaucher deux morceaux, et la détection d'archive est détruite. **Faux
  sentiment de sécurité** — pire que ne rien faire. Interdit (règle `B-9`).
- **Relever les plafonds à l'infini** : la mémoire du moteur n'est pas
  infinie ; on déplace le problème vers une panne de l'antivirus.

### Ce qui est livré
1. **500 Mo, pour l'admission comme pour l'analyse** (cadrage du 21/09,
   [`28`](28-precisions-de-cadrage.md) §1) : au-delà, `413 FILE_TOO_LARGE` dès
   la lecture de `Content-Length`. Le seuil est un paramètre
   (`praxedo.upload.max-size-bytes`), pas une constante. L'option « accepter
   puis classer `UNSCANNABLE` » envisagée pour `D-15` n'a plus lieu d'être : le
   service n'accepte rien qu'il ne puisse faire analyser en entier.
2. **Plafonds du moteur relevés et versionnés** dans `docker-compose.yml` :
   `MAX_SCAN_SIZE` 1024M, `MAX_FILE_SIZE` 2000M (qui règle aussi
   `StreamMaxLength`), `MAX_RECURSION` 16, `MAX_FILES` 10000.
3. **`AlertExceedsMax yes`**, forcé par une image dérivée
   ([`infra/antivirus/Dockerfile`](../infra/antivirus/Dockerfile)) dont la
   construction échoue si la directive n'est plus trouvée.
4. **`MaxFileSize` strictement supérieur à `MaxScanSize`** — découvert en le
   **mesurant** ([ADR-0005](adr/0005-ordre-des-limites-de-l-antivirus.md)) :
   dans l'autre sens, une grosse entrée d'archive est tronquée sans alerte et
   l'archive déclarée saine. L'image refuse de démarrer si la relation est
   violée.
5. **`UNSCANNABLE` pour ce que l'analyse découvre** : réponse
   `406 Heuristics.Limits.Exceeded…` (limite atteinte) ou `412` (contenu que
   le moteur ne sait pas lire). Conservé, jamais servi, motif visible.
6. Le port `AntivirusScanner` n'a **pas** de `capabilities()` : prévue au
   départ, la méthode a été retirée, la limite étant une donnée de
   configuration partagée par le service et l'image
   ([`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md) §5.2).

### Ce qui reste un angle mort
- **Entrée zip64** : une entrée compressée dont l'en-tête local est au format
  zip64 n'est pas analysée par ClamAV 1.4.6 (EICAR y passe). Caractérisé par un
  test, documenté en risque résiduel (README §10).
- **Archive chiffrée** : voir §2.

---

## 2. Ce que l'antivirus ne protège pas

Un fichier au verdict `CLEAN` n'est **pas** un fichier inoffensif : un
antivirus est **une** mesure, pas **la** mesure.

| Menace | Pourquoi l'antivirus ne la voit pas | Ce que fait le service |
|---|---|---|
| **HTML/SVG malveillant** | Ce n'est pas un virus : c'est du contenu légitime, exécuté par le navigateur de la victime | Tout est servi en `application/octet-stream`, `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`. `Content-Security-Policy: sandbox` n'est **pas** envoyé (audit S-18, en attente de décision) ; pas de domaine de service distinct |
| **Bombe de décompression** | Fichier minuscule, décompression colossale | `MaxScanSize`, `MaxRecursion`, `MaxFiles` avec `AlertExceedsMax` : un dépassement donne `UNSCANNABLE`, jamais `AVAILABLE` |
| **Archive chiffrée** | Contenu illisible par le moteur | ⚠️ **Non traité**, mesuré le 02/10 : `AlertEncryptedArchive` n'est pas activé dans l'image, le moteur répond donc « sain » (`200`) pour une archive qu'il n'a pas pu ouvrir, et le fichier devient disponible. Le motif `ENCRYPTED_ARCHIVE` existe au contrat et dans le domaine, mais aucune réponse du moteur ne le produit aujourd'hui. Limite connue, avec sa piste (README §10) |
| **Malware sans signature connue** | Aucune signature n'existe encore | Aucune réanalyse en v1 : piste au README §10 (état `RESCANNING`, réanalyse périodique). Le verdict garde la version des signatures et sa date |
| **Macro Office ciblée** | Détection partielle selon le moteur | Limite du moteur, hors périmètre (README §9) |
| **Fichier légitime mais confidentiel** | Hors périmètre d'un antivirus | Cloisonnement par propriétaire : un fichier d'autrui répond `404` |

---

## 3. Le nom de fichier est une donnée hostile

Le nom arrive dans l'en-tête `X-File-Name` (UTF-8 encodé en pourcents) ; il est
nettoyé par `FileName.sanitised` avant d'être enregistré.

| Attaque | Exemple | Protection livrée |
|---|---|---|
| Traversée de chemin | `../../etc/passwd` | La clé de stockage est un **UUID généré**, jamais dérivée du nom ; seul le dernier segment du nom est gardé (`passwd`) |
| Injection d'en-tête | nom contenant `\r\n` | Caractères de contrôle retirés à la réception ; `Content-Disposition` écrit selon RFC 6266 / 8187 (repli ASCII + nom UTF-8 encodé) |
| Double extension | `facture.pdf.exe` | L'extension ne décide de rien : le contenu est toujours servi en pièce jointe, en `application/octet-stream` |
| Caractères bidirectionnels (RTL override) | `facture` + U+202E + `fdp.exe`, affiché `factureexe.pdf` | Normalisation NFC, retrait des caractères de format et des marques bidirectionnelles |
| Longueur excessive | 10 000 caractères | En-tête borné à 1 024 caractères par le contrat ; nom tronqué à 255 en gardant l'extension |
| Nom vide après nettoyage | `   ` | `400 INVALID_FILE_NAME` |

Le nom d'origine est **conservé en métadonnée** (pour le restituer à
l'utilisateur) mais n'a **aucun rôle fonctionnel**.

---

## 4. Le `Content-Type` déclaré n'est pas fiable

- Le type déclaré par le client **n'est jamais consulté ni conservé** (règle
  `B-5`).
- Le type enregistré est **détecté par le service** sur les 512 premiers
  octets du flux, par un renifleur interne d'une quarantaine de lignes
  (`ContentSniffer` : PDF, PNG, JPEG, GIF, ZIP, gzip, exécutables, RTF, XML,
  texte ; sinon `application/octet-stream`). Compatible avec le flux, sans
  tampon intégral.
- **Apache Tika, envisagé au départ, n'a pas été retenu** : ce type n'est
  qu'une donnée **descriptive** (une icône, un champ de métadonnée). Il ne
  sert jamais à servir le contenu ; une détection imparfaite n'a donc aucune
  conséquence de sécurité, et une dépendance de cette taille ne se justifiait
  pas.
- Le contenu est **toujours** servi en `application/octet-stream`, avec
  `X-Content-Type-Options: nosniff` pour empêcher le navigateur de re-deviner.

---

## 5. Fuite d'information par les réponses — `D-09`

Que renvoyer sur `GET /files/{id}/content` quand le fichier est `INFECTED` ?

| Option | Avantage | Inconvénient |
|---|---|---|
| Refus explicite à tous | Utilisateur informé | Confirme l'existence de l'identifiant ; révèle le résultat de l'analyse |
| `404` opaque pour tous | Aucune information divulguée | Le propriétaire légitime ne comprend pas ce qui se passe |
| **Mixte** *(retenu)* | Refus explicite **au propriétaire**, `404` à tout autre appelant | Une règle de plus à tester |

**Livré** : le propriétaire reçoit `409 FILE_INFECTED` (ou `FILE_UNSCANNABLE`,
`FILE_SCAN_FAILED`, `FILE_NOT_READY`) — un `409`, parce que c'est l'état de la
ressource qui interdit l'opération, pas un défaut de droit. Tout autre
appelant reçoit `404 FILE_NOT_FOUND`, comme pour un fichier inconnu.

Règle générale, appliquée partout : **`404` pour un identifiant qui ne
m'appartient pas** (jamais `403`), afin de ne pas faire de l'API un oracle
d'existence. Le seul `403` du contrat est celui du jeton CSRF.

---

## 6. Confidentialité de l'antivirus lui-même

> Déléguer l'analyse à un service antivirus **tiers** revient à **transmettre
> les fichiers des clients à ce tiers**.

- Sur VirusTotal, tout fichier soumis devient consultable par les abonnés
  premium. Pour des « documents, rapports, exports » clients, c'est une fuite
  de données caractérisée.
- Certains services commerciaux conservent les échantillons.

**Retenu et livré** : un ClamAV auto-hébergé, dans un conteneur du même réseau
privé, consommé par son API HTTP
([ADR-0004](adr/0004-antivirus-par-api-http.md)). Aucun contenu ne sort ; seules
les signatures sont téléchargées. Cette API n'est ni chiffrée ni
authentifiée : l'antivirus est tenu pour un composant de confiance sur un
réseau privé, son durcissement est hors périmètre (README §9).

Piste non traitée : n'envoyer que l'**empreinte** à un service de réputation,
et le contenu au seul moteur local.

---

## 7. Authentification et autorisation — `D-10`, `D-11`

L'énoncé n'en dit rien, alors que « utilisateurs » et « systèmes tiers »
impliquent deux modes d'authentification.

| Option | Effort | Apport |
|---|---|---|
| **OAuth2 / OpenID Connect avec Keycloak** *(retenu)* | Élevé | Fort ; le cadrage ne l'exigeait pas, le porteur du projet l'a voulue |
| JWT validé avec une clé de test, sans fournisseur d'identité *(recommandation initiale)* | Faible | Montre la prise en compte sans monter une infrastructure d'identité |
| Clé d'API pour les systèmes tiers, JWT pour les utilisateurs | Moyen | Réaliste vis-à-vis de `EX-02` |
| Aucune authentification | Nul | **Affaiblit** un service dont la sécurité est le sujet |

**Livré** : l'authentification est **toujours exigée**, sans mode anonyme ni
réglage pour la couper ([ADR-0014](adr/0014-authentification-toujours-exigee.md)).
Le navigateur n'a qu'un cookie de session `HttpOnly`, le service étant le
client confidentiel de Keycloak
([ADR-0012](adr/0012-session-navigateur-client-confidentiel.md)) ; un système
tiers présente un jeton `Bearer`, sans session. Pas de rôle, pas de
`tenantId` : chaque fichier appartient au `sub` de l'appelant.

**Le contrôle d'appartenance est systématique** : chaque lecture filtre sur le
propriétaire. Une API où tout identifiant connu serait téléchargeable par
quiconque échouerait sur l'esprit de l'exercice, même avec un antivirus
parfait.

---

## 8. Les URL présignées sont des porteurs d'autorisation

Envisagées pour `D-05`, **écartées** : le contenu est servi par le service,
sous l'identité de l'appelant, l'état du fichier étant relu juste avant
l'ouverture ([ADR-0013](adr/0013-telechargement-par-l-identite-de-l-appelant.md)).
Les raisons, qui restent valables si la piste était reprise (README §10) :

- Une URL présignée est **transférable** : quiconque la possède télécharge.
- Elle ne **revérifie rien** : émise avant un changement d'état, elle reste
  valide jusqu'à son expiration.
- Elle ne doit jamais être journalisée en clair (elle contient la signature).
- La décision d'autorisation a lieu à l'**émission** : c'est là qu'il faudrait
  auditer.

Le lien signé par le service (HMAC), livré un temps, a été retiré le 29/09 pour
la même raison de fond : avec la session par cookie, il doublait
l'authentification par un second secret.

---

## 9. Autres mesures transverses

| Sujet | État dans le livrable |
|---|---|
| Transport | Le service ne porte pas TLS : hypothèse d'une passerelle ou d'un reverse proxy devant lui (README §9). Le cookie de session est `Secure` et préfixé `__Host-`, sauf en HTTP local |
| Chiffrement au repos | Non traité : aucune configuration de chiffrement du stockage objet dans l'exercice |
| Limitation de débit | **Non livrée**, limitation acceptée (README §10) : à porter par la passerelle |
| Quotas | Pas de quota par utilisateur (README §10). Deux bornes globales : 500 fichiers en attente, 50 dépôts simultanés par nœud (`429`) |
| Taille de requête | Plafond vérifié **pendant** le flux : un `Content-Length` mensonger donne `400 CONTENT_LENGTH_MISMATCH`, l'objet est supprimé |
| Dépôt au compte-gouttes | Échéance sur le corps : 60 s + 8 s par Mio annoncé, puis `408 UPLOAD_TOO_SLOW` |
| Audit | `file_audit_event`, en ajout seul, écrit par un trigger à chaque changement d'état ([ADR-0009](adr/0009-journal-d-audit-par-trigger.md)) ; le service y ajoute `DOWNLOAD_SERVED` avant le premier octet servi |
| Dépendances | Analyse ponctuelle du 01/10, Trivy et `npm audit` ([`33`](33-analyse-des-dependances.md)) ; pas d'intégration continue |
| Secrets | Variables d'environnement, aucune valeur par défaut, jamais journalisés |
| Droits de la base | Un rôle par usage : le service ne peut ni modifier le schéma ni réécrire le journal |
| En-têtes de réponse du contenu | `nosniff`, `Content-Disposition: attachment`, `Cache-Control: private, no-store` |
| Erreurs | `application/problem+json` (RFC 9457), sans trace d'exception ni détail interne |

L'audit du code livré, constat par constat, est dans
[`32-audit-de-securite.md`](32-audit-de-securite.md).

---

## 10. La réanalyse (dimension temporelle de la sécurité)

> Un fichier sain en janvier peut être reconnu infecté en mars.

Un verdict est **daté** et lié à une version de la base de signatures. Sans
politique de réanalyse, la garantie de l'énoncé se dégrade avec le temps.

Niveaux possibles, par coût croissant :

1. **Tracer** : exposer la date et la version des signatures du verdict.
2. **À la demande** : un point d'entrée pour relancer l'analyse d'un fichier.
3. **Planifié** : réanalyse des fichiers dont le verdict dépasse N jours.
4. **Complet** : réanalyse déclenchée par la mise à jour des signatures.

**Livré : le niveau 1 seulement.** Le détail d'un fichier expose `scannedAt`,
`engineVersion` et `signatureVersion`. Il n'existe **aucun** point d'entrée de
réanalyse, ni manuel ni planifié : c'est une limite connue, avec son
déclencheur, au README §10.
