# Architecture Decision Records

Une décision structurante actée = un ADR. Format court, immuable.

**Pourquoi des ADR plutôt qu'une section de README** : un ADR capture le
*moment* de la décision, avec le contexte qui l'a motivée et les alternatives
réellement envisagées. Six mois plus tard, on peut comprendre **pourquoi** un
choix a été fait, et donc juger s'il reste valide. C'est le format que parle un
architecte — et l'énoncé annonce un échange avec un architecte.

## Cycle de vie

| Statut | Signification |
|---|---|
| `Proposé` | En discussion |
| `Accepté` | En vigueur |
| `Déprécié` | Ne s'applique plus, sans remplaçant |
| `Remplacé par ADR-NNNN` | Une décision ultérieure a pris le relais |

Un ADR accepté **n'est jamais modifié** : on en écrit un nouveau qui le
remplace. L'historique des décisions fait partie de la valeur.

## Index

| N° | Titre | Décision(s) couverte(s) | Statut |
|---|---|---|---|
| [ADR-0001](0001-la-base-de-donnees-fait-file.md) | La base de données fait file — aucun broker | D-03 | Accepté |
| [ADR-0002](0002-un-processus-trois-identites.md) | Un seul processus, trois identités de stockage | D-02, D-16 | Accepté |
| [ADR-0003](0003-jpa-pour-lire-sql-pour-garantir.md) | JPA pour lire, SQL pour les instructions qui garantissent | — | Accepté |
| [ADR-0004](0004-antivirus-par-api-http.md) | L'antivirus est consommé par une API HTTP | D-01 | Accepté |
| [ADR-0005](0005-ordre-des-limites-de-l-antivirus.md) | MaxFileSize strictement supérieur à MaxScanSize | D-15 | Accepté |
| [ADR-0006](0006-promotion-par-relecture-verifiee.md) | Promotion par relecture vérifiée, sans zone temporaire | D-02 | Accepté |
| [ADR-0007](0007-liens-hmac-servis-par-le-service.md) | Liens de téléchargement HMAC, contenu servi par le service | D-05 | Remplacé par ADR-0013 |
| [ADR-0008](0008-authentification-activable.md) | Authentification activable : v1 sans, v2 Keycloak | D-10 | Remplacé par ADR-0014 |
| [ADR-0009](0009-journal-d-audit-par-trigger.md) | Journal d'audit écrit par la base, en ajout seul | D-07 | Accepté |
| [ADR-0010](0010-mvc-et-threads-virtuels.md) | Spring MVC et threads virtuels, bornes explicites | — | Accepté |
| [ADR-0011](0011-architecture-hexagonale-verifiee.md) | Architecture hexagonale vérifiée : ports d'entrée, ports de sortie | D-16 | Accepté |
| [ADR-0012](0012-session-navigateur-client-confidentiel.md) | Session du navigateur : client confidentiel, cookie seul, Spring Session | D-10 | Accepté |
| [ADR-0013](0013-telechargement-par-l-identite-de-l-appelant.md) | Téléchargement par l'identité de l'appelant, sans lien signé | D-05 | Accepté |
| [ADR-0014](0014-authentification-toujours-exigee.md) | Authentification toujours exigée, sans mode anonyme | D-10 | Accepté |
| [ADR-0015](0015-domaine-sans-annotation-de-persistance.md) | Le domaine sans annotation de persistance : l'entité JPA est distincte de l'agrégat | — | Accepté |

> Rédigés à partir du 27/09 à partir de ce qui est **livré et testé**, d'abord
> au statut *Proposé* : conformément à [`../CONFRONTATION.md`](../CONFRONTATION.md),
> ils sont passés à *Accepté* le 01/10, relus et signés par le porteur du
> projet. Deux ont été remplacés entre-temps (0007, 0008) et restent pour
> l'historique.

## Gabarit

```markdown
# ADR-NNNN — <Titre à l'impératif ou au substantif>

- **Statut** : Proposé | Accepté | Déprécié | Remplacé par ADR-NNNN
- **Date** : AAAA-MM-JJ
- **Décision du registre** : D-NN
- **Exigences concernées** : EX-NN, EX-NN

## Contexte

Quelle est la situation, quelle contrainte ou quelle exigence impose de
trancher ? Faits uniquement, pas d'opinion.

## Décision

Ce que nous faisons, formulé à l'affirmative et au présent.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| … | … |

## Conséquences

**Positives** — ce que cette décision nous apporte.

**Négatives** — ce qu'elle nous coûte. *Une section vide ici signale un ADR
malhonnête : toute décision d'architecture a un coût.*

**Ce qui la remettrait en cause** — le seuil, l'événement ou l'exigence qui
justifierait de la reconsidérer.
```

La dernière rubrique est la plus importante : elle transforme une décision en
**hypothèse falsifiable** plutôt qu'en dogme, et c'est exactement ce qu'un
architecte cherche à entendre.
