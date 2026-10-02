# 01 — Analyse de l'énoncé : qu'est-ce qui est réellement évalué ?

> **Lecture de l'énoncé faite avant le code** (série 00), **relue le 02/10**.
> C'est une lecture, pas une description du système : la dernière colonne du §1
> et les tableaux des §4 et §5 disent ce qu'elle est devenue. Les précisions
> obtenues le 21/09 sont dans [`28`](28-precisions-de-cadrage.md).

L'énoncé est court. Chaque phrase y est cependant un test déguisé. Cette lecture
détermine où investir l'effort.

---

## 1. Lecture ligne à ligne

| Phrase de l'énoncé | Lecture naïve | Ce qui est réellement testé | Ce qui a été livré |
|---|---|---|---|
| « Garantir qu'**aucun** fichier n'est servi sans avoir été scanné » | Ajouter un champ `scanned` et un `if` avant le téléchargement | Capacité à identifier **un invariant de sécurité** et à le rendre **structurellement** impossible à violer — défense en profondeur, pas garde applicative unique | Automate, contraintes `CHECK` de la base, et trois identités de stockage : celle qui sert les fichiers ne peut pas lire la quarantaine |
| « nombreux utilisateurs simultanés » | « Spring Boot tient la charge » | Aucun état dans un nœud, montée en charge horizontale, threads non bloqués par les entrées-sorties longues, contre-pression explicite, découplage du dépôt et de l'analyse | Analyse asynchrone, threads virtuels, sessions en base, `429` avant la lecture du corps, capacité mesurée |
| « fichiers de tailles **très variables** » | Augmenter `max-file-size` | **Flux de bout en bout**, jamais de tampon intégral, et surtout : **connaître les limites de taille de l'antivirus** | 500 Mo au plus (cadrage du 21/09), en une requête et en flux, mémoire constante mesurée ; limites du moteur mesurées (ADR-0005) |
| « déléguer leur analyse à un antivirus **via une API** » | Appeler ClamAV | Gestion d'une **dépendance externe faillible** : délais, réessais avec aléa, coupe-circuit, limite de concurrence, échec définitif visible, mode dégradé fermé par défaut | ClamAV derrière une API HTTP ; ces fonctions sont portées par la file en base, sans bibliothèque de résilience |
| « interface **programmatique** » | Exposer du REST | Contrat propre, versionné, OpenAPI, codes de statut justes, erreurs normalisées, idempotence pour les appelants automatisés | `contracts/openapi.yaml`, erreurs RFC 9457, `Idempotency-Key`, conformité vérifiée par un test |
| « utilisateurs **ou systèmes tiers** » | — | Deux profils d'appelants : un navigateur (interactif, tolère le polling) et un système (traitement par lots, a besoin d'idempotence) | Session par cookie pour le navigateur, jeton `Bearer` pour un système tiers ; suivi par polling (`ETag`, `Retry-After`), pas de webhook |
| « échange technique avec un **EM** et un **Architecte** » | Faire beau | **Deux audiences distinctes** : l'architecte veut les arbitrages et les contreparties ; l'EM veut la démarche, la testabilité, la maintenabilité, la capacité à cadrer | README, ADR, registre des décisions |
| « stockez les prompts utilisés » | Coller l'historique | Évaluation de la **méthode de travail avec l'IA** : sait-on cadrer, challenger, vérifier — ou déléguer aveuglément ? | [`prompts/`](prompts/README.md) : prompt, retenu, rejeté, vérifié |
| « il n'y a pas de bonne réponse unique » | — | **La justification prime sur le choix.** Un choix simple bien défendu bat un choix sophistiqué subi | Chaque décision a ses alternatives écartées et son déclencheur |

---

## 2. Le piège principal

> Livrer un CRUD de fichiers avec un appel ClamAV **synchrone** dans le
> contrôleur de dépôt.

Ça compile, ça passe la démo, et ça échoue sur **les trois exigences non
fonctionnelles explicitement citées** :

1. **Concurrence** — la requête HTTP est mobilisée pendant toute la durée de
   l'analyse. Sous charge, le service devient indisponible pour tout le monde,
   y compris les lectures.
