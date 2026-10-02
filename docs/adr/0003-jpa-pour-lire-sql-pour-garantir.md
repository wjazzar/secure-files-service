# ADR-0003 — JPA pour lire, SQL pour les instructions qui garantissent

- **Statut** : Accepté — signé par le porteur du projet le 2026-10-01 ; rédigé par Claude (Opus 5.5), session back-end
- **Date** : 2026-09-27
- **Décision du registre** : —
- **Exigences concernées** : EX-03, EX-15

## Contexte

La V0 était écrite sans JPA. Le porteur du projet a contredit ce choix (27/09) :
JPA apporte une lecture beaucoup plus simple des API et une concurrence
optimiste utile, mais il ne voit pas comment écrire le claim sans SQL
(`UPDATE … FOR UPDATE SKIP LOCKED … RETURNING`).

## Décision

**JPA** (Spring Data) pour les lectures et l'insertion : liste paginée, tri en
liste blanche, recherche, compteurs, détail. **`JdbcClient`** pour les
instructions qui portent une garantie : le claim, l'écriture du verdict
conditionnée au jeton, la promotion, le *reaper*, l'idempotence et l'audit. Les
transitions du domaine renvoient une **nouvelle instance** : le *dirty checking*
de JPA ne peut pas écrire un statut par accident. On ne mélange jamais JPA et
JDBC sur la même entité dans une transaction.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| Tout JPA | Le claim et les écritures conditionnelles n'ont pas d'équivalent JPQL ; les émuler avec `@Version` et des réessais ajoute du code et des conflits là où une instruction suffit |
| Tout SQL | Ce que la V0 faisait : moins lisible pour les lectures, que JPA exprime en quelques lignes |

## Conséquences

**Positives** — lectures courtes et lisibles ; les trois instructions critiques
restent visibles, relisibles et testées une par une.

**Négatives** — deux styles d'accès aux données à connaître ; une discipline à
tenir (pas de mélange sur une même entité).

**Complété par [ADR-0015](0015-domaine-sans-annotation-de-persistance.md)**
(30/09) — JPA lit toujours, mais une entité distincte de l'agrégat : le
domaine ne porte plus aucune annotation de persistance.

**Ce qui la remettrait en cause** — si les écritures conditionnelles devenaient
exprimables proprement par l'ORM, ou si les lectures devenaient assez complexes
pour justifier un outil de requêtes typées.
