# ADR-0009 — Journal d'audit écrit par la base, en ajout seul

- **Statut** : Accepté — signé par le porteur du projet le 2026-10-01 ; rédigé par Claude (Opus 5.5), session back-end
- **Date** : 2026-09-27
- **Décision du registre** : D-07
- **Exigences concernées** : EX-03, EX-15

## Contexte

Un service de fichiers sécurisés doit pouvoir répondre à « qui a déposé,
analysé, promu, téléchargé ce fichier, et quand ». Un audit écrit par le code
dépend de ce que chaque développeur pense à écrire.

## Décision

Les **transitions d'état** sont écrites par un **trigger** sur `stored_file`,
dans la transaction qui change l'état : aucun chemin de code ne peut changer un
statut sans trace. Les **téléchargements**, qui ne changent aucun état, sont
écrits par l'application avant le premier octet. La table est **en ajout seul**
par des triggers qui refusent `UPDATE`, `DELETE` et `TRUNCATE`. Pas de clé
étrangère : l'audit survit à la purge du fichier.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| Audit écrit par l'application seule | Oublis possibles ; un bug peut changer un statut sans trace |
| Journaux applicatifs | Ni transactionnels ni interrogeables ; perdus avec la rotation |

## Conséquences

**Positives** — la trace est une propriété de la base ; le verdict y porte sa
preuve (menace, versions du moteur et des signatures).

**Négatives** — de la logique en PL/pgSQL, à connaître ; un rôle propriétaire des
tables pourrait encore retirer les triggers.

**Ce qui la remettrait en cause** — un besoin d'audit infalsifiable
(signature ou chaînage des événements), ou la séparation d'un rôle de migration
et d'un rôle applicatif sans droit DDL — qui renforcerait cette décision.