2. **Tailles variables** — l'analyse d'un gros fichier dépasse les délais d'un
   proxy ou d'un répartiteur de charge (typiquement 60 s). Le client reçoit une
   erreur alors que le traitement a peut-être réussi → état ambigu, et un
   réessai renvoie tout le fichier.
3. **Garantie** — si l'analyse échoue en cours de route (crash, délai dépassé),
   quel est l'état du fichier ? Sans automate explicite, la réponse est « ça
   dépend » — exactement ce que l'invariant interdit.

**Y répondre par la conception, avant toute ligne de code, est le cœur du sujet.**

---

## 3. Le second piège : le sur-dimensionnement

Symétriquement, sortir Kafka + Kubernetes + event sourcing + CQRS + service mesh
sur un exercice de cette taille dessert le livrable : incapacité à calibrer,
complexité non justifiée, coût de maintenance ignoré.

**Le bon curseur** : une architecture dont chaque composant répond à une
exigence nommée de l'énoncé, et dont tout ce qui manque est **listé
explicitement en « pistes d'amélioration » avec le déclencheur qui le
justifierait**.

La première version de l'analyse (série 00) est elle-même tombée dans ce piège
— broker, outbox, bibliothèque de résilience. La série 20 l'a corrigé, et le
cadrage du 21/09 l'a confirmé : Spring Boot, React et une base suffisent.

---

## 4. Les trois axes d'évaluation probables

### A. Solidité de l'invariant (sécurité)
L'exigence la plus explicite de l'énoncé (« **aucun** »). Doit être :
- modélisée (automate à états),
- protégée à plusieurs niveaux (domaine, cas d'usage, base, identités du
  stockage),
- **prouvée par des tests** (un téléchargement tenté dans chaque état non
  servable).

### B. Tenue à la charge et aux tailles variables
- Analyse asynchrone, flux de bout en bout.
- Dépôt et analyse découplés : deux profils de charge, bornés séparément —
  dans un seul processus (ADR-0002), et non en deux composants déployés à part
  comme l'envisageait cette analyse.
- Les limites connues documentées plutôt que masquées.

### C. Qualité de la démarche
- Hypothèses explicites, chacune avec ce qui l'a motivée.
- Un ADR pour chaque choix structurant.
- Des tests automatisés crédibles : ceux qui comptent, pas un pourcentage.
- Un README qui permet de comprendre l'architecture **sans lire le code**.
- Un environnement qui se lance en une commande (`scripts/start`).

---

## 5. Ce qui ne rapportait probablement rien

Estimation du rapport effort/apport faite au départ, et ce qu'il en est advenu :

| Effort | Apport estimé | Estimation initiale | Ce qui a été fait |
|---|---|---|---|
| Front React riche (filtres, thèmes, animations) | Faible — l'enjeu est côté back-end | À minimiser | Parcours dépôt → suivi → téléchargement, plus un tableau paginé (recherche, filtre, tri) et le détail (`D-12`) |
| Couverture de tests à 95 % | Faible, voire négatif si les tests sont creux | Viser la pertinence | Aucun seuil de couverture |
| Plusieurs moteurs antivirus | Faible | À documenter, pas à implémenter | Un port, un adaptateur HTTP ; un second (verdict sain immédiat) réservé aux essais de capacité |
| Manifestes Kubernetes | Faible sans cible de déploiement | Sondes et arrêt propre dans l'application | Sondes et arrêt propre livrés ; aucun manifeste, pas de mise en production |
| Plusieurs microservices | **Négatif** — complexité gratuite | Un déployable, deux rôles séparables | Un seul processus (ADR-0002) |
| Intégration continue minimale | **Fort** pour un coût faible | À faire | **Non livrée** : décision du porteur du projet (01/10), les tests tournent sur le poste |
| ADR | **Fort** | À faire | ADR-0001 à ADR-0015 |

---

## 6. Hypothèse de travail sur le budget

Faute d'indication, l'hypothèse retenue au départ était de **2 à 4 jours de
travail effectif** : au-delà, on documente au lieu d'implémenter.

**Corollaire assumé** : mieux vaut un périmètre plus étroit, parfaitement
exécuté et parfaitement documenté, qu'un périmètre large et approximatif.
L'énoncé évalue le raisonnement, pas le volume livré.
