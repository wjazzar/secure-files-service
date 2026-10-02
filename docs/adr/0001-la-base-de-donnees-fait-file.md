# ADR-0001 — La base de données fait file — aucun broker

- **Statut** : Accepté — signé par le porteur du projet le 2026-10-01 ; rédigé par Claude (Opus 5.5), session back-end
- **Date** : 2026-09-27
- **Décision du registre** : D-03
- **Exigences concernées** : EX-03, EX-06, EX-11

## Contexte

L'analyse est asynchrone (confirmé lors du cadrage du 21/09). Il faut donc
une file de travaux : prise exclusive par un worker, réessais, reprise après
panne. Le cadrage a précisé que Spring Boot, React et une base de données
suffisent. La règle directrice du projet : un composant n'entre que s'il répond à
une exigence nommée que rien de déjà présent ne couvre.

## Décision

La table `stored_file` **est** la file. Un worker prend un travail par une
seule instruction `UPDATE … WHERE id = (SELECT … FOR UPDATE SKIP LOCKED) …
RETURNING`, qui choisit le plus urgent, exclut les autres workers, pose un bail
avec un jeton unique et compte la tentative. Les écritures suivantes (verdict,
promotion, échec) sont conditionnées au **jeton de bail**, jamais à
l'identifiant du worker. Le temps des baux est celui de la base
(`clock_timestamp()`), jamais celui d'un nœud. Le backoff est calculé en SQL,
donc persisté.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| Kafka, RabbitMQ, ActiveMQ Artemis | Un composant de plus à exploiter, et surtout **deux écritures** (la ligne et le message) qu'il faudrait rendre cohérentes — un outbox. Ici l'état et le travail sont la même ligne : l'outbox est gratuit |
| File en mémoire | Perdue au redémarrage ; impossible sur plusieurs nœuds |
| ShedLock / verrou distribué | Inutile : toutes les tâches planifiées sont des transitions conditionnelles, sans effet de bord si elles tournent partout |

## Conséquences

**Positives** — un composant de moins ; l'état et le travail ne peuvent pas
diverger ; plusieurs workers sur plusieurs nœuds sans coordination ; un worker
« zombie » ne peut rien écrire (vérifié : son verdict modifie zéro ligne).

**Négatives** — la file consomme des connexions et des écritures de la base
principale ; pas de *push* : les workers interrogent (toutes les 2 s à vide).

**Ce qui la remettrait en cause** — un débit de travaux tel que l'interrogation
et les écritures de bail pèsent sur la base (ordre de grandeur : des centaines
de prises par seconde), ou le besoin de diffuser des événements à d'autres
systèmes.
