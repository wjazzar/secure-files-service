# B-003 — Audit de la V0 : un seul processus, et retour de JPA

- **Date** : 2026-09-27
- **Outil** : Claude Opus 5 (Claude Code)
- **Objectif** : intégrer les retours d'audit du porteur du projet
- **Phase du projet** : back-end, après la V0 (B-002)

## Retours reçus

> 1. « Un seul processus à démarrer : la séparation des rôles doit se faire
>    dans le code, pas en trois déployables. »
> 2. « Écarter JPA est-il vraiment justifié ? Les garanties peuvent être
>    tenues autrement : en limitant l'accès à certains champs […], ou en
>    n'exposant que des méthodes de modification contrôlées. Sans JPA, le code
>    de lecture perd en lisibilité. »
> 3. « Les API de récupération et de téléchargement des fichiers manquent. »

## 1. Les rôles : mon modèle était faux

J'avais fait des rôles `ingest` / `delivery` / `worker` une **unité de
déploiement** — trois profils Spring, trois démarrages, et un refus de démarrer
sans profil. C'était de la complexité d'exploitation pour une séparation qui
doit vivre ailleurs.

Retiré : `ApplicationRole`, `ActiveRoles`, `RoleConfiguration`. L'application
démarre sans rien à retenir.

**Ce qui reste, et qui est la vraie protection** : trois jeux d'identifiants de
stockage objet, injectés à trois composants du même processus. Le chemin de
téléchargement ne reçoit jamais que les identifiants `delivery`, qui n'ont
**aucun** droit de lecture sur la quarantaine. Une erreur de code ne peut donc
pas lui faire servir un fichier non analysé : le stockage refuse.

## 2. JPA : objection reçue, décision révisée

**Mon argument initial était partiellement faux.** J'avais écarté JPA parce que
le *dirty checking* permet de changer un statut en affectant un champ. C'est
vrai, mais c'est un problème d'**encapsulation**, pas de technologie — et le
porteur du projet a raison de le dire.

La fermeture retenue est même plus forte que des *setters* privés : **les
transitions renvoient une nouvelle instance au lieu de muter**. Hibernate ne
persiste que ce qu'il gère, et ne gère jamais le résultat d'une transition.

**Ce qui reste en SQL, et ce n'est pas négociable** — trois statements sans
équivalent Spring Data :

| Statement | L'impasse |
|---|---|
| Le *claim* | `UPDATE … FOR UPDATE SKIP LOCKED … RETURNING` modifie **et** rend la ligne. Spring Data exige `@Modifying` pour un écrivain, et `@Modifying` ne peut pas retourner une entité — y compris en requête native |
| L'écriture du verdict | Doit répondre par un **booléen**, conditionnée au jeton de la prise. `@Version` lèverait une exception (du code en plus, remarque du porteur du projet) et vérifierait un autre critère : il ne protège pas du worker gelé |
| Le *reaper* | `UPDATE` de masse avec backoff calculé en SQL, donc persisté |

**Résultat** : `FileCatalog` (JPA, Spring Data) pour tout ce qui se lit et
l'insertion, `FileWorkQueue` (JdbcClient) pour ces trois-là. Deux ports, deux
responsabilités, chacun implémenté par la technologie qui convient.

### Ce que j'ai vérifié plutôt que supposé

| Point | Résultat |
|---|---|
| Les colonnes sont des énumérations **natives PostgreSQL**, pas du texte | Mappées par `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` — vérifié en exécutant l'insertion |
| L'agrégat pouvait-il rester un `record` ? | Non : une entité exige une classe et un constructeur sans argument. Passé en classe, **API publique identique** — les 86 tests du domaine n'ont pas changé d'une ligne |
| La validation à la lecture survit-elle ? | Oui, `validate()` est appelée par le constructeur **et** par `@PostLoad` |
| Les 35 tests de persistance restaient-ils valables ? | Oui : ils testaient le comportement à travers le port. C'est ce qui a rendu la bascule sûre |
| Cohabitation JPA / JDBC | **Piège réel** : un `UPDATE` JDBC ne déclenche pas de *flush* Hibernate et ne rafraîchit pas le contexte. Les deux chemins sont séparés par construction, et le test de persistance exerce maintenant les **deux ensemble** |
| `open-in-view` | **Coupé** : garder une connexion ouverte pendant le rendu d'une réponse est exactement ce qu'il ne faut pas faire en servant 500 Mo en flux |

### Ce que je n'ai pas cédé

Le porteur du projet évoquait `@Version` comme mécanisme de concurrence. Il est
en place (l'entité le porte), mais il **ne remplace pas** le contrôle par jeton
de bail sur l'écriture du verdict : `@Version` protège d'une écriture
concurrente, pas d'un worker gelé qui s'est re-réclamé le même fichier et
écrirait un verdict périmé. Les deux coexistent ; le test du worker zombie reste
la preuve.

## 3. Les API : calendrier

Aucun point d'entrée HTTP n'existe encore — c'est exact, et c'est la suite
immédiate. Ordre retenu : lectures d'abord (elles ne dépendent de rien
d'autre), puis dépôt et téléchargement, qui demandent l'adaptateur de stockage.

## Décision restée humaine

Les trois arbitrages de cette session sont ceux du porteur du projet. Le mien
— « aucun JPA » — a été revu après contradiction argumentée, et la trace du
raisonnement est conservée ici et dans `ARCHITECTURE.md` §6.1 : c'est le
désaccord, pas le consensus, qui montre la méthode.
